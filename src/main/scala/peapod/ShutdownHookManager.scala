/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package peapod

import java.io.File
import java.util.PriorityQueue

import org.apache.commons.io.FileUtils
import org.apache.hadoop.fs.FileSystem


/**
  * Various utility methods used by Spark.
  */
object ShutdownHookManager extends Logging {
  val DEFAULT_SHUTDOWN_PRIORITY = 100

  /**
    * The shutdown priority of the SparkContext instance. This is lower than the default
    * priority, so that by default hooks are run before the context is shut down.
    */
  val SPARK_CONTEXT_SHUTDOWN_PRIORITY = 50

  /**
    * The shutdown priority of temp directory must be lower than the SparkContext shutdown
    * priority. Otherwise cleaning the temp directories while Spark jobs are running can
    * throw undesirable errors at the time of shutdown.
    */
  val TEMP_DIR_SHUTDOWN_PRIORITY = 25

  private lazy val shutdownHooks = {
    val manager = new PeapodShutdownHookManager()
    manager.install()
    manager
  }

  private val shutdownDeletePaths = new scala.collection.mutable.HashSet[String]()

  // Add a shutdown hook to delete the temp dirs when the JVM exits
  addShutdownHook(TEMP_DIR_SHUTDOWN_PRIORITY) { () =>
    logInfo("Shutdown hook called")
    // we need to materialize the paths to delete because deleteRecursively removes items from
    // shutdownDeletePaths as we are traversing through it.
    shutdownDeletePaths.toArray.foreach { dirPath =>
      try {
        logInfo("Deleting directory " + dirPath)
        FileUtils.deleteDirectory(new File(dirPath))
      } catch {
        case e: Exception => logError(s"Exception while deleting Spark temp dir: $dirPath", e)
      }
    }
  }

  // Register the path to be deleted via shutdown hook
  def registerShutdownDeleteDir(file: File) {
    val absolutePath = file.getAbsolutePath
    shutdownDeletePaths.synchronized {
      shutdownDeletePaths += absolutePath
    }
  }

  /**
    * Detect whether this thread might be executing a shutdown hook. Will always return true if
    * the current thread is a running a shutdown hook but may spuriously return true otherwise (e.g.
    * if System.exit was just called by a concurrent thread).
    *
    * Currently, this detects whether the JVM is shutting down by Runtime#addShutdownHook throwing
    * an IllegalStateException.
    */
  def inShutdown(): Boolean = {
    try {
      val hook = new Thread {
        override def run() {}
      }
      // scalastyle:off runtimeaddshutdownhook
      Runtime.getRuntime.addShutdownHook(hook)
      // scalastyle:on runtimeaddshutdownhook
      Runtime.getRuntime.removeShutdownHook(hook)
    } catch {
      case ise: IllegalStateException => return true
    }
    false
  }

  /**
    * Adds a shutdown hook with default priority.
    *
    * @param hook The code to run during shutdown.
    * @return A handle that can be used to unregister the shutdown hook.
    */
  def addShutdownHook(hook: () => Unit): AnyRef = {
    addShutdownHook(DEFAULT_SHUTDOWN_PRIORITY)(hook)
  }

  /**
    * Adds a shutdown hook with the given priority. Hooks with lower priority values run
    * first.
    *
    * @param hook The code to run during shutdown.
    * @return A handle that can be used to unregister the shutdown hook.
    */
  def addShutdownHook(priority: Int)(hook: () => Unit): AnyRef = {
    shutdownHooks.add(priority, hook)
  }
}

class PeapodShutdownHookManager {

  private val hooks = new PriorityQueue[PeapodShutdownHook]()
  @volatile private var shuttingDown = false

  /**
    * Install a hook to run at shutdown and run all registered hooks in order.
    */
  def install(): Unit = {
    val hookTask = new Runnable() {
      override def run(): Unit = runAll()
    }
    org.apache.hadoop.util.ShutdownHookManager.get().addShutdownHook(
      hookTask, FileSystem.SHUTDOWN_HOOK_PRIORITY + 30)
  }

  def runAll(): Unit = {
    shuttingDown = true
    var nextHook: PeapodShutdownHook = null
    while ({ nextHook = hooks.synchronized { hooks.poll() }; nextHook != null }) {
      nextHook.run()
    }
  }

  def add(priority: Int, hook: () => Unit): AnyRef = {
    hooks.synchronized {
      if (shuttingDown) {
        throw new IllegalStateException("Shutdown hooks cannot be modified during shutdown.")
      }
      val hookRef = new PeapodShutdownHook(priority, hook)
      hooks.add(hookRef)
      hookRef
    }
  }

}

class PeapodShutdownHook(val priority: Int, hook: () => Unit)
  extends Comparable[PeapodShutdownHook] {

  override def compareTo(other: PeapodShutdownHook): Int = {
    other.priority - priority
  }

  def run(): Unit = hook()

}