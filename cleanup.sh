#!/bin/bash

# Cleanup script for Kafka Streams state and stuck processes

echo "======================================"
echo "Cleanup Script"
echo "======================================"
echo ""

# Kill any running AdaptiveController processes
echo "1. Checking for running AdaptiveController processes..."
PIDS=$(ps aux | grep "com.example.adaptive.AdaptiveController" | grep -v grep | awk '{print $2}')
if [ -n "$PIDS" ]; then
    echo "   Found processes: $PIDS"
    echo "   Killing processes..."
    echo "$PIDS" | xargs kill -9 2>/dev/null || true
    echo "   ✓ Processes killed"
else
    echo "   No running processes found"
fi
echo ""

# Clean up state directories
echo "2. Cleaning Kafka Streams state directories..."
rm -rf /tmp/kafka-streams* 2>/dev/null && echo "   ✓ Cleaned /tmp/kafka-streams*" || echo "   No /tmp state found"
rm -rf /var/folders/*/T/kafka-streams* 2>/dev/null && echo "   ✓ Cleaned temp state directories" || echo "   No temp state found"
echo ""

# Clean up log files
echo "3. Cleaning log files..."
rm -f controller.log controller-debug.log controller-test.log 2>/dev/null && echo "   ✓ Cleaned log files" || echo "   No log files found"
echo ""

echo "======================================"
echo "Cleanup Complete!"
echo "======================================"
echo ""
echo "You can now run the tests without state conflicts."
