package com.atlas.inventory.service;

import java.util.UUID;

/** One room type extracted from a consumed {@code HotelCreated}/{@code HotelUpdated} event. */
public record RoomTypeSeed(UUID roomTypeId, int totalRooms) {}
