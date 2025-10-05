# Troubleshooting Guide

## Common Issues and Solutions

### 1. State Directory Lock Error

**Error:**
```
org.apache.kafka.streams.errors.StreamsException: Unable to initialize state,
this can happen if multiple instances of Kafka Streams are running in the same
state directory
```

**Cause:**
- Previous Kafka Streams instance didn't shut down cleanly
- State directory is locked by another process
- You Ctrl+C'd the controller without clean shutdown

**Solutions:**

**Option A: Use the cleanup script (Recommended)**
```bash
./cleanup.sh
```

**Option B: Manual cleanup**
```bash
# Kill stuck processes
ps aux | grep AdaptiveController | grep -v grep | awk '{print $2}' | xargs kill -9

# Remove state directories
rm -rf /tmp/kafka-streams*
rm -rf /var/folders/*/T/kafka-streams/adaptive-controller
```

**Option C: Code-level fix (already implemented)**
The code now uses unique state directories per instance:
```java
props.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams-" + System.currentTimeMillis());
```

---

### 2. Hot Keys Not Detected

**Symptoms:**
- Controller runs but shows "No hot keys detected"
- No `[ALERT]` messages in logs
- Compaction never triggers

**Diagnosis:**

Check if messages are actually being produced:
```bash
cd /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic workload --from-beginning --max-messages 10
```

**Possible Causes & Solutions:**

1. **TestDataGenerator not producing messages**
   - Check you're using correct command: `mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="hot 60"`
   - Look for "Producer initialized" and "Sent X messages" in output

2. **Messages not reaching threshold**
   - Default threshold is 10 accesses per 30s window
   - Hot key scenario sends ~30 msg/sec, which should trigger easily
   - Check `[MONITOR]` logs show count values

3. **Timing issue**
   - Windows take 30 seconds to close
   - Wait at least 40 seconds after data generation starts
   - Controller checks every 35 seconds (after initial 10s delay)

4. **Kafka Streams not consuming**
   - Wait for `[STREAMS] State changed to RUNNING` message
   - This can take 10-20 seconds after startup

---

### 3. Compaction Not Triggered

**Symptoms:**
- Hot keys ARE detected (`[ALERT]` messages appear)
- But `[CONTROLLER] triggering compaction...` never shows
- See `[CONTROLLER] No hot keys detected - skipping compaction`

**Diagnosis:**

Check the timing of alerts vs. controller checks:
```bash
grep -E "\[ALERT\]|\[CONTROLLER\]" controller-test.log | tail -20
```

**Solution:**

This is a timing issue. The controller checks the `shouldCompact` flag every 35 seconds, starting 10 seconds after startup.

**Timeline:**
- T=0s: Controller starts
- T=10s: First controller check (likely no data yet)
- T=20-40s: Data generation happens, hot keys detected
- T=45s: Second controller check (should trigger compaction)

Make sure your test runs long enough to reach the second check!

---

### 4. Kafka Not Running

**Error:**
```
Connection to node -1 could not be established
```

**Check if Kafka is running:**
```bash
nc -zv localhost 9092
# Should show: Connection to localhost port 9092 [tcp/*] succeeded!
```

**Start Kafka (KRaft mode):**
```bash
cd /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0

# First time only: format storage
KAFKA_CLUSTER_ID="$(bin/kafka-storage.sh random-uuid)"
bin/kafka-storage.sh format -t $KAFKA_CLUSTER_ID -c config/kraft/server.properties

# Start Kafka
bin/kafka-server-start.sh config/kraft/server.properties
```

---

### 5. Maven Build Issues

**Error:**
```
Unknown mode: hot
```

**Cause:**
Using wrong main class or pom.xml has default mainClass set

**Solution:**
Always specify `-Dexec.mainClass` explicitly:
```bash
# Correct
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="hot 60"

# Wrong (uses App.main which expects different args)
mvn exec:java -Dexec.args="hot 60"
```

---

### 6. JUnit Tests Failing

**Error:**
```
Connection refused or timeout in tests
```

**Solution:**
Make sure Kafka is running BEFORE running tests:
```bash
# Check Kafka
nc -zv localhost 9092

# Run tests
mvn test
```

Tests require a live Kafka broker on localhost:9092.

---

## Debugging Commands

### Check running processes
```bash
ps aux | grep -E "kafka|Adaptive" | grep -v grep
```

### Check Kafka topics
```bash
cd /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0
bin/kafka-topics.sh --list --bootstrap-server localhost:9092
```

### Check message count in topic
```bash
cd /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0
bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic workload
```

### Reset consumer group (if stuck)
```bash
cd /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group adaptive-controller --reset-offsets --to-earliest \
  --topic workload --execute
```

### View Kafka logs
```bash
tail -f /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0/logs/server.log
```

---

## Quick Recovery

If everything is broken and you want to start fresh:

```bash
# 1. Clean everything
./cleanup.sh

# 2. Delete and recreate topics
cd /Users/kaustubhkhatri/Work/EB1A/releases/kafka_2.13-4.1.0
bin/kafka-topics.sh --delete --topic workload --bootstrap-server localhost:9092
bin/kafka-topics.sh --delete --topic workload-compacted-temp --bootstrap-server localhost:9092

bin/kafka-topics.sh --create --topic workload --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1

# 3. Rebuild
mvn clean compile

# 4. Run test
./quick-test.sh
```
