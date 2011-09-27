package starling.launcher

import starling.browser.osgi.BrowserBromptonActivator
import starling.singleclasspathmanager.{JettyBromptonActivator, SingleClasspathManager}
import java.net.{ConnectException, Socket, URL}
import starling.gui.osgi.{GuiLaunchParameters, GuiBromptonActivator}

//Starts the gui without osgi
object Launcher {
  def main(args: Array[String]) {
    if (args.length < 3) {
      throw new IllegalArgumentException("You need to specify 3 arguments: hostname, rmi port and servicePrincipalName")
    }
    println("Args: " + args.toList)
    val rmiHost = args(0)
    val rmiPort = args(1).toInt
    val servicePrincipalName = args(2)

    if (args.length == 4) {
      try {
        val socket = new Socket("localhost", 7777)
        socket.close()
      } catch {
        case e:ConnectException => {
          start(rmiHost, rmiPort, servicePrincipalName)
        }
      }
      val st = args(3)
      val index = st.indexOf("://")
      val s = st.substring(index + 3)
      val url = new URL("http://localhost:7777/" + s)
      val stream = url.openStream()
      stream.close()
    } else {
      start(rmiHost, rmiPort, servicePrincipalName)
    }
  }

  def startWithUser(overriddenUser:String) {
    if (rmiPort == -1) {
      throw new Exception("You can only run as user once start has been called")
    }
    start(rmiHost, rmiPort, servicePrincipalName, Some(overriddenUser))
  }

  // These variables are a big hack so we remember what they are when running the start method when changing users.
  private var rmiHost = ""
  private var rmiPort = -1
  private var servicePrincipalName = ""

  def start(rmiHost: String, rmiPort: Int, servicePrincipalName: String, overriddenUser:Option[String] = None) {

    this.rmiHost = rmiHost
    this.rmiPort = rmiPort
    this.servicePrincipalName = servicePrincipalName

    val launchParameters = GuiLaunchParameters(rmiHost, rmiPort, servicePrincipalName, overriddenUser)

    val activators = List(classOf[JettyBromptonActivator], classOf[GuiBromptonActivator], classOf[BrowserBromptonActivator])
    val single = new SingleClasspathManager(true, activators, List( (classOf[GuiLaunchParameters], launchParameters) ) )
    single.start()
  }
}