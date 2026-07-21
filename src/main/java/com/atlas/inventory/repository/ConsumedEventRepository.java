package com.atlas.inventory.repository;

import com.atlas.inventory.entity.ConsumedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the consumed-event idempotency store (EVT-005, EVT-008).
 * Accesses only local entities (DB-004).
 */
public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, UUID> {}
