package com.atlas.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the per-night hotel calendar (ADR-0008; no hardcoded values,
 * coding-standards §Configuration).
 *
 * @param horizonDays    how far ahead hotels are bookable. Catalog seeding materializes the nights
 *                       {@code [today, today + horizonDays)}; the rolling job keeps the window this
 *                       size (recommended default 365).
 * @param purgeAfterDays how long a completed night is kept before the rolling job purges it: nights
 *                       with {@code stayDate < today − purgeAfterDays} are deleted (recommended 7).
 */
@ConfigurationProperties(prefix = "atlas.inventory.hotel")
public record HotelCalendarProperties(int horizonDays, int purgeAfterDays) {}
