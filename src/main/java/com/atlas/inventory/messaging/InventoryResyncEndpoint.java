package com.atlas.inventory.messaging;

import com.atlas.inventory.service.InventoryAvailabilityResyncService;
import com.atlas.inventory.service.ResyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Operational endpoint to resync availability for a read-model rebuild (ADR-0027, Experiment 07):
 * {@code POST /actuator/resync} re-emits the current absolute {@code reserved}/{@code version} of
 * every flight and hotel-night through the outbox, which Search re-applies onto its projections.
 * <p>
 * Same operational-control pattern as {@code dlqreplay} (ADR-0022): internal management port only
 * (9090, not published via ingress), network- + RBAC-gated via the k8s API proxy. Deliberate, no
 * redeploy. MUST run <b>after</b> the catalog resyncs (flight/hotel) so the projection rows exist.
 */
@Component
@Endpoint(id = "resync")
@RequiredArgsConstructor
public class InventoryResyncEndpoint {

  private final InventoryAvailabilityResyncService resyncService;

  @WriteOperation
  public ResyncResult resync() {
    return resyncService.resyncAll();
  }
}
