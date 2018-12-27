/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin._
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class HeaderSyncSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with Logging with BeforeAndAfterAll {

  import ElectrumClient._

  var client: ActorRef = _
  val probe = TestProbe()

  override protected def beforeAll(): Unit = {
    client = system.actorOf(Props(new ElectrumClient(new InetSocketAddress("qtornado.com", 50002), SSL.LOOSE)), "electrum-client")
    probe.send(client, AddStatusListener(probe.ref))
    probe.expectMsgType[ElectrumReady](15 seconds)
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  ignore("build from checkpoints") {
    val checkpoints = CheckPoint.load(Block.LivenetGenesisBlock.hash)
    val blockchain = Blockchain.fromCheckpoints(Block.LivenetGenesisBlock.hash, checkpoints)

    // get the first header after the last checkpoint
    val checkpointHeight = checkpoints.size * 2016 - 1

    // get the next chunks of headers
    probe.send(client, GetHeaders(checkpoints.size * 2016, 2016))
    val GetHeadersResponse(start1, headers1, _) = probe.expectMsgType[GetHeadersResponse]
    probe.send(client, GetHeaders(start1 + headers1.length, 2016))
    val GetHeadersResponse(start2, headers2, _) = probe.expectMsgType[GetHeadersResponse]

    // check that we can add our headers
    val blockchain2 = Blockchain.addHeadersChunk(blockchain, start1, headers1)
    val blockchain3 = Blockchain.addHeadersChunk(blockchain2, start2, headers2)
    assert(blockchain3.height == 1 + 2016 + 2016)
  }

  ignore("initial header download") {
    val checkpoints = CheckPoint.load(Block.LivenetGenesisBlock.hash)
    var blockchain = Blockchain.fromCheckpoints(Block.LivenetGenesisBlock.hash, checkpoints)

    // get the remote server tip
    val dummy = TestProbe()
    probe.send(client, HeaderSubscription(dummy.ref))
    val HeaderSubscriptionResponse(height, tip) = dummy.expectMsgType[HeaderSubscriptionResponse]

    // download headers
    while (blockchain.tip.height < height) {
      probe.send(client, GetHeaders(blockchain.tip.height + 1, 2016))
      val GetHeadersResponse(start_height, headers, _) = probe.expectMsgType[GetHeadersResponse]
      blockchain = Blockchain.addHeadersChunk(blockchain, start_height, headers)
    }
  }
}
