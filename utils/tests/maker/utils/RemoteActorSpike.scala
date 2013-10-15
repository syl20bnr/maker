package maker.utils

import akka.actor.Actor
import akka.actor.Props
import akka.actor.ActorSystem
import maker.utils.os.ScalaCommand
import maker.utils.os.CommandOutputHandler
import maker.utils.FileUtils._
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import scala.concurrent.Await
import akka.pattern.ask
import akka.actor.ActorRef

object Config{
  def apply(port : Int) = {
    val confText = s"""
      akka {
        loggers = ["akka.event.slf4j.Slf4jLogger"]
        //loglevel = "DEBUG"
        actor {
          provider = "akka.remote.RemoteActorRefProvider"
        }
        remote {
          enabled-transports = ["akka.remote.netty.tcp"]
          netty.tcp {
            hostname = "127.0.0.1"
            port = $port
          }
          //log-received-messages = on
          //log-sent-messages = on
      }
      }
    """
    ConfigFactory.parseString(confText)
  }
}

object TestRunner extends App{
  println("Debug: " + (new java.util.Date()) + " RemoteActorSpike: making system")
  val system = ActorSystem.create("REMOTE", Config(2553))
  implicit val timeout = Timeout(60000 * 30) // 30 minutes
  val managerPath = "akka.tcp://Test-Manager@127.0.0.1:2552/user/manager"
  val manager = system.actorSelection(managerPath)

  class TestRunnerActor extends Actor{
    override def preStart(){
      println("Debug: " + (new java.util.Date()) + " RemoteActorSpike: registering")
      manager ! "REGISTER"
    }
    def receive = {
      case any =>
        println("Debug: " + (new java.util.Date()) + " RemoteActorSpike: Test Runner received " + any)
    }
  }
  val myTestRunner = system.actorOf(Props[TestRunnerActor], "runner")
}

object RemoteActorSpike extends App{

  val system = ActorSystem.create("Test-Manager", Config(2552))
  println("Debug: " + (new java.util.Date()) + " RemoteActorSpike: " + system.getClass)

  class Manager extends Actor{
    var reporters : List[ActorRef] = Nil
    def receive = {
      case "REGISTER" =>
        println("Debug: " + (new java.util.Date()) + " RemoteActorSpike: adding reporter")
        reporters = sender :: reporters
      case x : String =>
        sender ! (x + x)
      case x : Int =>
        sender ! (x + x)
    }
  }

  val manager = system.actorOf(Props[Manager], "manager")

  val props = maker.Props.apply(file("."))
  val cmd = ScalaCommand(
    props,
    CommandOutputHandler(),
    props.Java,
    Nil,
    System.getProperty("java.class.path"),
    "maker.utils.TestRunner",
    "Launch slave",
    Nil
  )
  cmd.execAsync


}
