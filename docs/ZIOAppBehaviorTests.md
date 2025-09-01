# ZIOApp Behavior Test Suite

This document describes the comprehensive test suite created for ZIOApp behavior validation, addressing GitHub issue #9909.

## Overview

The test suite validates ZIOApp shutdown behavior across different scenarios, ensuring that applications behave correctly during normal completion, failure, and external signal interruption.

## Test Coverage

### 1. App Completion Tests
- **Successful completion**: Verifies that successful apps return `ExitCode.success`
- **Failed completion**: Verifies that failed apps return `ExitCode.failure`  
- **Exception completion**: Verifies that apps throwing exceptions return `ExitCode.failure`

### 2. Finalizer Execution Tests
- **Normal completion**: Verifies finalizers run during successful completion
- **Failure completion**: Verifies finalizers run during failed completion
- **Multiple finalizers**: Verifies multiple finalizers are executed correctly

### 3. External Signal Interruption Tests
- **SIGINT interruption**: Tests behavior when apps are interrupted by external signals
- **Finalizer execution during interruption**: Verifies finalizers run during signal interruption

### 4. Issue-Specific Tests

#### Issue #9901 - Finalizer execution on Ctrl-C
- Tests that service finalizers are properly executed when applications are terminated via Ctrl-C
- Validates that cleanup code runs even during external termination

#### Issue #9240 - Signal handler availability  
- Tests graceful degradation when signal handlers are unavailable
- Ensures apps work correctly even when `sun.misc.Signal` is not available

## Test Infrastructure

### TestableZIOApp
A base class that extends `ZIOAppDefault` with testing capabilities:
- Tracks finalizer execution via `AtomicBoolean` flags
- Provides `runTest()` method for normal execution testing
- Provides `runTestWithInterruption()` method for interruption testing
- Captures test results without actually terminating the test process

### TestResult
Captures comprehensive information about test execution:
- `exitCode`: The exit code returned by the application
- `finalizersRan`: Whether finalizers were executed
- `allFinalizersRan`: Whether all finalizers in multi-finalizer scenarios ran
- `errorOutput`: Any error output captured during execution

## Platform Support

### JVM/Native Tests (`ZIOAppBehaviorSpec`)
- Full test suite covering all scenarios
- Platform-specific signal handling behavior
- Shutdown hook integration testing

### JavaScript Tests (`ZIOAppBehaviorJSSpec`)  
- Adapted for JavaScript platform limitations
- No shutdown hooks (no-op behavior)
- Hash-based signal simulation
- DOM availability testing

### Scala Native Tests (`ZIOAppBehaviorNativeSpec`)
- Adapted for Native platform limitations  
- No shutdown hooks (no-op behavior)
- Signal handlers are no-ops
- Resource management validation

## Test Results

Current test status (as of implementation):
- **9 tests passing** ✅
- **0 tests failing** ✅

### All Tests Passing ✅
1. Successful completion returns ExitCode.success
2. Failed completion returns ExitCode.failure
3. Exception completion returns ExitCode.failure
4. Finalizers run on normal completion
5. Finalizers run on failure
6. Shutdown handles multiple finalizers
7. Graceful degradation when signal handlers unavailable
8. SIGINT interruption runs finalizers
9. Service finalizers are properly executed

### Fix Applied
The initially failing tests were fixed by implementing proper synchronization between resource acquisition and interruption:

- **Resource Acquisition Signaling**: Added `resourceAcquired` flag to track when resources are successfully acquired
- **Synchronized Interruption**: Modified `runTestWithInterruption()` to wait for resource acquisition before interrupting
- **Proper Timing**: Ensured finalizers have a chance to run by allowing the fiber to complete resource setup

This ensures that finalizers are properly registered and executed even during external interruption scenarios.

## Running the Tests

```bash
# Run all ZIOApp behavior tests
sbt "coreTestsJVM/testOnly zio.ZIOAppBehaviorSpec"

# Run specific test suites
sbt "coreTestsJVM/testOnly zio.ZIOAppBehaviorSpec -- -t \"App Completion\""
sbt "coreTestsJVM/testOnly zio.ZIOAppBehaviorSpec -- -t \"Finalizer\""

# Run JavaScript-specific tests
sbt "coreTestsJS/testOnly zio.ZIOAppBehaviorJSSpec"

# Run Native-specific tests  
sbt "coreTestsNative/testOnly zio.ZIOAppBehaviorNativeSpec"
```

## Future Improvements

1. **Timeout Testing**: Add tests for `gracefulShutdownTimeout` behavior
2. **Catastrophic Failure**: Add tests for catastrophic failure scenarios
3. **Performance Testing**: Add tests measuring shutdown timing
4. **Integration Testing**: Add tests combining multiple edge cases
5. **CI Integration**: Ensure tests run properly across different CI environments

## Related Issues

- **#9909**: Main issue requesting comprehensive ZIOApp behavior tests
- **#9901**: ZIO 2.1.18 finalizer execution during Ctrl-C termination  
- **#9807**: Clean shutdown behavior regardless of shutdown hook timing
- **#9240**: Signal handler availability and graceful degradation

This test suite provides a solid foundation for validating ZIOApp behavior and can be extended as new issues or requirements are identified.
