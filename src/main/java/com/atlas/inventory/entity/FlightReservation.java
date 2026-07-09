package com.atlas.inventory.entity;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A reservation of flight seats (ADR-0008). Carries no stay dates — a flight instance is already a
 * date. Persisted in the shared {@code reservations} table with discriminator {@code FLIGHT}.
 */
@Entity
@DiscriminatorValue("FLIGHT")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FlightReservation extends Reservation {

    public FlightReservation(UUID id, UUID bookingId, UUID resourceId, int quantity,
                             ReservationStatus status, Instant expiresAt, String correlationId, String sagaId) {
        super(id, bookingId, resourceId, quantity, status, expiresAt, correlationId, sagaId);
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.FLIGHT;
    }
}
