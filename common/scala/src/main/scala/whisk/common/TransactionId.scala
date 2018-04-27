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

package whisk.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import scala.math.BigDecimal.int2bigDecimal
import scala.util.Try
import akka.event.Logging.{DebugLevel, InfoLevel, WarningLevel}
import akka.event.Logging.LogLevel
import spray.json._

import whisk.core.ConfigKeys

import pureconfig._

/**
 * A transaction id for tracking operations in the system that are specific to a request.
 * An instance of TransactionId is implicitly received by all logging methods. The actual
 * metadata is stored indirectly in the referenced meta object.
 */
case class TransactionId private (meta: TransactionMetadata) extends AnyVal {
  def id = meta.id
  override def toString = {
    if (meta.id > 0) s"#tid_${meta.id}"
    else if (meta.id < 0) s"#sid_${-meta.id}"
    else "??"
  }

  /**
   * Method to count events.
   *
   * @param from Reference, where the method was called from.
   * @param marker A LogMarkerToken. They are defined in <code>LoggingMarkers</code>.
   * @param message An additional message to be written into the log, together with the other information.
   * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
   */
  def mark(from: AnyRef, marker: LogMarkerToken, message: => String = "", logLevel: LogLevel = DebugLevel)(
    implicit logging: Logging) = {

    if (TransactionId.metricsLog) {
      // marker received with a debug level will be emitted on info level
      logging.emit(InfoLevel, this, from, createMessageWithMarker(message, LogMarker(marker, deltaToStart)))
    } else {
      logging.emit(logLevel, this, from, message)
    }

    MetricEmitter.emitCounterMetric(marker)

  }

  /**
   * Method to start taking time of an action in the code. It returns a <code>StartMarker</code> which has to be
   * passed into the <code>finished</code>-method.
   *
   * @param from Reference, where the method was called from.
   * @param marker A LogMarkerToken. They are defined in <code>LoggingMarkers</code>.
   * @param message An additional message to be written into the log, together with the other information.
   * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
   *
   * @return startMarker that has to be passed to the finished or failed method to calculate the time difference.
   */
  def started(from: AnyRef, marker: LogMarkerToken, message: => String = "", logLevel: LogLevel = DebugLevel)(
    implicit logging: Logging): StartMarker = {

    if (TransactionId.metricsLog) {
      // marker received with a debug level will be emitted on info level
      logging.emit(InfoLevel, this, from, createMessageWithMarker(message, LogMarker(marker, deltaToStart)))
    } else {
      logging.emit(logLevel, this, from, message)
    }

    MetricEmitter.emitCounterMetric(marker)
    StartMarker(Instant.now, marker)
  }

  /**
   * Method to stop taking time of an action in the code. The time the method used will be written into a log message.
   *
   * @param from Reference, where the method was called from.
   * @param startMarker <code>StartMarker</code> returned by a <code>starting</code> method.
   * @param message An additional message to be written into the log, together with the other information.
   * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
   * @param endTime Manually set the timestamp of the end. By default it is NOW.
   */
  def finished(from: AnyRef,
               startMarker: StartMarker,
               message: => String = "",
               logLevel: LogLevel = DebugLevel,
               endTime: Instant = Instant.now(Clock.systemUTC))(implicit logging: Logging) = {

    val endMarker = startMarker.startMarker.asFinish
    val deltaToEnd = deltaToMarker(startMarker, endTime)

    if (TransactionId.metricsLog) {
      logging.emit(
        InfoLevel,
        this,
        from,
        createMessageWithMarker(
          if (logLevel <= InfoLevel) message else "",
          LogMarker(endMarker, deltaToStart, Some(deltaToEnd))))
    } else {
      logging.emit(logLevel, this, from, message)
    }

    MetricEmitter.emitHistogramMetric(endMarker, deltaToEnd)
  }

  /**
   * Method to stop taking time of an action in the code that failed. The time the method used will be written into a log message.
   *
   * @param from Reference, where the method was called from.
   * @param startMarker <code>StartMarker</code> returned by a <code>starting</code> method.
   * @param message An additional message to be written into the log, together with the other information.
   * @param logLevel The <code>LogLevel</code> the message should have. Default is <code>WarningLevel</code>.
   */
  def failed(from: AnyRef, startMarker: StartMarker, message: => String = "", logLevel: LogLevel = WarningLevel)(
    implicit logging: Logging) = {

    val endMarker = startMarker.startMarker.asError
    val deltaToEnd = deltaToMarker(startMarker)

    if (TransactionId.metricsLog) {
      logging.emit(
        logLevel,
        this,
        from,
        createMessageWithMarker(message, LogMarker(endMarker, deltaToStart, Some(deltaToEnd))))
    } else {
      logging.emit(logLevel, this, from, message)
    }

    MetricEmitter.emitHistogramMetric(endMarker, deltaToEnd)
    MetricEmitter.emitCounterMetric(endMarker)
  }

  /**
   * Calculates the time between now and the beginning of the transaction.
   */
  def deltaToStart = Duration.between(meta.start, Instant.now(Clock.systemUTC)).toMillis

  /**
   * Calculates the time between now and the startMarker that was returned by <code>starting</code>.
   *
   * @param startMarker <code>StartMarker</code> returned by a <code>starting</code> method.
   * @param endTime Manually set the endtime. By default it is NOW.
   */
  def deltaToMarker(startMarker: StartMarker, endTime: Instant = Instant.now(Clock.systemUTC)) =
    Duration.between(startMarker.start, endTime).toMillis

  /**
   * Formats log message to include marker.
   *
   * @param message: The log message without the marker
   * @param marker: The marker to add to the message
   */
  private def createMessageWithMarker(message: String, marker: LogMarker): String = s"$message $marker"
}

