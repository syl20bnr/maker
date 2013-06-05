println("\n ** Loading Titan Modules Build...\n")


/**
 * titan component builds and helper utils
 */

// this the representation of titan (wars) from binaries using ivy
lazy val titanBinDeps = Project(file("titan.bindeps"))

// for inverted (regular JavaSE) combined classpath,
// pull some common stuff out of packages wars (inner classpath) into the outer (parent classpath) environment
lazy val additionalTitanLibraryExclusions = List(
  "commons-httpclient" % "commons-httpclient",
  "org.apache.httpcomponents" % "httpcore",
  "org.jboss.resteasy" % "resteasy-jaxrs",
  "com.oracle" % "ojdbc6",
  "org.jboss.resteasy" % "jaxrs-api",
//  "org.slf4j" % "slf4j-api",
  "xml-apis" % "xml-apis",
  "org.scalatest" % "scalatest_2.8.1" // less than ideal but titan common libs refer to scala 2.8.1 version of scalatest which can cause a runtime class cast exception when running tests. Luckily it has a crossed artifact id so it can be eliminated specifically
)

// as above but to exclude from the packaging explicitly, by name
lazy val classpathProvidedLibs : List[String] = "starling.client_2.9.1" :: additionalTitanLibraryExclusions.map(_.artifactId.id)
lazy val titanIvysettings = "../../../services/.maker/ivy/ivysettings.xml"

// shared cost and incomes lib that contains some common and test classes necessary to run c&i module unit tests
lazy val titanCostsAndIncomesLib = {
  val root = file("../../lib/costsandincomes/internal")
  new Project(
    root,
    "costsandincomes", 
    layout = ProjectLayout.maven(root, Some(file(root, targetDirName))).copy(
      ivySettingsFile = file(root, "../../../services/.maker/ivy/ivysettings.xml")
    ),
    props = makerProps,
    moduleIdentity = Some("com.trafigura.titan.shared-libs" % "costsandincomes-internal"),
    ivyAdjustments = IvyDependencyAdjustments(
      additionalLibs = List("com.oracle" % "ojdbc6" % "11.2.0.1.0"),
      additionalExcludedLibs = additionalTitanLibraryExclusions.filterNot(_.groupId.id == "com.oracle"), // dependency on oracle lib here is test scope only, redo once we support proper scoping/configs
      providedLibNames = "slf4j-api" :: classpathProvidedLibs
    )
  ) dependsOn (starlingClient, /* starlingDTOApi, */ daterange, quantity)
}

// build a standard titan component (module) webapp  definition,
//   but with classpath inversion considerations...
def projectT(name : String) : Project = {
  val extraLibs = List(
    "org.scalatest" % "scalatest_2.9.1" % "1.7.1",
//    "org.slf4j" % "slf4j-log4j12" % "1.6.1",
//    "org.slf4j" % "slf4j-api" % "1.6.1",
    "log4j" % "log4j" % "1.2.16")

  lazy val titanService = "../" + name + "/service"
  val root = file(titanService)
  new Project(
    root,
    name, 
    layout = ProjectLayout.maven(root, Some(file(root, targetDirName))).copy(
      ivySettingsFile = file(root, titanIvysettings)
    ),
    props = makerProps,
    ivyAdjustments = IvyDependencyAdjustments(
      additionalLibs = extraLibs,
      additionalExcludedLibs = additionalTitanLibraryExclusions,
      providedLibNames = classpathProvidedLibs
    ),
    webAppDir = Some(file(root, "src/main/webapp"))
  )
    /*
    sourceDirs = List(file(titanService, "src/main/scala")),
    tstDirs = List(file(titanService, "src/test/scala")),
    libDirs = List(file(titanService, "lib_managed"),
      file(titanService, "lib"),
      file(titanService, ".maker/scala-lib")),
//    providedLibDirs = List(file(titanService, "../../.maker/lib")),
    managedLibDirName = "lib_managed",
//    resDirs = List(file(titanService, "src/main/resources")),
    targetDir = targetDirFile(name), // for now, until we drop sbt so it doesn't clash!
    props = makerProps,
    ivySettingsFile = file(titanService, "../../.maker/ivy/ivysettings.xml"),
    webAppDir = Some(file(titanService, "src/main/webapp")),
    additionalLibs = extraLibs,
    additionalExcludedLibs = additionalTitanLibraryExclusions,
    providedLibs = classpathProvidedLibs */
}

