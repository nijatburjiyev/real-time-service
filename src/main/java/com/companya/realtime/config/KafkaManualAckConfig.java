package com.companya.realtime.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaManualAckConfig {

    @Bean(name = "manualAckContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> manualAckContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> genericFactory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(genericFactory, consumerFactory);
        genericFactory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        @SuppressWarnings("unchecked")
        ConcurrentKafkaListenerContainerFactory<String, String> typedFactory =
                (ConcurrentKafkaListenerContainerFactory<String, String>) (ConcurrentKafkaListenerContainerFactory<?, ?>) genericFactory;
        return typedFactory;
    }
}
