package maker.task.tasks

import org.scalatest.FunSuite
import maker.utils.FileUtils._
import maker.utils.RichString._
import java.io.File
import maker.project._
import maker.project.TestProject
import maker.MakerProps
import maker.task.compile._


class CachedCompilationTests extends FunSuite {

  def assertSomeFile(file:File) {
    assert(file.exists(), file + " does not exist")
    assert(file.length() > 1, file + " is empty")
  }
  def checkCompile(project:Project, expectedState :CompilationState) {
    val r = project.compile
    val info = r.results(0).info.get.asInstanceOf[CompilationInfo]
    assert(
      info.state === expectedState,
      "Error when compiling " + project 
    )
  }

  test("cached Compilation is used when source hash matches") {
    //projA depends on projU
    //compile both, modify projU, compile, revert change
    //assert the next compilation uses cache

      withTempDir{
      dir ⇒ {
        val projU = new TestProject(new File(dir, "u"), "CachedCompilationTests-u", props = MakerProps("CompilationCache","file", "StripInfoFromTaskResults", "false"))
        val v1 = "package foo\nobject Sample { def hello():Int = 1 }"
        val v2 = "package foo\nobject Sample { def hello():String = \"x\" }\nobject Bob"
        val sample = projU.writeSrc("foo/Sample.scala", v1)

        val projA = new TestProject(new File(dir, "A"), "CachedCompilationTests-A", props = MakerProps("CompilationCache","file", "StripInfoFromTaskResults", "false"), upstreamProjects = List(projU))
        val bar = projA.writeSrc("bar/Bar.scala", "package bar;\nobject Bar { def abc = foo.Sample.hello }")

        checkCompile(projA, CompilationSucceeded)
        assertSomeFile(new File(projU.compilePhase.outputDir, "foo/Sample.class"))

        projU.writeSrc("foo/Sample.scala", v2)
        checkCompile(projA, CompilationSucceeded)
        assertSomeFile(new File(projU.compilePhase.outputDir, "foo/Bob.class"))

        projU.writeSrc("foo/Sample.scala", v1)
        checkCompile(projA, CachedCompilation)
        assertSomeFile(new File(projU.compilePhase.outputDir, "foo/Sample.class"))
        assert(false === new File(projU.compilePhase.outputDir, "foo/Bob.class").exists())
      }
    }
  }
}