package com.bss.usage.events;

import com.bss.usage.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class EventConfig {

    @Bean
    @ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
    KafkaTemplate<String, Object> eventKafkaTemplate(KafkaProperties properties, ObjectMapper objectMapper) {
        // Boot's ObjectMapper, so envelopes serialize dates the same way the REST APIs do.
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(null),
                new StringSerializer(),
                new JsonSerializer<>(objectMapper)));
    }

    @Bean
    @ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
    DomainEventPublisher outboxDomainEventPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper, TenantScope tenantScope) {
        return new OutboxDomainEventPublisher(outbox, objectMapper, tenantScope);
    }

    @Bean
    @ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, Object> eventKafkaTemplate,
            @Value("${bss.events.topic}") String topic, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        return new OutboxRelay(outbox, eventKafkaTemplate, topic, objectMapper, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(name = "bss.events.enabled", havingValue = "false")
    DomainEventPublisher noopDomainEventPublisher() {
        return new NoopDomainEventPublisher();
    }
}
