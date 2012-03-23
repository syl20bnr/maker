import maker.project.Project
import java.io.File
import maker.Props
import maker.utils.FileUtils._
import maker.utils.Log
import maker.utils.Log._
import org.apache.log4j.Level._
import org.apache.commons.io.FileUtils._

lazy val properties = Props(file("Maker.conf"))

def project(name : String) = new Project(
  name, 
  file(name),
  libDirs = List(file(name, "lib_managed"), file(name, "lib"), file(name, "maker-lib"), file(".maker/scala-lib")),
  resourceDirs = List(file(name, "resources"), file(name, "test-resources")),
  props = properties
)
lazy val manager = project("manager")
lazy val utils = project("utils") dependsOn manager
lazy val osgirun = project("osgirun").copy(libDirs = List(new File("osgirun/lib_managed"), new File("osgirun/lib"), new File("osgirun/osgi_jars")))
lazy val booter = project("booter")
lazy val concurrent = project("concurrent") dependsOn utils
lazy val quantity = project("quantity") dependsOn utils
lazy val osgiManager = project("osgimanager") dependsOn utils
lazy val singleClasspathManager = project("singleclasspathmanager") dependsOn osgiManager
lazy val pivot = project("pivot") dependsOn quantity
lazy val daterange = project("daterange") dependsOn utils
lazy val pivotUtils = project("pivot.utils") dependsOn (daterange, pivot)
lazy val titanReturnTypes = project("titan.return.types") dependsOn (daterange, quantity)
lazy val maths = project("maths") dependsOn (daterange, quantity)
lazy val starlingApi = project("starling.api") dependsOn titanReturnTypes
lazy val props = project("props") dependsOn utils
lazy val auth = project("auth") dependsOn props
lazy val bouncyrmi = project("bouncyrmi") dependsOn auth
lazy val loopyxl = project("loopyxl") dependsOn auth
lazy val browserService = project("browser.service") dependsOn manager
lazy val browser = project("browser") dependsOn browserService
lazy val guiapi = project("gui.api") dependsOn (browserService, bouncyrmi, pivotUtils)
lazy val fc2Facility = project("fc2.facility") dependsOn guiapi
lazy val curves = project("curves") dependsOn (maths, guiapi)
lazy val instrument = project("instrument") dependsOn (curves, titanReturnTypes)
lazy val reportsFacility = project("reports.facility") dependsOn guiapi
lazy val rabbitEventViewerApi = project("rabbit.event.viewer.api") dependsOn(pivot, guiapi)
lazy val tradeFacility = project("trade.facility") dependsOn guiapi
lazy val gui = project("gui") dependsOn (fc2Facility, tradeFacility, reportsFacility, browser, rabbitEventViewerApi, singleClasspathManager)
lazy val starlingClient = project("starling.client") dependsOn (starlingApi, bouncyrmi)
lazy val dbx = project("dbx") dependsOn instrument
lazy val databases = project("databases") dependsOn (pivot, concurrent, starlingApi, dbx)
lazy val titan = project("titan") dependsOn (starlingApi, databases)
lazy val services = project("services").copy(resourceDirs = List(new File("services", "resources"), new File("services", "test-resources"))) dependsOn (curves, concurrent, loopyxl, titan, gui, titanReturnTypes)
lazy val services = project("services") dependsOn (curves, concurrent, loopyxl, titan, gui, titanReturnTypes)
lazy val rabbitEventViewerService = project("rabbit.event.viewer.service") dependsOn (rabbitEventViewerApi, databases, services)
lazy val tradeImpl = project("trade.impl") dependsOn (services, tradeFacility)
lazy val metals = project("metals").copy(resourceDirs = List(new File("metals", "resources"), new File("metals", "test-resources"))) dependsOn tradeImpl
lazy val reportsImpl = project("reports.impl") dependsOn services

lazy val webservice = {
  lazy val name = "webservice"
  lazy val libs = List(file(name, "lib_managed"), file(name, "lib"), file(name, "lib-jboss"), file(name, "maker-lib"), file(".maker/scala-lib"))
  lazy val resources =  List(file(name, "resources"), file(name, "test-resources"))
  new Project(
    name,
    file(name),
    libDirs = libs,
    resourceDirs = resources,
    props = properties
  ) dependsOn (props, starlingApi)
}


lazy val startserver = project("startserver") dependsOn (reportsImpl, metals, starlingClient, webservice, rabbitEventViewerService)
lazy val launcher = project("launcher") dependsOn (startserver, booter)


/**
 * Start of Titan related build and deploy definitions (should probably go in a separate file)
 */
def helpTitan() = {
  println("HelpTitan:")
  println("")
  println("\t titanBinDeps = project defining all binary dependencies of titan")
  println("\t titanComponents - list of titan components built for this env")
  println("\t titanBinDepComponents - list of binary dependencies for deployment")
  println("\t * titanLauncher - starling and titan launcher project")
  println("")
  println("\t buildWithTitan - build all of starling, build titan from sources and only package titan")
  println("")
  println("\t * buildAndDeployWithTitanJetty - build starling + titan from source and copy built wars to jetty")
  println("\t deployTitanJbossWars - deploy titan binary wars to jboss")
  println("")
  println("\t * runStarlingWithTitan - launch starling + titan in the repl")
  println("\t runStarlingWithTitanDeploy(false) - launch staring + titan in the repl with no jetty redeployment")
  println("")
}
lazy val titanBinDeps = {
  lazy val name = "titan.bindeps"
  new Project(
    name,
    file(name),
    managedLibDirName = "lib_managed",
    ivySettingsFile = file(name, "maker-ivysettings.xml"))
}

