package io.memoryos.iam;
import io.memoryos.shared.ActorId;

public interface UserQueryService {

    UserPage list(ActorId administrator, UserQuery query);
}
