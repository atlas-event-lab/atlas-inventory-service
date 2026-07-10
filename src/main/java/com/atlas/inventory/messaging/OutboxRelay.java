package com.atlas.inventory.messaging;

import com.atlas.inventory.entity.OutboxEvent;
import com.atlas.inventory.repository.OutboxRepository;
import com.atlas.inventory.shared.messaging.EventTopics;
import com.atlas.inventory.shared.messaging.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Phase-1 outbox relay (EVT-009): a scheduled poller that publishes outbox rows to Kafka.
 * <p>
 * Reads PENDING / FAILED rows oldest-first, publishes each envelope keyed by {@code aggregateId}
 * (the family-correct partition key), and marks it PUBLISHED. A failed publish is marked FAILED and
 * retried on a later poll. Delivery is at-least-once — consumers deduplicate on the envelope
 * {@code eventId} (coding-standards §Outbox & Event Publishing).
 * <p>
 * Idempotent and uses {@code fixedDelay}, so a slow run never overlaps the next.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    /** Max rows claimed per poll — mirrors the {@code LIMIT} in {@code claimBatchForPublishing}. */
    public static final int BATCH_LIMIT = 100;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Transactional
    public int publishPending() {
        // Claim a disjoint batch (FOR UPDATE SKIP LOCKED) so concurrent replicas never publish the
        // same row; the transaction holds the locks until each row is marked below (ADR-0013).
        // Scheduling is driven by OutboxRelayScheduler (adaptive interval + drain-in-loop).
        List<OutboxEvent> batch = outboxRepository.claimBatchForPublishing();
        if (batch.isEmpty()) {
            return 0;
        }
        log.debug("Outbox relay processing {} event(s)", batch.size());

        // Phase 1 — dispatch every send without blocking, so the producer pipelines and batches
        // them onto the wire (one poll = one batched flush, not N serial broker round-trips).
        List<Dispatch> dispatched = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            try {
                String topic = resolveTopic(event.getEventType());
                dispatched.add(new Dispatch(event,
                        kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())));
            } catch (Exception e) {
                // Nothing left the buffer for this row (e.g. topic resolution) — fail it now.
                event.markFailed();
                log.error("Failed to dispatch outbox event: id={}, eventType={}, attempts={}",
                        event.getId(), event.getEventType(), event.getAttempts(), e);
            }
        }
        try {
            kafkaTemplate.flush();
        } catch (Exception e) {
            // The per-send futures below still report each row's outcome; don't fail the whole tx.
            log.warn("Outbox relay flush failed; awaiting individual sends", e);
        }

        // Phase 2 — await each broker ack and mark the row by its own outcome. Delivery stays
        // at-least-once (consumers dedupe on eventId); a crash before save re-publishes next poll.
        Instant now = Instant.now();
        for (Dispatch dispatch : dispatched) {
            OutboxEvent event = dispatch.event();
            try {
                dispatch.future().get();
                event.markPublished(now);
                log.debug("Outbox event published: id={}, eventType={}, aggregateId={}",
                        event.getId(), event.getEventType(), event.getAggregateId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.markFailed();
                log.error("Interrupted publishing outbox event: id={}, eventType={}, attempts={}",
                        event.getId(), event.getEventType(), event.getAttempts(), e);
            } catch (Exception e) {
                event.markFailed();
                log.error("Failed to publish outbox event: id={}, eventType={}, attempts={}",
                        event.getId(), event.getEventType(), event.getAttempts(), e);
            }
        }

        // Single write-back for the whole batch instead of a save per row.
        outboxRepository.saveAll(batch);
        return batch.size();
    }

    /** One dispatched send awaiting its broker acknowledgement. */
    private record Dispatch(OutboxEvent event, CompletableFuture<SendResult<String, String>> future) {
    }

    /** Maps an event type to its owning Inventory topic (topics.md, inventory-events.yaml). */
    private String resolveTopic(EventType eventType) {
        return switch (eventType) {
            // Booking-facing (saga), keyed by bookingId
            case INVENTORY_RESERVED -> EventTopics.INVENTORY_BOOKING_RESERVED;
            case INVENTORY_REJECTED -> EventTopics.INVENTORY_BOOKING_REJECTED;
            case INVENTORY_RELEASED -> EventTopics.INVENTORY_BOOKING_RELEASED;
            // Resource-facing (availability), keyed by reservationId
            case FLIGHT_SEATS_RESERVED      -> EventTopics.INVENTORY_FLIGHT_RESERVED;
            case FLIGHT_SEATS_RELEASED      -> EventTopics.INVENTORY_FLIGHT_RELEASED;
            case FLIGHT_RESERVATION_EXPIRED -> EventTopics.INVENTORY_FLIGHT_EXPIRED;
            case HOTEL_ROOMS_RESERVED       -> EventTopics.INVENTORY_HOTEL_RESERVED;
            case HOTEL_ROOMS_RELEASED       -> EventTopics.INVENTORY_HOTEL_RELEASED;
            case HOTEL_RESERVATION_EXPIRED  -> EventTopics.INVENTORY_HOTEL_EXPIRED;
        };
    }
}
