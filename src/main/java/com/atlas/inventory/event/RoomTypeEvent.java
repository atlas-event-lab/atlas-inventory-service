package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Denormalized room type inside catalog event payloads (hotel-events.yaml RoomType).
 * Copied from hotel-service. Carries {@code totalRooms} so Inventory can seed per-room-type
 * availability.
 */
public record RoomTypeEvent(
        @NotNull UUID roomTypeId, String name, int totalRooms, int maxOccupancy, MoneyEvent pricePerNight) {}
