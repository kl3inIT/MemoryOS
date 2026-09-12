package io.memoryos.iam.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Actor lifecycle; account preferences are fields of the same aggregate. */
public interface JpaActorRepository extends JpaRepository<ActorEntity, UUID>, ActorRefresh {
}
