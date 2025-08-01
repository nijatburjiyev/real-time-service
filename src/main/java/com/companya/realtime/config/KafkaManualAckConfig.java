package com.companya.realtime.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaManualAckConfig {

    @Bean(name = "manualAckContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> manualAckContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> genericFactory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(genericFactory, consumerFactory);
        genericFactory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
        errorHandler.setCommitRecovered(true);
        genericFactory.setCommonErrorHandler(errorHandler);
        @SuppressWarnings("unchecked")
        ConcurrentKafkaListenerContainerFactory<String, String> typedFactory =
                (ConcurrentKafkaListenerContainerFactory<String, String>) (ConcurrentKafkaListenerContainerFactory<?, ?>) genericFactory;
        return typedFactory;
    }
}