// titan components we can potentially build from sources
lazy val titanConfig = projectT("configuration")
lazy val titanMurdoch = projectT("murdoch").dependsOn(trademgmtModelDeps : _*)
lazy val titanTradeService = projectT("tradeservice") dependsOn(trademgmtModelDeps : _*)
lazy val titanPermission = projectT("permission")
lazy val titanReferenceData = projectT("referencedata") dependsOn(trademgmtModelDeps : _*)
lazy val titanLogistics = projectT("logistics").dependsOn(logisticsModelDeps ::: trademgmtModelDeps : _*)

lazy val titanInvoicing = { 
  val p = projectT("invoicing")
  val p2 = p.withAdditionalSourceDirs(file(p.root, "target/generated-sources/"))
  p2.copy(
//    layout = p.layout.withAdditionalSourceDirs(file(p.root, "target/generated-sources/")),
    ivyAdjustments = p2.ivyAdjustments.copy(
      additionalExcludedLibs = List(),
      providedLibNames = classpathProvidedLibs)
  ).dependsOn(starlingClient :: trademgmtModelDeps : _*)
}
//.withAdditionalSourceDirs("target/generated-sources/").setAdditionalExcludedLibs().withProvidedLibs(classpathProvidedLibs : _*).dependsOn(starlingClient :: trademgmtModelDeps : _*)

lazy val titanCostsAndIncomes = projectT("costsandincomes")/*.withAdditionalTestDirs("../../../lib/costsandincomes/internal/src/test/scala").withAdditionalLibs("com.trafigura.titan.shared-libs" % "costsandincomes-internal" % "2.7.2")*/.dependsOn(titanCostsAndIncomesLib :: starlingClient :: daterange :: quantity :: starlingDTOApi :: trademgmtModelDeps : _*)

lazy val titanMtmPnl = projectT("mtmpnl").dependsOn(/* titanCostsAndIncomesLib :: */ starlingClient :: trademgmtModelDeps : _*)
lazy val titanReferenceDataNew = projectT("referencedatanew")
lazy val titanMapping = projectT("mapping")
lazy val titanSecuritisation = projectT("securitisation") dependsOn starlingClient
lazy val titanFinance= projectT("finance") dependsOn starlingClient

// all titan components that can be built from sources as part of an integrated build
lazy val allTitanComponents = Seq(
//                titanConfig,      // not on master
//                titanPermission,  // not on master
                titanMurdoch,
                titanTradeService,
                titanReferenceData,
                titanLogistics,
                titanCostsAndIncomes,
                titanMtmPnl,
                titanInvoicing
//                titanFinance      // this builds ok but is excluded until the runtime env is sorted out
)

// list of components to take from binaries

lazy val titanBinDepComponentList : List[String] = Option(starlingProperties.getProperty("TitanProxiedServices")).map(_.split(":").toList.map(_.toLowerCase)).getOrElse(Nil)

// list of titan projects to build from sources 
lazy val titanComponents = allTitanComponents.filterNot(p => titanBinDepComponentList.exists(c => p.name.contains(c)))

lazy val starlingTitanDeps = project("titanComponents") dependsOn (titanComponents : _*)

// builder and launcher are split up for the purposes of separating run time scope from compile time,
// when we get to add scopes to maker properly these two projects can be combined
lazy val titanBuilder = project("titan.builder").dependsOn(launcher, starlingTitanDeps)
lazy val titanLauncher = project("titan.launcher").dependsOn(launcher)

