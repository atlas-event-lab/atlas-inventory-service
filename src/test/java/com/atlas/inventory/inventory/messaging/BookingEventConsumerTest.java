package com.atlas.inventory.inventory.messaging;

import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.messaging.BookingEventConsumer;
import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.service.ReserveCommand;
import com.atlas.inventory.inventory.support.InventoryTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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

    @InjectMocks
    BookingEventConsumer consumer;

    @Test
    void onBookingCreated_extracts_items_and_saga_metadata() {
        Map<String, Object> envelope = Map.of(
                "eventId", EVENT_ID.toString(),
                "correlationId", InventoryTestData.CORRELATION_ID,
                "sagaId", InventoryTestData.SAGA_ID,
                "payload", Map.of(
                        "bookingId", BOOKING_ID.toString(),
                        "items", List.of(Map.of(
                                "type", "FLIGHT",
                                "resourceId", FLIGHT_RESOURCE_ID.toString(),
                                "quantity", 2))));

        consumer.onBookingCreated(envelope);

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
        Map<String, Object> envelope = Map.of(
                "eventId", EVENT_ID.toString(),
                "payload", Map.of("bookingId", BOOKING_ID.toString()));

        assertThatThrownBy(() -> consumer.onBookingCreated(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
    }

    @Test
    void onBookingCancelled_delegates_to_release() {
        Map<String, Object> envelope = Map.of(
                "eventId", EVENT_ID.toString(),
                "correlationId", InventoryTestData.CORRELATION_ID,
                "sagaId", InventoryTestData.SAGA_ID,
                "payload", Map.of("bookingId", BOOKING_ID.toString()));

        consumer.onBookingCancelled(envelope);

        verify(inventoryService).release(eq(EVENT_ID), eq(BOOKING_ID), eq("BookingCancelled"),
                eq(InventoryTestData.CORRELATION_ID), eq(InventoryTestData.SAGA_ID));
    }

    @Test
    void onBookingConfirmed_delegates_to_confirm() {
        Map<String, Object> envelope = Map.of(
                "eventId", EVENT_ID.toString(),
                "payload", Map.of("bookingId", BOOKING_ID.toString()));

        consumer.onBookingConfirmed(envelope);

        verify(inventoryService).confirm(EVENT_ID, BOOKING_ID);
    }

    @Test
    void onBookingExpired_delegates_to_expire() {
        Map<String, Object> envelope = Map.of(
                "eventId", EVENT_ID.toString(),
                "payload", Map.of("bookingId", BOOKING_ID.toString()));

        consumer.onBookingExpired(envelope);

        verify(inventoryService).expire(eq(EVENT_ID), eq(BOOKING_ID), eq(null), eq(null));
    }
}
