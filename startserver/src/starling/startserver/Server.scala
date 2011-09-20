package starling.startserver

import starling.services.osgi.ServicesBromptonActivator
import starling.bouncyrmi.BouncyRMIServerBromptonActivator
import starling.auth.osgi.AuthBromptonActivator
import starling.singleclasspathmanager.SingleClasspathManager
import starling.utils.Log
import starling.props.{Props, PropsHelper}
import management.ManagementFactory
import java.io.File
import starling.reports.osgi.ReportsBromptonActivator
import starling.rabbiteventviewer.internal.RabbitEventViewerServiceBromptonActivator


/**
 * Starts the server, without osgi
 */
object Server {

  def main(args:Array[String]) {
    run()
  }

  def run() {
    System.setProperty("log4j.configuration", "utils/resources/log4j.properties")
    PropsHelper.writeDefaults
    writePIDFile()
    val activators = List(
      classOf[SingleClasspathBroadcasterActivator],
      classOf[AuthBromptonActivator],
      classOf[ServicesBromptonActivator],
      classOf[ReportsBromptonActivator],
      classOf[BouncyRMIServerBromptonActivator],
      classOf[RabbitEventViewerServiceBromptonActivator]
    )
    val single = new SingleClasspathManager(starling.manager.Props.readDefault, activators)
    writePIDFile()
    Log.infoWithTime("Launching starling server") {
      single.start()
    }
  }

  def writePIDFile() {
    val processName = ManagementFactory.getRuntimeMXBean.getName
    val pid = processName.subSequence(0, processName.indexOf("@")).toString

    val file = new File("pid.txt")
    if (file.exists) file.delete
    val out = new java.io.FileWriter(file)
    out.write(pid + "\n")
    out.close

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() = if (file.exists) file.delete
    })
  }

}