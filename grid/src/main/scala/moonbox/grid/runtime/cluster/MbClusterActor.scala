package moonbox.grid.runtime.cluster

import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.{Date, Locale}

import akka.actor.{Actor, ActorRef}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import moonbox.common.util.Utils
import moonbox.common.{MbConf, MbLogging}
import moonbox.grid.JobInfo
import moonbox.grid.api._
import moonbox.grid.config._
import moonbox.grid.runtime.cluster.ClusterMessage.{ReportYarnAppResource, YarnAppInfo, YarnStatusChange}
import moonbox.protocol.app.{StopBatchAppByPeace, _}
import org.apache.spark.launcher.SparkAppHandle
import org.json4s.DefaultFormats

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS, SECONDS}
import scala.util.{Failure, Success}

class MbClusterActor(conf: MbConf, nodeActor: ActorRef) extends Actor with MbLogging {

    private implicit val ASK_TIMEOUT = Timeout(FiniteDuration(60, SECONDS))

    private val sparkAppHandleMap: ConcurrentHashMap[String, Future[SparkAppHandle]] = new ConcurrentHashMap[String, Future[SparkAppHandle]]
    private val appListenerMap: ConcurrentHashMap[String, MbAppListener] = new ConcurrentHashMap[String, MbAppListener]()

    private val appResourceMap = new mutable.HashMap[String, YarnAppInfo]   //id -- yarn resource
    private val appActorRefMap = new mutable.HashMap[String, ActorRef]      //id -- actor ref
    private val appJobTypeMap = new mutable.HashMap[String, Map[String, String]] //id --- BATCH / ADHOC

    // for interactive
    private val sessionIdToJobRunner = new mutable.HashMap[String, ActorRef]()
    private val jobIdToJobRunner = new mutable.HashMap[String, ActorRef]()
    private val yarnIdToJobInfo = new mutable.HashMap[String, JobInfo]()

    private val isOnYarn = conf.get("moonbox.grid.actor.yarn.on", "true").toBoolean

    logInfo("path->" + self.path)

    private def getActorSelectorPath: String = {
        val akkaPort = context.system.settings.config.getString("akka.remote.netty.tcp.port")
        val akkaHost = context.system.settings.config.getString("akka.remote.netty.tcp.hostname")
        val akkaPath = MbClusterActor.PROXY_PATH
        val systemName = conf.get(CLUSTER_NAME)

        s"akka.tcp://$systemName@$akkaHost:$akkaPort$akkaPath"
    }

    private def startAppByConfig(launchConf: Map[String, String]): String = {
        val id = newAppId + "_" + launchConf.getOrElse("name", "yarnapp") + "_" + launchConf.getOrElse("job.mode", "unknown")
        val yarnAppMainConf = mutable.Map.empty[String, String]
        yarnAppMainConf += ("moonbox.mixcal.cluster.actor.path" -> getActorSelectorPath)
        yarnAppMainConf += ("moonbox.mixcal.cluster.yarn.id" -> id)

        val yarnAppConfig = launchConf.filter{elem => elem._1.indexOf("spark.hadoop.") != -1}
                .map{elem => (elem._1.replace("spark.hadoop.", ""), elem._2)}
        val yarnAppType = launchConf.getOrElse("job.mode", "adhoc")

        appJobTypeMap.put(id, yarnAppConfig + ("job.mode" -> yarnAppType))

        val appListener = new MbAppListener(id, self)
        appListenerMap.put(id, appListener)

        yarnAppMainConf  ++= conf.getAll.filter(_._1.toLowerCase.startsWith("moonbox"))
        val handler = Future {
            MbAppLauncher.launch(yarnAppMainConf.toMap, launchConf,  appListener)
        }
        appResourceMap.put(id, YarnAppInfo(-1, -1, -1, -1, -1, System.currentTimeMillis()))
        sparkAppHandleMap.put(id, handler)
        id
    }

    private def startLaunchAll(): Unit = {
        val defaultFile = new File(Utils.getDefaultPropertiesFile().get)
        val defaultConfig = ConfigFactory.parseFile(defaultFile)

        //start application for adhoc
        defaultConfig.getConfigList("moonbox.mixcal.cluster").asScala.foreach { elem =>
            val applicationConf = elem.entrySet().asScala.map{ c => (c.getKey, c.getValue.unwrapped().toString)}.toMap
            startAppByConfig(applicationConf)
        }
    }


