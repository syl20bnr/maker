package starling.gui

import starling.rmi.{StarlingServer}
import starling.browser.{ServerContext, SubmitRequest}
import starling.reports.ReportService

trait StarlingSubmitRequest[R] extends SubmitRequest[R] {
  def baseSubmit(serverContext:ServerContext) = {
    submit(new StarlingServerContext(serverContext.lookup(classOf[StarlingServer]), serverContext.lookup(classOf[ReportService])))
  }
  def submit(server:StarlingServerContext):R
}

class StarlingServerContext(
    val server:StarlingServer,
    val reportService:ReportService)


