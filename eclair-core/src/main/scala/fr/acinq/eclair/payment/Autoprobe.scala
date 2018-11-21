package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.payment.PaymentLifecycle.{PaymentFailed, PaymentResult, RemoteFailure, SendPayment}
import fr.acinq.eclair.{NodeParams, randomBytes, secureRandom}
import fr.acinq.eclair.router.{Announcements, ChannelDesc}
import fr.acinq.eclair.wire.{ChannelUpdate, UnknownPaymentHash}

import scala.concurrent.duration._

/**
  * This actor periodically probes the network by sending payments to random nodes. The payments will eventually fail
  * because the recipient doesn't know the preimage, but it allows us to test channels and improve routing for real payments.
  */
class Autoprobe(nodeParams: NodeParams, router: ActorRef, paymentInitiator: ActorRef) extends Actor with ActorLogging {

  import Autoprobe._

  import scala.concurrent.ExecutionContext.Implicits.global

  // refresh our map of channel_updates regularly from the router
  context.system.scheduler.schedule(0 seconds, UPDATES_REFRESH_INTERVAL, router, 'updatesMap)

  override def receive: Receive = {
    case updates: Map[ChannelDesc, ChannelUpdate]@unchecked =>
      scheduleProbe()
      context become main(updates)
  }

  def main(updates: Map[ChannelDesc, ChannelUpdate]): Receive = {
    case updates: Map[ChannelDesc, ChannelUpdate]@unchecked =>
      context become main(updates)

    case TickProbe =>
      pickPaymentDestination(nodeParams.nodeId, updates) match {
        case Some(targetNodeId) =>
          val paymentHash = randomBytes(32) // we don't even know the preimage (this needs to be a secure random!)
          log.info(s"sending payment probe to node=$targetNodeId payment_hash=$paymentHash")
          paymentInitiator ! SendPayment(PAYMENT_AMOUNT_MSAT, paymentHash, targetNodeId, maxAttempts = 1)
        case None =>
          log.info(s"could not find a destination, re-scheduling")
          scheduleProbe()
      }

    case paymentResult: PaymentResult =>
      paymentResult match {
        case PaymentFailed(_, _ :+ RemoteFailure(_, ErrorPacket(targetNodeId, UnknownPaymentHash))) =>
          log.info(s"payment probe successful to node=$targetNodeId")
        case _ =>
          log.info(s"payment probe failed with paymentResult=$paymentResult")
      }
      scheduleProbe()
  }

  def scheduleProbe() = context.system.scheduler.scheduleOnce(PROBING_INTERVAL, self, TickProbe)


}

object Autoprobe {

  def props(nodeParams: NodeParams, router: ActorRef, paymentInitiator: ActorRef) = Props(classOf[Autoprobe], nodeParams, router, paymentInitiator)

  val UPDATES_REFRESH_INTERVAL = 10 minutes

  val PROBING_INTERVAL = 20 seconds

  val PAYMENT_AMOUNT_MSAT = 100 * 1000

  object TickProbe

  def pickPaymentDestination(nodeId: PublicKey, updates: Map[ChannelDesc, ChannelUpdate]): Option[PublicKey] = {
    // we only pick direct peers with enabled channels
    val peers = updates
      .collect {
        case (desc, u) if desc.a == nodeId && Announcements.isEnabled(u.channelFlags) => desc.b // we only consider outgoing channels that are enabled
      }
    if (peers.isEmpty) {
      None
    } else {
      peers.drop(secureRandom.nextInt(peers.size)).headOption
    }
  }

}
