package com.atlas.inventory.config;

import com.atlas.inventory.scheduler.ReservationExpirationProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the reservation TTL sweep and the hotel calendar: binds
 * {@link ReservationExpirationProperties} / {@link HotelCalendarProperties} and exposes a
 * {@link Clock} bean so the sweep's deadline check and the calendar's "today" are deterministic and
 * testable (coding-standards §Unit Tests — "Tests SHALL be deterministic. No sleeps").
 */
@Configuration
@EnableConfigurationProperties({ReservationExpirationProperties.class, HotelCalendarProperties.class})
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
