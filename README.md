# Adaptive Kafka Compaction

An intelligent Kafka topic compaction system that monitors workload patterns and triggers compaction only when necessary based on hot key detection.

## Overview

This system demonstrates **adaptive, workload-driven compaction** for Kafka topics. Instead of running compaction continuously or on a fixed schedule, it intelligently monitors key access patterns and triggers compaction only when needed - specifically when "hot keys" (frequently updated keys) are detected.

## Quick Start

```bash
# 1. Start Kafka (KRaft mode - no ZooKeeper needed)
cd /path/to/kafka_2.13-4.1.0
KAFKA_CLUSTER_ID="$(bin/kafka-storage.sh random-uuid)"
bin/kafka-storage.sh format -t $KAFKA_CLUSTER_ID -c config/kraft/server.properties
bin/kafka-server-start.sh config/kraft/server.properties

# 2. In project directory, run cleanup (if needed)
./cleanup.sh

# 3. Run automated test
./quick-test.sh
```

## What This System Does

- **Monitors** Kafka topic access patterns in real-time using Kafka Streams
- **Detects** hot keys (frequently accessed keys) in 30-second tumbling windows
- **Triggers** compaction automatically when keys exceed threshold (>10 accesses per window)
- **Compacts** the topic by keeping only the latest value for each key using in-memory HashMap

## Components

### Core Classes

#### 1. AdaptiveController.java - Main Intelligent Controller
The heart of the system that combines monitoring and adaptive triggering.

**Key Features:**
- Uses Kafka Streams to consume from `workload` topic
- Counts key accesses in 30-second tumbling windows (no grace period)
- Maintains a `volatile boolean shouldCompact` flag
- Checks the flag every 35 seconds (first check at 10 seconds after startup)
- When hot keys detected (count > 10), triggers `Compactor.main()`

**Configuration:**
```java
HOT_KEY_THRESHOLD = 10       // Messages per key per 30s window
CHECK_INTERVAL_MS = 35000    // How often to check for compaction
COMMIT_INTERVAL_MS = 1000    // Kafka Streams commit interval
```

**State Management:**
- Uses unique state directory per instance: `/tmp/kafka-streams-{timestamp}`
- Prevents conflicts when multiple instances run

#### 2. Monitor.java - Passive Monitoring Mode
A simplified version that only watches patterns without triggering compaction.

**Purpose:**
- Observe key access statistics
- Debug and understand workload patterns
- No automatic actions taken

**Use Case:** Understanding your data before enabling adaptive compaction

#### 3. Compactor.java - Compaction Engine
Performs the actual compaction using a simple in-memory algorithm.

**Algorithm:**
1. Consumes entire `workload` topic from beginning (`earliest` offset)
2. Stores records in `HashMap<String, String>` - key overwrites preserve latest value
3. Writes only unique keys (with latest values) to `workload-compacted-temp` topic
4. Stops when `poll()` returns empty (simplified stop condition)

**Compaction Strategy:**
- **Key-based deduplication**: Only the last value for each key is kept
- **In-memory processing**: All unique keys must fit in RAM
- **Full scan**: Reads entire topic each time (not incremental)
- **Separate output**: Preserves original topic by writing to new topic

**Limitations:**
- Memory-bound (all unique keys in HashMap)
- No tombstone (null value) handling for deletes
- No partition-aware processing
- Simple stop condition (production should use end offsets)

#### 4. TestDataGenerator.java - Workload Simulator
Generates realistic test workloads with configurable patterns.

**Scenarios:**
- **hot**: High-frequency updates to single key (~30 msg/sec)
- **normal**: Distributed load across multiple keys
- **mixed**: Combination of hot and cold keys (60/40 split)
- **burst**: Quiet period followed by sudden spike
- **compact-test**: Multiple versions of same keys for testing

**Producer Configuration:**
- ACKS = "1" (wait for leader acknowledgment)
- RETRIES = 3
- LINGER_MS = 10 (small batching)

#### 5. App.java - Entry Point Router
Simple dispatcher that routes to different modes based on command-line arguments.

**Usage:**
```bash
java App monitor    # Start Monitor
java App compact    # Run Compactor once
java App adaptive   # Start AdaptiveController
```

### Utility Scripts

- **cleanup.sh** - Kills stuck processes, removes state directories, cleans logs
- **quick-test.sh** - Automated end-to-end test with result analysis
- **test-scenarios.sh** - Interactive menu for different test scenarios
- **debug-test.sh** - Detailed test with extensive logging and diagnostics

### Documentation Files

