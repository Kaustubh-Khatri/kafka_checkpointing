#!/bin/bash

# Adaptive Kafka Compaction Test Scenarios
# This script provides easy-to-run test scenarios for the system

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
KAFKA_TOPIC="workload"
COMPACTED_TOPIC="workload-compacted-temp"

print_header() {
    echo -e "\n${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}\n"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

# Check if Kafka is running
check_kafka() {
    print_header "Checking Kafka availability"
    if nc -z localhost 9092 2>/dev/null; then
        print_success "Kafka is running on localhost:9092"
        return 0
    else
        print_error "Kafka is not running on localhost:9092"
        echo "Please start Kafka before running tests"
        return 1
    fi
}

# Build the project
build_project() {
    print_header "Building project"
    mvn clean package -DskipTests
    print_success "Build complete"
}

# Create Kafka topics
setup_topics() {
    print_header "Setting up Kafka topics"

    # Check if kafka-topics command is available
    if command -v kafka-topics &> /dev/null; then
        KAFKA_CMD="kafka-topics"
    elif command -v kafka-topics.sh &> /dev/null; then
        KAFKA_CMD="kafka-topics.sh"
    else
        print_warning "kafka-topics command not found, assuming auto-create is enabled"
        return 0
    fi

    # Create topics
    echo "Creating topic: $KAFKA_TOPIC"
    $KAFKA_CMD --create --topic $KAFKA_TOPIC \
        --bootstrap-server localhost:9092 \
        --partitions 3 \
        --replication-factor 1 \
        --if-not-exists || true

    echo "Creating topic: $COMPACTED_TOPIC"
    $KAFKA_CMD --create --topic $COMPACTED_TOPIC \
        --bootstrap-server localhost:9092 \
        --partitions 3 \
        --replication-factor 1 \
        --if-not-exists || true

    print_success "Topics ready"
}

# Clean topics
clean_topics() {
    print_header "Cleaning Kafka topics"

    if command -v kafka-topics &> /dev/null; then
        KAFKA_CMD="kafka-topics"
    elif command -v kafka-topics.sh &> /dev/null; then
        KAFKA_CMD="kafka-topics.sh"
    else
        print_warning "Cannot clean topics - kafka-topics command not found"
        return 0
    fi

    echo "Deleting topics..."
    $KAFKA_CMD --delete --topic $KAFKA_TOPIC --bootstrap-server localhost:9092 || true
    $KAFKA_CMD --delete --topic $COMPACTED_TOPIC --bootstrap-server localhost:9092 || true

    sleep 2
    print_success "Topics cleaned"
}

# Scenario 1: Hot Key Test
scenario_hot_key() {
    print_header "SCENARIO 1: Hot Key Detection"
    echo "This test generates hot key traffic that should trigger compaction"
    echo "Duration: 40 seconds"
    echo ""

    echo "Step 1: Starting Adaptive Controller in background..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.AdaptiveController" > controller.log 2>&1 &
    CONTROLLER_PID=$!
    echo "Controller PID: $CONTROLLER_PID"
    sleep 5

    echo -e "\nStep 2: Generating hot key workload..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="hot 40"

    echo -e "\nStep 3: Waiting for controller to process (45 seconds)..."
    sleep 45

    echo -e "\nStep 4: Checking controller logs..."
    if grep -q "Hot key detected" controller.log; then
        print_success "Hot key was detected!"
        grep "Hot key detected" controller.log | head -5
    else
        print_error "No hot key detected in logs"
    fi

    if grep -q "triggering compaction" controller.log; then
        print_success "Compaction was triggered!"
    else
        print_warning "Compaction was not triggered"
    fi

    echo -e "\nStopping controller..."
    kill $CONTROLLER_PID 2>/dev/null || true

    print_success "Scenario 1 complete. See controller.log for details"
}

# Scenario 2: Normal Workload Test
scenario_normal_workload() {
    print_header "SCENARIO 2: Normal Workload (No Compaction)"
    echo "This test generates normal traffic that should NOT trigger compaction"
    echo "Duration: 40 seconds"
    echo ""

    echo "Step 1: Starting Adaptive Controller in background..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.AdaptiveController" > controller.log 2>&1 &
    CONTROLLER_PID=$!
    echo "Controller PID: $CONTROLLER_PID"
    sleep 5

    echo -e "\nStep 2: Generating normal workload..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="normal 40"

    echo -e "\nStep 3: Waiting for controller to process (45 seconds)..."
    sleep 45

    echo -e "\nStep 4: Checking controller logs..."
    if grep -q "Hot key detected" controller.log; then
        print_warning "Hot key was detected (unexpected)"
        grep "Hot key detected" controller.log | head -5
    else
        print_success "No hot keys detected (as expected)"
    fi

    if grep -q "skipping compaction" controller.log; then
        print_success "Compaction was correctly skipped!"
    fi

    echo -e "\nStopping controller..."
    kill $CONTROLLER_PID 2>/dev/null || true

    print_success "Scenario 2 complete. See controller.log for details"
}

# Scenario 3: Mixed Workload
scenario_mixed_workload() {
    print_header "SCENARIO 3: Mixed Workload"
    echo "This test generates mixed traffic with hot and normal keys"
    echo "Duration: 40 seconds"
    echo ""

    echo "Step 1: Starting Adaptive Controller in background..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.AdaptiveController" > controller.log 2>&1 &
    CONTROLLER_PID=$!
    echo "Controller PID: $CONTROLLER_PID"
    sleep 5

    echo -e "\nStep 2: Generating mixed workload..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="mixed 40"

    echo -e "\nStep 3: Waiting for controller to process (45 seconds)..."
    sleep 45

    echo -e "\nStep 4: Analyzing results..."
    HOT_KEYS=$(grep -c "Hot key detected" controller.log || echo "0")
    echo "Hot keys detected: $HOT_KEYS"

    if [ "$HOT_KEYS" -gt 0 ]; then
        print_success "Hot keys detected in mixed workload"
        grep "Hot key detected" controller.log | head -10
    fi

    echo -e "\nStopping controller..."
    kill $CONTROLLER_PID 2>/dev/null || true

    print_success "Scenario 3 complete. See controller.log for details"
}

# Scenario 4: Compaction Correctness
scenario_compaction_correctness() {
    print_header "SCENARIO 4: Compaction Correctness Test"
    echo "This test verifies that compaction keeps only the latest values"
    echo ""

    echo "Step 1: Generating test data (multiple versions of same keys)..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.TestDataGenerator" -Dexec.args="compact-test"

    sleep 5

    echo -e "\nStep 2: Running compaction..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.Compactor"

    print_success "Scenario 4 complete. Check output for compacted results"
}

# Scenario 5: Monitor Only
scenario_monitor_only() {
    print_header "SCENARIO 5: Monitor Mode (No Auto-Compaction)"
    echo "This runs the monitor to observe key access patterns without compaction"
    echo "Press Ctrl+C to stop"
    echo ""

    echo "Starting monitor..."
    mvn exec:java -Dexec.mainClass="com.example.adaptive.Monitor"
}

# Run all scenarios
run_all_scenarios() {
    print_header "Running All Test Scenarios"

    check_kafka || exit 1
    build_project
    setup_topics

    scenario_compaction_correctness
    sleep 3
    clean_topics
    setup_topics

    scenario_normal_workload
    sleep 3
    clean_topics
    setup_topics

    scenario_hot_key
    sleep 3
    clean_topics
    setup_topics

    scenario_mixed_workload

    print_header "All Scenarios Complete!"
    print_success "Review controller.log for detailed results"
}

# Run JUnit tests
run_junit_tests() {
    print_header "Running JUnit Integration Tests"

    check_kafka || exit 1

    echo "Note: These tests require Kafka to be running"
    echo ""

    mvn test

    print_success "JUnit tests complete"
}

# Show menu
show_menu() {
    echo ""
    echo "Adaptive Kafka Compaction - Test Scenarios"
    echo "==========================================="
    echo ""
    echo "1) Hot Key Scenario (should trigger compaction)"
    echo "2) Normal Workload (should NOT trigger compaction)"
    echo "3) Mixed Workload (hot + normal keys)"
    echo "4) Compaction Correctness Test"
    echo "5) Monitor Only Mode"
    echo "6) Run All Scenarios"
    echo "7) Run JUnit Tests"
    echo "8) Setup (build + create topics)"
    echo "9) Clean Topics"
    echo "0) Exit"
    echo ""
}

# Main script
main() {
    if [ "$1" == "--all" ]; then
        run_all_scenarios
        exit 0
    fi

    if [ "$1" == "--junit" ]; then
        run_junit_tests
        exit 0
    fi

    while true; do
        show_menu
        read -p "Select option: " choice

        case $choice in
            1) check_kafka && setup_topics && scenario_hot_key ;;
            2) check_kafka && setup_topics && scenario_normal_workload ;;
            3) check_kafka && setup_topics && scenario_mixed_workload ;;
            4) check_kafka && setup_topics && scenario_compaction_correctness ;;
            5) check_kafka && setup_topics && scenario_monitor_only ;;
            6) run_all_scenarios ;;
            7) run_junit_tests ;;
            8) check_kafka && build_project && setup_topics ;;
            9) clean_topics ;;
            0) echo "Goodbye!"; exit 0 ;;
            *) print_error "Invalid option" ;;
        esac

        echo ""
        read -p "Press Enter to continue..."
    done
}

main "$@"
