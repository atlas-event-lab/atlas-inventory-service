package com.atlas.inventory.service;

import com.atlas.inventory.entity.FlightInventory;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.event.FlightAvailabilityPayload;
import com.atlas.inventory.event.HotelAvailabilityPayload;
import com.atlas.inventory.event.NightAvailability;
import com.atlas.inventory.messaging.OutboxEventWriter;
import com.atlas.inventory.repository.FlightInventoryRepository;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import com.atlas.inventory.shared.messaging.EventType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Availability resync for a read-model rebuild (ADR-0027, Experiment 07, Strategy B). Re-emits the
 * current <b>absolute</b> availability of every resource from {@code inventory_db} through the
 * outbox, so a wiped Search read model reconstructs its {@code reserved}/{@code version}:
 * <ul>
 *   <li>one {@code FLIGHT_SEATS_RESERVED} per {@code flight_inventory} row (absolute
 *       {@code reserved_count});</li>
 *   <li>one {@code HOTEL_ROOMS_RESERVED} per room type, carrying <b>all its future nights'</b>
 *       absolute {@code reserved} — the multi-night case a compacted topic could not express.</li>
 * </ul>
 * The emitted {@code version} is {@code clock.millis()} at resync time: ≥ any value Search currently
 * stores (so it is applied, never dropped as stale) yet below future live events (which carry a later
 * timestamp), matching the normal version scheme. Availability is absolute + version-guarded, so this
 * is idempotent; only Search consumes these topics. The rebuild orchestrator MUST run this
 * <b>after</b> the catalog resync (ADR-0025/0026) so every projection row already exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAvailabilityResyncService {

    private static final String AGGREGATE_FLIGHT = "Flight";
    private static final String AGGREGATE_HOTEL = "Hotel";
    /** Synthetic reservation/booking id — resync is not tied to a reservation; Search ignores these. */
    private static final UUID RESYNC_MARKER = new UUID(0L, 0L);

    private final FlightInventoryRepository flightInventoryRepository;
    private final RoomTypeNightAvailabilityRepository nightRepository;
    private final OutboxEventWriter outboxEventWriter;
    private final Clock clock;

    @Transactional
    public ResyncResult resyncAll() {
        long version = clock.millis();
        String correlationId = "resync-" + UUID.randomUUID();

        List<FlightInventory> flights = flightInventoryRepository.findAll();
        for (FlightInventory flight : flights) {
            outboxEventWriter.write(
                    AGGREGATE_FLIGHT,
                    flight.getResourceId(),
                    EventType.FLIGHT_SEATS_RESERVED,
                    correlationId,
                    null,
                    new FlightAvailabilityPayload(
                            RESYNC_MARKER, RESYNC_MARKER, flight.getResourceId(), flight.getReservedCount(), version));
        }

        LocalDate today = LocalDate.now(clock);
        Map<UUID, List<RoomTypeNightAvailability>> byRoomType = nightRepository.findAll().stream()
                .filter(row -> !row.getStayDate().isBefore(today))
                .collect(Collectors.groupingBy(RoomTypeNightAvailability::getRoomTypeId));

        for (Map.Entry<UUID, List<RoomTypeNightAvailability>> entry : byRoomType.entrySet()) {
            List<RoomTypeNightAvailability> rows = entry.getValue();
            UUID hotelId = rows.getFirst().getHotelId();
            List<NightAvailability> nights = rows.stream()
                    .sorted(Comparator.comparing(RoomTypeNightAvailability::getStayDate))
                    .map(row -> new NightAvailability(row.getStayDate(), row.getReserved()))
                    .toList();
            outboxEventWriter.write(
                    AGGREGATE_HOTEL,
                    entry.getKey(),
                    EventType.HOTEL_ROOMS_RESERVED,
                    correlationId,
                    null,
                    new HotelAvailabilityPayload(
                            RESYNC_MARKER, RESYNC_MARKER, entry.getKey(), hotelId, nights, version));
        }

        log.warn(
                "Availability resync: re-emitted {} flight + {} room-type availability events (version={})",
                flights.size(),
                byRoomType.size(),
                version);
        return new ResyncResult(flights.size(), byRoomType.size());
    }
}
