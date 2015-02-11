/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gearpump.cluster.appmaster

import akka.actor._
import com.typesafe.config.Config
import org.apache.gearpump.cluster.AppMasterToMaster.RequestResource
import org.apache.gearpump.cluster.MasterToAppMaster.ResourceAllocated
import org.apache.gearpump.cluster._
import org.apache.gearpump.cluster.appmaster.ExecutorSystemLauncher._
import org.apache.gearpump.cluster.appmaster.ExecutorSystemScheduler._
import org.apache.gearpump.cluster.scheduler.{ResourceAllocation, ResourceRequest}
import org.apache.gearpump.util.{Constants, LogUtil}

import scala.concurrent.duration._

/**
 * ExecutorSystem is also a type of resource, this class schedules ExecutorSystem for AppMaster.
 * AppMaster can use this class to directly request a live executor actor systems. The communication
 * IN the background with Master and Worker is hidden from AppMaster.
 *
 * Plase use ExecutorSystemScheduler.props() to construct this actor
 *
 * @param appId
 * @param masterProxy
 * @param executorSystemLauncher
*/
private[appmaster]
class ExecutorSystemScheduler (appId: Int, masterProxy: ActorRef,
    executorSystemLauncher: (Int, Session) => Props) extends Actor {

  private val LOG = LogUtil.getLogger(getClass, app = appId)

  implicit val timeout = Constants.FUTURE_TIMEOUT
  implicit val actorSystem = context.system
  var currentSystemId = 0

  var resourceAgents = Map.empty[ActorRef, ActorRef]

  def receive: Receive = clientCommands orElse resourceAllocationMessageHandler orElse executorSystemMessageHandler

  def clientCommands: Receive = {
    case start: StartExecutorSystems =>
      LOG.info(s"starting executor systems $start")
      val requestor = sender()
      val executorSystemConfig = start.executorSystemConfig
      val session  = Session(requestor, executorSystemConfig)
      val agent = resourceAgents.get(requestor).getOrElse{
        context.actorOf(Props(new ResourceAgent(masterProxy, session)))
      }
      resourceAgents = resourceAgents + (sender -> agent)

      start.resources.foreach {resource =>
        agent ! RequestResource(appId, resource)
      }

    case StopExecutorSystem(executorSystem) =>
      executorSystem.shutdown
  }

  def resourceAllocationMessageHandler: Receive = {
    case ResourceAllocatedForSession(allocations, session) =>

      if (isSessionAlive(session)) {
        val groupedResource = allocations.groupBy(_.worker).mapValues(
          _.reduce((resourceA, resourceB) =>
              resourceA.copy(resource = (resourceA.resource + resourceB.resource))))
          .toArray

        groupedResource.map((workerAndResources) => {
          val ResourceAllocation(resource, worker, workerId) = workerAndResources._2

          val launcher = context.actorOf(executorSystemLauncher(appId, session))
          launcher ! LaunchExecutorSystem(WorkerInfo(workerId, worker), currentSystemId, resource)
          currentSystemId = currentSystemId + 1
        })
      }
    case ResourceAllocationTimeOut(session) =>
      if (isSessionAlive(session)) {
        resourceAgents = resourceAgents - (session.requestor)
        session.requestor ! StartExecutorSystemTimeout
      }
  }

  def executorSystemMessageHandler : Receive = {
    case LaunchExecutorSystemSuccess(system, session) =>
      if (isSessionAlive(session)) {
        LOG.info("LaunchExecutorSystemSuccess, send back to " + session.requestor)
        system.bindLifeCycleWith(self)
        session.requestor ! ExecutorSystemStarted(system)
      } else {
        LOG.error("We get a ExecutorSystem back, but resource requestor is no longer valid. Will shutdown the allocated system")
        system.shutdown
      }
    case LaunchExecutorSystemTimeout(session) =>
      if (isSessionAlive(session)) {
        LOG.error(s"Failed to launch executor system for ${session.requestor} due to timeout")
        session.requestor ! StartExecutorSystemTimeout
      }

    case LaunchExecutorSystemRejected(resource, reason, session) =>
      if (isSessionAlive(session)) {
        LOG.error(s"Failed to launch executor system, due to $reason, will ask master to allocate new resources $resource")
        resourceAgents.get(session.requestor).map(_ ! RequestResource(appId, ResourceRequest(resource)))
      }
  }

  private def isSessionAlive(session: Session): Boolean = {
    Option(session).flatMap(session => resourceAgents.get(session.requestor)).nonEmpty
  }
}

object ExecutorSystemScheduler {

  case class StartExecutorSystems(resources: Array[ResourceRequest], executorSystemConfig: ExecutorSystemJvmConfig)
  case class ExecutorSystemStarted(system: ExecutorSystem)
  case class StopExecutorSystem(system: ExecutorSystem)
  case object StartExecutorSystemTimeout

  case class ExecutorSystemJvmConfig(classPath : Array[String], jvmArguments : Array[String],
     jar: Option[AppJar], username : String, executorAkkaConfig: Config = null)

  /**
   * For each client which ask for an executor system, the scheduler will create a session for it.
   *
   * @param requestor
   * @param executorSystemJvmConfig
   */
  private [appmaster]
  case class Session(requestor: ActorRef, executorSystemJvmConfig: ExecutorSystemJvmConfig)

  /**
   * This is a agent for session to request resource
   * @param master
   * @param session the original requester of the resource requests
   */
  private[appmaster]
  class ResourceAgent(master: ActorRef, session: Session) extends Actor {
    private var resourceRequestor: ActorRef = null
    var timeOutClock: Cancellable = null
    private var unallocatedResource: Int = 0
    import context.dispatcher

    def receive: Receive = {
      case request: RequestResource =>
        unallocatedResource += request.request.resource.slots
        Option(timeOutClock).map(_.cancel)
        timeOutClock = context.system.scheduler.scheduleOnce(15 seconds)(self ! ResourceAllocationTimeOut)
        resourceRequestor = sender
        master ! request
      case ResourceAllocated(allocations) =>
        unallocatedResource -= allocations.map(_.resource.slots).sum
        resourceRequestor forward ResourceAllocatedForSession(allocations, session)
      case ResourceAllocationTimeOut=>
        if (unallocatedResource > 0) {
          resourceRequestor ! ResourceAllocationTimeOut(session)
          //we will not receive any ResourceAllocation after timeout
          context.stop(self)
        }
    }
  }

  private[ExecutorSystemScheduler]
  case class ResourceAllocatedForSession(resource: Array[ResourceAllocation], session: Session)

  private[ExecutorSystemScheduler]
  case class ResourceAllocationTimeOut(session: Session)
}