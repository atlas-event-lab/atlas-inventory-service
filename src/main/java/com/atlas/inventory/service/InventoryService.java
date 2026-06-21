package com.atlas.inventory.service;

import java.util.UUID;

/**
 * Inventory reservation lifecycle as a saga participant (features/reserve-inventory).
 * Every method is idempotent on the envelope {@code eventId} (EVT-005, EVT-008) and writes both
 * its state change and any produced events to the outbox in one local transaction (no dual-write,
 * EVT-009/EVT-010). Transitions follow services/inventory/state_machine.md.
 */
public interface InventoryService {

    /** {@code BookingCreated}: reserve all items atomically (all-or-nothing) or reject. */
    void reserve(UUID eventId, ReserveCommand command);

    /** {@code BookingConfirmed}: RESERVED → CONFIRMED for the booking's reservations; no event. */
    void confirm(UUID eventId, UUID bookingId);

    /**
     * {@code BookingCancelled} / {@code BookingFailed}: RESERVED|CONFIRMED → RELEASED, restore
     * availability, emit {@code InventoryReleased} + resource-facing released per item.
     *
     * @param triggerEventType the consumed event name, recorded for idempotency diagnostics.
     */
    void release(UUID eventId, UUID bookingId, String triggerEventType, String correlationId, String sagaId);

    /**
     * {@code BookingExpired}: RESERVED → EXPIRED, restore availability, emit {@code InventoryReleased}
     * + resource-facing released/expired per item. No-op if nothing was reserved.
     */
    void expire(UUID eventId, UUID bookingId, String correlationId, String sagaId);

    /** TTL sweep: expire a single due RESERVED reservation, restoring availability and emitting the
     * resource-facing {@code *Expired} event. Idempotent (no-op if not RESERVED). */
    void expireReservation(UUID reservationId);
}
