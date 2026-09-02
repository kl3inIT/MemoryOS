package io.memoryos.identity.keycloak;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.memoryos.identity.IdentityProvisioningException;
import io.memoryos.identity.IdentityProvisioningFailureReason;
import io.memoryos.identity.KeycloakRecipientProvisioner;
import io.memoryos.identity.KeycloakRecipientProvisioning;
import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.admin.client.spi.ResteasyClientClassicProvider;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
final class AdminClientKeycloakRecipientProvisioner
        implements KeycloakRecipientProvisioner, AutoCloseable {

    static final String MEMORYOS_PROVISIONED_ATTRIBUTE = "memoryos.provisioned";
    private static final String TRUE = "true";
    private static final String VERIFY_EMAIL = "VERIFY_EMAIL";
    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";
    private static final List<String> ACTIVATION_ACTIONS = List.of(VERIFY_EMAIL, UPDATE_PASSWORD);

    private final Keycloak keycloak;
    private final String realm;
    private final String actionClientId;
    private final String actionRedirectUri;
    private final Clock clock;

    @Autowired
    AdminClientKeycloakRecipientProvisioner(KeycloakAdminProperties properties) {
        this(createClient(properties), properties, Clock.systemUTC());
    }

    AdminClientKeycloakRecipientProvisioner(
            Keycloak keycloak,
            KeycloakAdminProperties properties,
            Clock clock
    ) {
        this.keycloak = Objects.requireNonNull(keycloak, "keycloak must not be null");
        this.realm = properties.realm();
        this.actionClientId = properties.actionClientId();
        this.actionRedirectUri = properties.actionRedirectUri();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public KeycloakRecipientProvisioning provision(
            String normalizedEmail,
            Instant actionExpiresAt
    ) {
        Assert.hasText(normalizedEmail, "normalizedEmail must not be blank");
        Objects.requireNonNull(actionExpiresAt, "actionExpiresAt must not be null");

        try {
            UsersResource users = keycloak.realm(realm).users();
            UserRepresentation existing = findExact(users, normalizedEmail);
            if (existing != null) {
                return provisionExisting(users, existing, actionExpiresAt);
            }
            return createAndActivate(users, normalizedEmail, actionExpiresAt);
        } catch (ProcessingException | WebApplicationException exception) {
            throw providerUnavailable(exception);
        }
    }

    private KeycloakRecipientProvisioning createAndActivate(
            UsersResource users,
            String email,
            Instant actionExpiresAt
    ) {
        UserRepresentation created = new UserRepresentation();
        created.setUsername(email);
        created.setEmail(email);
        created.setEnabled(true);
        created.setEmailVerified(false);
        created.setRequiredActions(ACTIVATION_ACTIONS);
        created.setAttributes(Map.of(MEMORYOS_PROVISIONED_ATTRIBUTE, List.of(TRUE)));

        try (Response response = users.create(created)) {
            if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                UserRepresentation raced = findExact(users, email);
                if (raced == null) {
                    throw accountConflict("Keycloak reported a duplicate user without an exact email match");
                }
                return provisionExisting(users, raced, actionExpiresAt);
            }
            if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw providerUnavailable(
                        "Keycloak user creation returned HTTP " + response.getStatus()
                );
            }

            String userId = CreatedResponseUtil.getCreatedId(response);
            sendActivation(users.get(userId), actionExpiresAt);
            return KeycloakRecipientProvisioning.ACTIVATION_EMAIL_SENT;
        }
    }

    private KeycloakRecipientProvisioning provisionExisting(
            UsersResource users,
            UserRepresentation searchResult,
            Instant actionExpiresAt
    ) {
        UserResource user = users.get(searchResult.getId());
        UserRepresentation existing = user.toRepresentation();
        if (!Boolean.TRUE.equals(existing.isEnabled())) {
            throw accountConflict("the existing Keycloak account is disabled");
        }
        if (Boolean.TRUE.equals(existing.isEmailVerified())) {
            return KeycloakRecipientProvisioning.EXISTING_VERIFIED;
        }
        if (!isMemoryOsProvisioned(existing)) {
            throw accountConflict("an unrelated unverified Keycloak account owns the invited email");
        }

        LinkedHashSet<String> requiredActions = new LinkedHashSet<>();
        if (existing.getRequiredActions() != null) {
            requiredActions.addAll(existing.getRequiredActions());
        }
        requiredActions.addAll(ACTIVATION_ACTIONS);
        existing.setRequiredActions(List.copyOf(requiredActions));
        user.update(existing);
        sendActivation(user, actionExpiresAt);
        return KeycloakRecipientProvisioning.ACTIVATION_EMAIL_SENT;
    }

    private UserRepresentation findExact(UsersResource users, String email) {
        List<UserRepresentation> matches = users.searchByEmail(email, true).stream()
                .filter(user -> user.getEmail() != null && email.equalsIgnoreCase(user.getEmail()))
                .toList();
        if (matches.size() > 1) {
            throw accountConflict("multiple Keycloak users own the invited email");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private void sendActivation(UserResource user, Instant actionExpiresAt) {
        long seconds = Duration.between(clock.instant(), actionExpiresAt).toSeconds();
        if (seconds <= 0) {
            throw providerUnavailable("invitation expired before Keycloak activation delivery");
        }
        int lifespan = (int) Math.min(seconds, Integer.MAX_VALUE);
        user.executeActionsEmail(
                actionClientId,
                actionRedirectUri,
                lifespan,
                ACTIVATION_ACTIONS
        );
    }

    private static boolean isMemoryOsProvisioned(UserRepresentation user) {
        Map<String, List<String>> attributes = user.getAttributes();
        return attributes != null
                && attributes.getOrDefault(MEMORYOS_PROVISIONED_ATTRIBUTE, List.of()).stream()
                .anyMatch(TRUE::equalsIgnoreCase);
    }

    @Override
    @PreDestroy
    public void close() {
        keycloak.close();
    }

    @SuppressWarnings("resource")
    private static Keycloak createClient(KeycloakAdminProperties properties) {
        Client restClient = ResteasyClientClassicProvider.createClientBuilder()
                .connectTimeout(properties.connectTimeout().toMillis(), MILLISECONDS)
                .connectionCheckoutTimeout(properties.connectionRequestTimeout().toMillis(), MILLISECONDS)
                .readTimeout(properties.readTimeout().toMillis(), MILLISECONDS)
                .build()
                .register(JacksonProvider.class, 100);
        return KeycloakBuilder.builder()
                .serverUrl(properties.serverUrl())
                .realm(properties.realm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .resteasyClient(restClient)
                .build();
    }

    private static IdentityProvisioningException accountConflict(String diagnosticMessage) {
        return new IdentityProvisioningException(
                IdentityProvisioningFailureReason.ACCOUNT_CONFLICT,
                diagnosticMessage
        );
    }

    private static IdentityProvisioningException providerUnavailable(String diagnosticMessage) {
        return new IdentityProvisioningException(
                IdentityProvisioningFailureReason.PROVIDER_UNAVAILABLE,
                diagnosticMessage
        );
    }

    private static IdentityProvisioningException providerUnavailable(Throwable cause) {
        return new IdentityProvisioningException(
                IdentityProvisioningFailureReason.PROVIDER_UNAVAILABLE,
                "local Keycloak provisioning request failed",
                cause
        );
    }
}
