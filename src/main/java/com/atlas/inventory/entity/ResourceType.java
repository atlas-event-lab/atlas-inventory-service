package com.atlas.inventory.entity;

/** Type of reservable resource owned by Inventory (services/inventory/service.md). */
public enum ResourceType {

    /** A flight; {@code resourceId} is the flightId from the Flight catalog. */
    FLIGHT,

    /** A hotel room type; {@code resourceId} is the roomTypeId from the Hotel catalog. */
    HOTEL
}
