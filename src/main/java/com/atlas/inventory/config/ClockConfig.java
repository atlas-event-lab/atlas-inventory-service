package com.atlas.inventory.config;

import com.atlas.inventory.scheduler.ReservationExpirationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the reservation TTL sweep: binds {@link ReservationExpirationProperties} and exposes a
 * {@link Clock} bean so the sweep's deadline check is deterministic and testable
 * (coding-standards §Unit Tests — "Tests SHALL be deterministic. No sleeps").
 */
@Configuration
@EnableConfigurationProperties(ReservationExpirationProperties.class)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
