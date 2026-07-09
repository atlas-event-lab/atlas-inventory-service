-- ADR-0008: date-based (per-night) hotel availability.
-- Strategy is truncate + rebuild from the catalog (impl plan Phase 6), so this is forward-only DDL:
-- it reshapes the schema and drops now-invalid hotel rows; it does not migrate row data.

-- 1. Specialize the scalar `inventory` table to flights only (drop the FLIGHT/HOTEL discriminator and
--    the hotel-only parent id). Hotel rows are rebuilt into `room_type_availability`.
DELETE FROM inventory WHERE resource_type = 'HOTEL';

DROP INDEX IF EXISTS idx_inventory_parent_resource_id;
ALTER TABLE inventory DROP CONSTRAINT IF EXISTS uq_inventory_resource;
ALTER TABLE inventory DROP COLUMN resource_type;
ALTER TABLE inventory DROP COLUMN parent_resource_id;
ALTER TABLE inventory RENAME TO flight_inventory;
ALTER TABLE flight_inventory ADD CONSTRAINT uq_flight_inventory_resource UNIQUE (resource_id);

-- 2. Per-night hotel availability — the authoritative hotel inventory (one row per room type per night).
CREATE TABLE room_type_availability
(
    id           UUID                     NOT NULL,
    room_type_id UUID                     NOT NULL,
    hotel_id     UUID                     NOT NULL,
    stay_date    DATE                     NOT NULL,
    total_rooms  INTEGER                  NOT NULL,
    reserved     INTEGER                  NOT NULL DEFAULT 0,
    status       VARCHAR(20)              NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_room_type_availability PRIMARY KEY (id),
    CONSTRAINT uq_room_type_availability_night UNIQUE (room_type_id, stay_date),
    CONSTRAINT ck_room_type_availability_reserved_not_negative CHECK (reserved >= 0)
);

-- Reserve/query path filters and locks by (room_type_id, stay_date).
CREATE INDEX idx_room_type_availability_room_type_stay_date ON room_type_availability (room_type_id, stay_date);
-- Catalog reconcile (HotelUpdated/HotelDeleted) locks a hotel's future calendar.
CREATE INDEX idx_room_type_availability_hotel_id ON room_type_availability (hotel_id);
-- Rolling job clones/purges by stay_date.
CREATE INDEX idx_room_type_availability_stay_date ON room_type_availability (stay_date);

-- 3. Reservations become a SINGLE_TABLE hierarchy discriminated by the existing `resource_type`
--    column, with nullable hotel stay-date columns. Drop now-invalid hotel reservations (rebuilt).
DELETE FROM reservation_history
    WHERE reservation_id IN (SELECT id FROM reservations WHERE resource_type = 'HOTEL');
DELETE FROM reservations WHERE resource_type = 'HOTEL';

ALTER TABLE reservations ADD COLUMN check_in  DATE;
ALTER TABLE reservations ADD COLUMN check_out DATE;
