package com.atlas.inventory.repository;

import com.atlas.inventory.entity.Inventory;
import com.atlas.inventory.entity.ResourceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Inventory}. Accesses only local entities (DB-004).
 */
public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    /**
     * Loads an Inventory row under a {@code PESSIMISTIC_WRITE} lock ({@code SELECT … FOR UPDATE}),
     * serializing concurrent reservations for the same resource so {@code available} never goes
     * negative (state_machine.md §Concurrency). MUST be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.resourceType = :resourceType and i.resourceId = :resourceId")
    Optional<Inventory> findForUpdate(@Param("resourceType") ResourceType resourceType,
                                      @Param("resourceId") UUID resourceId);

    /** Read-only lookup for the availability query API (no lock). */
    Optional<Inventory> findByResourceTypeAndResourceId(ResourceType resourceType, UUID resourceId);

    /** All per-room-type rows of a hotel (HotelUpdated reconcile / HotelDeleted disable). */
    List<Inventory> findByParentResourceId(UUID parentResourceId);
}
