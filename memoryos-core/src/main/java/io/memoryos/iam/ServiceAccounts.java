package io.memoryos.iam;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.UUID;

/**
 * Accounts that act for a part of the Tenant's own setup, such as a channel's bot, and not for a person. A service
 * account is an active member in the protected Basic Group and in no other, so it reads what every member may
 * read. Nobody signs in as it: its identity is issued here and no identity provider signs for {@link #ISSUER}.
 *
 * <p>It is not a person in the Tenant's administration: the Users list, the people pickers and the Group pages do
 * not show it, and it is neither deactivated nor put in a Group from there. Whoever owns it creates and retires it.
 */
public interface ServiceAccounts {

    /** The issuer of every service account's identity; the subject is the owner's id. */
    String ISSUER = "urn:memoryos:service";

    /**
     * Creates the account of {@code owner}, which has none yet, inside the caller's transaction.
     *
     * @param owner what the account acts for; one account an owner
     * @param displayName the name shown wherever an actor is named
     * @param uiLanguage the language of what is written for it, {@code vi} or {@code en}
     */
    ActorId create(TenantId tenantId, UUID owner, String displayName, String uiLanguage);

    /** Ends the account's membership inside the caller's transaction; what it did keeps its name. */
    void retire(TenantId tenantId, ActorId account);
}
