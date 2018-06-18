/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool

import scala.collection.immutable
import whisk.common.{AkkaLogging, LoggingMarkers, TransactionId}
import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.core.connector.MessageFeed

import scala.concurrent.duration._

sealed trait WorkerState
case object Busy extends WorkerState
case object Free extends WorkerState

case class WorkerData(data: ContainerData, state: WorkerState)

/**
 * A pool managing containers to run actions on.
 *
 * This pool fulfills the other half of the ContainerProxy contract. Only
 * one job (either Start or Run) is sent to a child-actor at any given
 * time. The pool then waits for a response of that container, indicating
 * the container is done with the job. Only then will the pool send another
 * request to that container.
 *
 * Upon actor creation, the pool will start to prewarm containers according
 * to the provided prewarmConfig, iff set. Those containers will **not** be
 * part of the poolsize calculation, which is capped by the poolSize parameter.
 * Prewarm containers are only used, if they have matching arguments
 * (kind, memory) and there is space in the pool.
 *
 * @param childFactory method to create new container proxy actor
 * @param feed actor to request more work from
 * @param prewarmConfig optional settings for container prewarming
 * @param poolConfig config for the ContainerPool
 */
class ContainerPool(childFactory: ActorRefFactory => ActorRef,
                    feed: ActorRef,
                    prewarmConfig: List[PrewarmingConfig] = List.empty,
                    poolConfig: ContainerPoolConfig)
    extends Actor {
  implicit val logging = new AkkaLogging(context.system.log)

  var freePool = immutable.Map.empty[ActorRef, ContainerData]
  var busyPool = immutable.Map.empty[ActorRef, ContainerData]
  var prewarmedPool = immutable.Map.empty[ActorRef, ContainerData]
  val logMessageInterval = 10.seconds

  prewarmConfig.foreach { config =>
    logging.info(this, s"pre-warming ${config.count} ${config.exec.kind} ${config.memoryLimit.toString}")(
      TransactionId.invokerWarmup)
    (1 to config.count).foreach { _ =>
      prewarmContainer(config.exec, config.memoryLimit)
    }
  }

  def logContainerStart(r: Run, containerState: String): Unit = {
    val namespaceName = r.msg.user.namespace.name
    val actionName = r.action.name.name
    val activationId = r.msg.activationId.toString

    r.msg.transid.mark(
      this,
      LoggingMarkers.INVOKER_CONTAINER_START(containerState),
      s"containerStart containerState: $containerState action: $actionName namespace: $namespaceName activationId: $activationId",
      akka.event.Logging.InfoLevel)
  }

  def receive: Receive = {
    // A job to run on a container
    //
    // Run messages are received either via the feed or from child containers which cannot process
    // their requests and send them back to the pool for rescheduling (this may happen if "docker" operations
    // fail for example, or a container has aged and was destroying itself when a new request was assigned)
    case r: Run =>
      val createdContainer = if (busyPool.size < poolConfig.maxActiveContainers) {

        // Schedule a job to a warm container
        ContainerPool
          .schedule(r.action, r.msg.user.namespace.name, freePool)
          .map(container => {
            (container, "warm")
          })
          .orElse {
            if (busyPool.size + freePool.size < poolConfig.maxActiveContainers) {
              takePrewarmContainer(r.action)
                .map(container => {
                  (container, "prewarmed")
                })
                .orElse {
                  Some(createContainer(), "cold")
                }
            } else None
          }
          .orElse {
            // Remove a container and create a new one for the given job
            ContainerPool.remove(freePool).map { toDelete =>
              removeContainer(toDelete)
              takePrewarmContainer(r.action)
                .map(container => {
                  (container, "recreated")
                })
                .getOrElse {
                  (createContainer(), "recreated")
                }
            }
          }
      } else None

      createdContainer match {
        case Some(((actor, data), containerState)) =>
          busyPool = busyPool + (actor -> data)
          freePool = freePool - actor
          actor ! r // forwards the run request to the container
          logContainerStart(r, containerState)
        case None =>
          // this can also happen if createContainer fails to start a new container, or
          // if a job is rescheduled but the container it was allocated to has not yet destroyed itself
          // (and a new container would over commit the pool)
          val isErrorLogged = r.retryLogDeadline.map(_.isOverdue).getOrElse(true)
          val retryLogDeadline = if (isErrorLogged) {
            logging.error(
              this,
              s"Rescheduling Run message, too many message in the pool, freePoolSize: ${freePool.size}, " +
                s"busyPoolSize: ${busyPool.size}, maxActiveContainers ${poolConfig.maxActiveContainers}, " +
                s"userNamespace: ${r.msg.user.namespace.name}, action: ${r.action}")(r.msg.transid)
            Some(logMessageInterval.fromNow)
          } else {
            r.retryLogDeadline
          }
          self ! Run(r.action, r.msg, retryLogDeadline)
      }

    // Container is free to take more work
    case NeedWork(data: WarmedData) =>
      freePool = freePool + (sender() -> data)
      busyPool.get(sender()).foreach { _ =>
        busyPool = busyPool - sender()
        feed ! MessageFeed.Processed
      }

    // Container is prewarmed and ready to take work
    case NeedWork(data: PreWarmedData) =>
      prewarmedPool = prewarmedPool + (sender() -> data)

    // Container got removed
    case ContainerRemoved =>
      freePool = freePool - sender()
      busyPool.get(sender()).foreach { _ =>
        busyPool = busyPool - sender()
        // container was busy, so there is capacity to accept another job request
        feed ! MessageFeed.Processed
      }

    // This message is received for one of these reasons:
    // 1. Container errored while resuming a warm container, could not process the job, and sent the job back
    // 2. The container aged, is destroying itself, and was assigned a job which it had to send back
    // 3. The container aged and is destroying itself
    // Update the free/busy lists but no message is sent to the feed since there is no change in capacity yet
    case RescheduleJob =>
      freePool = freePool - sender()
      busyPool = busyPool - sender()
  }

  /** Creates a new container and updates state accordingly. */
  def createContainer(): (ActorRef, ContainerData) = {
    val ref = childFactory(context)
    val data = NoData()
    freePool = freePool + (ref -> data)
    ref -> data
  }

  /** Creates a new prewarmed container */
  def prewarmContainer(exec: CodeExec[_], memoryLimit: ByteSize) =
    childFactory(context) ! Start(exec, memoryLimit)

  /**
   * Takes a prewarm container out of the prewarmed pool
   * iff a container with a matching kind is found.
   *
   * @param kind the kind you want to invoke
   * @return the container iff found
   */
  def takePrewarmContainer(action: ExecutableWhiskAction): Option[(ActorRef, ContainerData)] = {
    val kind = action.exec.kind
    val memory = action.limits.memory.megabytes.MB
    prewarmedPool
      .find {
        case (_, PreWarmedData(_, `kind`, `memory`)) => true
        case _                                       => false
      }
      .map {
        case (ref, data) =>
          // Move the container to the usual pool
          freePool = freePool + (ref -> data)
          prewarmedPool = prewarmedPool - ref
          // Create a new prewarm container
          // NOTE: prewarming ignores the action code in exec, but this is dangerous as the field is accessible to the factory
          prewarmContainer(action.exec, memory)
          (ref, data)
      }
  }

  /** Removes a container and updates state accordingly. */
  def removeContainer(toDelete: ActorRef) = {
    toDelete ! Remove
    freePool = freePool - toDelete
    busyPool = busyPool - toDelete
  }
}

