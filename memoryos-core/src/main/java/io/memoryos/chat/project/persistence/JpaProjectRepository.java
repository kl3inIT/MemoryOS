package io.memoryos.chat.project.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface JpaProjectRepository extends JpaRepository<ProjectEntity, UUID> {
    List<ProjectEntity> findByTenantIdAndOwnerIdOrderByPositionAscUpdatedAtDescIdAsc(UUID tenantId, UUID ownerId, Pageable page);
    Optional<ProjectEntity> findByTenantIdAndOwnerIdAndId(UUID tenantId, UUID ownerId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectEntity p where p.tenantId=:tenant and p.ownerId=:actor and p.id=:id")
    Optional<ProjectEntity> locked(UUID tenant, UUID actor, UUID id);

    @Query("select p.id from ProjectEntity p where p.tenantId=:tenant and p.ownerId=:actor")
    List<UUID> ids(UUID tenant, UUID actor);

    /** One less than the owner's lowest position, so a new Project comes first without rewriting the others. */
    @Query("select coalesce(min(p.position) - 1, 0) from ProjectEntity p where p.tenantId=:tenant and p.ownerId=:actor")
    int firstPosition(UUID tenant, UUID actor);

    /**
     * Places the owner's Projects at {@code 0..n-1} in the order of {@code ids}, in one statement. A bulk update
     * bypasses the entity, so it leaves {@code revision} and {@code updatedAt} alone.
     */
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE chat_project p SET position = CAST(o.ordinal - 1 AS integer)
            FROM unnest(CAST(:ids AS uuid[])) WITH ORDINALITY AS o(id, ordinal)
            WHERE p.tenant_id = :tenant AND p.owner_actor_id = :actor AND p.id = o.id
            """)
    int place(UUID tenant, UUID actor, UUID[] ids);
}
