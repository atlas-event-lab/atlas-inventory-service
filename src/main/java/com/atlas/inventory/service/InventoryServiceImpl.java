package com.atlas.inventory.service;

import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.entity.Inventory;
import com.atlas.inventory.entity.Reservation;
import com.atlas.inventory.entity.ReservationHistory;
import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.entity.ConsumedEvent;
import com.atlas.inventory.event.FailedItem;
import com.atlas.inventory.event.InventoryEventTypes;
import com.atlas.inventory.event.InventoryRejectedPayload;
import com.atlas.inventory.event.InventoryReleasedPayload;
import com.atlas.inventory.event.InventoryReservedPayload;
import com.atlas.inventory.event.ReservationDeltaPayload;
import com.atlas.inventory.event.ReservedItem;
import com.atlas.inventory.exception.InventoryNotFoundException;
import com.atlas.inventory.exception.ReservationNotFoundException;
import com.atlas.inventory.messaging.OutboxEventWriter;
import com.atlas.inventory.repository.ConsumedEventRepository;
import com.atlas.inventory.repository.InventoryRepository;
import com.atlas.inventory.repository.ReservationHistoryRepository;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.scheduler.ReservationExpirationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inventory Service implementation — the Inventory side of the booking choreography saga
 * (features/reserve-inventory). All methods are {@code @Transactional} and idempotent on the
 * envelope {@code eventId}; state changes and produced events commit together via the outbox
 * (no dual-write, EVT-009/EVT-010). Reservation transitions follow
 * services/inventory/state_machine.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private static final String AGGREGATE_BOOKING     = "Booking";
    private static final String AGGREGATE_RESERVATION = "Reservation";

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationHistoryRepository reservationHistoryRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final OutboxEventWriter outboxEventWriter;
    private final ReservationExpirationProperties properties;
    private final Clock clock;

    // -------------------------------------------------------------------------
    // BookingCreated — reserve (all-or-nothing)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void reserve(UUID eventId, ReserveCommand command) {
        if (consumedEventRepository.existsById(eventId)) {
            log.info("Skipping duplicate BookingCreated: eventId={}, bookingId={}", eventId, command.bookingId());
            return;
        }

        // Lock items in a deterministic order to avoid deadlocks between concurrent bookings.
        List<RequestedItem> sortedItems = command.items().stream()
                .sorted(Comparator.comparing((RequestedItem i) -> i.resourceType().name())
                        .thenComparing(RequestedItem::resourceId))
                .toList();

        List<Inventory> reservable = new ArrayList<>();
        List<RequestedItem> reservableItems = new ArrayList<>();
        List<FailedItem> failedItems = new ArrayList<>();

        // Evaluate availability for EVERY item under a pessimistic lock BEFORE mutating anything,
        // so the reject branch has nothing to roll back and reports all failing items (ARCH-007).
        for (RequestedItem item : sortedItems) {
            Optional<Inventory> found = inventoryRepository.findForUpdate(item.resourceType(), item.resourceId());
            if (found.isEmpty()) {
                log.warn("BookingCreated references unknown resource, treating as unavailable: "
                        + "resourceType={}, resourceId={}, bookingId={}",
                        item.resourceType(), item.resourceId(), command.bookingId());
                failedItems.add(new FailedItem(item.resourceType(), item.resourceId(), item.quantity(), 0));
                continue;
            }
            Inventory inventory = found.get();
            if (!inventory.canReserve(item.quantity())) {
                failedItems.add(new FailedItem(item.resourceType(), item.resourceId(),
                        item.quantity(), Math.max(0, inventory.available())));
                continue;
            }
            reservable.add(inventory);
            reservableItems.add(item);
        }

        consumedEventRepository.save(new ConsumedEvent(eventId, "BookingCreated"));

        if (!failedItems.isEmpty()) {
            // All-or-nothing: nothing persisted; emit only the booking-facing rejection.
            outboxEventWriter.write(AGGREGATE_BOOKING, command.bookingId(), InventoryEventTypes.INVENTORY_REJECTED,
                    command.correlationId(), command.sagaId(),
                    new InventoryRejectedPayload(command.bookingId(), failedItems));
            log.info("Inventory rejected: bookingId={}, failedItems={}", command.bookingId(), failedItems.size());
            return;
        }

        Instant expiresAt = clock.instant().plus(properties.ttl());
        List<ReservedItem> reservedItems = new ArrayList<>();

        for (int i = 0; i < reservable.size(); i++) {
            Inventory inventory = reservable.get(i);
            RequestedItem item = reservableItems.get(i);

            inventory.reserve(item.quantity());

            UUID reservationId = UUID.randomUUID();
            Reservation reservation = new Reservation(
                    reservationId, command.bookingId(), item.resourceType(), item.resourceId(),
                    item.quantity(), ReservationStatus.RESERVED, expiresAt,
                    command.correlationId(), command.sagaId());
            reservationRepository.save(reservation);
            recordHistory(reservationId, ReservationStatus.RESERVED);

            reservedItems.add(new ReservedItem(reservationId, item.resourceType(), item.resourceId(), item.quantity()));

            // Resource-facing reserved event, keyed by reservationId (partitioning.md).
            outboxEventWriter.write(AGGREGATE_RESERVATION, reservationId,
                    InventoryEventTypes.reserved(item.resourceType()),
                    command.correlationId(), command.sagaId(),
                    new ReservationDeltaPayload(reservationId, command.bookingId(),
                            item.resourceType(), item.resourceId(), item.quantity()));
        }

        // Booking-facing reserved event, keyed by bookingId.
        outboxEventWriter.write(AGGREGATE_BOOKING, command.bookingId(), InventoryEventTypes.INVENTORY_RESERVED,
                command.correlationId(), command.sagaId(),
                new InventoryReservedPayload(command.bookingId(), reservedItems));

        log.info("Inventory reserved: bookingId={}, items={}", command.bookingId(), reservedItems.size());
    }

    // -------------------------------------------------------------------------
    // BookingConfirmed — confirm
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void confirm(UUID eventId, UUID bookingId) {
        if (consumedEventRepository.existsById(eventId)) {
            log.info("Skipping duplicate BookingConfirmed: eventId={}, bookingId={}", eventId, bookingId);
            return;
        }

        for (Reservation reservation : reservationRepository.findByBookingId(bookingId)) {
            if (reservation.getStatus() == ReservationStatus.RESERVED) {
                ReservationStateTransitionGuard.assertAllowed(reservation.getStatus(), ReservationStatus.CONFIRMED);
                reservation.setStatus(ReservationStatus.CONFIRMED);
                recordHistory(reservation.getId(), ReservationStatus.CONFIRMED);
            } else {
                // Guard mismatch (already RELEASED/EXPIRED/CONFIRMED) — ignore (EVT-005).
                log.debug("Ignoring BookingConfirmed for reservation in state {}: reservationId={}",
                        reservation.getStatus(), reservation.getId());
            }
        }

        consumedEventRepository.save(new ConsumedEvent(eventId, "BookingConfirmed"));
        log.info("Inventory confirmed: bookingId={}", bookingId);
    }

    // -------------------------------------------------------------------------
    // BookingCancelled / BookingFailed — release
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void release(UUID eventId, UUID bookingId, String triggerEventType,
                        String correlationId, String sagaId) {
        if (consumedEventRepository.existsById(eventId)) {
            log.info("Skipping duplicate {}: eventId={}, bookingId={}", triggerEventType, eventId, bookingId);
            return;
        }

        List<UUID> releasedIds = releaseActiveReservations(
                bookingId, ReservationStatus.RELEASED, correlationId, sagaId, InventoryEventTypes::released);

        consumedEventRepository.save(new ConsumedEvent(eventId, triggerEventType));

        if (!releasedIds.isEmpty()) {
            outboxEventWriter.write(AGGREGATE_BOOKING, bookingId, InventoryEventTypes.INVENTORY_RELEASED,
                    correlationId, sagaId, new InventoryReleasedPayload(bookingId, releasedIds));
        }
        log.info("Inventory released ({}): bookingId={}, released={}", triggerEventType, bookingId, releasedIds.size());
    }

    // -------------------------------------------------------------------------
    // BookingExpired — expire
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void expire(UUID eventId, UUID bookingId, String correlationId, String sagaId) {
        if (consumedEventRepository.existsById(eventId)) {
            log.info("Skipping duplicate BookingExpired: eventId={}, bookingId={}", eventId, bookingId);
            return;
        }

        // Only RESERVED reservations expire on BookingExpired; emit resource-facing *Expired per item.
        List<UUID> expiredIds = new ArrayList<>();
        for (Reservation reservation : reservationRepository.findByBookingId(bookingId)) {
            if (reservation.getStatus() == ReservationStatus.RESERVED) {
                expireOne(reservation, correlationId, sagaId);
                expiredIds.add(reservation.getId());
            }
        }

        consumedEventRepository.save(new ConsumedEvent(eventId, "BookingExpired"));

        if (!expiredIds.isEmpty()) {
            outboxEventWriter.write(AGGREGATE_BOOKING, bookingId, InventoryEventTypes.INVENTORY_RELEASED,
                    correlationId, sagaId, new InventoryReleasedPayload(bookingId, expiredIds));
        }
        log.info("Inventory expired (BookingExpired): bookingId={}, expired={}", bookingId, expiredIds.size());
    }

    // -------------------------------------------------------------------------
    // TTL sweep — expire a single reservation
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void expireReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // Idempotent: a confirm/release already won the race, or a previous sweep handled it.
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            log.debug("Skipping TTL expiry, reservation not RESERVED: reservationId={}, status={}",
                    reservationId, reservation.getStatus());
            return;
        }

        // The TTL sweep does NOT emit the booking-facing InventoryReleased — Booking does not react to
        // a reservation expiry (it is settled by Payment's PaymentTimedOut). Only the resource-facing
        // *Expired event is published, adjusting Search availability (feature.md).
        expireOne(reservation, reservation.getCorrelationId(), reservation.getSagaId());
        log.info("Reservation expired by TTL sweep: reservationId={}, bookingId={}",
                reservationId, reservation.getBookingId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Releases every active (RESERVED/CONFIRMED) reservation of a booking and emits a resource-facing
     * event per released item. Returns the ids of the reservations actually released. */
    private List<UUID> releaseActiveReservations(UUID bookingId, ReservationStatus target,
                                                 String correlationId, String sagaId,
                                                 ResourceEventTypeResolver resourceEvent) {
        List<UUID> releasedIds = new ArrayList<>();
        for (Reservation reservation : reservationRepository.findByBookingId(bookingId)) {
            if (!reservation.isActive()) {
                // Idempotent release: already RELEASED/EXPIRED has no side effect (glossary).
                continue;
            }
            ReservationStateTransitionGuard.assertAllowed(reservation.getStatus(), target);
            reservation.setStatus(target);
            recordHistory(reservation.getId(), target);
            restoreAvailability(reservation);

            outboxEventWriter.write(AGGREGATE_RESERVATION, reservation.getId(),
                    resourceEvent.resolve(reservation.getResourceType()),
                    correlationId, sagaId, deltaOf(reservation));
            releasedIds.add(reservation.getId());
        }
        return releasedIds;
    }

    /** Expires a single RESERVED reservation: state → EXPIRED, restore availability, emit *Expired. */
    private void expireOne(Reservation reservation, String correlationId, String sagaId) {
        ReservationStateTransitionGuard.assertAllowed(reservation.getStatus(), ReservationStatus.EXPIRED);
        reservation.setStatus(ReservationStatus.EXPIRED);
        recordHistory(reservation.getId(), ReservationStatus.EXPIRED);
        restoreAvailability(reservation);

        outboxEventWriter.write(AGGREGATE_RESERVATION, reservation.getId(),
                InventoryEventTypes.expired(reservation.getResourceType()),
                correlationId, sagaId, deltaOf(reservation));
    }

    /** Returns a reservation's quantity to the Inventory row (under a pessimistic lock). */
    private void restoreAvailability(Reservation reservation) {
        Inventory inventory = inventoryRepository
                .findForUpdate(reservation.getResourceType(), reservation.getResourceId())
                .orElseThrow(() -> new InventoryNotFoundException(
                        reservation.getResourceType(), reservation.getResourceId()));
        inventory.release(reservation.getQuantity());
    }

    private ReservationDeltaPayload deltaOf(Reservation reservation) {
        return new ReservationDeltaPayload(reservation.getId(), reservation.getBookingId(),
                reservation.getResourceType(), reservation.getResourceId(), reservation.getQuantity());
    }

    private void recordHistory(UUID reservationId, ReservationStatus status) {
        reservationHistoryRepository.save(new ReservationHistory(UUID.randomUUID(), reservationId, status));
    }

    /** Resolves the resource-facing event name for a {@link ResourceType}. */
    @FunctionalInterface
    private interface ResourceEventTypeResolver {
        String resolve(ResourceType type);
    }
}
