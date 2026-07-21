package com.atlas.inventory.service;

/**
 * Summary of an availability resync (ADR-0027): how many current-state, absolute availability events
 * were re-emitted through the outbox for a read-model rebuild.
 *
 * @param flights   flight inventory rows re-emitted (one {@code FLIGHT_SEATS_RESERVED} each)
 * @param roomTypes room types re-emitted (one {@code HOTEL_ROOMS_RESERVED} carrying all future nights)
 */
public record ResyncResult(int flights, int roomTypes) {
    public int total() {
        return flights + roomTypes;
    }
}
