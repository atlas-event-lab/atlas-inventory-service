package com.atlas.inventory.repository;

import com.atlas.inventory.entity.RoomTypeNightAvailability;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link RoomTypeNightAvailability} (per-night hotel availability, ADR-0008).
 * Accesses only local entities (DB-004).
 */
public interface RoomTypeNightAvailabilityRepository extends JpaRepository<RoomTypeNightAvailability, UUID> {

    /**
     * Loads the night rows of a room type for the given stay dates under a {@code PESSIMISTIC_WRITE}
     * lock, ordered by {@code stayDate} for a deterministic lock order (deadlock-safe between
     * concurrent overlapping stays, state_machine.md §Concurrency). MUST run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from RoomTypeNightAvailability r
            where r.roomTypeId = :roomTypeId and r.stayDate in :stayDates
            order by r.stayDate asc
            """)
    List<RoomTypeNightAvailability> findForUpdateByRoomTypeIdAndStayDateIn(
            @Param("roomTypeId") UUID roomTypeId, @Param("stayDates") Collection<LocalDate> stayDates);

    /** Read-only per-night availability over {@code [from, to)} for the query API (no lock). */
    @Query("""
            select r from RoomTypeNightAvailability r
            where r.roomTypeId = :roomTypeId and r.stayDate >= :from and r.stayDate < :to
            order by r.stayDate asc
            """)
    List<RoomTypeNightAvailability> findByRoomTypeIdInRange(
            @Param("roomTypeId") UUID roomTypeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Locks every night row of a hotel from {@code fromDate} onward (its future calendar), ordered by
     * {@code roomTypeId} then {@code stayDate}, so catalog seeding serializes with the reservation
     * path. Used by HotelCreated/HotelUpdated (upsert + reconcile) and HotelDeleted (disable).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from RoomTypeNightAvailability r
            where r.hotelId = :hotelId and r.stayDate >= :fromDate
            order by r.roomTypeId asc, r.stayDate asc
            """)
    List<RoomTypeNightAvailability> findForUpdateByHotelIdFromDate(
            @Param("hotelId") UUID hotelId, @Param("fromDate") LocalDate fromDate);

    /** All room-type night rows on a given date (rolling job: clone the frontier night forward). */
    List<RoomTypeNightAvailability> findByStayDate(LocalDate stayDate);

    /** Purges completed nights older than {@code cutoff} (rolling job). */
    int deleteByStayDateBefore(LocalDate cutoff);
}
