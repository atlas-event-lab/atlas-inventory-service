package com.atlas.inventory.service;

import com.atlas.inventory.entity.ResourceType;

import java.util.UUID;

/** One item to reserve, extracted from a consumed {@code BookingCreated} event. */
public record RequestedItem(
        ResourceType resourceType,
        UUID resourceId,
        int quantity
) {}
