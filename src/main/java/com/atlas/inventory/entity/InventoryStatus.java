package com.atlas.inventory.entity;

/** Availability status of an {@link Inventory} resource (services/inventory/state_machine.md). */
public enum InventoryStatus {

    /** Resource is bookable: {@code available = totalCapacity − reservedCount}. */
    ACTIVE,

    /** Resource withdrawn from the catalog; no new reservations accepted. */
    DISABLED
}
