package com.atlas.inventory.repository;

import com.atlas.inventory.entity.OutboxEvent;
import com.atlas.inventory.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for the Transactional Outbox (EVT-009). Accesses only local entities (DB-004).
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Reads a batch of unpublished events oldest-first for the relay to publish.
     * Backed by the {@code (status, created_at)} index.
     */
    List<OutboxEvent> findTop100ByStatusInOrderByCreatedAtAsc(Collection<OutboxStatus> statuses);
}