- **README-TESTING.md** - Comprehensive testing strategies and procedures
- **manual-test-steps.md** - Step-by-step manual testing instructions
- **TROUBLESHOOTING.md** - Common issues, diagnostics, and solutions

## How It Works

### System Flow

```
1. Producer sends messages to 'workload' topic
   ↓
2. AdaptiveController (Kafka Streams) consumes and counts
   - Groups by key
   - Tumbling window: 30 seconds
   - Counts messages per key per window
   ↓
3. Hot Key Detection
   - If count > 10: Set shouldCompact = true
   - Log alert with key and count
   ↓
4. Periodic Check (every 35 seconds)
   - If shouldCompact == true:
     → Trigger Compactor
     → Reset shouldCompact = false
   ↓
5. Compactor runs
   - Read all messages from 'workload' (from beginning)
   - Store in HashMap: latest.put(key, value)
   - Write HashMap contents to 'workload-compacted-temp'
   ↓
6. Result: Compacted topic contains only latest value per key
```

### Example Scenario

**Input (workload topic):**
```
user-123 → "login"      (timestamp: 1000)
user-456 → "click"      (timestamp: 1005)
user-123 → "purchase"   (timestamp: 1010)
user-123 → "logout"     (timestamp: 1015)
user-456 → "logout"     (timestamp: 1020)
```

**Monitoring Window (30 seconds):**
```
user-123: 3 messages  → Count = 3 (below threshold, no alert)
user-456: 2 messages  → Count = 2 (below threshold, no alert)
```

But if user-123 sends 15 messages in 30 seconds:
```
[ALERT] Hot key detected: user-123 with 15 accesses (exceeds threshold 10)
[CONTROLLER] Hot keys detected - triggering compaction...
```

**Output (workload-compacted-temp):**
```
user-123 → "logout"     (only latest value)
user-456 → "logout"     (only latest value)
```

## Usage

### Running the Complete System

**Terminal 1: Start AdaptiveController**
```bash
mvn exec:java -Dexec.mainClass="com.example.adaptive.AdaptiveController"
```

**Terminal 2: Generate Workload (wait 15-20 seconds for controller to initialize)**
```bash
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" \
  -Dexec.args="hot 60"
```

### Running Individual Components

**Monitor Only (no auto-compaction):**
```bash
mvn exec:java -Dexec.mainClass="com.example.adaptive.Monitor"
```

**Manual Compaction:**
```bash
mvn exec:java -Dexec.mainClass="com.example.adaptive.Compactor"
```

**Test Data Generation:**
```bash
# Hot key scenario (~30 msg/sec to one key for 60 seconds)
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" \
  -Dexec.args="hot 60"

# Normal workload (distributed across 5 keys)
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" \
  -Dexec.args="normal 60"

# Mixed workload (60% hot keys, 40% normal)
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" \
  -Dexec.args="mixed 60"

# Burst pattern (quiet then sudden spike)
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" \
  -Dexec.args="burst"

# Compaction test (multiple versions of same keys)
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" \
  -Dexec.args="compact-test"
```

## Configuration

### AdaptiveController Parameters

Located in `AdaptiveController.java`:

| Parameter | Value | Description |
|-----------|-------|-------------|
| `HOT_KEY_THRESHOLD` | 10 | Messages per key per window to trigger alert |
| `CHECK_INTERVAL_MS` | 35000 | How often to check shouldCompact flag (milliseconds) |
| `COMMIT_INTERVAL_MS` | 1000 | Kafka Streams commit interval |
| Window Size | 30 seconds | Tumbling window for aggregation (no grace period) |
| Initial Delay | 10000 ms | First check happens 10s after startup |

### Kafka Topics

| Topic | Purpose | Partitions |
|-------|---------|------------|
| `workload` | Source topic with all messages | 3 |
| `workload-compacted-temp` | Compacted output (latest values only) | 3 |

### Modifying Behavior

**To change hot key sensitivity:**
```java
// In AdaptiveController.java:13
private static final int HOT_KEY_THRESHOLD = 5;  // More sensitive
```

**To change check frequency:**
```java
// In AdaptiveController.java:14
private static final long CHECK_INTERVAL_MS = 15000;  // Check every 15s
```

**To change window size:**
```java
// In AdaptiveController.java:32
.windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofSeconds(60)))  // 60s windows
```

## Expected Output

### Successful Hot Key Detection and Compaction

**Controller Startup:**
```
Starting Adaptive Kafka Compaction Controller
Hot key threshold: 10 accesses per 30s window
Check interval: 35000ms

[STREAMS] State changed from CREATED to REBALANCING
[STREAMS] Kafka Streams started, waiting for RUNNING state...
[STREAMS] State changed from REBALANCING to RUNNING
```

