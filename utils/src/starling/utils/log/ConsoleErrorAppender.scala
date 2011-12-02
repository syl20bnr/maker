package starling.utils.log

import org.apache.log4j.spi.LoggingEvent
import org.apache.log4j.{Level, ConsoleAppender}


class ConsoleErrorAppender extends org.apache.log4j.WriterAppender {
  private lazy val out = new ConsoleAppender(getLayout, ConsoleAppender.SYSTEM_OUT)
  private lazy val err = new ConsoleAppender(getLayout, ConsoleAppender.SYSTEM_ERR)

  override def append(event: LoggingEvent) = {
    val level = event.getLevel
    if (level == Level.WARN || level == Level.ERROR || level == Level.FATAL)
      err.append(event)
    else
      out.append(event)
  }
}