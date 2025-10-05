#!/bin/bash

# Quick debug script to test hot key detection

echo "========================================"
echo "Debugging Hot Key Detection"
echo "========================================"
echo ""

# Step 1: Clean up old state
echo "Step 1: Cleaning up old Kafka Streams state..."
rm -rf /tmp/kafka-streams* 2>/dev/null || true
rm -rf /var/folders/*/T/kafka-streams/adaptive-controller 2>/dev/null || true
echo "✓ State cleaned"
echo ""

# Step 2: Start controller
echo "Step 2: Starting AdaptiveController..."
mvn exec:java -Dexec.mainClass="com.example.adaptive.AdaptiveController" > controller-debug.log 2>&1 &
CONTROLLER_PID=$!
echo "Controller PID: $CONTROLLER_PID"
echo "Waiting 15 seconds for Kafka Streams to initialize..."
sleep 15
echo ""

# Step 3: Check if controller is running
if ps -p $CONTROLLER_PID > /dev/null; then
    echo "✓ Controller is running"
else
    echo "✗ Controller failed to start! Check controller-debug.log"
    cat controller-debug.log
    exit 1
fi
echo ""

# Step 4: Show what we're looking for
echo "Step 3: What we're testing..."
echo "  - Threshold: 10 messages per 30s window"
echo "  - TestDataGenerator sends ~30 messages/second"
echo "  - Expected: ~900 messages in 30s window (far exceeds threshold)"
echo ""

# Step 5: Generate hot key traffic
echo "Step 4: Generating hot key traffic (60 seconds)..."
echo "This will send approximately 1800 messages to key 'user-123'"
mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="hot 60"
echo ""

# Step 6: Wait for processing
echo "Step 5: Waiting 45 seconds for window to close and processing..."
for i in {45..1}; do
    echo -ne "  Waiting: $i seconds remaining...\r"
    sleep 1
done
echo ""
echo ""

# Step 7: Analyze logs
echo "========================================"
echo "Log Analysis"
echo "========================================"
echo ""

echo "--- Kafka Streams State ---"
grep "\[STREAMS\]" controller-debug.log || echo "No stream state changes logged"
echo ""

echo "--- Monitor Output (last 10 lines) ---"
grep "\[MONITOR\]" controller-debug.log | tail -10 || echo "No monitor output found"
echo ""

echo "--- Hot Key Alerts ---"
grep "\[ALERT\]" controller-debug.log || echo "⚠ No hot keys detected!"
echo ""

echo "--- Controller Checks ---"
grep "\[CONTROLLER\]" controller-debug.log || echo "No controller checks found"
echo ""

echo "--- Compaction Status ---"
if grep -q "triggering compaction" controller-debug.log; then
    echo "✓ Compaction was triggered!"
    grep "COMPACTION" controller-debug.log
else
    echo "✗ Compaction was NOT triggered"
fi
echo ""

# Step 8: Summary
echo "========================================"
echo "Summary"
echo "========================================"

MONITOR_COUNT=$(grep -c "\[MONITOR\]" controller-debug.log 2>/dev/null || echo "0")
ALERT_COUNT=$(grep -c "\[ALERT\]" controller-debug.log 2>/dev/null || echo "0")
CONTROLLER_CHECKS=$(grep -c "Checking shouldCompact" controller-debug.log 2>/dev/null || echo "0")

echo "Monitor messages: $MONITOR_COUNT"
echo "Hot key alerts: $ALERT_COUNT"
echo "Controller checks: $CONTROLLER_CHECKS"

if [ "$ALERT_COUNT" -gt 0 ]; then
    echo ""
    echo "✓ Hot keys were detected!"
    if grep -q "triggering compaction" controller-debug.log; then
        echo "✓ Compaction was triggered!"
        echo ""
        echo "SUCCESS: The system is working correctly!"
    else
        echo "✗ But compaction was NOT triggered"
        echo ""
        echo "ISSUE: Hot keys detected but compaction didn't trigger."
        echo "Possible causes:"
        echo "  1. Flag was set but check happened at wrong time"
        echo "  2. Controller checks aren't running"
        echo "  3. Timing issue between window closure and checks"
    fi
else
    echo ""
    echo "✗ No hot keys detected"
    echo ""
    echo "ISSUE: Hot keys should have been detected."
    echo "Possible causes:"
    echo "  1. Kafka Streams not processing messages"
    echo "  2. Window aggregation not working"
    echo "  3. Messages not reaching topic"
    echo "  4. Consumer group offset issues"
    echo ""
    echo "Check the full log: controller-debug.log"
fi

echo ""
echo "Full log saved to: controller-debug.log"
echo ""

# Step 9: Cleanup
echo "Stopping controller..."
kill $CONTROLLER_PID 2>/dev/null || true
sleep 2

echo ""
echo "Done! Review controller-debug.log for details."
