package com.example.adaptive;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.TimeWindows;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AdaptiveController {
    private static final int HOT_KEY_THRESHOLD = 10; // Trigger compaction if key count > 10 in a window
    private static final long CHECK_INTERVAL_MS = 35000; // Check every 35 seconds (slightly more than window)

    private final KafkaStreams streams;
    private final ScheduledExecutorService scheduler;
    private volatile boolean shouldCompact = false;

    public AdaptiveController() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "adaptive-controller");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        // Reduce commit interval for faster window processing
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        // Use a unique state directory per instance to avoid conflicts
        props.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams-" + System.currentTimeMillis());

        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, String> stream = builder.stream("workload");

        // Count keys in 30-second windows
        stream.groupByKey()
              .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofSeconds(30)))
              .count(Materialized.as("key-counts"))
              .toStream()
              .foreach((windowedKey, count) -> {
                  System.out.printf("[MONITOR] Key=%s count=%d window=%s (threshold=%d)%n",
                          windowedKey.key(), count, windowedKey.window().start(), HOT_KEY_THRESHOLD);

                  // Check if we have hot keys
                  if (count > HOT_KEY_THRESHOLD) {
                      System.out.printf("[ALERT] *** Hot key detected: %s with %d accesses (exceeds threshold %d) ***%n",
                              windowedKey.key(), count, HOT_KEY_THRESHOLD);
                      System.out.printf("[ALERT] Setting shouldCompact flag to TRUE%n");
                      shouldCompact = true;
                  }
              });

        this.streams = new KafkaStreams(builder.build(), props);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        // Add state change listener for debugging
        streams.setStateListener((newState, oldState) -> {
            System.out.printf("[STREAMS] State changed from %s to %s%n", oldState, newState);
        });

        streams.start();
        System.out.println("[STREAMS] Kafka Streams started, waiting for RUNNING state...");

        // Periodically check if we should trigger compaction
        // Start checking after 10 seconds (not 35), to catch hot keys earlier
        scheduler.scheduleAtFixedRate(() -> {
            System.out.printf("[CONTROLLER] Checking shouldCompact flag: %s%n", shouldCompact);
            if (shouldCompact) {
                System.out.println("\n[CONTROLLER] Hot keys detected - triggering compaction...");
                triggerCompaction();
                shouldCompact = false; // Reset flag
            } else {
                System.out.println("[CONTROLLER] No hot keys detected - skipping compaction");
            }
        }, 10000, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            streams.close();
        }));
    }

    private void triggerCompaction() {
        try {
            System.out.println("[COMPACTION] Starting compaction process...");
            Compactor.main(new String[]{});
            System.out.println("[COMPACTION] Compaction completed successfully\n");
        } catch (Exception e) {
            System.err.println("[ERROR] Compaction failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.out.println("Starting Adaptive Kafka Compaction Controller");
        System.out.println("Hot key threshold: " + HOT_KEY_THRESHOLD + " accesses per 30s window");
        System.out.println("Check interval: " + CHECK_INTERVAL_MS + "ms\n");

        AdaptiveController controller = new AdaptiveController();
        controller.start();
    }
}
