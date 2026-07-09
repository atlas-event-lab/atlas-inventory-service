package com.atlas.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A temporary lock over Inventory for one booking item (glossary; one Reservation per item).
 * Abstract base of a JPA {@code SINGLE_TABLE} hierarchy (ADR-0008): {@link FlightReservation} (no
 * dates) and {@link HotelReservation} (carries the stay range). One physical {@code reservations}
 * table discriminated by {@code resource_type}; the hotel date columns are nullable.
 *
 * <p>The lifecycle ({@code RESERVED → CONFIRMED/RELEASED/EXPIRED}) is defined in
 * services/inventory/state_machine.md and enforced by {@code ReservationStateTransitionGuard}.
 * {@code correlationId}/{@code sagaId} are carried from the originating Booking event so the TTL
 * sweep can still propagate them onto resource-facing events (OBS-002, OBS-003).
 */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "resource_type", discriminatorType = DiscriminatorType.STRING, length = 20)
@Table(name = "reservations")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public abstract class Reservation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    /** Local reference to the originating booking (ARCH-004); also the saga partition key. */
    @Column(name = "booking_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID bookingId;

    /** Local reference to the reserved resource (flightId / roomTypeId), never a cross-service FK (ARCH-004). */
    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ToString.Include
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "correlation_id", updatable = false, length = 36)
    private String correlationId;

    @Column(name = "saga_id", updatable = false, length = 36)
    private String sagaId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reservation(UUID id, UUID bookingId, UUID resourceId, int quantity,
                          ReservationStatus status, Instant expiresAt, String correlationId, String sagaId) {
        this.id = id;
        this.bookingId = bookingId;
        this.resourceId = resourceId;
        this.quantity = quantity;
        this.status = status;
        this.expiresAt = expiresAt;
        this.correlationId = correlationId;
        this.sagaId = sagaId;
    }

    /** The resource family this reservation belongs to (drives resource-facing event type resolution). */
    public abstract ResourceType resourceType();

    /** True while the reservation still holds inventory (counts towards reserved availability). */
    public boolean isActive() {
        return status == ReservationStatus.RESERVED || status == ReservationStatus.CONFIRMED;
    }
}