// build a standard titan component (module) webapp  definition
def projectT(name : String) = {
  lazy val titanService = "../" + name + "/service"
  new Project(
    name, 
    file(titanService),
    sourceDirs = List(file(titanService, "src/main/scala")),
    tstDirs = List(file(titanService, "src/test/scala")),
    libDirs = List(file(titanService, "lib_managed"),
      file(titanService, "lib"),
      file(titanService, ".maker/scala-lib")),
    providedDirs = List(file(titanService, "../../.maker/lib")),
    managedLibDirName = "lib_managed",
    resourceDirs = List(file(titanService, "src/main/resources")),
    props = properties,
    ivySettingsFile = file(titanService, "../../.maker/ivy/maker-ivysettings.xml"),
    webAppDir = Some(file(titanService, "src/main/webapp"))
  )
}

lazy val titanConfig = projectT("configuration")
lazy val titanMurdoch = projectT("murdoch")
lazy val titanTradeService = projectT("tradeservice")
lazy val titanPermission = projectT("permission")
lazy val titanReferenceData = projectT("referencedata")
lazy val titanLogistics = projectT("logistics")

lazy val titanComponents = Seq(
//                titanConfig,      // not on master 
                titanMurdoch,
                titanTradeService,
//                titanPermission,  // not on master
                titanReferenceData,
                titanLogistics)

lazy val titanBinDepComponents = Seq("Configuration", "Permission", "referencedatanew", "mapping")

lazy val starlingTitanDeps = project("titanComponents") dependsOn (titanComponents : _*)

lazy val titanLauncher = project("titan.launcher").dependsOn(launcher, starlingTitanDeps)

import maker.task.BuildResult
def buildWithTitan = {
  titanLauncher.compile
  lazy val r : BuildResult = starlingTitanDeps.pack
  r.res match {
    case Right(_) => Some(r)
    case _ => None
  }
}
lazy val jbossDeployDir = file(System.getenv("JBOSS_HOME") + "/server/trafigura/deploy")
lazy val jettyDeployDir = file("titan.deploy/wars")
def deployWar(deployDir : File)(project : Project) {
  copyFileToDirectory(file(project.root, "package/" + project.name + ".war"), deployDir)
}
lazy val deployWarToJetty = deployWar(jettyDeployDir) _
lazy val deployWarToJboss = deployWar(jbossDeployDir) _
def deployWarsTo(deployDir : File) = {
  Log.info("Titan deploy to: " + deployDir.getAbsolutePath)
  titanComponents.foreach(deployWar(deployDir))
}
def deployToTitanJboss = deployWarsTo(jbossDeployDir)
def deployToTitanJetty = deployWarsTo(jettyDeployDir)

// build all of startling and titan dependencies for starling
// compile and package the titan web apps
// deploy them to local jboss
def buildAndDeployWithTitanJboss = buildWithTitan.map(_ => deployToTitanJboss)
def buildAndDeployWithTitanJetty = buildWithTitan.map(_ => deployToTitanJetty)

def deployTitanJbossWars {
  titanBinDeps.update
  val binDepsDir = titanBinDeps.managedLibDir
  Log.info("Available bin deps: " + binDepsDir.listFiles.mkString(","))
  val filesToCopyToJboss = titanBinDeps.managedLibDir.listFiles.filter(f => f.getName.endsWith(".war") && titanBinDepComponents.exists(c => f.getName.toLowerCase.contains(c.toLowerCase)))
  Log.info("copying files to " + jbossDeployDir.getAbsolutePath + ", " + filesToCopyToJboss.mkString(","))
  filesToCopyToJboss.foreach(f => copyFileToDirectory(f, jbossDeployDir))
}

lazy val verboseGC = false
lazy val commonLaunchArgs = List(
  "-server",
  "-XX:MaxPermSize=1024m",
  "-Xss128k",
  "-Xms6000m",
  "-Xmx12000m",
  "-Dsun.awt.disablegrab=true",
  "-XX:+UseConcMarkSweepGC") ::: {
    if (verboseGC) List(
      "-verbose:gc",
      "-XX:+PrintGCTimeStamps",
      "-XX:+PrintGCDetails")
    else Nil
  }


def runStarlingWithTitanDeploy(deployWars : Boolean = true) {
  titanLauncher.compile
  if (deployWars) deployToTitanJetty
  titanLauncher.runMain(
    "starling.launcher.DevLauncher")(
    ( "-Djavax.xml.parsers.DocumentBuilderFactory=com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl" ::
      "-Dtitan.webapp.server.logs=logs/" :: 
    commonLaunchArgs.toList) : _*)()
}
def runStarlingWithTitan : Unit = runStarlingWithTitanDeploy()

def runDevLauncher = {
  launcher.compile
  launcher.runMain(
    "starling.launcher.DevLauncher")(
    commonLaunchArgs : _*)()
}

def runServer = {
  launcher.compile
  launcher.runMain(
    "starling.startserver.Server")(
    commonLaunchArgs : _*)()
}

import java.io._

def writeToFile(fileName : String, text : String){
  val fstream = new FileWriter(fileName)
  val out = new BufferedWriter(fstream)
  out.write(text)
  out.close()
}

def writeClasspath{
  val cp = launcher.compilationClasspath
  writeToFile("launcher-classpath.sh", "export STARLING_CLASSPATH=" + cp)
}

