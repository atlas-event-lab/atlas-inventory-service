package com.atlas.inventory.messaging;

import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.event.BookingCreatedPayload;
import com.atlas.inventory.event.BookingItemEvent;
import com.atlas.inventory.event.BookingLifecyclePayload;
import com.atlas.inventory.event.EventEnvelope;
import com.atlas.inventory.event.EventValidator;
import com.atlas.inventory.exception.InvalidReservationStateTransitionException;
import com.atlas.inventory.exception.InventoryNotFoundException;
import com.atlas.inventory.exception.ReservationNotFoundException;
import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.service.RequestedItem;
import com.atlas.inventory.service.ReserveCommand;
import com.atlas.inventory.shared.messaging.ConsumerEventType;
import com.atlas.inventory.shared.messaging.EventTopics;
import jakarta.validation.ConstraintViolationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

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
    private final EventValidator eventValidator;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {
                InventoryNotFoundException.class,
                ReservationNotFoundException.class,
                InvalidReservationStateTransitionException.class,
                IllegalArgumentException.class,
                ConstraintViolationException.class
            })
    @KafkaListener(
            topics = EventTopics.BOOKING_CREATED,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "sagaListenerFactory")
    public void onBookingCreated(EventEnvelope<BookingCreatedPayload> envelope) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        BookingCreatedPayload payload = envelope.payload();

        ReserveCommand command = new ReserveCommand(
                payload.bookingId(),
                envelope.correlationId(),
                envelope.sagaId(),
                extractItems(payload),
                payload.total().amount());

        log.debug(
                "Received BookingCreated: eventId={}, bookingId={}, items={}",
                eventId,
                command.bookingId(),
                command.items().size());
        inventoryService.reserve(eventId, command);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {
                InventoryNotFoundException.class,
                ReservationNotFoundException.class,
                InvalidReservationStateTransitionException.class,
                IllegalArgumentException.class,
                ConstraintViolationException.class
            })
    @KafkaListener(
            topics = EventTopics.BOOKING_CONFIRMED,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "sagaListenerFactory")
    public void onBookingConfirmed(EventEnvelope<BookingLifecyclePayload> envelope) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        UUID bookingId = envelope.payload().bookingId();

        log.debug("Received BookingConfirmed: eventId={}, bookingId={}", eventId, bookingId);
        inventoryService.confirm(eventId, bookingId);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {
                InventoryNotFoundException.class,
                ReservationNotFoundException.class,
                InvalidReservationStateTransitionException.class,
                IllegalArgumentException.class,
                ConstraintViolationException.class
            })
    @KafkaListener(topics = EventTopics.BOOKING_CANCELLED, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingCancelled(EventEnvelope<BookingLifecyclePayload> envelope) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        UUID bookingId = envelope.payload().bookingId();

        log.debug("Received BookingCancelled: eventId={}, bookingId={}", eventId, bookingId);
        inventoryService.release(
                eventId, bookingId, ConsumerEventType.BOOKING_CANCELLED, envelope.correlationId(), envelope.sagaId());
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {
                InventoryNotFoundException.class,
                ReservationNotFoundException.class,
                InvalidReservationStateTransitionException.class,
                IllegalArgumentException.class,
                ConstraintViolationException.class
            })
    @KafkaListener(topics = EventTopics.BOOKING_FAILED, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingFailed(EventEnvelope<BookingLifecyclePayload> envelope) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        UUID bookingId = envelope.payload().bookingId();

        log.debug("Received BookingFailed: eventId={}, bookingId={}", eventId, bookingId);
        inventoryService.release(
                eventId, bookingId, ConsumerEventType.BOOKING_FAILED, envelope.correlationId(), envelope.sagaId());
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS),
            dltTopicSuffix = ".dlq",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "false",
            exclude = {
                InventoryNotFoundException.class,
                ReservationNotFoundException.class,
                InvalidReservationStateTransitionException.class,
                IllegalArgumentException.class,
                ConstraintViolationException.class
            })
    @KafkaListener(topics = EventTopics.BOOKING_EXPIRED, groupId = "${spring.kafka.consumer.group-id}")
    public void onBookingExpired(EventEnvelope<BookingLifecyclePayload> envelope) {
        eventValidator.validate(envelope);
        UUID eventId = envelope.eventId();
        UUID bookingId = envelope.payload().bookingId();

        log.debug("Received BookingExpired: eventId={}, bookingId={}", eventId, bookingId);
        inventoryService.expire(eventId, bookingId, envelope.correlationId(), envelope.sagaId());
    }

    private List<RequestedItem> extractItems(BookingCreatedPayload payload) {
        List<BookingItemEvent> items = payload.items();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty 'items' in BookingCreated payload");
        }
        return items.stream().map(this::toRequestedItem).toList();
    }

    private RequestedItem toRequestedItem(BookingItemEvent item) {
        String type = item.type();
        UUID resourceId = item.resourceId();
        Integer quantity = item.quantity();
        BigDecimal amount = item.amount();

        ResourceType resourceType = ResourceType.valueOf(type);
        int qty = quantity == null ? 1 : quantity;
        if (resourceType == ResourceType.HOTEL) {
            return toHotelItem(item, resourceId, qty, amount);
        }
        return RequestedItem.flight(resourceId, qty, amount);
    }

    /** HOTEL items must carry a valid stay range {@code [checkIn, checkOut)} with nights >= 1
     * (Booking validates the full rules per ADR-0010; Inventory rejects malformed events). */
    private RequestedItem toHotelItem(BookingItemEvent item, UUID resourceId, int qty, BigDecimal amount) {
        LocalDate checkIn = item.checkIn();
        LocalDate checkOut = item.checkOut();
        if (checkIn == null || checkOut == null) {
            throw new IllegalArgumentException("Hotel booking item missing 'checkIn' or 'checkOut'");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("Hotel booking item requires checkOut > checkIn: " + "checkIn=" + checkIn
                    + ", checkOut=" + checkOut);
        }
        return RequestedItem.hotel(resourceId, qty, amount, checkIn, checkOut);
    }
}