/**
 * The StartMarker which includes the <code>LogMarkerToken</code> and the start-time.
 *
 * @param start the time when the startMarker was set
 * @param startMarker the LogMarkerToken which defines the start event
 */
case class StartMarker(val start: Instant, startMarker: LogMarkerToken)

/**
 * The transaction metadata encapsulates important properties about a transaction.
 *
 * @param id the transaction identifier; it is positive for client requests,
 *           negative for system operation and zero when originator is not known
 * @param start the timestamp when the request processing commenced
 */
protected case class TransactionMetadata(val id: Long, val start: Instant, val extraLogging: Boolean = false)

object TransactionId {

  // get the metric parameters directly from the environment since WhiskConfig can not be instantiated here
  val metricsKamon: Boolean = sys.env.get("METRICS_KAMON").getOrElse("False").toBoolean
  val metricsKamonTags: Boolean = sys.env.get("METRICS_KAMON_TAGS").getOrElse("False").toBoolean
  val metricsLog: Boolean = sys.env.get("METRICS_LOG").getOrElse("True").toBoolean

  val unknown = TransactionId(0)
  val testing = TransactionId(-1) // Common id for for unit testing
  val invoker = TransactionId(-100) // Invoker startup/shutdown or GC activity
  val invokerWarmup = TransactionId(-101) // Invoker warmup thread that makes stem-cell containers
  val invokerNanny = TransactionId(-102) // Invoker nanny thread
  val dispatcher = TransactionId(-110) // Kafka message dispatcher
  val loadbalancer = TransactionId(-120) // Loadbalancer thread
  val invokerHealth = TransactionId(-121) // Invoker supervision
  val controller = TransactionId(-130) // Controller startup
  val dbBatcher = TransactionId(-140) // Database batcher

  def apply(tid: BigDecimal, extraLogging: Boolean = false): TransactionId = {
    Try {
      val now = Instant.now(Clock.systemUTC())
      TransactionId(TransactionMetadata(tid.toLong, now, extraLogging))
    } getOrElse unknown
  }

  implicit val serdes = new RootJsonFormat[TransactionId] {
    def write(t: TransactionId) = {
      if (t.meta.extraLogging)
        JsArray(JsNumber(t.meta.id), JsNumber(t.meta.start.toEpochMilli), JsBoolean(t.meta.extraLogging))
      else
        JsArray(JsNumber(t.meta.id), JsNumber(t.meta.start.toEpochMilli))
    }

    def read(value: JsValue) =
      Try {
        value match {
          case JsArray(Vector(JsNumber(id), JsNumber(start))) =>
            TransactionId(TransactionMetadata(id.longValue, Instant.ofEpochMilli(start.longValue), false))
          case JsArray(Vector(JsNumber(id), JsNumber(start), JsBoolean(extraLogging))) =>
            TransactionId(TransactionMetadata(id.longValue, Instant.ofEpochMilli(start.longValue), extraLogging))
        }
      } getOrElse unknown
  }
}

/**
 * A thread-safe transaction counter.
 */
trait TransactionCounter {
  case class TransactionCounterConfig(stride: Int)

  val transCounterConfig = loadConfigOrThrow[TransactionCounterConfig](ConfigKeys.transactions)
  val stride = transCounterConfig.stride
  val instanceOrdinal: Int

  // seed the counter so transids do not overlap: instanceOrdinal + n * stride, start at n = 1
  private lazy val cnt = new AtomicInteger(instanceOrdinal + stride)

  def transid(extraLogging: Boolean = false): TransactionId = {
    TransactionId(cnt.addAndGet(stride), extraLogging)
  }
}
