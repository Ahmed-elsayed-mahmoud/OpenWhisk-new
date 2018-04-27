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

package actionContainers

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.sys.process.ProcessLogger
import scala.sys.process.stringToProcess
import scala.util.Random

import org.apache.commons.lang3.StringUtils
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import akka.actor.ActorSystem
import common.WhiskProperties
import spray.json._
import whisk.core.entity.Exec

/**
 * For testing convenience, this interface abstracts away the REST calls to a
 * container as blocking method calls of this interface.
 */
trait ActionContainer {
  def init(value: JsValue): (Int, Option[JsObject])
  def run(value: JsValue): (Int, Option[JsObject])
}

trait ActionProxyContainerTestUtils extends FlatSpec with Matchers {
  import ActionContainer.{filterSentinel, sentinel}

  def initPayload(code: String, main: String = "main"): JsObject =
    JsObject(
      "value" -> JsObject(
        "code" -> { if (code != null) JsString(code) else JsNull },
        "main" -> JsString(main),
        "binary" -> JsBoolean(Exec.isBinaryCode(code))))

  def runPayload(args: JsValue, other: Option[JsObject] = None): JsObject =
    JsObject(Map("value" -> args) ++ (other map { _.fields } getOrElse Map()))

  def checkStreams(out: String,
                   err: String,
                   additionalCheck: (String, String) => Unit,
                   sentinelCount: Int = 1): Unit = {
    withClue("expected number of stdout sentinels") {
      sentinelCount shouldBe StringUtils.countMatches(out, sentinel)
    }
    withClue("expected number of stderr sentinels") {
      sentinelCount shouldBe StringUtils.countMatches(err, sentinel)
    }

    val (o, e) = (filterSentinel(out), filterSentinel(err))
    o should not include sentinel
    e should not include sentinel
    additionalCheck(o, e)
  }
}

object ActionContainer {
  private lazy val dockerBin: String = {
    List("/usr/bin/docker", "/usr/local/bin/docker").find { bin =>
      new File(bin).isFile
    }.get // This fails if the docker binary couldn't be located.
  }

  private lazy val dockerCmd: String = {
    val version = WhiskProperties.getProperty("whisk.version.name")
    // Check if we are running on docker-machine env.
    val hostStr = if (version.toLowerCase().contains("mac")) {
      s" --host tcp://${WhiskProperties.getMainDockerEndpoint} "
    } else {
      " "
    }
    s"$dockerBin $hostStr"
  }

  private def docker(command: String): String = s"$dockerCmd $command"

  // Runs a process asynchronously. Returns a future with (exitCode,stdout,stderr)
  private def proc(cmd: String): Future[(Int, String, String)] = Future {
    blocking {
      val out = new ByteArrayOutputStream
      val err = new ByteArrayOutputStream
      val outW = new PrintWriter(out)
      val errW = new PrintWriter(err)
      val v = cmd ! ProcessLogger(o => outW.println(o), e => errW.println(e))
      outW.close()
      errW.close()
      (v, out.toString, err.toString)
    }
  }

  // Tying it all together, we have a method that runs docker, waits for
  // completion for some time then returns the exit code, the output stream
  // and the error stream.
  private def awaitDocker(cmd: String, t: Duration): (Int, String, String) = {
    Await.result(proc(docker(cmd)), t)
  }

  // Filters out the sentinel markers inserted by the container (see relevant private code in Invoker.scala)
  val sentinel = "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX"
  def filterSentinel(str: String): String = str.replaceAll(sentinel, "").trim

  def withContainer(imageName: String, environment: Map[String, String] = Map.empty)(code: ActionContainer => Unit)(
    implicit actorSystem: ActorSystem): (String, String) = {
    val rand = { val r = Random.nextInt; if (r < 0) -r else r }
    val name = imageName.toLowerCase.replaceAll("""[^a-z]""", "") + rand
    val envArgs = environment.toSeq
      .map {
        case (k, v) => s"-e $k=$v"
      }
      .mkString(" ")

    // We create the container... and find out its IP address...
    def createContainer(portFwd: Option[Int] = None): Unit = {
      val runOut = awaitDocker(
        s"run ${portFwd.map(p => s"-p $p:8080").getOrElse("")} --name $name $envArgs -d $imageName",
        60.seconds)
      assert(runOut._1 == 0, "'docker run' did not exit with 0: " + runOut)
    }

    // ...find out its IP address...
    val (ip, port) =
      if (WhiskProperties.getProperty("whisk.version.name") == "local" &&
          WhiskProperties.onMacOSX()) {
        // on MacOSX, where docker for mac does not permit communicating with container directly
        val p = 8988 // port must be available or docker run will fail
        createContainer(Some(p))
        Thread.sleep(1500) // let container/server come up cleanly
        ("localhost", p)
      } else {
        // not "mac" i.e., docker-for-mac, use direct container IP directly (this is OK for Ubuntu, and docker-machine)
        createContainer()
        val ipOut = awaitDocker(s"""inspect --format '{{.NetworkSettings.IPAddress}}' $name""", 10.seconds)
        assert(ipOut._1 == 0, "'docker inspect did not exit with 0")
        (ipOut._2.replaceAll("""[^0-9.]""", ""), 8080)
      }

    // ...we create an instance of the mock container interface...
    val mock = new ActionContainer {
      def init(value: JsValue): (Int, Option[JsObject]) = syncPost(ip, port, "/init", value)
      def run(value: JsValue): (Int, Option[JsObject]) = syncPost(ip, port, "/run", value)
    }

    try {
      // ...and finally run the code with it.
      code(mock)
      // I'm told this is good for the logs.
      Thread.sleep(100)
      val (_, out, err) = awaitDocker(s"logs $name", 10.seconds)
      (out, err)
    } finally {
      awaitDocker(s"kill $name", 10.seconds)
      awaitDocker(s"rm $name", 10.seconds)
    }
  }

  private def syncPost(host: String, port: Int, endPoint: String, content: JsValue): (Int, Option[JsObject]) = {
    whisk.core.containerpool.HttpUtils.post(host, port, endPoint, content)
  }
}
