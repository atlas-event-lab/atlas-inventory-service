package com.atlas.inventory.messaging;

import com.atlas.inventory.entity.OutboxEvent;
import com.atlas.inventory.repository.OutboxRepository;
import com.atlas.inventory.shared.messaging.EventEnvelope;
import com.atlas.inventory.shared.messaging.EventType;
import com.atlas.inventory.shared.web.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes domain events to the Transactional Outbox (EVT-009).
 * <p>
 * Called from inside a {@code @Transactional} Service method so the outbox row is committed
 * atomically with the reservation state change — no Kafka call happens here, avoiding the
 * dual-write (coding-standards §Outbox & Event Publishing). The {@code OutboxRelay} publishes the
 * row afterwards.
 * <p>
 * Inventory produces two event families with different partition keys, so the caller passes the
 * family-correct {@code aggregateId}: {@code bookingId} for booking-facing events, {@code
 * reservationId} for resource-facing events (partitioning.md).
 */
@Component
@RequiredArgsConstructor
public class OutboxEventWriter {

    private static final String PRODUCER = "inventory-service";
    private static final int EVENT_VERSION = 1;

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Builds the full event envelope (message-envelope.md) and stores it as a PENDING outbox row.
     *
     * @param aggregateType descriptive aggregate name ({@code Booking} for booking-facing events,
     *                      {@code Reservation} for resource-facing events)
     * @param aggregateId   the Kafka partition key (bookingId or reservationId, partitioning.md)
     * @param eventType     produced event type, e.g. {@code INVENTORY_RESERVED} / {@code FLIGHT_SEATS_RESERVED}
     * @param correlationId correlation id propagated through the saga (OBS-002)
     * @param sagaId        saga instance id (OBS-003)
     * @param payload       the business payload (never null, never carries metadata)
     */
    public void write(String aggregateType, UUID aggregateId, EventType eventType,
                      String correlationId, String sagaId, Object payload) {
        var envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                eventType.name(),
                EVENT_VERSION,
                Instant.now(),
                resolveTraceId(),
                correlationId,
                sagaId,
                PRODUCER,
                payload);

        outboxRepository.save(
            new OutboxEvent(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                EVENT_VERSION,
                serialize(envelope))
        );
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize event envelope for outbox: eventType=" + envelope.eventType(), e);
        }
    }

    /** Reads traceId from MDC (set by {@link CorrelationIdFilter}), falls back to a new UUID. */
    private String resolveTraceId() {
        String traceId = MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY);
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
    }
}