    override def preStart: Unit = {
        if (isOnYarn) {
            startLaunchAll()
        }

        val initialDelay = conf.get(SCHEDULER_INITIAL_WAIT.key, SCHEDULER_INITIAL_WAIT.defaultValue.get)
        val interval = conf.get(SCHEDULER_INTERVAL.key, SCHEDULER_INTERVAL.defaultValue.get)

        context.system.scheduler.schedule(
            FiniteDuration(initialDelay, MILLISECONDS),
            FiniteDuration(interval, MILLISECONDS),
            new Runnable {
                override def run(): Unit = {
                    val allAvailableYarn = appListenerMap.asScala.filter{_._2.state == SparkAppHandle.State.RUNNING}
                            .filter {elem => appResourceMap.contains(elem._1) && appJobTypeMap.contains(elem._1)}
                            .map {elem => (appResourceMap(elem._1), appJobTypeMap(elem._1).getOrElse("job.mode", "adhoc")) }

                    val adhocInfo = allAvailableYarn.filter(_._2 == "adhoc").keys
                                                       .foldLeft(YarnAppInfo(0, 0, 0, 0, 0, 0)) { (z, i) =>
                                                            if ( z.coresFree > i.coresFree) { z}
                                                            else { i }
                                                       }
                    val batchInfo = jobIdToJobRunner.size

                    nodeActor ! ReportYarnAppResource(adhocInfo, batchInfo)
                }
            }
        )
    }



    override def receive: Receive = {

        case request: MbApi =>
            log.info(request.toString)
            process.apply(request)  // TODO privilege check

        case result: JobStateChanged =>
            handle.apply(result)

        case control =>
            internal.apply(control)

    }

    private def getYarnClusterInfo(id: String): Option[String] = {
        if(appJobTypeMap.contains(id)) {
            val map = appJobTypeMap(id)
            import org.json4s.jackson.Serialization.write
            implicit val formats = DefaultFormats
            Some(write(map))
        } else None

    }

    private def internal: Receive = {
        case YarnStatusChange(id, appid, status) =>
            Future {
                if (appJobTypeMap(id).getOrElse("job.type", "adhoc") == "adhoc") {
                    if (status == SparkAppHandle.State.SUBMITTED || status == SparkAppHandle.State.RUNNING) { //add
                        val cfg = getYarnClusterInfo(id)
                        Utils.updateYarnAppInfo2File(appid, cfg, true)
                    } else if (status == SparkAppHandle.State.FINISHED || status == SparkAppHandle.State.LOST) { //del
                        val cfg = getYarnClusterInfo(id)
                        Utils.updateYarnAppInfo2File(appid, cfg, false)
                    }
                }
            }

        case m@RegisterAppRequest(id, batchJobId, seq, totalCores, totalMemory, freeCores, freeMemory) =>
            val appDriver = sender()
            appDriver ! RegisterAppResponse
            appActorRefMap.put(id, appDriver)

            if (batchJobId.isDefined && seq == -1) {  //batch first message trigger
                nodeActor ! JobStateChanged(batchJobId.get, seq, JobState.SUCCESS, UnitData)
            }

            if (appResourceMap.contains(id)){  //adhoc use this map
                val submit = appResourceMap(id).submit
                appResourceMap.update(id, YarnAppInfo(totalCores, totalMemory, freeCores, freeMemory, System.currentTimeMillis(), submit))
            }else {
                appResourceMap.put(id, YarnAppInfo(totalCores, totalMemory, freeCores, freeMemory, System.currentTimeMillis(), System.currentTimeMillis()))
            }

            logInfo(s"RegisterAppRequest $totalCores, $totalMemory, $freeCores, $freeMemory")
            if (isOnYarn) {
                val appId = appListenerMap.get(id)
                if (appId == null) {
                    logInfo(s"Yarn Application appId Not know yet registered. ref is $appDriver")
                }else {
                    logInfo(s"Yarn Application $appId registered. ref is $appDriver")
                }
            } else {
                logInfo(s"Yarn Application registered. $appDriver")
            }

        case m =>
            logInfo(s"RECEIVE UNKNOWN MESG $m")

    }


