package io.memoryos.chat.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface JpaPersonaRepository extends JpaRepository<PersonaEntity, UUID> {
    @Query("select p from PersonaEntity p where p.tenantId=:tenant and p.deletedAt is null "
            + "and (p.builtinKey is not null or p.ownerId=:actor) order by p.builtinKey nulls last, p.name, p.id")
    List<PersonaEntity> readable(UUID tenant, UUID actor, Pageable page);

    Optional<PersonaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PersonaEntity p where p.tenantId=:tenant and p.id=:id")
    Optional<PersonaEntity> locked(UUID tenant, UUID id);
}
