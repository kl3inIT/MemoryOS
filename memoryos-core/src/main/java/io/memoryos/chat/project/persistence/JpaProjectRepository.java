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

    /** A bulk update bypasses the entity, so it leaves {@code revision} and {@code updatedAt} alone. */
    @Modifying(clearAutomatically = true)
    @Query("update ProjectEntity p set p.position=:position where p.tenantId=:tenant and p.ownerId=:actor and p.id=:id")
    int place(UUID tenant, UUID actor, UUID id, int position);
}
