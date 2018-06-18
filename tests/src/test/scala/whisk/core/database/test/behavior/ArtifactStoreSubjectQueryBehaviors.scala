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

package whisk.core.database.test.behavior

import spray.json.DefaultJsonProtocol._
import spray.json.{JsBoolean, JsObject}
import whisk.common.TransactionId
import whisk.core.database.{NoDocumentException, StaleParameter}
import whisk.core.entity._
import whisk.core.entity.types.AuthStore
import whisk.core.invoker.NamespaceBlacklist

import scala.concurrent.duration.Duration

trait ArtifactStoreSubjectQueryBehaviors extends ArtifactStoreBehaviorBase {

  behavior of s"${storeType}ArtifactStore query subjects"

  it should "find subject by namespace" in {
    implicit val tid: TransactionId = transid()
    val uuid1 = UUID()
    val uuid2 = UUID()
    val ak1 = AuthKey(uuid1, Secret())
    val ak2 = AuthKey(uuid2, Secret())
    val ns1 = Namespace(aname(), uuid1)
    val ns2 = Namespace(aname(), uuid2)
    val subs =
      Array(WhiskAuth(Subject(), Set(WhiskNamespace(ns1, ak1))), WhiskAuth(Subject(), Set(WhiskNamespace(ns2, ak2))))
    subs foreach (put(authStore, _))

    waitOnView(authStore, ak1, 1)
    waitOnView(authStore, ak2, 1)

    val s1 = Identity.get(authStore, ns1.name).futureValue
    s1.subject shouldBe subs(0).subject

    val s2 = Identity.get(authStore, ak2).futureValue
    s2.subject shouldBe subs(1).subject
  }

  it should "not get blocked subject" in {
    implicit val tid: TransactionId = transid()
    val uuid1 = UUID()
    val ns1 = Namespace(aname(), uuid1)
    val ak1 = AuthKey()
    val auth = new ExtendedAuth(Subject(), Set(WhiskNamespace(ns1, ak1)), blocked = true)
    put(authStore, auth)

    Identity.get(authStore, ns1.name).failed.futureValue shouldBe a[NoDocumentException]
  }

  it should "not find subject when authKey matches partially" in {
    implicit val tid: TransactionId = transid()
    val uuid1 = UUID()
    val uuid2 = UUID()
    val ak1 = AuthKey(uuid1, Secret())
    val ak2 = AuthKey(uuid2, Secret())
    val ns1 = Namespace(aname(), uuid1)
    val ns2 = Namespace(aname(), uuid2)

    val auth = WhiskAuth(
      Subject(),
      Set(WhiskNamespace(ns1, AuthKey(ak1.uuid, ak2.key)), WhiskNamespace(ns2, AuthKey(ak2.uuid, ak1.key))))

    put(authStore, auth)

    waitOnView(authStore, AuthKey(ak1.uuid, ak2.key), 1)
    Identity.get(authStore, ak1).failed.futureValue shouldBe a[NoDocumentException]
  }

  it should "find subject by namespace with limits" in {
    implicit val tid: TransactionId = transid()
    val uuid1 = UUID()
    val uuid2 = UUID()
    val ak1 = AuthKey(uuid1, Secret())
    val ak2 = AuthKey(uuid2, Secret())
    val name1 = Namespace(aname(), uuid1)
    val name2 = Namespace(aname(), uuid2)
    val subs = Array(
      WhiskAuth(Subject(), Set(WhiskNamespace(name1, ak1))),
      WhiskAuth(Subject(), Set(WhiskNamespace(name2, ak2))))
    subs foreach (put(authStore, _))

    waitOnView(authStore, ak1, 1)
    waitOnView(authStore, ak2, 1)

    val limits = UserLimits(invocationsPerMinute = Some(7), firesPerMinute = Some(31))
    put(authStore, new LimitEntity(name1.name, limits))

    val i = Identity.get(authStore, name1.name).futureValue
    i.subject shouldBe subs(0).subject
    i.limits shouldBe limits
  }

  it should "find blacklisted namespaces" in {
    implicit val tid: TransactionId = transid()

    val n1 = aname()
    val n2 = aname()
    val n3 = aname()
    val n4 = aname()
    val n5 = aname()

    val uuid1 = UUID()
    val uuid2 = UUID()
    val ak1 = AuthKey(uuid1, Secret())
    val ak2 = AuthKey(uuid2, Secret())

    //Create 3 limits entry where one has limits > 0 thus non blacklisted
    //And one blocked subject with 2 namespaces
    val limitsAndAuths = Seq(
      new LimitEntity(n1, UserLimits(invocationsPerMinute = Some(0))),
      new LimitEntity(n2, UserLimits(concurrentInvocations = Some(0))),
      new LimitEntity(n3, UserLimits(invocationsPerMinute = Some(7), concurrentInvocations = Some(7))),
      new ExtendedAuth(
        Subject(),
        Set(WhiskNamespace(Namespace(n4, uuid1), ak1), WhiskNamespace(Namespace(n5, uuid2), ak2)),
        blocked = true))

    limitsAndAuths foreach (put(authStore, _))

    //2 for limits
    //2 for 2 namespace in user blocked
    waitOnBlacklistView(authStore, 2 + 2)

    //Use contains assertion to ensure that even if same db is used by other setup
    //we at least get our expected entries
    val blacklist = new NamespaceBlacklist(authStore)
    blacklist
      .refreshBlacklist()
      .futureValue should contain allElementsOf Seq(n1, n2, n4, n5).map(_.asString).toSet
  }

  def waitOnBlacklistView(db: AuthStore, count: Int)(implicit transid: TransactionId, timeout: Duration) = {
    val success = retry(() => {
      blacklistCount().map { listCount =>
        if (listCount != count) {
          throw RetryOp()
        } else true
      }
    }, timeout)
    assert(success.isSuccess, "wait aborted after: " + timeout + ": " + success)
  }

  private def blacklistCount()(implicit transid: TransactionId) = {
    //NamespaceBlacklist uses StaleParameter.UpdateAfter which would lead to race condition
    //So use actual call here
    authStore
      .query(
        table = NamespaceBlacklist.view.name,
        startKey = List.empty,
        endKey = List.empty,
        skip = 0,
        limit = Int.MaxValue,
        includeDocs = false,
        descending = true,
        reduce = false,
        stale = StaleParameter.No)
      .map(_.map(_.fields("key").convertTo[String]).toSet)
      .map(_.size)
  }

  private class LimitEntity(name: EntityName, limits: UserLimits) extends WhiskAuth(Subject(), Set.empty) {
    override def docid = DocId(s"${name.name}/limits")

    //There is no api to write limits. So piggy back on WhiskAuth but replace auth json
    //with limits!
    override def toJson = UserLimits.serdes.write(limits).asJsObject
  }

  private class ExtendedAuth(subject: Subject, namespaces: Set[WhiskNamespace], blocked: Boolean)
      extends WhiskAuth(subject, namespaces) {
    override def toJson = JsObject(super.toJson.fields + ("blocked" -> JsBoolean(blocked)))
  }
}