object ContainerPool {

  /**
   * Finds the best container for a given job to run on.
   *
   * Selects an arbitrary warm container from the passed pool of idle containers
   * that matches the action and the invocation namespace. The implementation uses
   * matching such that structural equality of action and the invocation namespace
   * is required.
   * Returns None iff no matching container is in the idle pool.
   * Does not consider pre-warmed containers.
   *
   * @param action the action to run
   * @param invocationNamespace the namespace, that wants to run the action
   * @param idles a map of idle containers, awaiting work
   * @return a container if one found
   */
  protected[containerpool] def schedule[A](action: ExecutableWhiskAction,
                                           invocationNamespace: EntityName,
                                           idles: Map[A, ContainerData]): Option[(A, ContainerData)] = {
    idles.find {
      case (_, WarmedData(_, `invocationNamespace`, `action`, _)) => true
      case _                                                      => false
    }
  }

  /**
   * Finds the oldest previously used container to remove to make space for the job passed to run.
   *
   * NOTE: This method is never called to remove an action that is in the pool already,
   * since this would be picked up earlier in the scheduler and the container reused.
   *
   * @param pool a map of all free containers in the pool
   * @return a container to be removed iff found
   */
  protected[containerpool] def remove[A](pool: Map[A, ContainerData]): Option[A] = {
    val freeContainers = pool.collect {
      case (ref, w: WarmedData) => ref -> w
    }

    if (freeContainers.nonEmpty) {
      val (ref, _) = freeContainers.minBy(_._2.lastUsed)
      Some(ref)
    } else None
  }

  def props(factory: ActorRefFactory => ActorRef,
            poolConfig: ContainerPoolConfig,
            feed: ActorRef,
            prewarmConfig: List[PrewarmingConfig] = List.empty) =
    Props(new ContainerPool(factory, feed, prewarmConfig, poolConfig))
}

/** Contains settings needed to perform container prewarming. */
case class PrewarmingConfig(count: Int, exec: CodeExec[_], memoryLimit: ByteSize)