    private def process: Receive = {
        case request@AllocateSession(username, database) =>
            val client = sender()
            val yarnApp = selectYarnApp(false)
            yarnApp match {
                case Some(yarnActorRef) =>
                    yarnActorRef.ask(request).mapTo[AllocateSessionResponse].onComplete {
                        case Success(rsp) =>
                            rsp match {
                                case m@AllocatedSession(sessionId) =>
                                    sessionIdToJobRunner.put(sessionId, yarnActorRef)
                                    client ! m
                                case m@AllocateSessionFailed(error) =>
                                    client ! m
                            }
                        case Failure(e) =>
                            client ! AllocateSessionFailed(e.getMessage)
                    }
                case None =>
                    client ! AllocateSessionFailed("there is no available worker.")
            }

        case request@FreeSession(sessionId) =>
            val client = sender()
            sessionIdToJobRunner.get(sessionId) match {
                case Some(worker) =>
                    val future = worker.ask(request).mapTo[FreeSessionResponse]
                    future.onComplete {
                        case Success(response) =>
                            response match {
                                case m@FreedSession(id) =>
                                    sessionIdToJobRunner.remove(id)
                                    client ! m
                                case m@FreeSessionFailed(error) =>
                                    client ! m
                            }
                        case Failure(e) =>
                            client ! FreeSessionFailed(e.getMessage)
                    }
                case None =>
                    client ! FreeSessionFailed(s"Session $sessionId does not exist.")
            }

        case request@AssignTaskToWorker(sqlInfo) =>
            val client = sender()
            sessionIdToJobRunner.get(sqlInfo.sessionId.get) match {  //TODO: for adhoc
                case Some(worker) =>
                    worker ! request
                case None =>
                    client ! JobFailed(sqlInfo.jobId, "Session lost in master.")
            }

        // Batch:
        //           ^------->   schedule      ^---f---->
        // client ---| node1 |---> master -----| node2  |----proxy------yarnAPP -----> Runner
        // client <--------------- master -------------------proxy------yarnAPP------- Runner
        //
        case JobSubmitInternal(jobInfo) =>
            // 1. if config is None, use a old yarn application
            // 2. if config has string, start a new yarn application
            if (jobInfo.config.isDefined) {
                val typeConfig = ConfigFactory.parseString(jobInfo.config.get)
                val typeMap = typeConfig.entrySet().asScala.map{ c => (c.getKey, c.getValue.unwrapped().toString)}.toMap

                val yarnid = startAppByConfig(typeMap)
                yarnIdToJobInfo.put(yarnid, jobInfo)
            } else { //TODO: how to do if no job config

            }
        case m@FetchData(sessionId, jobId, fetchSize) =>
            val client = sender()
            sessionIdToJobRunner.get(sessionId) match {
                case Some(actor) => actor ! FetchDataFromRunner(sessionId, jobId, fetchSize)
                case None => client ! FetchDataFailed(s"sessionId $sessionId does not exist or has been removed.")
            }

        case StopBatchAppByPeace(jobId) =>  //stop all finished batch
            if (jobIdToJobRunner.contains(jobId)) {
                jobIdToJobRunner(jobId) ! StopBatchAppByPeace(jobId)
            }

        case StartBatchAppByPeace(jobId, config) =>
            val requester = sender()

            val typeConfig = ConfigFactory.parseString(config)
            val typeMap = typeConfig.entrySet().asScala.map{ c => (c.getKey, c.getValue.unwrapped().toString)}.toMap
            val newMap = typeMap + ("moonbox.mixcal.cluster.yarn.batchId" -> jobId)
            val id = startAppByConfig(newMap)

            requester ! StartedYarnApp(id)


        case m@JobCancelInternal(jobId) =>
            if (sessionIdToJobRunner.contains(jobId)) { //adhoc, sessionid --> jobId
                sessionIdToJobRunner(jobId) ! RemoveJobFromWorker(jobId)
            }
            if (jobIdToJobRunner.contains(jobId)) {
                jobIdToJobRunner(jobId) ! RemoveJobFromWorker(jobId)
            }


        case GetYarnAppsInfo =>
            val requester = sender()
            val info = appListenerMap.asScala.map { case (id, listener) =>
                val res = if (appResourceMap.contains(id)) {
                    val app = appResourceMap(id)
                    (app.coresTotal, app.memoryTotal, app.coresFree, app.memoryFree, Utils.formatDate(app.lastHeartbeat), Utils.formatDate(app.submit))
                } else {
                    (-1, -1L, -1, -1L, "", "")
                }
                val path = if (appActorRefMap.contains(id)) {
                    appActorRefMap(id).path.toString
                } else {
                    ""
                }
                Seq(id, listener.appId, listener.state.toString, path, res._1, res._2, res._3, res._4, res._5, res._6)
            }.toSeq
            val schema = Seq("id", "yarnId", "state", "path", "core", "memory", "freeCore", "freeMemory", "heartBeat", "submitted")
            requester ! GottenYarnAppsInfo(schema, info)

        case KillYarnApp(id) =>
            val requester = sender()
            Future{
                if (appListenerMap.containsKey(id)){
                    var currentState = appListenerMap.get(id).state
                    var tryTime = 0
                    while ( !currentState.isFinal && tryTime < 5 ) {
                        stopApp(id)
                        tryTime += 1
                        Thread.sleep(1000)
                        currentState = appListenerMap.get(id).state
                    }
                    if( !currentState.isFinal ){
                        killApp(id)
                        Thread.sleep(2000)
                        currentState = appListenerMap.get(id).state
                    }
                    if(currentState.isFinal) {
                        requester ! KilledYarnApp(id)
                    }else{
                        requester ! KilledYarnAppFailed(id, s"Yarn App Id $id can not stopped")
                    }
                } else {
                    requester ! KilledYarnAppFailed(id, s"No Yarn App Id $id is found in AppProxyActor")
                }
                killApp(id)
            }

        case StartYarnApp(config) =>
            val requester = sender()

            val typeConfig = ConfigFactory.parseString(config)
            val typeMap = typeConfig.entrySet().asScala.map{ c => (c.getKey, c.getValue.unwrapped().toString)}.toMap
            val id = startAppByConfig(typeMap)
            requester ! StartedYarnApp(id)


    }


