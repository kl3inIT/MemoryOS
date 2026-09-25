package io.memoryos.chat.persona.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Persona rows; authority is applied by callers through {@link JdbcAgentRepository#access}. */
public interface JpaPersonaRepository extends JpaRepository<PersonaEntity, UUID> {
    Optional<PersonaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<PersonaEntity> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PersonaEntity p where p.tenantId=:tenant and p.id=:id")
    Optional<PersonaEntity> locked(UUID tenant, UUID id);

    /** Write-locks the given rows in one statement, in id order, so concurrent callers cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PersonaEntity p where p.tenantId=:tenant and p.id in :ids order by p.id")
    List<PersonaEntity> lockedAll(UUID tenant, Collection<UUID> ids);
}
