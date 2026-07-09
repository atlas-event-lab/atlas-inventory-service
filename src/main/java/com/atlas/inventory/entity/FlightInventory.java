package com.atlas.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Scalar availability of a flight (services/inventory/service.md; ADR-0008). A flight instance is
 * already a date, so it keeps the scalar model: {@code available = totalCapacity − reservedCount}.
 * The row is pessimistically locked while reserving so concurrent reservations are serialized and
 * {@code available} never goes negative (no double-booking, state_machine.md §Concurrency).
 *
 * <p>This is the former shared {@code Inventory} specialized to FLIGHT (ADR-0008): the
 * {@code resource_type} and {@code parent_resource_id} discriminator columns are removed; hotels now
 * live in {@link RoomTypeNightAvailability}.
 */
@Entity
@Table(name = "flight_inventory")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class FlightInventory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    /** Local reference to the flight in the catalog (flightId) — never a cross-service FK (ARCH-004). */
    @Column(name = "resource_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID resourceId;

    @Column(name = "total_capacity", nullable = false)
    private int totalCapacity;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InventoryStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FlightInventory(UUID id, UUID resourceId, int totalCapacity, int reservedCount, InventoryStatus status) {
        this.id = id;
        this.resourceId = resourceId;
        this.totalCapacity = totalCapacity;
        this.reservedCount = reservedCount;
        this.status = status;
    }

    /**
     * Units still available for reservation. Clamped at zero: catalog validation guarantees published
     * capacity never drops below {@code reservedCount}, but if that is ever violated (validation
     * bypassed) availability still never goes negative (feature.md seed-inventory Error table).
     */
    public int available() {
        return Math.max(0, totalCapacity - reservedCount);
    }

    public boolean isActive() {
        return status == InventoryStatus.ACTIVE;
    }

    /** True when the flight is ACTIVE and has at least {@code quantity} seats available. */
    public boolean canReserve(int quantity) {
        return isActive() && available() >= quantity;
    }

    /**
     * Allocates {@code quantity} seats. Caller MUST hold the pessimistic lock and have checked
     * {@link #canReserve(int)} first; the guard here is defence in depth so availability never
     * goes negative (state_machine.md §Concurrency).
     */
    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException(
                    "Cannot reserve " + quantity + " seats of flight inventory " + id
                    + " (status=" + status + ", available=" + available() + ")");
        }
        this.reservedCount += quantity;
    }

    /** Returns {@code quantity} seats to availability (release/expiry). Floors at zero (idempotent-safe). */
    public void release(int quantity) {
        this.reservedCount = Math.max(0, this.reservedCount - quantity);
    }

    /** Sets {@code totalCapacity} to the catalog's new absolute published value (FlightUpdated). */
    public void updateCapacity(int newCapacity) {
        this.totalCapacity = newCapacity;
    }

    /** Withdraws the flight: no new reservations; existing reservations are untouched (FlightDeleted). */
    public void disable() {
        this.status = InventoryStatus.DISABLED;
    }

    /** True when the stored capacity is below what is already reserved (oversold — SHALL NOT normally occur). */
    public boolean isOversold() {
        return totalCapacity < reservedCount;
    }
}
