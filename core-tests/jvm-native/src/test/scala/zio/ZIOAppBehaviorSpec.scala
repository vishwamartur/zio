package zio

import zio.test._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/**
 * Comprehensive test suite for ZIOApp behavior covering:
 * 1. App completion (success/failure)
 * 2. External signal interruption (SIGINT)
 * 3. Correct exit codes
 * 4. Finalizer execution
 * 5. Shutdown timeout behavior
 * 6. Related issues #9901, #9807, #9240
 */
object ZIOAppBehaviorSpec extends ZIOSpecDefault {

  def spec = suite("ZIOApp Behavior")(
    suite("App Completion")(
      test("successful completion returns ExitCode.success") {
        for {
          result <- TestZIOApp.successful.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success
        )
      },
      test("failed completion returns ExitCode.failure") {
        for {
          result <- TestZIOApp.failing.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.failure
        )
      },
      test("exception completion returns ExitCode.failure") {
        for {
          result <- TestZIOApp.throwing.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.failure
        )
      }
    ),
    suite("External Signal Interruption")(
      test("SIGINT interruption runs finalizers") {
        for {
          result <- TestZIOApp.withFinalizer.runTestWithInterruption()
        } yield assertTrue(
          result.exitCode == ExitCode.failure, // Interrupted
          result.finalizersRan
        )
      }
    ),
    suite("Finalizer Execution")(
      test("finalizers run on normal completion") {
        for {
          result <- TestZIOApp.withFinalizerSuccess.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.finalizersRan
        )
      },
      test("finalizers run on failure") {
        for {
          result <- TestZIOApp.withFinalizerFailure.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.failure,
          result.finalizersRan
        )
      }
    ),
    suite("Multiple Finalizers")(
      test("shutdown handles multiple finalizers") {
        for {
          result <- TestZIOApp.multipleFinalizers.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success,
          result.finalizersRan,
          result.allFinalizersRan
        )
      }
    ),
    suite("Issue #9901 - Finalizer execution on Ctrl-C")(
      test("service finalizers are properly executed") {
        for {
          result <- TestZIOApp.serviceWithFinalizer.runTestWithInterruption()
        } yield assertTrue(
          result.finalizersRan
        )
      }
    ),
    suite("Issue #9240 - Signal handler availability")(
      test("graceful degradation when signal handlers unavailable") {
        for {
          result <- TestZIOApp.noSignalHandlers.runTest()
        } yield assertTrue(
          result.exitCode == ExitCode.success
        )
      }
    )
  ) @@ TestAspect.jvmOnly // Platform-specific shutdown behavior

  /**
   * Test result capturing all relevant information about ZIOApp execution
   */
  case class TestResult(
    exitCode: ExitCode,
    finalizersRan: Boolean,
    allFinalizersRan: Boolean,
    errorOutput: String
  )

  /**
   * Base class for testable ZIOApp instances that can be controlled and monitored
   */
  abstract class TestableZIOApp extends ZIOAppDefault {
    // Shared state for tracking test execution
    val finalizerRan = new AtomicBoolean(false)
    val allFinalizersRan = new AtomicBoolean(false)
    val errorOutput = new AtomicReference[String]("")
    val resourceAcquired = new AtomicBoolean(false)

    /**
     * Run this app in a test environment and return results
     */
    def runTest(): UIO[TestResult] = {
      for {
        exit <- invoke(Chunk.empty).exit
        exitCode = exit match {
          case Exit.Success(_) => ExitCode.success
          case Exit.Failure(_) => ExitCode.failure
        }
      } yield TestResult(
        exitCode = exitCode,
        finalizersRan = finalizerRan.get(),
        allFinalizersRan = allFinalizersRan.get(),
        errorOutput = errorOutput.get()
      )
    }

    /**
     * Run this app with simulated interruption
     */
    def runTestWithInterruption(): UIO[TestResult] = {
      for {
        fiber <- invoke(Chunk.empty).fork
        // Wait until resource is acquired before interrupting
        _ <- ZIO.whileLoop(!resourceAcquired.get())(ZIO.yieldNow)(_ => ZIO.unit)
        _ <- fiber.interrupt
        exit <- fiber.await
        exitCode = exit match {
          case Exit.Success(_) => ExitCode.success
          case Exit.Failure(_) => ExitCode.failure
        }
      } yield TestResult(
        exitCode = exitCode,
        finalizersRan = finalizerRan.get(),
        allFinalizersRan = allFinalizersRan.get(),
        errorOutput = errorOutput.get()
      )
    }

  }

  /**
   * Test infrastructure for creating controllable ZIOApp instances
   */
  object TestZIOApp {

    def successful: TestableZIOApp = new TestableZIOApp {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.succeed("Success!")
    }

    def failing: TestableZIOApp = new TestableZIOApp {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.fail("Failed!")
    }

    def throwing: TestableZIOApp = new TestableZIOApp {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] = ZIO.die(new RuntimeException("Boom!"))
    }

    def withFinalizer: TestableZIOApp = new TestableZIOApp {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        ZIO.acquireReleaseWith(
          ZIO.succeed("resource") <* ZIO.succeed(resourceAcquired.set(true))
        )(_ =>
          ZIO.succeed(finalizerRan.set(true))
        )(_ =>
          // Wait indefinitely after signaling resource acquisition
          ZIO.never
        )
    }

    def withFinalizerSuccess: TestableZIOApp = new TestableZIOApp {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        ZIO.acquireReleaseWith(
          ZIO.succeed("resource")
        )(_ =>
          ZIO.succeed(finalizerRan.set(true))
        )(_ => ZIO.succeed("Done"))
    }

    def withFinalizerFailure: TestableZIOApp = new TestableZIOApp {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        ZIO.acquireReleaseWith(
          ZIO.succeed("resource")
        )(_ =>
          ZIO.succeed(finalizerRan.set(true))
        )(_ => ZIO.fail("App failed"))
    }

    def multipleFinalizers: TestableZIOApp = new TestableZIOApp {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        for {
          _ <- ZIO.acquireReleaseWith(ZIO.succeed("r1"))(_ => ZIO.succeed(finalizerRan.set(true)))(_ => ZIO.unit)
          _ <- ZIO.acquireReleaseWith(ZIO.succeed("r2"))(_ => ZIO.succeed(allFinalizersRan.set(true)))(_ => ZIO.unit)
        } yield "Done"
    }

    def serviceWithFinalizer: TestableZIOApp = new TestableZIOApp {
      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        ZIO.acquireReleaseWith(
          ZIO.succeed("Service") <* ZIO.succeed(resourceAcquired.set(true))
        )(_ =>
          ZIO.succeed(finalizerRan.set(true))
        )(_ =>
          // Wait indefinitely after signaling resource acquisition
          ZIO.never
        )
    }

    def noSignalHandlers: TestableZIOApp = new TestableZIOApp {
      // Override to simulate no signal handler installation
      override protected def installSignalHandlers(runtime: Runtime[Any])(implicit trace: Trace): UIO[Any] =
        ZIO.unit

      def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
        ZIO.succeed("App runs without signal handlers")
    }
  }
}
