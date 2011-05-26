package starling.services

import java.util.{TimerTask, Timer}
import starling.daterange.Day
import starling.utils.Log

import starling.utils.ImplicitConversions._


case class TaskDescription(name: String, time: ScheduledTime, task: ScheduledTask) extends TimerTask {
  val cal = time.cal
  def attribute(name: String, alternative: String = "") = task.attribute(name, alternative)

  def schedule(timer: Timer) = Log.infoF("Scheduled: %s @ %s, %s %s" % (name, time.prettyTime, time.description, time.cal.name)) {
    time.schedule(this, timer)
  }

  def run = if (Day.today.isBusinessDay(cal)) Log.infoF("Executing scheduled task: " + name) {
    task.execute(Day.today)
  } else {
    Log.info("Not a business day in calendar: %s, thus skipping: " % (cal.name, name))
  }
}