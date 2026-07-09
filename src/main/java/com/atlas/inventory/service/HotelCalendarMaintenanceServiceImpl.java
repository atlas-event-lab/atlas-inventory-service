package com.atlas.inventory.service;

import com.atlas.inventory.config.HotelCalendarProperties;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rolling maintenance of the hotel calendar (ADR-0008). Materialization is eager, so the seed already
 * covers {@code [today, today + horizonDays)}; as {@code today} advances this job creates the new far
 * night (cloning the previous frontier) and purges nights past the retention window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelCalendarMaintenanceServiceImpl implements HotelCalendarMaintenanceService {

    private final RoomTypeNightAvailabilityRepository roomTypeAvailabilityRepository;
    private final HotelCalendarProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public int rollHorizonForward() {
        LocalDate today = LocalDate.now(clock);
        // Frontier night of the window [today, today + horizonDays): today + horizonDays - 1.
        LocalDate frontier = today.plusDays((long) properties.horizonDays() - 1);
        LocalDate source = frontier.minusDays(1);

        Set<UUID> alreadyExtended = roomTypeAvailabilityRepository.findByStayDate(frontier).stream()
                .map(RoomTypeNightAvailability::getRoomTypeId)
                .collect(Collectors.toSet());

        int created = 0;
        for (RoomTypeNightAvailability row : roomTypeAvailabilityRepository.findByStayDate(source)) {
            if (alreadyExtended.contains(row.getRoomTypeId())) {
                continue;
            }
            roomTypeAvailabilityRepository.save(new RoomTypeNightAvailability(
                    UUID.randomUUID(), row.getRoomTypeId(), row.getHotelId(), frontier,
                    row.getTotalRooms(), 0, row.getStatus()));
            created++;
        }
        if (created > 0) {
            log.info("Rolled hotel calendar forward: created {} night(s) for {}", created, frontier);
        }
        return created;
    }

    @Override
    @Transactional
    public int purgePastNights() {
        LocalDate cutoff = LocalDate.now(clock).minusDays(properties.purgeAfterDays());
        int deleted = roomTypeAvailabilityRepository.deleteByStayDateBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} completed hotel night(s) older than {}", deleted, cutoff);
        }
        return deleted;
    }
}
