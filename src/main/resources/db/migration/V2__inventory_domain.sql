-- Phase 2: Inventory domain (services/inventory/service.md, state_machine.md).
-- One Inventory row per reservable resource; one Reservation per booking item;
-- ReservationHistory is the immutable audit trail of reservation transitions.

CREATE TABLE inventory
(
    id             UUID                     NOT NULL,
    resource_type  VARCHAR(20)              NOT NULL,
    resource_id    UUID                     NOT NULL,
    total_capacity INTEGER                  NOT NULL,
    reserved_count INTEGER                  NOT NULL DEFAULT 0,
    status         VARCHAR(20)              NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (id),
    CONSTRAINT uq_inventory_resource UNIQUE (resource_type, resource_id),
    CONSTRAINT ck_inventory_reserved_not_negative CHECK (reserved_count >= 0),
    CONSTRAINT ck_inventory_reserved_within_capacity CHECK (reserved_count <= total_capacity)
);

CREATE TABLE reservations
(
    id             UUID                     NOT NULL,
    booking_id     UUID                     NOT NULL,
    resource_type  VARCHAR(20)              NOT NULL,
    resource_id    UUID                     NOT NULL,
    quantity       INTEGER                  NOT NULL,
    status         VARCHAR(20)              NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id VARCHAR(36),
    saga_id        VARCHAR(36),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_reservations PRIMARY KEY (id),
    CONSTRAINT ck_reservations_quantity_positive CHECK (quantity >= 1)
);

-- All reservations of a booking are looked up together (confirm/release/expire).
CREATE INDEX idx_reservations_booking_id ON reservations (booking_id);
-- TTL sweep selects RESERVED rows past their deadline (status, expires_at).
CREATE INDEX idx_reservations_status_expires_at ON reservations (status, expires_at);

CREATE TABLE reservation_history
(
    id             UUID                     NOT NULL,
    reservation_id UUID                     NOT NULL,
    status         VARCHAR(20)              NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_reservation_history PRIMARY KEY (id)
);

CREATE INDEX idx_reservation_history_reservation_id ON reservation_history (reservation_id);
