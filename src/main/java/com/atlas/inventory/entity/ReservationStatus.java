package com.atlas.inventory.entity;

/** Lifecycle state of a {@link Reservation} (services/inventory/state_machine.md). */
public enum ReservationStatus {

    /** Item locked for a booking; awaiting confirmation. Initial state. */
    RESERVED,

    /** Booking confirmed; inventory permanently allocated. Non-terminal (may still be released). */
    CONFIRMED,

    /** Reservation cancelled/compensated; inventory returned to availability. Terminal. */
    RELEASED,

    /** Reservation timed out; inventory auto-released. Terminal. */
    EXPIRED
}
