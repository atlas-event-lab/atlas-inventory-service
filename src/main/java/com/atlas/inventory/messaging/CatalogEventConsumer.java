package com.atlas.inventory.messaging;

import com.atlas.inventory.event.EventValidator;
import com.atlas.inventory.event.FlightCatalogPayload;
import com.atlas.inventory.event.FlightDeletedPayload;
import com.atlas.inventory.event.HotelCatalogPayload;
import com.atlas.inventory.event.HotelDeletedPayload;
import com.atlas.inventory.service.CatalogSeedingService;
import com.atlas.inventory.service.RoomTypeSeed;
import com.atlas.inventory.shared.messaging.ConsumerEventType;
import com.atlas.inventory.event.EventEnvelope;
import com.atlas.inventory.shared.messaging.EventTopics;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Kafka consumer that seeds Inventory from the Flight/Hotel catalogs
 * (features/seed-inventory-from-catalog). Delegates each catalog event to {@link CatalogSeedingService};
 * holds no business logic. Reactions are idempotent on {@code eventId}.
 * <p>
 * Retry strategy (retry-strategy.md): 4 attempts (5s → 30s → 120s → DLQ). Malformed payloads
 * ({@link IllegalArgumentException} / {@link ConstraintViolationException}) are non-retryable and go
 * straight to the DLQ (dlq-strategy.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogEventConsumer {

    private static final long RETRY_DELAY_MS = 5_000L;
    private static final double RETRY_MULTIPLIER = 6.0;
    private static final long RETRY_MAX_DELAY_MS = 120_000L;

    private final CatalogSeedingService catalogSeedingService;
    private final EventValidator eventValidator;

    // ── Flight ────────────────────────────────────────────────────────────────

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {IllegalArgumentException.class, ConstraintViolationException.class}
    )
    @KafkaListener(topics = EventTopics.FLIGHT_CREATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onFlightCreated(EventEnvelope<FlightCatalogPayload> envelope) {
        upsertFlight(envelope, ConsumerEventType.FLIGHT_CREATED);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {IllegalArgumentException.class, ConstraintViolationException.class}
    )
    @KafkaListener(topics = EventTopics.FLIGHT_UPDATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onFlightUpdated(EventEnvelope<FlightCatalogPayload> envelope) {
        upsertFlight(envelope, ConsumerEventType.FLIGHT_UPDATED);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {IllegalArgumentException.class, ConstraintViolationException.class}
    )
    @KafkaListener(topics = EventTopics.FLIGHT_DELETED, groupId = "${spring.kafka.consumer.group-id}")
    public void onFlightDeleted(EventEnvelope<FlightDeletedPayload> envelope) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        UUID flightId = envelope.payload().flightId();
        log.debug("Received FlightDeleted: eventId={}, flightId={}", eventId, flightId);
        catalogSeedingService.disableFlight(eventId, flightId);
    }

    // ── Hotel ─────────────────────────────────────────────────────────────────

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {IllegalArgumentException.class, ConstraintViolationException.class}
    )
    @KafkaListener(topics = EventTopics.HOTEL_CREATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onHotelCreated(EventEnvelope<HotelCatalogPayload> envelope) {
        upsertHotel(envelope, ConsumerEventType.HOTEL_CREATED);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {IllegalArgumentException.class, ConstraintViolationException.class}
    )
    @KafkaListener(topics = EventTopics.HOTEL_UPDATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onHotelUpdated(EventEnvelope<HotelCatalogPayload> envelope) {
        upsertHotel(envelope, ConsumerEventType.HOTEL_UPDATED);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {IllegalArgumentException.class, ConstraintViolationException.class}
    )
    @KafkaListener(topics = EventTopics.HOTEL_DELETED, groupId = "${spring.kafka.consumer.group-id}")
    public void onHotelDeleted(EventEnvelope<HotelDeletedPayload> envelope) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        UUID hotelId = envelope.payload().hotelId();
        log.debug("Received HotelDeleted: eventId={}, hotelId={}", eventId, hotelId);
        catalogSeedingService.disableHotel(eventId, hotelId);
    }

    // ── Dispatch helpers ────────────────────────────────────────────────────────

    private void upsertFlight(EventEnvelope<FlightCatalogPayload> envelope, ConsumerEventType eventType) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        FlightCatalogPayload payload = envelope.payload();
        UUID flightId = payload.flightId();
        int totalSeats = payload.totalSeats();
        log.debug("Received {}: eventId={}, flightId={}, totalSeats={}", eventType, eventId, flightId, totalSeats);
        catalogSeedingService.upsertFlight(eventId, eventType, flightId, totalSeats);
    }

    private void upsertHotel(EventEnvelope<HotelCatalogPayload> envelope, ConsumerEventType eventType) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        HotelCatalogPayload payload = envelope.payload();
        UUID hotelId = payload.hotelId();
        List<RoomTypeSeed> roomTypes = payload.roomTypes().stream()
                .map(rt -> new RoomTypeSeed(rt.roomTypeId(), rt.totalRooms()))
                .toList();
        log.debug("Received {}: eventId={}, hotelId={}, roomTypes={}", eventType, eventId, hotelId, roomTypes.size());
        catalogSeedingService.upsertHotel(eventId, eventType, hotelId, roomTypes);
    }
}