**Hot Key Detection:**
```
[MONITOR] Key=user-123 count=210 window=1759606260000 (threshold=10)
[ALERT] *** Hot key detected: user-123 with 210 accesses (exceeds threshold 10) ***
[ALERT] Setting shouldCompact flag to TRUE
[MONITOR] Key=user-123 count=241 window=1759606260000 (threshold=10)
[ALERT] *** Hot key detected: user-123 with 241 accesses (exceeds threshold 10) ***
...
```

**Compaction Triggered:**
```
[CONTROLLER] Checking shouldCompact flag: true

[CONTROLLER] Hot keys detected - triggering compaction...
[COMPACTION] Starting compaction process...
Writing compacted records...
Compaction complete.
[COMPACTION] Compaction completed successfully
```

**Normal Operation (no hot keys):**
```
[CONTROLLER] Checking shouldCompact flag: false
[CONTROLLER] No hot keys detected - skipping compaction
```

## Architecture

```
┌──────────────────────┐
│ TestDataGenerator    │
│ (Producer)           │
└──────────┬───────────┘
           │ Produces messages
           │ (key-value pairs)
           ▼
   ┌───────────────────┐
   │  workload topic   │  ← Source topic (all messages)
   │  Partitions: 3    │
   └───────┬───────────┘
           │
           │ Consumed by
           ▼
   ┌───────────────────────────────┐
   │  AdaptiveController           │
   │  (Kafka Streams)              │
   │                               │
   │  • groupByKey()               │
   │  • windowedBy(30s)            │
   │  • count()                    │
   │  • foreach() → detect hot     │
   │                               │
   │  shouldCompact = true?        │
   └─────────┬─────────────────────┘
             │
             │ Every 35s: check flag
             │ If true → trigger
             ▼
   ┌─────────────────────┐
   │  Compactor          │
   │                     │
   │  1. Read all from   │
   │     workload topic  │
   │  2. HashMap.put()   │
   │     (latest wins)   │
   │  3. Write unique    │
   │     keys only       │
   └─────────┬───────────┘
             │
             │ Writes to
             ▼
   ┌──────────────────────────┐
   │ workload-compacted-temp  │  ← Output (deduplicated)
   │ Contains: Latest value   │
   │          per key only    │
   └──────────────────────────┘
```

## Design Decisions & Trade-offs

### Strengths
- **Adaptive**: Only compacts when workload justifies it
- **Simple**: Easy to understand HashMap-based compaction
- **Observable**: Detailed logging for debugging
- **Flexible**: Configurable thresholds and windows

### Limitations
- **Memory-bound**: Compactor uses in-memory HashMap (all unique keys must fit in RAM)
- **Full-scan**: Reads entire topic each time (not incremental)
- **No delete support**: Doesn't handle tombstones (null values)
- **Simplistic stop condition**: Uses empty poll instead of end offsets
- **Single-threaded compaction**: No partition-level parallelism

### Production Considerations

For production use, consider:
1. **Disk-backed compaction**: Use RocksDB or external storage
2. **Incremental compaction**: Track offsets and compact only new data
3. **Partition-aware processing**: Compact partitions independently
4. **Tombstone handling**: Support deletes with null values
5. **Atomic swap**: Replace source topic atomically instead of separate output
6. **Metrics**: Expose compaction stats via JMX or Prometheus
7. **Backpressure handling**: Rate-limit compaction if source is high-throughput

## System Requirements

- **Java**: 17 or higher
- **Apache Kafka**: 4.1.0 (KRaft mode, no ZooKeeper required)
- **Maven**: 3.x
- **Memory**: ~512MB for JVM + space for unique keys in HashMap

## Building

```bash
# Compile
mvn clean compile

# Run tests (requires Kafka running)
mvn test

# Package
mvn clean package
```

## Troubleshooting

For detailed troubleshooting, see `TROUBLESHOOTING.md`.

**Common Issues:**

1. **State directory locked**: Run `./cleanup.sh`
2. **No hot keys detected**: Make sure to generate test data
3. **Kafka not running**: Check `nc -zv localhost 9092`

## References

- **Kafka Streams Documentation**: https://kafka.apache.org/documentation/streams/
- **Log Compaction**: https://kafka.apache.org/documentation/#compaction
- **KRaft Mode**: https://kafka.apache.org/documentation/#kraft

## License

This is a demonstration project for adaptive Kafka compaction.
