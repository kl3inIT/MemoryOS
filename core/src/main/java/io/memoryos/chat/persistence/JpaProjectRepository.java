package io.memoryos.chat.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface JpaProjectRepository extends JpaRepository<ProjectEntity, UUID> {
    List<ProjectEntity> findByTenantIdAndOwnerIdOrderByUpdatedAtDescIdAsc(UUID tenantId, UUID ownerId, Pageable page);
    Optional<ProjectEntity> findByTenantIdAndOwnerIdAndId(UUID tenantId, UUID ownerId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectEntity p where p.tenantId=:tenant and p.ownerId=:actor and p.id=:id")
    Optional<ProjectEntity> locked(UUID tenant, UUID actor, UUID id);
}
