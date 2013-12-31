package maker.task.tasks

import maker.task.Task
import maker.project.Module
import maker.task.TaskResult
import maker.utils.Stopwatch
import maker.utils.os.Command
import maker.utils.FileUtils._
import java.io.File
import maker.task.compile.SourceCompileTask
import maker.task.compile._
import maker.task.TaskContext

case class PackageJarTask(module : Module) extends Task {
  def name = "Package Jar"
  val props = module.props
  val log = props.log

  def upstreamTasks = SourceCompileTask(module) :: module.immediateUpstreamModules.map(PackageJarTask(_))

  // Note: until we support scopes properly we have to be careful what we put on the runtime classpath
  //   and in package runtime binary artifacts (so test scope content is deliberately excluded here)
  private lazy val dirsToPack : List[File] =  List(module.compilePhase.outputDir, module.resourceDir).filter(_.exists)

  def exec(context : TaskContext) = {
    synchronized{
      if (fileIsLaterThan(module.outputArtifact, dirsToPack)) {
        log.info("Packaging up to date for " + module.name + ", skipping...")
        TaskResult.success(this)
      } else {
        doPackage()
      }
    }
  }

  private def doPackage() = {
    val jar = props.Jar

    case class WrappedCommand(cmd : Command, ignoreFailure : Boolean){
      def exec : Int = {
        (cmd.exec, ignoreFailure) match {
          case  (0, _) => 0
          case (errorNo, true) => {
            log.warn("Ignoring  error in " + cmd + ". Artifact may be missing content")
            0
          }
          case (errorNo, _) => errorNo
        }
      }
    }
    def jarCommand(updateOrCreate : String, dir : File) = WrappedCommand( 
      Command(List(jar, updateOrCreate, module.outputArtifact.getAbsolutePath, "-C", dir.getAbsolutePath, "."): _*),
      ignoreFailure = false
    )
    def createJarCommand(dir : File) = jarCommand("cf", dir)
    def updateJarCommand(dir : File) = jarCommand("uf", dir)

    if (!module.packageDir.exists)
      module.packageDir.mkdirs

    var cmds : List[WrappedCommand] = createJarCommand(dirsToPack.head) :: dirsToPack.tail.map(updateJarCommand)

    cmds.find(_.exec != 0) match {
      case Some(failingCommand) => TaskResult.failure(this, message = Some(failingCommand.cmd.savedOutput))
      case None => TaskResult.success(this)
    }
  }
}