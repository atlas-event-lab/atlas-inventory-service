package com.atlas.inventory.config;

import com.atlas.inventory.shared.messaging.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Kafka configuration.
 * KafkaTemplate and ProducerFactory are auto-configured from application.yml
 * (spring.kafka.producer.*). This class declares all inventory.* topics owned by
 * Inventory Service; KafkaAdmin creates them on startup if they do not exist (topics.md).
 */
@Configuration
public class KafkaConfig {

    @Bean
    public RecordMessageConverter jsonConverter() {
        return new StringJsonMessageConverter();
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    /** Producer Topics */
    // ── Booking-facing (saga) ──
    @Bean NewTopic inventoryBookingReservedTopic() { return topic(EventTopics.INVENTORY_BOOKING_RESERVED); }
    @Bean NewTopic inventoryBookingRejectedTopic() { return topic(EventTopics.INVENTORY_BOOKING_REJECTED); }
    @Bean NewTopic inventoryBookingReleasedTopic() { return topic(EventTopics.INVENTORY_BOOKING_RELEASED); }

    // ── Resource-facing (availability) ──
    @Bean NewTopic inventoryFlightReservedTopic() { return topic(EventTopics.INVENTORY_FLIGHT_RESERVED); }
    @Bean NewTopic inventoryFlightReleasedTopic() { return topic(EventTopics.INVENTORY_FLIGHT_RELEASED); }
    @Bean NewTopic inventoryFlightExpiredTopic()  { return topic(EventTopics.INVENTORY_FLIGHT_EXPIRED); }
    @Bean NewTopic inventoryHotelReservedTopic()  { return topic(EventTopics.INVENTORY_HOTEL_RESERVED); }
    @Bean NewTopic inventoryHotelReleasedTopic()  { return topic(EventTopics.INVENTORY_HOTEL_RELEASED); }
    @Bean NewTopic inventoryHotelExpiredTopic()   { return topic(EventTopics.INVENTORY_HOTEL_EXPIRED); }
}
