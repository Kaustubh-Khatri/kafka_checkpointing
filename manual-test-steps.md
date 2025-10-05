# Manual Testing Steps

Follow these steps to manually test and debug the system:

## Before You Start

**IMPORTANT**: If you get a state directory error, run the cleanup script first:
```bash
./cleanup.sh
```

This will kill any stuck processes and clean up locked state directories.

## Step 1: Start the Controller

In Terminal 1:
```bash
cd /Users/kaustubhkhatri/Work/EB1A/code/kafka_checkpointing
mvn exec:java -Dexec.mainClass="com.example.adaptive.AdaptiveController"
```

**Wait for this output:**
```
[STREAMS] State changed from REBALANCING to RUNNING
```

This means Kafka Streams is ready. This takes 10-20 seconds typically.

## Step 2: Generate Test Data

In Terminal 2 (wait until you see RUNNING in Terminal 1):
```bash
cd /Users/kaustubhkhatri/Work/EB1A/code/kafka_checkpointing
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="hot 60"
```

This will send ~1800 messages over 60 seconds.

## Step 3: Watch Terminal 1

You should see:

**Immediately (as messages arrive):**
```
[MONITOR] Key=user-123 count=1 window=... (threshold=10)
[MONITOR] Key=user-123 count=2 window=... (threshold=10)
...
```

**After ~30 seconds (when window closes):**
```
[MONITOR] Key=user-123 count=900 window=... (threshold=10)
[ALERT] *** Hot key detected: user-123 with 900 accesses (exceeds threshold 10) ***
[ALERT] Setting shouldCompact flag to TRUE
```

**After ~40 seconds (first controller check at 10s + window close time):**
```
[CONTROLLER] Checking shouldCompact flag: true
[CONTROLLER] Hot keys detected - triggering compaction...
[COMPACTION] Starting compaction process...
[COMPACTION] Compaction completed successfully
```

## Step 4: Verify

If you don't see the expected output, check:

### Problem 1: No [MONITOR] messages
**Diagnosis:** Messages not reaching Kafka or Streams not consuming

**Check:**
```bash
# Verify topic has messages
cd /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic workload --from-beginning --max-messages 10
```

**Fix:** Make sure TestDataGenerator is actually running and not erroring

### Problem 2: [MONITOR] messages but no [ALERT]
**Diagnosis:** Count not exceeding threshold OR window hasn't closed yet

**Check:**
- Wait a full 30 seconds after messages start
- Look at the count values in MONITOR messages
- If count is low (< 10), generator isn't sending enough messages

### Problem 3: [ALERT] but no compaction trigger
**Diagnosis:** Timing issue with controller checks

**Check controller-test.log:**
```bash
grep "Checking shouldCompact" controller-test.log
```

If you see `Checking shouldCompact flag: false` after alerts, the timing is off.

**Fix:** The recent code changes should fix this (10s initial delay instead of 35s)

### Problem 4: Controller crashes or errors

**Check the error:**
```bash
tail -50 controller-test.log
```

Common issues:
- Kafka not running
- Port 9092 not accessible
- RocksDB state directory locked (solution: `rm -rf /tmp/kafka-streams/adaptive-controller`)

## Quick Automated Test

Instead of manual steps, run:
```bash
./quick-test.sh
```

This does all the above automatically and shows you the results.

## Debugging Commands

```bash
# Check if Kafka is running
nc -zv localhost 9092

# List topics
cd /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0
bin/kafka-topics.sh --list --bootstrap-server localhost:9092

# Count messages in workload topic
bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic workload

# Reset consumer group (if stuck)
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group adaptive-controller --reset-offsets --to-earliest \
  --topic workload --execute

# Delete and recreate topic (fresh start)
bin/kafka-topics.sh --delete --topic workload --bootstrap-server localhost:9092
bin/kafka-topics.sh --delete --topic workload-compacted-temp --bootstrap-server localhost:9092
bin/kafka-topics.sh --create --topic workload --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1
```
