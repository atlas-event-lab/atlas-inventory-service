package com.atlas.inventory.service;

import com.atlas.inventory.entity.ConsumedEvent;
import com.atlas.inventory.entity.FlightInventory;
import com.atlas.inventory.entity.FlightReservation;
import com.atlas.inventory.entity.HotelReservation;
import com.atlas.inventory.entity.Reservation;
import com.atlas.inventory.entity.ReservationHistory;
import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.event.FailedItem;
import com.atlas.inventory.event.FlightAvailabilityPayload;
import com.atlas.inventory.event.HotelAvailabilityPayload;
import com.atlas.inventory.event.InventoryRejectedPayload;
import com.atlas.inventory.event.InventoryReleasedPayload;
import com.atlas.inventory.event.InventoryReservedPayload;
import com.atlas.inventory.event.NightAvailability;
import com.atlas.inventory.event.ReservedItem;
import com.atlas.inventory.exception.InventoryNotFoundException;
import com.atlas.inventory.exception.ReservationNotFoundException;
import com.atlas.inventory.messaging.OutboxEventWriter;
import com.atlas.inventory.repository.ConsumedEventRepository;
import com.atlas.inventory.repository.FlightInventoryRepository;
import com.atlas.inventory.repository.ReservationHistoryRepository;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import com.atlas.inventory.scheduler.ReservationExpirationProperties;
import com.atlas.inventory.shared.messaging.ConsumerEventType;
import com.atlas.inventory.shared.messaging.EventType;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inventory Service implementation — the Inventory side of the booking choreography saga
 * (features/reserve-inventory; ADR-0008). All methods are {@code @Transactional} and idempotent on
 * the envelope {@code eventId}; state changes and produced events commit together via the outbox
 * (no dual-write, EVT-009/EVT-010). Reservation transitions follow
 * services/inventory/state_machine.md.
 *
 * <p>Flights use the scalar {@link FlightInventory}; hotels use per-night
 * {@link RoomTypeNightAvailability}. A hotel item reserves {@code quantity} rooms on <b>every</b>
 * night of its stay {@code [checkIn, checkOut)}, all-or-nothing across items <b>and</b> nights.
 * Resource-facing events carry the <b>absolute</b> post-transaction {@code reserved} value plus a
 * monotonic {@code version} and are keyed by {@code flightId} / {@code roomTypeId} (ADR-0008).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

  private static final String AGGREGATE_BOOKING = "Booking";
  private static final String AGGREGATE_FLIGHT = "Flight";
  private static final String AGGREGATE_HOTEL = "Hotel";

  private final FlightInventoryRepository flightInventoryRepository;
  private final RoomTypeNightAvailabilityRepository roomTypeAvailabilityRepository;
  private final ReservationRepository reservationRepository;
  private final ReservationHistoryRepository reservationHistoryRepository;
  private final ConsumedEventRepository consumedEventRepository;
  private final OutboxEventWriter outboxEventWriter;
  private final ReservationExpirationProperties properties;
  private final java.time.Clock clock;
  private final MeterRegistry meterRegistry;

  // Domain metrics (Micrometer → /actuator/prometheus).
  // invariant (no oversell) these expose the numbers Experiment 02 asserts. Naming: dot-notation
  // becomes atlas_inventory_*_total in Prometheus.
  private static final String M_RESERVATIONS = "atlas.inventory.reservations"; // {result}
  private static final String M_OVERSELL = "atlas.inventory.oversell.attempts";
  private static final String M_UNITS = "atlas.inventory.units";               // {action}

  // -------------------------------------------------------------------------
  // BookingCreated — reserve (all-or-nothing across items AND nights)
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public void reserve(UUID eventId, ReserveCommand command) {
    if (consumedEventRepository.existsById(eventId)) {
      log.info("Skipping duplicate BookingCreated: eventId={}, bookingId={}", eventId,
          command.bookingId());
      return;
    }

    // Lock resources in a deterministic order to avoid deadlocks between concurrent bookings.
    List<RequestedItem> sortedItems = command.items().stream()
        .sorted(Comparator.comparing((RequestedItem i) -> i.resourceType().name())
            .thenComparing(RequestedItem::resourceId))
        .toList();

    List<FlightReservable> flightReservable = new ArrayList<>();
    List<HotelReservable> hotelReservable = new ArrayList<>();
    List<FailedItem> failedItems = new ArrayList<>();

    // Evaluate availability for EVERY item under a pessimistic lock BEFORE mutating anything, so the
    // reject branch has nothing to roll back and reports all failing items (ARCH-007).
    for (RequestedItem item : sortedItems) {
      if (item.resourceType() == ResourceType.FLIGHT) {
        evaluateFlight(item, flightReservable, failedItems);
      } else {
        evaluateHotel(item, hotelReservable, failedItems);
      }
    }

    consumedEventRepository.save(new ConsumedEvent(eventId, ConsumerEventType.BOOKING_CREATED));

    if (!failedItems.isEmpty()) {
      // All-or-nothing: nothing persisted; emit only the booking-facing rejection.
      outboxEventWriter.write(
          AGGREGATE_BOOKING, command.bookingId(), EventType.INVENTORY_REJECTED,
          command.correlationId(), command.sagaId(),
          new InventoryRejectedPayload(command.bookingId(), failedItems));
      meterRegistry.counter(M_RESERVATIONS, "result", "rejected").increment();
      log.info("Inventory rejected: bookingId={}, failedItems={}", command.bookingId(),
          failedItems.size());
      return;
    }

    Instant expiresAt = clock.instant().plus(properties.ttl());
    long version = clock.millis();
    List<ReservedItem> reservedItems = new ArrayList<>();

    try {
      for (FlightReservable reservable : flightReservable) {
        reservedItems.add(reserveFlight(reservable, command, expiresAt, version));
      }
      for (HotelReservable reservable : hotelReservable) {
        reservedItems.add(reserveHotel(reservable, command, expiresAt, version));
      }
    } catch (IllegalStateException e) {
      // Defence-in-depth guard tripped: availability was re-checked under the pessimistic lock,
      // so this SHALL NOT happen. If it ever does, the no-oversell invariant broke — surface it
      // as a metric (must stay 0) and let the transaction roll back.
      meterRegistry.counter(M_OVERSELL).increment();
      log.error("Oversell guard tripped while reserving bookingId={}: {}", command.bookingId(),
          e.getMessage());
      throw e;
    }

    // Booking-facing reserved event, keyed by bookingId.
    outboxEventWriter.write(
        AGGREGATE_BOOKING, command.bookingId(), EventType.INVENTORY_RESERVED,
        command.correlationId(), command.sagaId(),
        new InventoryReservedPayload(command.bookingId(), command.total(), reservedItems));

    meterRegistry.counter(M_RESERVATIONS, "result", "reserved").increment();
    log.info("Inventory reserved: bookingId={}, items={}", command.bookingId(),
        reservedItems.size());
  }

  private void evaluateFlight(RequestedItem item, List<FlightReservable> reservable,
      List<FailedItem> failed) {
    Optional<FlightInventory> found = flightInventoryRepository.findForUpdate(item.resourceId());
    if (found.isEmpty()) {
      log.warn("BookingCreated references unknown flight, treating as unavailable: resourceId={}",
          item.resourceId());
      failed.add(new FailedItem(ResourceType.FLIGHT, item.resourceId(), item.quantity(), 0));
      return;
    }
    FlightInventory inventory = found.get();
    if (!inventory.canReserve(item.quantity())) {
      failed.add(new FailedItem(ResourceType.FLIGHT, item.resourceId(),
          item.quantity(), Math.max(0, inventory.available())));
      return;
    }
    reservable.add(new FlightReservable(item, inventory));
  }

  private void evaluateHotel(
      RequestedItem item,
      List<HotelReservable> reservable,
      List<FailedItem> failed
  ) {
    List<LocalDate> nights = item.nights();
    List<RoomTypeNightAvailability> rows =
        roomTypeAvailabilityRepository.findForUpdateByRoomTypeIdAndStayDateIn(item.resourceId(),
            nights);

    if (rows.size() != nights.size()) {
      // A night of the stay is missing from the calendar → cannot guarantee availability.
      log.warn(
          "BookingCreated hotel item has {} of {} nights in the calendar, treating as unavailable: "
              + "roomTypeId={}, checkIn={}, checkOut={}",
          rows.size(), nights.size(), item.resourceId(), item.checkIn(), item.checkOut());
      failed.add(new FailedItem(ResourceType.HOTEL, item.resourceId(), item.quantity(), 0));
      return;
    }
    int minAvailable = rows.stream().mapToInt(RoomTypeNightAvailability::available).min().orElse(0);
    boolean allReservable = rows.stream().allMatch(row -> row.canReserve(item.quantity()));
    if (!allReservable) {
      failed.add(new FailedItem(ResourceType.HOTEL, item.resourceId(), item.quantity(),
          Math.max(0, minAvailable)));
      return;
    }
    reservable.add(new HotelReservable(item, rows));
  }

  private ReservedItem reserveFlight(
      FlightReservable reservable,
      ReserveCommand command,
      Instant expiresAt,
      long version
  ) {
    reservable.inventory().reserve(reservable.item().quantity());
    meterRegistry.counter(M_UNITS, "action", "reserved").increment(reservable.item().quantity());

    UUID reservationId = UUID.randomUUID();
    reservationRepository.save(
        new FlightReservation(reservationId, command.bookingId(), reservable.item().resourceId(),
            reservable.item().quantity(), ReservationStatus.RESERVED, expiresAt,
            command.correlationId(), command.sagaId()));
    recordHistory(reservationId, ReservationStatus.RESERVED);

    // Resource-facing event, keyed by flightId (partitioning.md), carrying absolute reserved + version.
    outboxEventWriter.write(AGGREGATE_FLIGHT, reservable.item().resourceId(),
        EventType.FLIGHT_SEATS_RESERVED,
        command.correlationId(), command.sagaId(),
        new FlightAvailabilityPayload(reservationId, command.bookingId(),
            reservable.item().resourceId(),
            reservable.inventory().getReservedCount(), version));

    return new ReservedItem(reservationId, ResourceType.FLIGHT, reservable.item().resourceId(),
        reservable.item().quantity(), reservable.item().amount());
  }

  private ReservedItem reserveHotel(
      HotelReservable reservable,
      ReserveCommand command,
      Instant expiresAt,
      long version
  ) {
    List<NightAvailability> nights = new ArrayList<>();
    for (RoomTypeNightAvailability row : reservable.rows()) {
      row.reserve(reservable.item().quantity());
      nights.add(new NightAvailability(row.getStayDate(), row.getReserved()));
    }
    meterRegistry.counter(M_UNITS, "action", "reserved").increment(reservable.item().quantity());

    UUID reservationId = UUID.randomUUID();
    UUID hotelId = reservable.rows().getFirst().getHotelId();
    reservationRepository.save(
        new HotelReservation(reservationId, command.bookingId(), reservable.item().resourceId(),
            reservable.item().quantity(), ReservationStatus.RESERVED, expiresAt,
            command.correlationId(), command.sagaId(),
            reservable.item().checkIn(), reservable.item().checkOut()));
    recordHistory(reservationId, ReservationStatus.RESERVED);

    // Resource-facing event, keyed by roomTypeId (partitioning.md), carrying per-night absolute reserved + version.
    outboxEventWriter.write(AGGREGATE_HOTEL, reservable.item().resourceId(),
        EventType.HOTEL_ROOMS_RESERVED,
        command.correlationId(), command.sagaId(),
        new HotelAvailabilityPayload(reservationId, command.bookingId(),
            reservable.item().resourceId(),
            hotelId, nights, version));

    return new ReservedItem(reservationId, ResourceType.HOTEL, reservable.item().resourceId(),
        reservable.item().quantity(), reservable.item().amount());
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
        ReservationStateTransitionGuard.assertAllowed(reservation.getStatus(),
            ReservationStatus.CONFIRMED);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        recordHistory(reservation.getId(), ReservationStatus.CONFIRMED);
      } else {
        // Guard mismatch (already RELEASED/EXPIRED/CONFIRMED) — ignore (EVT-005).
        log.debug("Ignoring BookingConfirmed for reservation in state {}: reservationId={}",
            reservation.getStatus(), reservation.getId());
      }
    }

    consumedEventRepository.save(new ConsumedEvent(eventId, ConsumerEventType.BOOKING_CONFIRMED));
    log.info("Inventory confirmed: bookingId={}", bookingId);
  }

  // -------------------------------------------------------------------------
  // BookingCancelled / BookingFailed — release
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public void release(UUID eventId, UUID bookingId, ConsumerEventType triggerEventType,
      String correlationId, String sagaId) {
    if (consumedEventRepository.existsById(eventId)) {
      log.info("Skipping duplicate {}: eventId={}, bookingId={}", triggerEventType, eventId,
          bookingId);
      return;
    }

    long version = clock.millis();
    List<UUID> releasedIds = new ArrayList<>();
    for (Reservation reservation : reservationRepository.findByBookingId(bookingId)) {
      if (!reservation.isActive()) {
        // Idempotent release: already RELEASED/EXPIRED has no side effect (glossary).
        continue;
      }
      ReservationStateTransitionGuard.assertAllowed(reservation.getStatus(),
          ReservationStatus.RELEASED);
      reservation.setStatus(ReservationStatus.RELEASED);
      recordHistory(reservation.getId(), ReservationStatus.RELEASED);
      restoreAndEmit(reservation, EventType.released(reservation.resourceType()), correlationId,
          sagaId, version);
      releasedIds.add(reservation.getId());
    }

    consumedEventRepository.save(new ConsumedEvent(eventId, triggerEventType));

    if (!releasedIds.isEmpty()) {
      outboxEventWriter.write(AGGREGATE_BOOKING, bookingId, EventType.INVENTORY_RELEASED,
          correlationId, sagaId, new InventoryReleasedPayload(bookingId, releasedIds));
    }
    log.info("Inventory released ({}): bookingId={}, released={}", triggerEventType, bookingId,
        releasedIds.size());
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

    long version = clock.millis();
    List<UUID> expiredIds = new ArrayList<>();
    for (Reservation reservation : reservationRepository.findByBookingId(bookingId)) {
      if (reservation.getStatus() == ReservationStatus.RESERVED) {
        expireOne(reservation, correlationId, sagaId, version);
        expiredIds.add(reservation.getId());
      }
    }

    consumedEventRepository.save(new ConsumedEvent(eventId, ConsumerEventType.BOOKING_EXPIRED));

    if (!expiredIds.isEmpty()) {
      outboxEventWriter.write(AGGREGATE_BOOKING, bookingId, EventType.INVENTORY_RELEASED,
          correlationId, sagaId, new InventoryReleasedPayload(bookingId, expiredIds));
    }
    log.info("Inventory expired (BookingExpired): bookingId={}, expired={}", bookingId,
        expiredIds.size());
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

    // The TTL sweep does NOT emit the booking-facing InventoryReleased — Booking does not react to a
    // reservation expiry (it is settled by Payment's PaymentTimedOut). Only the resource-facing
    // *Expired event is published, adjusting Search availability (feature.md).
    expireOne(reservation, reservation.getCorrelationId(), reservation.getSagaId(), clock.millis());
    log.info("Reservation expired by TTL sweep: reservationId={}, bookingId={}",
        reservationId, reservation.getBookingId());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Expires a single RESERVED reservation: state → EXPIRED, restore availability, emit *Expired.
   */
  private void expireOne(Reservation reservation, String correlationId, String sagaId,
      long version) {
    ReservationStateTransitionGuard.assertAllowed(reservation.getStatus(),
        ReservationStatus.EXPIRED);
    reservation.setStatus(ReservationStatus.EXPIRED);
    recordHistory(reservation.getId(), ReservationStatus.EXPIRED);
    restoreAndEmit(reservation, EventType.expired(reservation.resourceType()), correlationId,
        sagaId, version);
  }

  /**
   * Returns a reservation's quantity to availability (under a pessimistic lock) and emits the
   * resource-facing event carrying the new <b>absolute</b> reserved value(s) + {@code version}.
   */
  private void restoreAndEmit(Reservation reservation, EventType eventType,
      String correlationId, String sagaId, long version) {
    if (reservation instanceof FlightReservation) {
      FlightInventory inventory = flightInventoryRepository.findForUpdate(
              reservation.getResourceId())
          .orElseThrow(() -> new InventoryNotFoundException(ResourceType.FLIGHT,
              reservation.getResourceId()));
      inventory.release(reservation.getQuantity());
      meterRegistry.counter(M_UNITS, "action", "released").increment(reservation.getQuantity());
      outboxEventWriter.write(AGGREGATE_FLIGHT, reservation.getResourceId(), eventType,
          correlationId, sagaId,
          new FlightAvailabilityPayload(reservation.getId(), reservation.getBookingId(),
              reservation.getResourceId(), inventory.getReservedCount(), version));
    } else if (reservation instanceof HotelReservation hotel) {
      List<RoomTypeNightAvailability> rows = roomTypeAvailabilityRepository
          .findForUpdateByRoomTypeIdAndStayDateIn(hotel.getResourceId(), hotel.nights());
      if (rows.isEmpty()) {
        // All nights already purged (fully past stay) — nothing to restore or project.
        log.debug(
            "No calendar nights to restore for hotel reservation: reservationId={}, roomTypeId={}",
            hotel.getId(), hotel.getResourceId());
        return;
      }
      List<NightAvailability> nights = new ArrayList<>();
      for (RoomTypeNightAvailability row : rows) {
        row.release(hotel.getQuantity());
        nights.add(new NightAvailability(row.getStayDate(), row.getReserved()));
      }
      meterRegistry.counter(M_UNITS, "action", "released").increment(hotel.getQuantity());
      outboxEventWriter.write(AGGREGATE_HOTEL, hotel.getResourceId(), eventType,
          correlationId, sagaId,
          new HotelAvailabilityPayload(hotel.getId(), hotel.getBookingId(), hotel.getResourceId(),
              rows.getFirst().getHotelId(), nights, version));
    }
  }

  private void recordHistory(UUID reservationId, ReservationStatus status) {
    reservationHistoryRepository.save(
        new ReservationHistory(UUID.randomUUID(), reservationId, status));
  }

  /**
   * A flight item that passed availability evaluation, with its locked inventory row.
   */
  private record FlightReservable(RequestedItem item, FlightInventory inventory) {

  }

  /**
   * A hotel item that passed availability evaluation, with its locked night rows.
   */
  private record HotelReservable(RequestedItem item, List<RoomTypeNightAvailability> rows) {

  }
}
