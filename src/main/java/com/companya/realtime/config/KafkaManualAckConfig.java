package com.companya.realtime.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
public class KafkaManualAckConfig {

    private static final Logger logger = LoggerFactory.getLogger(KafkaManualAckConfig.class);

    @Bean(name = "manualAckContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> manualAckContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> genericFactory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(genericFactory, consumerFactory);

        // Manual acknowledgment for fine-grained control
        genericFactory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Configure error handling with retry logic
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (consumerRecord, exception) -> {
                    logger.error("Error processing Kafka message after retries - Topic: {}, Partition: {}, Offset: {}, Key: {}, Error: {}",
                               consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(),
                               consumerRecord.key(), exception.getMessage(), exception);
                },
                new FixedBackOff(Duration.ofSeconds(10).toMillis(), 3) // 3 retries with 10-second intervals
        );

        // Add non-retryable exceptions (poison messages)
        errorHandler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonParseException.class,
                com.fasterxml.jackson.databind.JsonMappingException.class,
                IllegalArgumentException.class
        );

        genericFactory.setCommonErrorHandler(errorHandler);

        // Configure container properties for resilience
        ContainerProperties containerProps = genericFactory.getContainerProperties();
        containerProps.setPollTimeout(Duration.ofSeconds(3).toMillis());
        containerProps.setIdleEventInterval(Duration.ofSeconds(30).toMillis());

        // Enable transaction synchronization for better consistency
        containerProps.setSyncCommits(true);

        logger.info("Configured Kafka consumer factory with manual acknowledgment and error handling");

        @SuppressWarnings("unchecked")
        ConcurrentKafkaListenerContainerFactory<String, String> typedFactory =
                (ConcurrentKafkaListenerContainerFactory<String, String>) (ConcurrentKafkaListenerContainerFactory<?, ?>) genericFactory;
        return typedFactory;
    }
}
