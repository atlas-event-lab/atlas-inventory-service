package com.atlas.inventory.inventory.messaging;

import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.event.BookingCreatedPayload;
import com.atlas.inventory.event.BookingItemEvent;
import com.atlas.inventory.event.BookingLifecyclePayload;
import com.atlas.inventory.event.EventValidator;
import com.atlas.inventory.event.MoneyEvent;
import com.atlas.inventory.messaging.BookingEventConsumer;
import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.service.ReserveCommand;
import com.atlas.inventory.inventory.support.InventoryTestData;
import com.atlas.inventory.shared.messaging.ConsumerEventType;
import com.atlas.inventory.event.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.atlas.inventory.inventory.support.InventoryTestData.BOOKING_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.EVENT_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.FLIGHT_RESOURCE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookingEventConsumerTest {

    @Mock InventoryService inventoryService;
    @Mock EventValidator eventValidator;

    @InjectMocks
    BookingEventConsumer consumer;

    private static <T> EventEnvelope<T> envelope(String eventType, T payload) {
        return new EventEnvelope<>(
                EVENT_ID,
                eventType,
                1,
                Instant.now(),
                null,
                InventoryTestData.CORRELATION_ID,
                InventoryTestData.SAGA_ID,
                "booking-service",
                payload);
    }

    @Test
    void onBookingCreated_extracts_items_and_saga_metadata() {
        EventEnvelope<BookingCreatedPayload> env = envelope("BOOKING_CREATED",
                new BookingCreatedPayload(
                        BOOKING_ID,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of(new BookingItemEvent("FLIGHT", FLIGHT_RESOURCE_ID, 2, new BigDecimal("100.00"))),
                        1,
                        new MoneyEvent(new BigDecimal("100.00"), "USD")));

        consumer.onBookingCreated(env);

        ArgumentCaptor<ReserveCommand> command = ArgumentCaptor.forClass(ReserveCommand.class);
        verify(inventoryService).reserve(eq(EVENT_ID), command.capture());
        ReserveCommand cmd = command.getValue();
        assertThat(cmd.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(cmd.correlationId()).isEqualTo(InventoryTestData.CORRELATION_ID);
        assertThat(cmd.sagaId()).isEqualTo(InventoryTestData.SAGA_ID);
        assertThat(cmd.items()).hasSize(1);
        assertThat(cmd.items().getFirst().resourceType()).isEqualTo(ResourceType.FLIGHT);
        assertThat(cmd.items().getFirst().resourceId()).isEqualTo(FLIGHT_RESOURCE_ID);
        assertThat(cmd.items().getFirst().quantity()).isEqualTo(2);
    }

    @Test
    void onBookingCreated_missing_items_is_rejected() {
        EventEnvelope<BookingCreatedPayload> env = envelope("BOOKING_CREATED",
                new BookingCreatedPayload(
                        BOOKING_ID,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of(),
                        1,
                        new MoneyEvent(new BigDecimal("100.00"), "USD")));

        assertThatThrownBy(() -> consumer.onBookingCreated(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
    }

    @Test
    void onBookingCancelled_delegates_to_release() {
        EventEnvelope<BookingLifecyclePayload> env = envelope("BOOKING_CANCELLED",
                new BookingLifecyclePayload(BOOKING_ID, null, null));

        consumer.onBookingCancelled(env);

        verify(inventoryService).release(EVENT_ID, BOOKING_ID, ConsumerEventType.BOOKING_CANCELLED,
                InventoryTestData.CORRELATION_ID, InventoryTestData.SAGA_ID);
    }

    @Test
    void onBookingConfirmed_delegates_to_confirm() {
        EventEnvelope<BookingLifecyclePayload> env = envelope("BOOKING_CONFIRMED",
                new BookingLifecyclePayload(BOOKING_ID, null, null));

        consumer.onBookingConfirmed(env);

        verify(inventoryService).confirm(EVENT_ID, BOOKING_ID);
    }

    @Test
    void onBookingExpired_delegates_to_expire() {
        EventEnvelope<BookingLifecyclePayload> env = envelope("BOOKING_EXPIRED",
                new BookingLifecyclePayload(BOOKING_ID, null, null));

        consumer.onBookingExpired(env);

        verify(inventoryService).expire(EVENT_ID, BOOKING_ID,
                InventoryTestData.CORRELATION_ID, InventoryTestData.SAGA_ID);
    }
}