    private def handle: Receive = {
        case m@JobStateChanged(jobId, sessionId, jobState, result) =>
            if (jobIdToJobRunner.contains(jobId)) { //batch clear mapping
                jobIdToJobRunner.remove(jobId)
                nodeActor ! m.copy(result = UnitData)
            } else {
                logInfo(s"In node adhoc, Job $jobId state changed to $jobState $result")
                nodeActor ! m.copy(result = UnitData)
            }

    }


    private def appId: Seq[String] = {
        appListenerMap.values().asScala.map{listener => listener.appId}.toSeq
    }

    private def stopApp(id: String): Unit = {
        if(sparkAppHandleMap.containsKey(id)) {
            sparkAppHandleMap.get(id).foreach(_.stop())
        }
    }

    private def killApp(id: String): Unit = {
        if(sparkAppHandleMap.containsKey(id)) {
            sparkAppHandleMap.get(id).foreach(_.kill())
        }
    }

    private def stopAllApp(): Unit = {
        sparkAppHandleMap.values().asScala.foreach(_.foreach(_.stop()))
    }

    private def killAllApp(): Unit = {
        sparkAppHandleMap.values().asScala.foreach(_.foreach(_.kill()))
    }

    private var nextAppNumber = 0
    def newAppId: String = {
        val submitDate = new Date(Utils.now)
        val appId = "app-%s-%05d".format(createDateFormat.format(submitDate), nextAppNumber)
        nextAppNumber += 1
        appId
    }

    private var nextJobNumber = 0
    private def createDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    def newJobId(submitDate: Date): String = {
        val jobId = "job-%s-%05d".format(createDateFormat.format(submitDate), nextJobNumber)
        nextJobNumber += 1
        jobId
    }

    private def selectYarnApp(isbatch: Boolean): Option[ActorRef] = {
        val now = System.currentTimeMillis()
        val availableNode = appListenerMap.asScala.filter(_._2.state == SparkAppHandle.State.RUNNING)
                .filter { elem => appResourceMap.contains(elem._1) && appActorRefMap.contains(elem._1) && appJobTypeMap.contains(elem._1) }
        val selected = if (isbatch) {
            availableNode.filter(elem => appJobTypeMap(elem._1).getOrElse("job.mode", "adhoc") == "batch").filter(elem => appResourceMap(elem._1).coresFree > 0 && now - appResourceMap(elem._1).lastHeartbeat < 60000).keys
                    .toSeq.sortWith(appResourceMap(_).coresFree > appResourceMap(_).coresFree).map {
                appActorRefMap(_)
            }.headOption
        } else {
            availableNode.filter(elem => appJobTypeMap(elem._1).getOrElse("job.mode", "adhoc") == "adhoc").filter(elem => appResourceMap(elem._1).coresFree > 0 && now - appResourceMap(elem._1).lastHeartbeat < 60000).keys
                    .toSeq.sortWith(appResourceMap(_).coresFree > appResourceMap(_).coresFree).map {
                appActorRefMap(_)
            }.headOption
        }

        selected
    }

    private def schedule(): Unit = {
        // jobId to worker

    }


}

object MbClusterActor {
    val PROXY_PATH = "/user/MbClusterActor"

}