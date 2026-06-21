package com.atlas.inventory.controller;

import com.atlas.inventory.dto.AvailabilityResponse;
import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.service.AvailabilityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only availability query API (inventory.yaml; OpenAPI server {@code /api/v1}). Requires a valid
 * Keycloak JWT (SEC-002). Holds no business logic (API-003) — delegates to {@link AvailabilityQueryService}.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final AvailabilityQueryService availabilityQueryService;

    @GetMapping("/{resourceType}/{resourceId}")
    public ResponseEntity<AvailabilityResponse> getAvailability(
            @PathVariable ResourceType resourceType,
            @PathVariable UUID resourceId) {
        return ResponseEntity.ok(availabilityQueryService.getAvailability(resourceType, resourceId));
    }
}
