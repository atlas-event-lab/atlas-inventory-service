package com.atlas.inventory.repository;

import com.atlas.inventory.entity.ReservationHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the {@link ReservationHistory} audit trail. Accesses only local entities (DB-004).
 */
public interface ReservationHistoryRepository extends JpaRepository<ReservationHistory, UUID> {}
