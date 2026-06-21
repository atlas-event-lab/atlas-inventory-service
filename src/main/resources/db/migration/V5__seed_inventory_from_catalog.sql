-- Seed Inventory from Catalog feature.
-- HOTEL rows carry their parent hotelId so HotelDeleted/HotelUpdated (which only reference the
-- hotel) can locate and reconcile every per-room-type Inventory row. FLIGHT rows leave it NULL.
ALTER TABLE inventory ADD COLUMN parent_resource_id UUID;

CREATE INDEX idx_inventory_parent_resource_id ON inventory (parent_resource_id);

-- Drop the reserved<=capacity invariant: a catalog *Updated lowers totalCapacity to an absolute
-- published value. Catalog-side validation guarantees newCapacity >= reservedCount, but if that is
-- ever bypassed the seeding service clamps available() to 0 and logs ERROR rather than rejecting the
-- event (feature.md Error table) — so the absolute value must be storable.
ALTER TABLE inventory DROP CONSTRAINT ck_inventory_reserved_within_capacity;
