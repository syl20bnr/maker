package starling.services

import swing.event.Event

import starling.daterange.Day
import starling.pivot._
import starling.utils.Broadcaster

import starling.utils.ImplicitConversions._
import starling.gui.api.EmailEvent


trait ScheduledTaskAttributes {
  val DataSource = "DataSource"
  val DataSink   = "DataSink"
  val EmailFrom  = "EmailFrom"
  val EmailTo    = "EmailTo"
}

trait ScheduledTask extends ScheduledTaskAttributes { self =>
  def attribute(name: String, alternative: String = "") = attributes.getOrElse(name, alternative)
  def attributes: Map[String, String] = Map()
  def execute(observationDay: Day)

  protected def fields(names: String*) = names.map(Field(_)).toList
  protected def filters(filters: (String, Any)*): List[(Field, Selection)] =
    filters.toMap.mapKeys(Field(_)).mapValues(value => SomeSelection(Set(value))).toList
  protected def filterToString(filter: List[(Field, Selection)]) = {
    filter.toMap.mapKeys(_.name).mapValues(_.description).map("%s = %s" % _).mkString(", ")
  }

  def withAttributes(additionalAttributes: (String, String)*) = new ScheduledTask {
    def execute(observationDay: Day) = self.execute(observationDay)
    override def attributes = self.attributes ++ additionalAttributes
  }

  def withSource(name: String) = withAttributes(DataSource → name)
  def withSink(name: String) = withAttributes(DataSink → name)
}

case class SimpleScheduledTask(task: Day => Unit) extends ScheduledTask {
  def execute(observationDay: Day) = task(observationDay)
}

abstract class BroadcastingScheduledTask(broadcaster: Broadcaster) extends ScheduledTask {
  final def execute(observationDay: Day) = eventFor(observationDay).map(broadcaster.broadcast)

  protected def eventFor(observationDay: Day): Option[Event]
}

abstract class EmailingScheduledTask(broadcaster: Broadcaster, from: String, to: Seq[String])
  extends BroadcastingScheduledTask(broadcaster) {

  final protected def eventFor(observationDay: Day) = eventFor(observationDay, EmailEvent(from, to))
  override def attributes = super.attributes + (EmailFrom → from) + (EmailTo → to.mkString(", "))

  protected def eventFor(observationDay: Day, email: EmailEvent): Option[EmailEvent]
}