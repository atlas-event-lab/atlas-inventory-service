package com.atlas.inventory.scheduler;

import com.atlas.inventory.service.HotelCalendarMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rolls the per-night hotel calendar forward and purges long-past nights (ADR-0008;
 * services/inventory/service.md §Rolling horizon).
 *
 * <p>Idempotent and stateless (ARCH-010): {@code fixedDelay} prevents overlap and both operations are
 * safe to re-run (a night that already exists is skipped; a purge of nothing is a no-op —
 * coding-standards §Spring Boot "Scheduled jobs SHALL be idempotent"). The interval defaults to daily
 * because "today" advances once a day.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelCalendarRollingScheduler {

    private final HotelCalendarMaintenanceService maintenanceService;

    @Scheduled(fixedDelayString = "${atlas.inventory.hotel.roll-interval-ms:86400000}",
            initialDelayString = "${atlas.inventory.hotel.roll-initial-delay-ms:60000}")
    public void rollAndPurge() {
        try {
            maintenanceService.rollHorizonForward();
            maintenanceService.purgePastNights();
        } catch (Exception e) {
            // Never let a maintenance failure kill the scheduler thread; the next tick retries.
            log.error("Hotel calendar rolling/purge failed; will retry on the next tick", e);
        }
    }
}
