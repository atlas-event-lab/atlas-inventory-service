package com.atlas.inventory.repository;

import com.atlas.inventory.entity.FlightInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link FlightInventory}. Accesses only local entities (DB-004).
 */
public interface FlightInventoryRepository extends JpaRepository<FlightInventory, UUID> {

    /**
     * Loads a flight row under a {@code PESSIMISTIC_WRITE} lock ({@code SELECT … FOR UPDATE}),
     * serializing concurrent reservations for the same flight so {@code available} never goes
     * negative (state_machine.md §Concurrency). MUST be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FlightInventory f where f.resourceId = :resourceId")
    Optional<FlightInventory> findForUpdate(@Param("resourceId") UUID resourceId);

    /** Read-only lookup for the availability query API (no lock). */
    Optional<FlightInventory> findByResourceId(UUID resourceId);
}
