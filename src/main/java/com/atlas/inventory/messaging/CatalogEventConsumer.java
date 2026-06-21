package com.atlas.inventory.messaging;

import com.atlas.inventory.service.CatalogSeedingService;
import com.atlas.inventory.service.RoomTypeSeed;
import com.atlas.inventory.shared.messaging.EventTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka consumer that seeds Inventory from the Flight/Hotel catalogs
 * (features/seed-inventory-from-catalog). Delegates each catalog event to {@link CatalogSeedingService};
 * holds no business logic. Reactions are idempotent on {@code eventId}.
 * <p>
 * Retry strategy (retry-strategy.md): 4 attempts (5s → 30s → 120s → DLQ). Malformed payloads
 * ({@link IllegalArgumentException}) are non-retryable and go straight to the DLQ (dlq-strategy.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogEventConsumer {

    private static final long RETRY_DELAY_MS = 5_000L;
    private static final double RETRY_MULTIPLIER = 6.0;
    private static final long RETRY_MAX_DELAY_MS = 120_000L;

    private final CatalogSeedingService catalogSeedingService;

    // ── Flight ────────────────────────────────────────────────────────────────

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.FLIGHT_CREATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onFlightCreated(Map<String, Object> envelope) {
        upsertFlight(envelope, "FlightCreated");
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.FLIGHT_UPDATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onFlightUpdated(Map<String, Object> envelope) {
        upsertFlight(envelope, "FlightUpdated");
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.FLIGHT_DELETED, groupId = "${spring.kafka.consumer.group-id}")
    public void onFlightDeleted(Map<String, Object> envelope) {
        UUID eventId = extractEventId(envelope);
        UUID flightId = extractUuid(extractPayload(envelope), "flightId");
        log.debug("Received FlightDeleted: eventId={}, flightId={}", eventId, flightId);
        catalogSeedingService.disableFlight(eventId, flightId);
    }

    // ── Hotel ─────────────────────────────────────────────────────────────────

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.HOTEL_CREATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onHotelCreated(Map<String, Object> envelope) {
        upsertHotel(envelope, "HotelCreated");
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.HOTEL_UPDATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onHotelUpdated(Map<String, Object> envelope) {
        upsertHotel(envelope, "HotelUpdated");
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.HOTEL_DELETED, groupId = "${spring.kafka.consumer.group-id}")
    public void onHotelDeleted(Map<String, Object> envelope) {
        UUID eventId = extractEventId(envelope);
        UUID hotelId = extractUuid(extractPayload(envelope), "hotelId");
        log.debug("Received HotelDeleted: eventId={}, hotelId={}", eventId, hotelId);
        catalogSeedingService.disableHotel(eventId, hotelId);
    }

    // ── Dispatch helpers ────────────────────────────────────────────────────────

    private void upsertFlight(Map<String, Object> envelope, String eventType) {
        UUID eventId = extractEventId(envelope);
        Map<String, Object> payload = extractPayload(envelope);
        UUID flightId = extractUuid(payload, "flightId");
        int totalSeats = extractInt(payload, "totalSeats");
        log.debug("Received {}: eventId={}, flightId={}, totalSeats={}", eventType, eventId, flightId, totalSeats);
        catalogSeedingService.upsertFlight(eventId, eventType, flightId, totalSeats);
    }

    private void upsertHotel(Map<String, Object> envelope, String eventType) {
        UUID eventId = extractEventId(envelope);
        Map<String, Object> payload = extractPayload(envelope);
        UUID hotelId = extractUuid(payload, "hotelId");
        List<RoomTypeSeed> roomTypes = extractRoomTypes(payload);
        log.debug("Received {}: eventId={}, hotelId={}, roomTypes={}", eventType, eventId, hotelId, roomTypes.size());
        catalogSeedingService.upsertHotel(eventId, eventType, hotelId, roomTypes);
    }

    // ── Envelope/payload extraction ──────────────────────────────────────────────

    private UUID extractEventId(Map<String, Object> envelope) {
        Object raw = envelope.get("eventId");
        if (raw == null) {
            throw new IllegalArgumentException("Missing eventId in envelope");
        }
        return UUID.fromString(raw.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPayload(Map<String, Object> envelope) {
        Object raw = envelope.get("payload");
        if (raw == null) {
            throw new IllegalArgumentException("Missing payload in envelope");
        }
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private List<RoomTypeSeed> extractRoomTypes(Map<String, Object> payload) {
        Object raw = payload.get("roomTypes");
        if (!(raw instanceof List<?> rawRoomTypes) || rawRoomTypes.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty 'roomTypes' in hotel payload");
        }
        return rawRoomTypes.stream()
                .map(o -> (Map<String, Object>) o)
                .map(rt -> new RoomTypeSeed(extractUuid(rt, "roomTypeId"), extractInt(rt, "totalRooms")))
                .toList();
    }

    private UUID extractUuid(Map<String, Object> map, String field) {
        Object raw = map.get(field);
        if (raw == null) {
            throw new IllegalArgumentException("Missing field '" + field + "'");
        }
        return UUID.fromString(raw.toString());
    }

    private int extractInt(Map<String, Object> map, String field) {
        Object raw = map.get(field);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("Missing or non-numeric field '" + field + "'");
        }
        return number.intValue();
    }
}
