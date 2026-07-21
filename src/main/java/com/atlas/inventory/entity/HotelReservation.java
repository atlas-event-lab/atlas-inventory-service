package com.atlas.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A reservation of hotel rooms over a stay range (ADR-0008). Records {@code checkIn} / {@code checkOut}
 * so release / expiry can restore {@code reserved} on every night of the stay. The stay occupies the
 * nights {@code [checkIn, checkOut)} — the check-out night is not occupied. Persisted in the shared
 * {@code reservations} table with discriminator {@code HOTEL}.
 */
@Entity
@DiscriminatorValue("HOTEL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HotelReservation extends Reservation {

    @Column(name = "check_in")
    private LocalDate checkIn;

    @Column(name = "check_out")
    private LocalDate checkOut;

    public HotelReservation(
            UUID id,
            UUID bookingId,
            UUID resourceId,
            int quantity,
            ReservationStatus status,
            Instant expiresAt,
            String correlationId,
            String sagaId,
            LocalDate checkIn,
            LocalDate checkOut) {
        super(id, bookingId, resourceId, quantity, status, expiresAt, correlationId, sagaId);
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.HOTEL;
    }

    /** The nights occupied by this stay: {@code checkIn … checkOut−1} (check-out night excluded). */
    public List<LocalDate> nights() {
        List<LocalDate> nights = new ArrayList<>();
        for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) {
            nights.add(d);
        }
        return nights;
    }
}
