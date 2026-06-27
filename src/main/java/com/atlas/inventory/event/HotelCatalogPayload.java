package com.atlas.inventory.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Payload for {@code HotelCreated} / {@code HotelUpdated} (hotel-events.yaml HotelCatalogPayload).
 * Copied from hotel-service so Inventory consumes a strongly typed envelope. Inventory uses
 * {@code hotelId} and the per-room-type {@code totalRooms} to seed room availability. Never carries
 * live availability (data ownership).
 */
public record HotelCatalogPayload(
        @NotNull
        UUID hotelId,
        String name,
        String city,
        String country,
        int rating,

        @Valid
        @NotEmpty
        List<RoomTypeEvent> roomTypes,
        List<String> amenities
) {}
