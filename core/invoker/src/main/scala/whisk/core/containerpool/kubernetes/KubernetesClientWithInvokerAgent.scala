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

package whisk.core.containerpool.kubernetes

import whisk.common.{Logging, TransactionId}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, MessageEntity, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import pureconfig.loadConfigOrThrow
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.ConfigKeys
import whisk.core.entity.ByteSize

import collection.JavaConverters._
import scala.concurrent.{blocking, ExecutionContext, Future}

/**
 * An extended kubernetes client that works in tandem with an invokerAgent DaemonSet with
 * instances running on every worker node that runs user containers to provide
 * suspend/resume capability and higher performance log processing.
 */
class KubernetesClientWithInvokerAgent(config: KubernetesClientConfig =
                                         loadConfigOrThrow[KubernetesClientConfig](ConfigKeys.kubernetes))(
  executionContext: ExecutionContext)(implicit log: Logging, as: ActorSystem)
    extends KubernetesClient(config)(executionContext)
    with KubernetesApiWithInvokerAgent {

  override def rm(key: String, value: String, ensureUnpaused: Boolean = false)(
    implicit transid: TransactionId): Future[Unit] = {
    if (ensureUnpaused) {
      // The caller can't guarantee that every container with the label key=value is already unpaused.
      // Therefore we must enumerate them and ensure they are unpaused before we attempt to delete them.
      Future {
        blocking {
          kubeRestClient
            .inNamespace(kubeRestClient.getNamespace)
            .pods()
            .withLabel(key, value)
            .list()
            .getItems
            .asScala
            .map { pod =>
              val container = toContainer(pod)
              container
                .resume()
                .recover { case _ => () } // Ignore errors; it is possible the container was not actually suspended.
                .map(_ => rm(container))
            }
        }
      }.flatMap(futures =>
        Future
          .sequence(futures)
          .map(_ => ()))
    } else {
      super.rm(key, value, ensureUnpaused)
    }
  }

  override def suspend(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
    agentCommand("suspend", container)
      .map(_.discardEntityBytes())
  }

  override def resume(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
    agentCommand("resume", container)
      .map(_.discardEntityBytes())
  }

  override def forwardLogs(container: KubernetesContainer,
                           lastOffset: Long,
                           sizeLimit: ByteSize,
                           sentinelledLogs: Boolean,
                           additionalMetadata: Map[String, JsValue],
                           augmentedActivation: JsObject)(implicit transid: TransactionId): Future[Long] = {
    val serializedData = Map(
      "lastOffset" -> JsNumber(lastOffset),
      "sizeLimit" -> JsNumber(sizeLimit.toBytes),
      "sentinelledLogs" -> JsBoolean(sentinelledLogs),
      "encodedLogLineMetadata" -> JsString(fieldsString(additionalMetadata)),
      "encodedActivation" -> JsString(augmentedActivation.compactPrint))

    agentCommand("logs", container, Some(serializedData))
      .flatMap(response => Unmarshal(response.entity).to[String].map(_.toLong))
  }

  override def agentCommand(command: String,
                            container: KubernetesContainer,
                            payload: Option[Map[String, JsValue]] = None): Future[HttpResponse] = {
    val uri = Uri()
      .withScheme("http")
      .withHost(container.workerIP)
      .withPort(config.invokerAgent.port)
      .withPath(Path / command / container.nativeContainerId)

    Marshal(payload).to[MessageEntity].flatMap { entity =>
      Http().singleRequest(HttpRequest(uri = uri, entity = entity))
    }
  }

  private def fieldsString(fields: Map[String, JsValue]) =
    fields
      .map {
        case (key, value) => s""""$key":${value.compactPrint}"""
      }
      .mkString(",")
}

trait KubernetesApiWithInvokerAgent extends KubernetesApi {

  /**
   * Request the invokerAgent running on the container's worker node to execute the given command
   * @param command The command verb to execute
   * @param container The container to which the command should be applied
   * @param payload The additional data needed to execute the command.
   * @return The HTTPResponse from the remote agent.
   */
  def agentCommand(command: String,
                   container: KubernetesContainer,
                   payload: Option[Map[String, JsValue]] = None): Future[HttpResponse]

  /**
   * Forward a section the argument container's stdout/stderr output to an external logging service.
   *
   * @param container the container whose logs should be forwarded
   * @param lastOffset the last offset previously read in the remote log file
   * @param sizeLimit The maximum number of bytes of log that should be forwarded before truncation
   * @param sentinelledLogs Should the log forwarder expect a sentinel line at the end of stdout/stderr streams?
   * @param additionalMetadata Additional metadata that should be injected into every log line
   * @param augmentedActivation Activation record to be appended to the forwarded log.
   * @return the last offset read from the remote log file (to be used on next call to forwardLogs)
   */
  def forwardLogs(container: KubernetesContainer,
                  lastOffset: Long,
                  sizeLimit: ByteSize,
                  sentinelledLogs: Boolean,
                  additionalMetadata: Map[String, JsValue],
                  augmentedActivation: JsObject)(implicit transid: TransactionId): Future[Long]
}
