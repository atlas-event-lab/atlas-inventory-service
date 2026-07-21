package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload for {@code FlightCreated} / {@code FlightUpdated} (flight-events.yaml FlightCatalogPayload).
 * Copied from flight-service so Inventory consumes a strongly typed envelope. Inventory only uses
 * {@code flightId} and {@code totalSeats} to seed seat availability; the remaining display fields are
 * carried verbatim. Never carries live availability (data ownership).
 */
public record FlightCatalogPayload(
        @NotNull UUID flightId,
        String flightNumber,
        String airlineCode,
        String airlineName,
        String originAirportCode,
        String destinationAirportCode,
        Instant departureTime,
        Instant arrivalTime,
        int totalSeats,
        MoneyEvent basePrice,
        List<FlightSegmentEvent> segments) {}
