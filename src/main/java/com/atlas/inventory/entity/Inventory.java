package com.atlas.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * Availability of a reservable resource (services/inventory/service.md). Inventory is the single
 * authority for live availability: {@code available = totalCapacity − reservedCount}. The row is
 * pessimistically locked while reserving so concurrent reservations are serialized and
 * {@code available} never goes negative (no double-booking, state_machine.md §Concurrency).
 */
@Entity
@Table(
        name = "inventory",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_inventory_resource",
                columnNames = {"resource_type", "resource_id"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Inventory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, updatable = false, length = 20)
    @ToString.Include
    private ResourceType resourceType;

    /** Local reference to the catalog resource (flightId / roomTypeId) — never a cross-service FK (ARCH-004). */
    @Column(name = "resource_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID resourceId;

    /**
     * Parent catalog resource id: the {@code hotelId} for HOTEL rows (one Inventory row per room type),
     * {@code null} for FLIGHT rows. Lets a {@code HotelDeleted}/{@code HotelUpdated} (which carry only
     * {@code hotelId}) locate and reconcile every room-type row of a hotel. Local reference (ARCH-004).
     */
    @Column(name = "parent_resource_id", updatable = false)
    private UUID parentResourceId;

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

    public Inventory(UUID id, ResourceType resourceType, UUID resourceId,
                     int totalCapacity, int reservedCount, InventoryStatus status) {
        this(id, resourceType, resourceId, null, totalCapacity, reservedCount, status);
    }

    public Inventory(UUID id, ResourceType resourceType, UUID resourceId, UUID parentResourceId,
                     int totalCapacity, int reservedCount, InventoryStatus status) {
        this.id = id;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.parentResourceId = parentResourceId;
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

    /** True when the resource is ACTIVE and has at least {@code quantity} units available. */
    public boolean canReserve(int quantity) {
        return isActive() && available() >= quantity;
    }

    /**
     * Allocates {@code quantity} units. Caller MUST hold the pessimistic lock and have checked
     * {@link #canReserve(int)} first; the guard here is defence in depth so availability never
     * goes negative (state_machine.md §Concurrency).
     */
    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException(
                    "Cannot reserve " + quantity + " units of inventory " + id
                    + " (status=" + status + ", available=" + available() + ")");
        }
        this.reservedCount += quantity;
    }

    /** Returns {@code quantity} units to availability (release/expiry). Floors at zero (idempotent-safe). */
    public void release(int quantity) {
        this.reservedCount = Math.max(0, this.reservedCount - quantity);
    }

    /** Sets {@code totalCapacity} to the catalog's new absolute published value (FlightUpdated/HotelUpdated). */
    public void updateCapacity(int newCapacity) {
        this.totalCapacity = newCapacity;
    }

    /** Withdraws the resource: no new reservations; existing reservations are untouched (FlightDeleted/HotelDeleted). */
    public void disable() {
        this.status = InventoryStatus.DISABLED;
    }

    /** True when the stored capacity is below what is already reserved (oversold — SHALL NOT normally occur). */
    public boolean isOversold() {
        return totalCapacity < reservedCount;
    }
}
