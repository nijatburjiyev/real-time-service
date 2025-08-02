package com.example.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point. Scheduling and Kafka listeners are enabled so the
 * service can poll CSV files on a schedule and react to Kafka messages in
 * real time.
 */
@SpringBootApplication
@EnableScheduling // allows methods annotated with @Scheduled to run
@EnableKafka      // activates @KafkaListener components
public class RealTimeServiceApplication {

    public static void main(String[] args) {
        // Bootstraps the Spring context and starts the application
        SpringApplication.run(RealTimeServiceApplication.class, args);
    }
}
