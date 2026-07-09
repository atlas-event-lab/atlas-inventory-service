package com.atlas.inventory.service;

import com.atlas.inventory.entity.ResourceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One item to reserve, extracted from a consumed {@code BookingCreated} event. Hotel items carry the
 * stay range {@code [checkIn, checkOut)}; flight items leave the dates null (ADR-0008/ADR-0010).
 */
public record RequestedItem(
        ResourceType resourceType,
        UUID resourceId,
        int quantity,
        BigDecimal amount,
        LocalDate checkIn,
        LocalDate checkOut
) {

    /** Flight item convenience factory (no stay dates). */
    public static RequestedItem flight(UUID resourceId, int quantity, BigDecimal amount) {
        return new RequestedItem(ResourceType.FLIGHT, resourceId, quantity, amount, null, null);
    }

    /** Hotel item convenience factory (stay range). */
    public static RequestedItem hotel(UUID resourceId, int quantity, BigDecimal amount,
                                      LocalDate checkIn, LocalDate checkOut) {
        return new RequestedItem(ResourceType.HOTEL, resourceId, quantity, amount, checkIn, checkOut);
    }

    /** The nights occupied by a hotel stay: {@code checkIn … checkOut−1} (check-out night excluded). */
    public List<LocalDate> nights() {
        List<LocalDate> nights = new ArrayList<>();
        for (LocalDate night = checkIn; night.isBefore(checkOut); night = night.plusDays(1)) {
            nights.add(night);
        }
        return nights;
    }
}
