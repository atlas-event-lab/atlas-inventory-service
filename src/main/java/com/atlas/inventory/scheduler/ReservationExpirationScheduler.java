package com.atlas.inventory.scheduler;

import com.atlas.inventory.entity.Reservation;
import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reservation TTL sweep (features/reserve-inventory §Reservation Expiration). Expires due
 * {@code RESERVED} reservations (→ {@code EXPIRED}), restoring availability and emitting the
 * resource-facing {@code *Expired} events. {@code ReservationExpired} is NOT consumed by Booking;
 * it only adjusts Search availability.
 * <p>
 * Idempotent and stateless (ARCH-010): the deadline derives from each reservation's persisted
 * {@code expiresAt}; {@code fixedDelay} prevents a slow run from overlapping the next tick, and the
 * per-reservation status guard makes a second pass a no-op (coding-standards §Spring Boot —
 * "Scheduled jobs SHALL be idempotent"). Each reservation is expired in its own transaction inside
 * {@link InventoryService#expireReservation(java.util.UUID)}; a failure on one does not abort the batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpirationScheduler {

    private final ReservationRepository reservationRepository;
    private final InventoryService inventoryService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${atlas.inventory.reservation.sweep-interval-ms:60000}")
    public void expireDueReservations() {
        Instant now = clock.instant();
        List<Reservation> due = reservationRepository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                ReservationStatus.RESERVED, now);
        if (due.isEmpty()) {
            return;
        }
        log.info("Expiring {} due reservation(s) past their TTL as of {}", due.size(), now);
        for (Reservation reservation : due) {
            try {
                inventoryService.expireReservation(reservation.getId());
            } catch (Exception e) {
                // Isolate per-reservation failures so the rest of the batch still proceeds.
                log.error("Failed to expire reservation: reservationId={}", reservation.getId(), e);
            }
        }
    }
}
