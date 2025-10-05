# Testing Guide for Adaptive Kafka Compaction

This guide explains how to test the Adaptive Kafka Compaction system.

## Prerequisites

1. **Kafka Setup**: Ensure Kafka is running on `localhost:9092`
   ```bash
   # Start Zookeeper (Not needed for Kafka 3.7.0 and later)
   bin/zookeeper-server-start.sh config/zookeeper.properties

   # Start Kafka
   bin/kafka-server-start.sh config/server.properties
   ```

2. **Build the Project**:
   ```bash
   mvn clean package
   ```

## Testing Methods

### Method 1: Interactive Test Script (Recommended)

The easiest way to test is using the interactive script:

```bash
./test-scenarios.sh
```

This provides a menu with the following options:

1. **Hot Key Scenario** - Generates high-frequency access to a single key (should trigger compaction)
2. **Normal Workload** - Distributes access across multiple keys (should NOT trigger compaction)
3. **Mixed Workload** - Combination of hot and normal keys
4. **Compaction Correctness Test** - Verifies compaction keeps only latest values
5. **Monitor Only Mode** - Observe key patterns without auto-compaction
6. **Run All Scenarios** - Execute all tests sequentially
7. **Run JUnit Tests** - Execute integration tests
8. **Setup** - Build project and create Kafka topics
9. **Clean Topics** - Delete test topics

**Example Usage:**
```bash
# Run all scenarios automatically
./test-scenarios.sh --all

# Run JUnit tests
./test-scenarios.sh --junit

# Interactive mode
./test-scenarios.sh
```

### Method 2: Manual Testing with TestDataGenerator

Run specific test scenarios manually:

```bash
# Hot key scenario (40 seconds)
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="hot 40"

# Normal workload (40 seconds)
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="normal 40"

# Mixed workload (40 seconds)
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="mixed 40"

# Burst pattern
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="burst"

# Compaction test data
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="compact-test"
```

### Method 3: JUnit Integration Tests

Run the JUnit test suite:

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=AdaptiveControllerTest

# Run specific test method
mvn test -Dtest=AdaptiveControllerTest#testHotKeyDetection
```

**Available Tests:**
- `testHotKeyDetection` - Verifies hot key detection
- `testNormalWorkload` - Verifies normal workload handling
- `testCompactionCorrectness` - Verifies compaction produces correct results
- `testBurstPattern` - Tests burst traffic detection
- `testMultipleHotKeys` - Tests multiple hot keys simultaneously
- `testPerformance` - Measures throughput

## Test Scenarios Explained

### 1. Hot Key Scenario
**Purpose**: Verify that frequent access to a single key triggers compaction

**Expected Behavior**:
- Sends 15+ messages/second for the same key
- Hot key threshold (10 accesses/30s) should be exceeded
- Controller should detect hot key and trigger compaction
- Compaction should consolidate duplicate key updates

**Verification**:
```bash
# Check controller logs
grep "Hot key detected" controller.log
grep "triggering compaction" controller.log
```

### 2. Normal Workload
**Purpose**: Verify that normal traffic patterns don't trigger unnecessary compaction

**Expected Behavior**:
- Sends messages distributed across 5 keys
- Each key receives <10 accesses per 30s window
- No hot keys should be detected
- Compaction should be skipped

**Verification**:
```bash
# Check controller logs
grep "skipping compaction" controller.log
```

### 3. Mixed Workload
**Purpose**: Test system with realistic mix of hot and cold keys

**Expected Behavior**:
- 60% of traffic goes to 2 hot keys
- 40% distributed across 10 normal keys
- Hot keys should be detected
- Compaction should be triggered

### 4. Compaction Correctness
**Purpose**: Verify compaction logic preserves only latest values

**Expected Behavior**:
- Sends 20 versions of 3 different keys (60 total messages)
- Compaction should reduce to 3 messages
- Each key should have only its latest value (version-20)

**Verification**:
- Check compacted topic contains exactly 3 records
- Verify each record has the latest version

### 5. Burst Pattern
**Purpose**: Test detection of sudden traffic spikes

**Expected Behavior**:
- Quiet period with minimal traffic
- Sudden burst of 300 messages to one key
- Hot key should be detected during burst
- Compaction should be triggered

## Running the Complete System

### Start Adaptive Controller
```bash
mvn exec:java -Dexec.mainClass="com.example.adaptive.AdaptiveController"
```

### Start Monitor Only
```bash
mvn exec:java -Dexec.mainClass="com.example.adaptive.Monitor"
```

### Run Manual Compaction
```bash
mvn exec:java -Dexec.mainClass="com.example.adaptive.Compactor"
```

## Monitoring and Verification

### Check Kafka Topics
```bash
# List topics
kafka-topics.sh --list --bootstrap-server localhost:9092

# Check topic contents
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic workload --from-beginning

kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic workload-compacted-temp --from-beginning
```

### Monitor Consumer Groups
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group adaptive-controller --describe
```

### View Logs
```bash
# Controller logs
tail -f controller.log

# Search for specific events
grep "Hot key" controller.log
grep "COMPACTION" controller.log
grep "MONITOR" controller.log
```

## Expected Output Examples

### Hot Key Detection
```
[MONITOR] Key=user-123 count=15 window=1696800000000
[ALERT] Hot key detected: user-123 with 15 accesses
[CONTROLLER] Hot keys detected - triggering compaction...
[COMPACTION] Starting compaction process...
[COMPACTION] Compaction completed successfully
```

### Normal Workload
```
[MONITOR] Key=user-1 count=4 window=1696800000000
[MONITOR] Key=user-2 count=4 window=1696800000000
[CONTROLLER] No hot keys detected - skipping compaction
```

## Troubleshooting

### Kafka Connection Issues
```bash
# Verify Kafka is running
nc -zv localhost 9092

# Check Kafka logs
tail -f kafka/logs/server.log
```

### Test Failures
- Ensure Kafka is running before tests
- Check topic auto-creation is enabled
- Verify no port conflicts (9092)
- Clean topics between test runs

### Performance Issues
- Increase JVM heap size: `export MAVEN_OPTS="-Xmx2g"`
- Reduce test duration for faster iteration
- Use separate Kafka broker for testing

## Configuration Tuning

Modify these constants in the code for testing:

**AdaptiveController.java**:
- `HOT_KEY_THRESHOLD = 10` - Adjust sensitivity
- `CHECK_INTERVAL_MS = 35000` - Change monitoring frequency
- Window size (30 seconds) - Modify for different time scales

**TestDataGenerator.java**:
- Message send rates
- Test duration
- Number of keys
- Traffic distribution

## CI/CD Integration

For automated testing in CI/CD pipelines:

```bash
# Setup script
./test-scenarios.sh --all

# Or run JUnit tests only
mvn clean test
```

Add to `.github/workflows/test.yml` or similar CI configuration.
