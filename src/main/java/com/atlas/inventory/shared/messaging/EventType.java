package com.atlas.inventory.shared.messaging;

import com.atlas.inventory.entity.ResourceType;

/**
 * Produced event types (inventory-events.yaml message names). Stored on each outbox row and used by
 * {@code OutboxRelay} to resolve the destination topic. Booking-facing names are fixed;
 * resource-facing names depend on the {@link ResourceType}.
 */
public enum EventType {

  // ── Booking-facing (saga) ──
  INVENTORY_RESERVED,
  INVENTORY_REJECTED,
  INVENTORY_RELEASED,

  // ── Resource-facing (availability) ──
  FLIGHT_SEATS_RESERVED,
  FLIGHT_SEATS_RELEASED,
  FLIGHT_RESERVATION_EXPIRED,
  HOTEL_ROOMS_RESERVED,
  HOTEL_ROOMS_RELEASED,
  HOTEL_RESERVATION_EXPIRED;

  /** Resource-facing reserved event for the given resource type. */
  public static EventType reserved(ResourceType type) {
    return type == ResourceType.FLIGHT ? FLIGHT_SEATS_RESERVED : HOTEL_ROOMS_RESERVED;
  }

  /** Resource-facing released event for the given resource type. */
  public static EventType released(ResourceType type) {
    return type == ResourceType.FLIGHT ? FLIGHT_SEATS_RELEASED : HOTEL_ROOMS_RELEASED;
  }

  /** Resource-facing expired event for the given resource type. */
  public static EventType expired(ResourceType type) {
    return type == ResourceType.FLIGHT ? FLIGHT_RESERVATION_EXPIRED : HOTEL_RESERVATION_EXPIRED;
  }
}
