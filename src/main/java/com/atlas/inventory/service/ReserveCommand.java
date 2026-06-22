package com.atlas.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Command to reserve all items of a booking (all-or-nothing), built from a consumed
 * {@code BookingCreated} envelope. {@code correlationId}/{@code sagaId} are propagated onto the
 * produced inventory events (OBS-002, OBS-003).
 */
public record ReserveCommand(
        UUID bookingId,
        String correlationId,
        String sagaId,
        List<RequestedItem> items,
        BigDecimal total
) {}
