package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveServiceAccountKey;
import io.memoryos.connector.GoogleDriveServiceAccountService;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultGoogleDriveServiceAccountService implements GoogleDriveServiceAccountService {
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[A-Za-z0-9.-]{1,253}");
    private final JdbcGoogleDriveCredentialRepository credentials;
    private final GoogleDriveProvider provider;
    private final IamAuthorization authorization;
    private final TransactionTemplate transactions;

    public DefaultGoogleDriveServiceAccountService(JdbcGoogleDriveCredentialRepository credentials, GoogleDriveProvider provider,
            IamAuthorization authorization, PlatformTransactionManager transactionManager) {
        this.credentials = credentials;
        this.provider = provider;
        this.authorization = authorization;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public CredentialId create(ActorId actorId, String name, String keyJson, String adminEmail) {
        String normalizedName = requireName(name);
        inTransaction(() -> requireGlobalManagement(actorId));
        String admin = validate(keyJson, adminEmail);
        return inTransaction(() -> {
            TenantId tenant = requireGlobalManagement(actorId);
            try (var key = GoogleDriveServiceAccountKey.parse(keyJson)) {
                return credentials.createServiceAccount(tenant, normalizedName, key, admin);
            }
        });
    }

    @Override
    public long replace(ActorId actorId, CredentialId credentialId, long expectedRevision, String name, String keyJson,
            String adminEmail) {
        String normalizedName = requireName(name);
        inTransaction(() -> requireReplaceable(actorId, credentialId, expectedRevision, keyJson));
        String admin = validate(keyJson, adminEmail);
        return inTransaction(() -> {
            TenantId tenant = requireReplaceable(actorId, credentialId, expectedRevision, keyJson);
            try (var key = GoogleDriveServiceAccountKey.parse(keyJson)) {
                return credentials.replaceServiceAccount(tenant, credentialId, normalizedName, expectedRevision, key, admin);
            }
        });
    }

    /**
     * Acts as the primary admin once, outside any transaction: the token exchange proves the delegation grants every
     * scope, the My Drive root proves Drive access, and the Directory record proves an active administrator.
     * Returns the admin's canonical primary email.
     */
    private String validate(String keyJson, String adminEmail) {
        String admin = requireAdminEmail(adminEmail);
        var key = GoogleDriveServiceAccountKey.parse(keyJson);
        if (admin.equals(key.clientEmail())) {
            key.close();
            throw GoogleDriveException.serviceAccountAdminRequired();
        }
        try (var credential = new GoogleDriveProvider.ServiceAccountCredential(key, admin);
             var session = provider.open(credential)) {
            GoogleDriveRootValidation.resolveMyDriveRoot(session);
            var user = session.directoryUser(admin);
            if (!user.admin() || user.suspended()) throw GoogleDriveException.serviceAccountAdminRequired();
            return user.primaryEmail();
        } catch (GoogleDriveProviderException exception) {
            throw switch (exception.failure()) {
                case AUTHENTICATION, SCOPE_INSUFFICIENT -> GoogleDriveException.serviceAccountDelegationMissing();
                case ACCESS_DENIED, NOT_FOUND -> GoogleDriveException.serviceAccountAdminRequired();
                default -> exception;
            };
        }
    }

    private TenantId requireGlobalManagement(ActorId actorId) {
        var tenant = authorization.lockAndRequireScopedMutation(Objects.requireNonNull(actorId), IamCapability.SOURCES_MANAGE).tenantId();
        authorization.require(actorId, IamCapability.SOURCES_MANAGE, false);
        credentials.requireConfigured();
        return tenant;
    }

    private TenantId requireReplaceable(ActorId actorId, CredentialId credentialId, long expectedRevision, String keyJson) {
        TenantId tenant = requireGlobalManagement(actorId);
        var stored = credentials.lock(tenant, credentialId).orElseThrow(SourceException::notFound);
        if (!stored.serviceAccount()) throw SourceException.conflict("Google credential is not a service account");
        if (stored.revision() != expectedRevision) throw SourceException.conflict("Google credential revision is stale");
        try (var key = GoogleDriveServiceAccountKey.parse(keyJson)) {
            if (!stored.subject().equals(key.clientId())) throw SourceException.conflict("Google service account changed");
        }
        return tenant;
    }

    private <T> T inTransaction(Supplier<T> action) {
        return Objects.requireNonNull(transactions.execute(_ -> action.get()));
    }

    private static String requireAdminEmail(String email) {
        if (email == null || email.strip().length() > 320 || !EMAIL.matcher(email.strip()).matches()) {
            throw SourceException.invalid("Enter the email of a Google Workspace administrator.", "invalid primary admin email");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw SourceException.invalid("Credential name must contain 1 to 120 characters.", "invalid credential name");
        }
        return name.strip();
    }
}
