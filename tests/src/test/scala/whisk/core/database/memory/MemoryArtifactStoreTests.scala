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

package whisk.core.database.memory

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import whisk.core.database.test.behavior.ArtifactStoreBehavior
import whisk.core.entity._

import scala.reflect.classTag

@RunWith(classOf[JUnitRunner])
class MemoryArtifactStoreTests extends FlatSpec with ArtifactStoreBehavior {
  override def storeType = "Memory"

  override val authStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    MemoryArtifactStoreProvider.makeStore[WhiskAuth]()
  }

  override val entityStore =
    MemoryArtifactStoreProvider.makeStore[WhiskEntity]()(
      classTag[WhiskEntity],
      WhiskEntityJsonFormat,
      WhiskDocumentReader,
      actorSystem,
      logging,
      materializer)

  override val activationStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    MemoryArtifactStoreProvider.makeStore[WhiskActivation]()
  }
}
