package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Payload for {@code HotelDeleted} (hotel-events.yaml HotelDeletedPayload). */
public record HotelDeletedPayload(@NotNull UUID hotelId) {}
