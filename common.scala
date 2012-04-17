println("\n ** Loading common definitions...\n")


lazy val unmanagedGlobalProperties : Properties = file("developer.conf")

val verboseGC = false

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

