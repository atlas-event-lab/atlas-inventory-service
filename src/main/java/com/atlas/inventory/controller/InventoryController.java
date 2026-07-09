package com.atlas.inventory.controller;

import com.atlas.inventory.dto.FlightAvailabilityResponse;
import com.atlas.inventory.dto.HotelAvailabilityResponse;
import com.atlas.inventory.service.AvailabilityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only availability query API (inventory.yaml; OpenAPI server {@code /api/v1}; code-first per
 * ADR-0006). Requires a valid Keycloak JWT (SEC-002). Holds no business logic (API-003) — delegates
 * to {@link AvailabilityQueryService}. Flights are scalar; hotels are queried per night over a range.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final AvailabilityQueryService availabilityQueryService;

    @GetMapping("/flight/{flightId}")
    public ResponseEntity<FlightAvailabilityResponse> getFlightAvailability(@PathVariable UUID flightId) {
        return ResponseEntity.ok(availabilityQueryService.getFlightAvailability(flightId));
    }

    @GetMapping("/hotel/{roomTypeId}")
    public ResponseEntity<HotelAvailabilityResponse> getHotelAvailability(
            @PathVariable UUID roomTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(availabilityQueryService.getHotelAvailability(roomTypeId, from, to));
    }
}
