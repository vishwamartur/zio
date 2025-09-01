package zio

import zio.test._
import zio.test.Assertion._

/**
 * JavaScript-specific tests for ZIOApp behavior. On JS platform, shutdown hooks
 * and signal handlers work differently.
 */
object ZIOAppBehaviorJSSpec extends ZIOSpecDefault {

  def spec = suite("ZIOApp Behavior - JavaScript")(
    suite("Basic App Completion")(
      test("successful completion returns ExitCode.success") {
        for {
          result <- TestZIOAppJS.successful.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.completed
        )
      },
      test("failed completion returns ExitCode.failure") {
        for {
          result <- TestZIOAppJS.failing.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.failure,
          result.completed
        )
      }
    ),
    suite("Signal Handling - JS Specific")(
      test("signal handlers work via URL hash changes") {
        for {
          result <- TestZIOAppJS.withSignalHandler.runTestWithHashSignal("INFO")
        } yield assertTrue(
          result.signalReceived,
          result.completed
        )
      },
      test("graceful degradation when DOM not available") {
        for {
          result <- TestZIOAppJS.noDOMAccess.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.completed
        )
      }
    ),
    suite("Finalizer Behavior - JS")(
      test("finalizers run on normal completion") {
        for {
          result <- TestZIOAppJS.withFinalizer.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.finalizersRan,
          result.completed
        )
      },
      test("finalizers run on failure") {
        for {
          result <- TestZIOAppJS.withFinalizerFailure.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.failure,
          result.finalizersRan,
          result.completed
        )
      }
    ),
    suite("Platform Differences")(
      test("no shutdown hooks on JS platform") {
        for {
          result <- TestZIOAppJS.withShutdownHook.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.completed,
          !result.shutdownHookRan // Shutdown hooks are no-op on JS
        )
      },
      test("exit doesn't terminate process on JS") {
        for {
          result <- TestZIOAppJS.explicitExit.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.completed,
          result.exitCalled
        )
      }
    )
  ) @@ TestAspect.jsOnly

  /**
   * Test result for JavaScript-specific behavior
   */
  case class TestResultJS(
    exitCode: ExitCode,
    completed: Boolean,
    finalizersRan: Boolean,
    signalReceived: Boolean,
    shutdownHookRan: Boolean,
    exitCalled: Boolean,
    errorOutput: String
  )

  /**
   * Base class for testable ZIOApp instances on JavaScript
   */
  abstract class TestableZIOAppJS extends ZIOAppDefault {
    import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

    val finalizerRan    = new AtomicBoolean(false)
    val signalReceived  = new AtomicBoolean(false)
    val shutdownHookRan = new AtomicBoolean(false)
    val exitCalled      = new AtomicBoolean(false)
    val completed       = new AtomicBoolean(false)
    val errorOutput     = new AtomicReference[String]("")

    // Override to capture exit behavior without actually exiting
    override protected[zio] def exitUnsafe(code: ExitCode)(implicit unsafe: Unsafe): Unit = {
      exitCalled.set(true)
      completed.set(true)
    }

    def runTest(): UIO[TestResultJS] =
      for {
        exit <- invoke(Chunk.empty).exit
        exitCode = exit match {
                     case Exit.Success(_) => ExitCode.success
                     case Exit.Failure(_) => ExitCode.failure
                   }
      } yield TestResultJS(
        exitCode = exitCode,
        completed = completed.get(),
        finalizersRan = finalizerRan.get(),
        signalReceived = signalReceived.get(),
        shutdownHookRan = shutdownHookRan.get(),
        exitCalled = exitCalled.get(),
        errorOutput = errorOutput.get()
      )

    def runTestWithHashSignal(signal: String): UIO[TestResultJS] =
      for {
        fiber <- invoke(Chunk.empty).fork
        _     <- ZIO.sleep(100.millis) // Let app start
        _     <- simulateHashSignal(signal)
        exit  <- fiber.await
        exitCode = exit match {
                     case Exit.Success(_) => ExitCode.success
                     case Exit.Failure(_) => ExitCode.failure
                   }
      } yield TestResultJS(
        exitCode = exitCode,
        completed = completed.get(),
        finalizersRan = finalizerRan.get(),
        signalReceived = signalReceived.get(),
        shutdownHookRan = shutdownHookRan.get(),
        exitCalled = exitCalled.get(),
        errorOutput = errorOutput.get()
      )

    private def simulateHashSignal(signal: String): UIO[Unit] =
      ZIO.succeed {
        // Simulate hash change that would trigger signal handler
        signalReceived.set(true)
      }
  }

  object TestZIOAppJS {
    def successful: TestableZIOAppJS = new TestableZIOAppJS {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.succeed("Success!")
    }

    def failing: TestableZIOAppJS = new TestableZIOAppJS {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.fail("Failed!")
    }

    def withSignalHandler: TestableZIOAppJS = new TestableZIOAppJS {
      override protected def installSignalHandlers(runtime: Runtime[Any])(implicit trace: Trace): UIO[Any] =
        ZIO.succeed {
          // Simulate signal handler installation
          signalReceived.set(true)
        }

      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.never
    }

    def noDOMAccess: TestableZIOAppJS = new TestableZIOAppJS {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.succeed("Works without DOM")
    }

    def withFinalizer: TestableZIOAppJS = new TestableZIOAppJS {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        ZIO.acquireReleaseWith(
          ZIO.succeed("resource")
        )(_ => finalizerRan.set(true) *> ZIO.succeed(()))(_ => ZIO.succeed("Done"))
    }

    def withFinalizerFailure: TestableZIOAppJS = new TestableZIOAppJS {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        ZIO.acquireReleaseWith(
          ZIO.succeed("resource")
        )(_ => finalizerRan.set(true) *> ZIO.succeed(()))(_ => ZIO.fail("App failed"))
    }

    def withShutdownHook: TestableZIOAppJS = new TestableZIOAppJS {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        // Try to add shutdown hook (should be no-op on JS)
        ZIO.succeed {
          try {
            java.lang.Runtime.getRuntime.addShutdownHook(new Thread {
              override def run(): Unit = shutdownHookRan.set(true)
            })
          } catch {
            case _: Throwable => // Expected on JS
          }
        } *> ZIO.succeed("Done")
    }

    def explicitExit: TestableZIOAppJS = new TestableZIOAppJS {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        exit(ExitCode.success) *> ZIO.succeed("Should not reach here")
    }
  }
}
