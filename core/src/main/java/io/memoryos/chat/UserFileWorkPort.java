package io.memoryos.chat;

import io.memoryos.document.DocumentContent;
import io.memoryos.iam.TenantId;
import java.util.Optional;
import java.util.UUID;

/** Worker boundary; does not load the Chat inference runtime. */
public interface UserFileWorkPort {
    Optional<UserFileWork> claim(TenantId tenant, UUID operation, UUID delivery);
    boolean renew(UserFileWork work);
    boolean complete(UserFileWork work, DocumentContent content);
    boolean deleted(UserFileWork work);
    void failed(UserFileWork work, String code);
}
