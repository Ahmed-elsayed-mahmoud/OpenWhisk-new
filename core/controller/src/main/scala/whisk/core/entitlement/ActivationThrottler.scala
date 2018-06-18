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

package whisk.core.entitlement

import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.entity.Identity
import whisk.core.loadBalancer.LoadBalancer
import whisk.http.Messages

import scala.concurrent.{ExecutionContext, Future}

/**
 * Determine whether the namespace currently invoking a new action should be allowed to do so.
 *
 * @param loadBalancer contains active quotas
 * @param concurrencyLimit a calculated limit relative to the user using the system
 * @param systemOverloadLimit the limit when the system is considered overloaded
 */
class ActivationThrottler(loadBalancer: LoadBalancer, concurrencyLimit: Identity => Int, systemOverloadLimit: Int)(
  implicit logging: Logging,
  executionContext: ExecutionContext) {

  logging.info(this, s"systemOverloadLimit = $systemOverloadLimit")(TransactionId.controller)

  /**
   * Checks whether the operation should be allowed to proceed.
   */
  def check(user: Identity)(implicit tid: TransactionId): Future[RateLimit] = {
    loadBalancer.activeActivationsFor(user.namespace.uuid).map { concurrentActivations =>
      val currentLimit = concurrencyLimit(user)
      logging.debug(
        this,
        s"namespace = ${user.namespace.uuid.asString}, concurrent activations = $concurrentActivations, below limit = $currentLimit")
      ConcurrentRateLimit(concurrentActivations, currentLimit)
    }
  }

  /**
   * Checks whether the system is in a generally overloaded state.
   */
  def isOverloaded()(implicit tid: TransactionId): Future[Boolean] = {
    loadBalancer.totalActiveActivations.map { concurrentActivations =>
      val overloaded = concurrentActivations > systemOverloadLimit
      if (overloaded)
        logging.info(
          this,
          s"concurrent activations in system = $concurrentActivations, below limit = $systemOverloadLimit")
      overloaded
    }
  }
}

sealed trait RateLimit {
  def ok: Boolean
  def errorMsg: String
  def limitName: String
}

case class ConcurrentRateLimit(count: Int, allowed: Int) extends RateLimit {
  val ok: Boolean = count < allowed // must have slack for the current activation request
  override def errorMsg: String = Messages.tooManyConcurrentRequests(count, allowed)
  val limitName: String = "ConcurrentRateLimit"
}

case class TimedRateLimit(count: Int, allowed: Int) extends RateLimit {
  val ok: Boolean = count <= allowed // the count is already updated to account for the current request
  override def errorMsg: String = Messages.tooManyRequests(count, allowed)
  val limitName: String = "TimedRateLimit"
}
