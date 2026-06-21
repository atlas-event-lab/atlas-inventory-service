package com.atlas.inventory.repository;

import com.atlas.inventory.entity.Reservation;
import com.atlas.inventory.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link Reservation}. Accesses only local entities (DB-004).
 */
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** All reservations for a booking (confirm/release/expire operate on the whole booking). */
    List<Reservation> findByBookingId(UUID bookingId);

    /**
     * A batch of reservations in {@code status} whose deadline has passed, oldest-first.
     * Backed by the {@code (status, expires_at)} index; used by the TTL sweep.
     */
    List<Reservation> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            ReservationStatus status, Instant cutoff);
}
