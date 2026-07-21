package com.atlas.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Per-night availability of a hotel room type (ADR-0008; services/inventory/service.md). One row per
 * {@code (roomTypeId, stayDate)} is the authoritative hotel inventory — it replaces the scalar hotel
 * {@code Inventory}. Availability is per night: {@code available(night) = totalRooms − reserved}.
 *
 * <p>A stay {@code [checkIn, checkOut)} occupies the nights {@code checkIn … checkOut−1}. The affected
 * night rows are pessimistically locked (ordered by {@code stayDate}) while reserving so concurrent
 * overlapping stays serialize on shared nights and {@code available} never goes negative
 * (state_machine.md §Concurrency).
 */
@Entity
@Table(name = "room_type_availability")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class RoomTypeNightAvailability {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    /** Local reference to the catalog room type (roomTypeId) — never a cross-service FK (ARCH-004). */
    @Column(name = "room_type_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID roomTypeId;

    /** Parent hotel id, so HotelDeleted/HotelUpdated (which carry only hotelId) can reconcile every night row. */
    @Column(name = "hotel_id", nullable = false, updatable = false)
    private UUID hotelId;

    @Column(name = "stay_date", nullable = false, updatable = false)
    @ToString.Include
    private LocalDate stayDate;

    @Column(name = "total_rooms", nullable = false)
    private int totalRooms;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InventoryStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RoomTypeNightAvailability(
            UUID id,
            UUID roomTypeId,
            UUID hotelId,
            LocalDate stayDate,
            int totalRooms,
            int reserved,
            InventoryStatus status) {
        this.id = id;
        this.roomTypeId = roomTypeId;
        this.hotelId = hotelId;
        this.stayDate = stayDate;
        this.totalRooms = totalRooms;
        this.reserved = reserved;
        this.status = status;
    }

    /** Rooms still available on this night. Clamped at zero so availability never goes negative. */
    public int available() {
        return Math.max(0, totalRooms - reserved);
    }

    public boolean isActive() {
        return status == InventoryStatus.ACTIVE;
    }

    /** True when the night is ACTIVE and has at least {@code rooms} available. */
    public boolean canReserve(int rooms) {
        return isActive() && available() >= rooms;
    }

    /**
     * Allocates {@code rooms} on this night. Caller MUST hold the pessimistic lock and have checked
     * {@link #canReserve(int)} first; the guard here is defence in depth so availability never
     * goes negative (state_machine.md §Concurrency).
     */
    public void reserve(int rooms) {
        if (!canReserve(rooms)) {
            throw new IllegalStateException("Cannot reserve " + rooms + " rooms of room type " + roomTypeId + " on "
                    + stayDate + " (status=" + status + ", available=" + available() + ")");
        }
        this.reserved += rooms;
    }

    /** Returns {@code rooms} to availability (release/expiry). Floors at zero (idempotent-safe). */
    public void release(int rooms) {
        this.reserved = Math.max(0, this.reserved - rooms);
    }

    /** Sets {@code totalRooms} to the catalog's new absolute published value (HotelUpdated). */
    public void updateCapacity(int newTotalRooms) {
        this.totalRooms = newTotalRooms;
    }

    /** Withdraws this night: no new reservations; existing reservations are
     * untouched (HotelDeleted/removed room type). */
    public void disable() {
        this.status = InventoryStatus.DISABLED;
    }

    /** True when the stored capacity is below what is already reserved (oversold — SHALL NOT normally occur). */
    public boolean isOversold() {
        return totalRooms < reserved;
    }
}
