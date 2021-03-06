/*
 * Copyright (c) 2011-2012, Alex McGuire, Louis Botterill
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met: 
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer. 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution. 
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package maker.task

import java.lang.Runnable
import java.util.Comparator
import java.util.concurrent.{BlockingQueue, Executors, FutureTask}
import java.util.concurrent.{PriorityBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import maker.project.{BaseProject, Module}
import maker.task.compile.{CompileTask, TestCompileTask}
import maker.task.tasks.{RunUnitTestsTask, UpdateTask}
import maker.utils.{MakerLog, Stopwatch}

case class Build(
  name : String,
  graph : Dependency.Graph,
  numberOfWorkers : Int
) {

  override def toString = "Build " + name

  def toLongString = {
    val buf = new StringBuffer
    buf.append("Build\n  " + name + "\n")
    buf.append("  Graph:\n  " + graph.toString.split("\n").mkString("\n  ") + "\n")
    buf.toString
  }
  
  val log = MakerLog()

  def execute = new Execute().execute
  class Execute{

    val executor = Build.PriorityExecutor(numberOfWorkers, name)

    val monitor = new Build.TaskMonitor(log, graph, executor)

    def execute : BuildResult = {
      val taskResults = execTasksInGraph()
      val buildResult = BuildResult(
        name,
        taskResults.toList,
        graph
      )
      buildResult
    }

    private def passToExecutor(pt : Task, resultsSoFar : Set[TaskResult]){
      val sw = new Stopwatch
      monitor.addToQueue(pt)
      val priority = pt match {
        case _: UpdateTask => -1000
        case task: TestCompileTask =>
          -100 - task.module.allUpstreamModules.size
        case task: CompileTask =>
          -500 - task.module.allUpstreamModules.size
        case tests: RunUnitTestsTask =>
          // higher priority for top level tests
          // (more likely to be slower, integration style, tests)
          tests.module match {
            case m: Module => -m.allUpstreamModules.size
            case _ => -tests.upstreamTasks.size
          }
        case _ => 0
      }
      log.debug("SUBMITTING " + pt + " with priority " + priority)
      executor.executeWithPriority(priority){
        log.debug("EXECUTING " + pt + " with priority " + priority)
            val threadNumber = Thread.currentThread.getName.last.toString.toInt
            try {
              val result = try {
                // Reassure the user some activity is happening
                if (monitor.incCounter % 20 == 0)
                  println
                print(".")
                monitor.addLaunch(pt)
                log.debug("Launched " + pt + " (" + monitor.numRunning + " running, " + monitor.numQueued + " queued)")
                val res = pt.exec(resultsSoFar.toList, sw)
                res
              } catch {
                case e: Throwable =>
                  log.warn("exception thrown:" + e + " when running task " + pt)
                  e.printStackTrace
                  DefaultTaskResult(pt, false, sw, exception = Some(e))
              }
              sw.takeSnapshot(TaskResult.TASK_END)
              monitor.addResult(pt, result)
              log.debug("Finished " + pt + " (" + monitor.numRunning + " running, " + monitor.numQueued + " queued)")
            } catch {
              case e: Throwable ⇒
                log.warn("Exception " + e + " thrown during " + pt)
                e.printStackTrace
                System.exit(-1)
            }
            finally {
              monitor.synchronized{ monitor.notifyAll }
            }
          }
    }


    private def execTasksInGraph() = {

      while (! monitor.isComplete){
        monitor.doLog

        val (next, results) = monitor.nextAndResultsSoFar

        next.foreach {
          pt => 
            passToExecutor(pt, results)
        }

        monitor.synchronized{ monitor.wait(100) }
        monitor.doLog
      }
      waitForRunningTasksToComplete()
      executor.shutdown
      val (_, results) = monitor.nextAndResultsSoFar

      results
    }

    private def waitForRunningTasksToComplete(){
      while (monitor.isRunning){
        // We get here if a task failure causes early interruption
        // We still need to wait for other tasks to finish, if not
        // maker's state (e.g. file dependencies) coule be corrupted
        log.debug("Waiting for tasks to complete")
        Thread.sleep(100)
      }
    }
  }
}

object Build{
  val taskCount = new AtomicInteger(0)

  def apply(project : BaseProject, tasks : Task*) : Build = {
    Build(
      tasks.toList.headOption.map(_.name).getOrElse("No Tasks"),
      Dependency.Graph.transitiveClosure(project, tasks.toList),
      numberOfWorkers = project.props.NumberOfTaskThreads()
    )
  }

  def apply(task : Task) : Build = apply(task.baseProject, task)

  private class PrioritisedFutureTask(r: Runnable, val priority: Int)
      extends FutureTask[Unit](r, null) with Comparable[PrioritisedFutureTask] {
    override def compareTo(o: PrioritisedFutureTask) = priority - o.priority
  }

  /** `Executor` that can take a priority value (lower is higher
    * priority) along with a closure which is used when assigning work
    * to `Thread`s. Tasks without a priority value will be treated as
    * low priority.
    *
    * Note that once a task has been accepted, it will not be stopped, so
    * a low priority task that has been accepted will continue to run
    * even if high priority tasks are subsequently submitted.
    */
  class PriorityExecutor private(workers: Int, queue: BlockingQueue[Runnable])
      extends ThreadPoolExecutor(workers, workers, Long.MaxValue, TimeUnit.NANOSECONDS, queue) {
    def executeWithPriority(priority: Int)(f: => Unit): Unit = {
      val task = new PrioritisedFutureTask(new Runnable {
        override def run(): Unit = f
      }, priority)
      super.execute(task)
    }
  }

  object PriorityExecutor {
    def apply(workers: Int, name: String): PriorityExecutor = {
      // should really be a PriorityExecutor[ComparableFutureTask] but
      // we are forced by invariance to use reflection and handle
      // Runnables
      val comparator = new Comparator[Runnable] {
        type P = PrioritisedFutureTask
        def compare(a: Runnable, b: Runnable) =
          if (a.isInstanceOf[P] && b.isInstanceOf[P])
            a.asInstanceOf[P].compareTo(b.asInstanceOf[P])
          else if (a.isInstanceOf[P]) -1
          else if (b.isInstanceOf[P]) 1
          else 0
      }
      val queue = new PriorityBlockingQueue[Runnable](32, comparator)
      val factory = new ThreadFactory(){
        val threadCount = new AtomicInteger(0)
        def newThread(r : Runnable) = {
          val thread  = Executors.defaultThreadFactory.newThread(r)
          thread.setName("Build-" + Build.taskCount.getAndIncrement + "-" + name + "." + threadCount.getAndIncrement)
          thread
        }
      }
      new PriorityExecutor(workers, queue)
    }
  }


  class TaskMonitor(log : MakerLog, graph : Dependency.Graph, executor : ThreadPoolExecutor){
    private val lock = new Object
    var results = List[TaskResult]()
    private var completed = Set[Task]()
    private var queued = Set[Task]()
    private var running = Set[Task]()
    private var unacknowledged = Set[Task]()
    private var remaining = graph
    private var taskLaunchCounter = 0

    def isComplete = lock.synchronized{
      completed.size == graph.size || results.exists{r => r.failed && r.task.failureHaltsTaskManager}
    }

    def isRunning = lock.synchronized{
      ! running.isEmpty
    }

    def addToQueue(t : Task){
      lock.synchronized{
        queued += t
        remaining -= t
      }
    }
    def addLaunch(t : Task){
      lock.synchronized{
        queued -= t
        running += t
      }
    }

    def addResult(t : Task, r : TaskResult){
      lock.synchronized{
        results ::= r
        running -= t
        completed += t
        unacknowledged += t
      }
    }

    def doLog{
      lock.synchronized{
        if (unacknowledged.nonEmpty){
          unacknowledged = Set[Task]()
          log.debug("Queue size " + executor.getQueue.size)
          log.debug("queued " + queued.size + queued.toList.mkString("\n\t", "\n\t", ""))
          log.debug("running " + running.size + running.toList.mkString("\n\t", "\n\t", ""))
          log.debug("all " + graph.size)
          log.debug("completed " + completed.size)
        }
      }
    }

    def nextAndResultsSoFar = lock.synchronized{
      val next = remaining.leaves.filter{
        pt => 
          graph.upstreams(pt).subsetOf(completed)
      }
      (next, Set[TaskResult]() ++ results)
    }
    def incCounter = lock.synchronized{
      taskLaunchCounter += 1
      taskLaunchCounter
    }
    def numRunning = lock.synchronized{
      running.size
    }
    def numQueued = lock.synchronized{
      executor.getQueue.size
    }
  }
}
