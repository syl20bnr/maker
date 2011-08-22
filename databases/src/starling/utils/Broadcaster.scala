package starling.utils

import java.util.concurrent.Executors
import swing.event.Event
import scala.collection.JavaConversions

import starling.bouncyrmi.BouncyRMIServer
import starling.rmi.StarlingServer
import collection.immutable.List
import ClosureUtil._
import ImplicitConversions._
import starling.gui.api.{RabbitEvent, EventBatch}
import starling.auth.User


trait Broadcaster {
  def broadcast(event: Event)
}

object Broadcaster {
  val Null = new Broadcaster() { def broadcast(event: Event) {} }
}

class CompositeBroadcaster(broadcasters: (Boolean, Broadcaster)*) extends Broadcaster {
  private val enabledBroadcasters = broadcasters.filter(_._1).map(_._2)

  def broadcast(event: Event) = enabledBroadcasters.map(_.broadcast(event))
}

abstract class TypedBroadcaster[T](implicit manifest: Manifest[T]) extends Broadcaster {
  override def broadcast(event: Event) = manifest.cast(event).foreach(typedBroadcast)

  def typedBroadcast(t: T)
}

class RMIBroadcaster(rmiServer0: => BouncyRMIServer[User]) extends Broadcaster {
  lazy val executor = Executors.newCachedThreadPool()
  lazy val rmiServer = rmiServer0

  def broadcast(event: Event) = if (!event.isInstanceOf[RabbitEvent] && rmiServer != null) {
    executor.execute { rmiServer.publish(EventBatch(List(event))) }
  }
}

class RabbitBroadcaster(sender: RabbitMessageSender) extends TypedBroadcaster[RabbitEvent] {
  def typedBroadcast(rabbitEvent: RabbitEvent) = safely { sender.send(rabbitEvent.queueName, rabbitEvent.toMessage) }
}