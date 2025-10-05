#!/bin/bash

# Quick end-to-end test for hot key detection

echo "======================================"
echo "Quick Hot Key Detection Test"
echo "======================================"
echo ""

# Clean old state
echo "Cleaning old Kafka Streams state..."
rm -rf /tmp/kafka-streams* 2>/dev/null || true
rm -rf /var/folders/*/T/kafka-streams/adaptive-controller 2>/dev/null || true

# Start controller in background
echo "Starting AdaptiveController..."
cd /Users/kaustubhkhatri/Work/EB1A/code/kafka_checkpointing
mvn exec:java -Dexec.mainClass="com.example.adaptive.AdaptiveController" 2>&1 | tee controller-test.log &
CONTROLLER_PID=$!

echo "Controller PID: $CONTROLLER_PID"
echo "Waiting 20 seconds for Kafka Streams to initialize..."
sleep 20

# Check if controller is still running
if ! ps -p $CONTROLLER_PID > /dev/null; then
    echo "ERROR: Controller died! Check controller-test.log"
    cat controller-test.log
    exit 1
fi

echo ""
echo "Generating hot key workload (60 seconds)..."
echo "This sends ~30 messages/second to key 'user-123'"
echo ""

mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="hot 60" 2>&1 | grep -v "^\[INFO\]"

echo ""
echo "Waiting 50 seconds for window processing and controller check..."
sleep 50

echo ""
echo "======================================"
echo "Results"
echo "======================================"
echo ""

# Show alerts
echo "=== Hot Key Alerts ==="
grep "\[ALERT\]" controller-test.log || echo "No alerts found"
echo ""

# Show controller decisions
echo "=== Controller Decisions ==="
grep "\[CONTROLLER\]" controller-test.log | tail -5 || echo "No controller decisions"
echo ""

# Check compaction
echo "=== Compaction Status ==="
if grep -q "triggering compaction" controller-test.log; then
    echo "✓ SUCCESS: Compaction was triggered!"
else
    echo "✗ FAILURE: Compaction was NOT triggered"
fi
echo ""

# Cleanup
echo "Stopping controller (PID: $CONTROLLER_PID)..."
kill $CONTROLLER_PID 2>/dev/null || true

echo ""
echo "Full log: controller-test.log"
