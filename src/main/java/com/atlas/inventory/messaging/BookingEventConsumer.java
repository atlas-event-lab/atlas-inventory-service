package com.atlas.inventory.messaging;

import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.exception.InvalidReservationStateTransitionException;
import com.atlas.inventory.exception.InventoryNotFoundException;
import com.atlas.inventory.exception.ReservationNotFoundException;
import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.service.RequestedItem;
import com.atlas.inventory.service.ReserveCommand;
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
 * Kafka consumer for the Inventory-side choreography Saga (features/reserve-inventory).
 * <p>
 * Listens on Booking lifecycle topics and delegates each transition to {@link InventoryService}.
 * The service methods are {@code @Transactional} and idempotent: a re-delivered event with the same
 * {@code eventId} causes no second state transition (EVT-005, EVT-008).
 * <p>
 * Retry strategy (retry-strategy.md): 4 total attempts (1 initial + 3 retries) via Retry Topics,
 * delays 5s → 30s → 120s → DLQ. Business-logic failures ({@link InventoryNotFoundException},
 * {@link ReservationNotFoundException}, {@link InvalidReservationStateTransitionException},
 * {@link IllegalArgumentException}) go straight to the DLQ (dlq-strategy.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventConsumer {

    private static final long RETRY_DELAY_MS = 5_000L;
    private static final double RETRY_MULTIPLIER = 6.0;
    private static final long RETRY_MAX_DELAY_MS = 120_000L;

    private final InventoryService inventoryService;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {InventoryNotFoundException.class, ReservationNotFoundException.class,
                       InvalidReservationStateTransitionException.class, IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.BOOKING_CREATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingCreated(Map<String, Object> envelope) {
        UUID eventId = extractEventId(envelope);
        Map<String, Object> payload = extractPayload(envelope);

        ReserveCommand command = new ReserveCommand(
                extractUuid(payload, "bookingId"),
                stringOrNull(envelope.get("correlationId")),
                stringOrNull(envelope.get("sagaId")),
                extractItems(payload));

        log.debug("Received BookingCreated: eventId={}, bookingId={}, items={}",
                eventId, command.bookingId(), command.items().size());
        inventoryService.reserve(eventId, command);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {InventoryNotFoundException.class, ReservationNotFoundException.class,
                       InvalidReservationStateTransitionException.class, IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.BOOKING_CONFIRMED, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingConfirmed(Map<String, Object> envelope) {
        UUID eventId = extractEventId(envelope);
        UUID bookingId = extractUuid(extractPayload(envelope), "bookingId");

        log.debug("Received BookingConfirmed: eventId={}, bookingId={}", eventId, bookingId);
        inventoryService.confirm(eventId, bookingId);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {InventoryNotFoundException.class, ReservationNotFoundException.class,
                       InvalidReservationStateTransitionException.class, IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.BOOKING_CANCELLED, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingCancelled(Map<String, Object> envelope) {
        UUID eventId = extractEventId(envelope);
        UUID bookingId = extractUuid(extractPayload(envelope), "bookingId");

        log.debug("Received BookingCancelled: eventId={}, bookingId={}", eventId, bookingId);
        inventoryService.release(eventId, bookingId, "BookingCancelled",
                stringOrNull(envelope.get("correlationId")), stringOrNull(envelope.get("sagaId")));
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {InventoryNotFoundException.class, ReservationNotFoundException.class,
                       InvalidReservationStateTransitionException.class, IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.BOOKING_FAILED, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingFailed(Map<String, Object> envelope) {
        UUID eventId = extractEventId(envelope);
        UUID bookingId = extractUuid(extractPayload(envelope), "bookingId");

        log.debug("Received BookingFailed: eventId={}, bookingId={}", eventId, bookingId);
        inventoryService.release(eventId, bookingId, "BookingFailed",
                stringOrNull(envelope.get("correlationId")), stringOrNull(envelope.get("sagaId")));
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            exclude = {InventoryNotFoundException.class, ReservationNotFoundException.class,
                       InvalidReservationStateTransitionException.class, IllegalArgumentException.class}
    )
    @KafkaListener(topics = EventTopics.BOOKING_EXPIRED, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingExpired(Map<String, Object> envelope) {
        UUID eventId = extractEventId(envelope);
        UUID bookingId = extractUuid(extractPayload(envelope), "bookingId");

        log.debug("Received BookingExpired: eventId={}, bookingId={}", eventId, bookingId);
        inventoryService.expire(eventId, bookingId,
                stringOrNull(envelope.get("correlationId")), stringOrNull(envelope.get("sagaId")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
    private List<RequestedItem> extractItems(Map<String, Object> payload) {
        Object raw = payload.get("items");
        if (!(raw instanceof List<?> rawItems) || rawItems.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty 'items' in BookingCreated payload");
        }
        return rawItems.stream()
                .map(o -> (Map<String, Object>) o)
                .map(this::toRequestedItem)
                .toList();
    }

    private RequestedItem toRequestedItem(Map<String, Object> item) {
        Object type = item.get("type");
        Object resourceId = item.get("resourceId");
        Object quantity = item.get("quantity");
        if (type == null || resourceId == null) {
            throw new IllegalArgumentException("Booking item missing 'type' or 'resourceId'");
        }
        return new RequestedItem(
                ResourceType.valueOf(type.toString()),
                UUID.fromString(resourceId.toString()),
                quantity == null ? 1 : ((Number) quantity).intValue());
    }

    private UUID extractUuid(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        if (raw == null) {
            throw new IllegalArgumentException("Missing field '" + field + "' in payload");
        }
        return UUID.fromString(raw.toString());
    }

    private String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
