package zio

import zio.test._
import zio.test.Assertion._

/**
 * Scala Native-specific tests for ZIOApp behavior.
 * On Native platform, shutdown hooks and signal handlers are no-ops.
 */
object ZIOAppBehaviorNativeSpec extends ZIOSpecDefault {

  def spec = suite("ZIOApp Behavior - Scala Native")(
    suite("Basic App Completion")(
      test("successful completion returns ExitCode.success") {
        for {
          result <- TestZIOAppNative.successful.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.completed
        )
      },
      test("failed completion returns ExitCode.failure") {
        for {
          result <- TestZIOAppNative.failing.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.failure,
          result.completed
        )
      }
    ),
    suite("Signal Handling - Native Specific")(
      test("signal handlers are no-op on Native") {
        for {
          result <- TestZIOAppNative.withSignalHandler.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.completed,
          !result.signalHandlerInstalled // Should be no-op
        )
      }
    ),
    suite("Finalizer Behavior - Native")(
      test("finalizers run on normal completion") {
        for {
          result <- TestZIOAppNative.withFinalizer.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.finalizersRan,
          result.completed
        )
      },
      test("finalizers run on failure") {
        for {
          result <- TestZIOAppNative.withFinalizerFailure.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.failure,
          result.finalizersRan,
          result.completed
        )
      }
    ),
    suite("Platform Differences")(
      test("no shutdown hooks on Native platform") {
        for {
          result <- TestZIOAppNative.withShutdownHook.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.completed,
          !result.shutdownHookRan // Shutdown hooks are no-op on Native
        )
      },
      test("exit behavior on Native") {
        for {
          result <- TestZIOAppNative.explicitExit.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.completed,
          result.exitCalled
        )
      }
    ),
    suite("Resource Management - Native")(
      test("resources are properly cleaned up") {
        for {
          result <- TestZIOAppNative.multipleResources.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.finalizersRan,
          result.allResourcesCleaned
        )
      }
    )
  ) @@ TestAspect.nativeOnly

  /**
   * Test result for Native-specific behavior
   */
  case class TestResultNative(
    exitCode: ExitCode,
    completed: Boolean,
    finalizersRan: Boolean,
    allResourcesCleaned: Boolean,
    signalHandlerInstalled: Boolean,
    shutdownHookRan: Boolean,
    exitCalled: Boolean,
    errorOutput: String
  )

  /**
   * Base class for testable ZIOApp instances on Scala Native
   */
  abstract class TestableZIOAppNative extends ZIOAppDefault {
    import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
    
    val finalizerRan = new AtomicBoolean(false)
    val allResourcesCleaned = new AtomicBoolean(false)
    val signalHandlerInstalled = new AtomicBoolean(false)
    val shutdownHookRan = new AtomicBoolean(false)
    val exitCalled = new AtomicBoolean(false)
    val completed = new AtomicBoolean(false)
    val errorOutput = new AtomicReference[String]("")

    // Override to capture exit behavior without actually exiting
    override protected[zio] def exitUnsafe(code: ExitCode)(implicit unsafe: Unsafe): Unit = {
      exitCalled.set(true)
      completed.set(true)
    }

    def runTest(): UIO[TestResultNative] = {
      for {
        exit <- invoke(Chunk.empty).exit
        exitCode = exit match {
          case Exit.Success(_) => ExitCode.success
          case Exit.Failure(_) => ExitCode.failure
        }
      } yield TestResultNative(
        exitCode = exitCode,
        completed = completed.get(),
        finalizersRan = finalizerRan.get(),
        allResourcesCleaned = allResourcesCleaned.get(),
        signalHandlerInstalled = signalHandlerInstalled.get(),
        shutdownHookRan = shutdownHookRan.get(),
        exitCalled = exitCalled.get(),
        errorOutput = errorOutput.get()
      )
    }
  }

  object TestZIOAppNative {
    def successful: TestableZIOAppNative = new TestableZIOAppNative {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.succeed("Success!")
    }

    def failing: TestableZIOAppNative = new TestableZIOAppNative {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.fail("Failed!")
    }

    def withSignalHandler: TestableZIOAppNative = new TestableZIOAppNative {
      override protected def installSignalHandlers(runtime: Runtime[Any])(implicit trace: Trace): UIO[Any] = {
        // On Native, this should be a no-op, but we can track if it was called
        ZIO.succeed {
          signalHandlerInstalled.set(true)
        }
      }

      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.succeed("Done")
    }

    def withFinalizer: TestableZIOAppNative = new TestableZIOAppNative {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = 
        ZIO.acquireReleaseWith(
          ZIO.succeed("resource")
        )(_ => 
          finalizerRan.set(true) *> ZIO.succeed(())
        )(_ => ZIO.succeed("Done"))
    }

    def withFinalizerFailure: TestableZIOAppNative = new TestableZIOAppNative {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = 
        ZIO.acquireReleaseWith(
          ZIO.succeed("resource")
        )(_ => 
          finalizerRan.set(true) *> ZIO.succeed(())
        )(_ => ZIO.fail("App failed"))
    }

    def withShutdownHook: TestableZIOAppNative = new TestableZIOAppNative {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
        // Try to add shutdown hook (should be no-op on Native)
        ZIO.succeed {
          try {
            java.lang.Runtime.getRuntime.addShutdownHook(new Thread {
              override def run(): Unit = shutdownHookRan.set(true)
            })
          } catch {
            case _: Throwable => // Expected on Native
          }
        } *> ZIO.succeed("Done")
      }
    }

    def explicitExit: TestableZIOAppNative = new TestableZIOAppNative {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = 
        exit(ExitCode.success) *> ZIO.succeed("Should not reach here")
    }

    def multipleResources: TestableZIOAppNative = new TestableZIOAppNative {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = 
        for {
          _ <- ZIO.acquireReleaseWith(ZIO.succeed("r1"))(_ => finalizerRan.set(true))(_ => ZIO.unit)
          _ <- ZIO.acquireReleaseWith(ZIO.succeed("r2"))(_ => allResourcesCleaned.set(true))(_ => ZIO.unit)
        } yield "All resources managed"
    }
  }
}
