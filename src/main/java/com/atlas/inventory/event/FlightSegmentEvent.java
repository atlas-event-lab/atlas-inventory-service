package com.atlas.inventory.event;

import java.time.Instant;

/**
 * Denormalized flight leg inside catalog event payloads (flight-events.yaml FlightSegment).
 * Copied from flight-service so Inventory can consume {@code FlightCreated}/{@code FlightUpdated}
 * with a strongly typed payload (no {@code Map<String,Object>}).
 */
public record FlightSegmentEvent(
        int sequence,
        String originAirportCode,
        String destinationAirportCode,
        Instant departureTime,
        Instant arrivalTime
) {}
