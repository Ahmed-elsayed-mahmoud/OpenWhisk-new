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

import java.nio.charset.StandardCharsets

import extension.whisk.OpenWhiskProtocolBuilder
import extension.whisk.Predef._
import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.core.util.Resource
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._

class BlockingInvokeOneActionSimulation extends Simulation {
  // Specify parameters for the run
  val host = sys.env("OPENWHISK_HOST")

  // Specify authentication
  val Array(uuid, key) = sys.env("API_KEY").split(":")

  val connections: Int = sys.env("CONNECTIONS").toInt
  val seconds: FiniteDuration = sys.env.getOrElse("SECONDS", "10").toInt.seconds

  // Specify thresholds
  val requestsPerSec: Int = sys.env("REQUESTS_PER_SEC").toInt
  val minimalRequestsPerSec: Int = sys.env.getOrElse("MIN_REQUESTS_PER_SEC", requestsPerSec.toString).toInt

  // Generate the OpenWhiskProtocol
  val openWhiskProtocol: OpenWhiskProtocolBuilder = openWhisk.apiHost(host)

  val actionName = "testActionForBlockingInvokeOneAction"

  // Define scenario
  val test: ScenarioBuilder = scenario("Invoke one action blocking")
    .doIf(_.userId == 1) {
      exec(
        openWhisk("Create action")
          .authenticate(uuid, key)
          .action(actionName)
          .create(FileUtils
            .readFileToString(Resource.body("nodeJSAction.js").get.file, StandardCharsets.UTF_8)))
    }
    .rendezVous(connections)
    .during(5.seconds) {
      exec(openWhisk("Warm containers up").authenticate(uuid, key).action(actionName).invoke())
    }
    .rendezVous(connections)
    .during(seconds) {
      exec(openWhisk("Invoke action").authenticate(uuid, key).action(actionName).invoke())
    }
    .rendezVous(connections)
    .doIf(_.userId == 1) {
      exec(openWhisk("Delete action").authenticate(uuid, key).action(actionName).delete())
    }

  setUp(test.inject(atOnceUsers(connections)))
    .protocols(openWhiskProtocol)
    // One failure will make the build yellow
    .assertions(details("Invoke action").requestsPerSec.gt(minimalRequestsPerSec))
    .assertions(details("Invoke action").requestsPerSec.gt(requestsPerSec))
    // Mark the build yellow, if there are failed requests. And red if both conditions fail.
    .assertions(details("Invoke action").failedRequests.count.is(0))
    .assertions(details("Invoke action").failedRequests.percent.lte(0.1))
}
