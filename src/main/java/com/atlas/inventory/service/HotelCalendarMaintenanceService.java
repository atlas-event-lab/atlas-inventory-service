package com.atlas.inventory.service;

/**
 * Rolling maintenance of the per-night hotel calendar (ADR-0008; services/inventory/service.md
 * §Rolling horizon). Keeps the bookable window a fixed size as time advances and purges long-past
 * nights. Both operations are idempotent so the scheduled job is safe to re-run.
 */
public interface HotelCalendarMaintenanceService {

    /**
     * Extends every room type's calendar to the far edge of the horizon by cloning the previous
     * frontier night ({@code totalRooms}/{@code status}, {@code reserved = 0}). Idempotent: a night
     * that already exists is left untouched.
     *
     * @return the number of new night rows created.
     */
    int rollHorizonForward();

    /**
     * Purges completed nights older than the retention window
     * ({@code stayDate < today − purgeAfterDays}).
     *
     * @return the number of night rows deleted.
     */
    int purgePastNights();
}