def buildWithTitan = {
  titanLauncher.compile
  val r = starlingTitanDeps.pack
  if (r.succeeded) Some(r) else None
}
lazy val jbossDeployDir = file(System.getenv("JBOSS_HOME") + "/server/trafigura/deploy")
lazy val jettyDeployDir = file("titan.deploy/wars")
def deployWar(deployDir : File)(project : Project) {
  project.webAppDir match {
    case Some(_) => copyFileToDirectory(project.outputArtifact, deployDir)
    case _ => throw new RuntimeException("can't deploy a war from a non webapp project!")
  }
}
lazy val deployWarToJetty = deployWar(jettyDeployDir) _
lazy val deployWarToJboss = deployWar(jbossDeployDir) _
def deployWarsTo(deployDir : File) = {
  println("building and packaging: " + titanComponents.map(_.name).mkString(","))
  val failures = titanComponents.map(p => p.packOnly).filter(_.succeeded != true) //collect{ case e @ TaskFailed(_, _) => e }
  failures match {
    case Nil  =>
      println("Titan deploy to: " + deployDir.getAbsolutePath)
      titanComponents.foreach(deployWar(deployDir))
    case r @ _ => println("failed to build "); r
  }
}
def deployToTitanJboss = deployWarsTo(jbossDeployDir)
def deployToTitanJetty = deployWarsTo(jettyDeployDir)

/**
 * build all of startling and titan dependencies for starling
 * compile and package the titan web apps
 * deploy them to local jboss
 */
def buildAndDeployWithTitanJboss = buildWithTitan.map(_ => deployToTitanJboss)
def buildAndDeployWithTitanJetty = buildWithTitan.map(_ => deployToTitanJetty)

def deployTitanJbossWarsWithUpdate(update : Boolean = true) {
  if (!jbossDeployDir.exists) {
    println("jboss deploy dir does not exist, please check JBOSS_HOME")
    return
  }
  if (update) {
    println("updating binary dependencies...")
    titanBinDeps.update
  }
  val titanBinDepsDir = titanBinDeps.layout.managedLibDir
  val availableBinDeps = titanBinDepsDir.listFiles.toList.filter(f => f.getName.endsWith(".war"))
  println("available bin deps:\n " + availableBinDeps.mkString("\n"))
  val filesToCopyToJboss = availableBinDeps.filter(f => titanBinDepComponentList.exists(c => f.getName.toLowerCase.contains(c.toLowerCase)))

  def baseName(name : String) = name.split('.')(0).split('-')(0)
  def names(files : List[File]) : List[(File,  String)] = files.filter(_.getName.endsWith(".war")).map(f ⇒ (f, baseName(f.getName)))

  val updatedNames = names(filesToCopyToJboss)
  //println("names: \n " + updatedNames.mkString("\n"))
  
  val warsToRemove = names(jbossDeployDir.listFiles.toList).filter(n ⇒  updatedNames.exists(x ⇒ x._2 == n._2))
  println("to remove : \n " + warsToRemove.mkString("\n"))
  
  val removedWars = warsToRemove.map(f ⇒ (f, f._1.delete))
  println("removed:\n " + removedWars.mkString("\n"))

  println("copying: \n" + filesToCopyToJboss.mkString("\n") + "\n to: " + jbossDeployDir.getAbsolutePath)
  filesToCopyToJboss.foreach(f => copyFileToDirectory(f, jbossDeployDir))
}
def deployTitanJbossWars : Unit = deployTitanJbossWarsWithUpdate()
def runStarlingWithTitanDeploy(deployWars : Boolean = true, debug : Boolean = false) {
  val titanProxiedComponents = titanBinDepComponentList.mkString(":")
  val titanProxyHost = starlingProperties.getProperty("TitanProxiedServiceHost")
  val titanProxyPort = starlingProperties.getProperty("TitanProxiedServicePort")
  println("***** Titan Proxied Components = " + titanProxiedComponents)
  titanBuilder.compile
  if (deployWars) deployToTitanJetty
  titanLauncher.runMain(
    "starling.launcher.DevLauncher")(
    ( "-Djavax.xml.parsers.DocumentBuilderFactory=com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl" ::
      "-Dstarling.titan.enabled=true" ::
      "-Dtitan.webapp.server.log=logs/titan-server.log" :: (if (debug) 
      "-Xdebug" :: "-Xrunjdwp:transport=dt_socket,server=y,address=6666" :: Nil else Nil) :::
    commonLaunchArgs.toList) : _*)()
}
def runStarlingWithTitan : Unit = runStarlingWithTitanDeploy()

println("\n ** (Type helpTitan for a list of common Titan commands) ** \n")
