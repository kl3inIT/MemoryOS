package io.memoryos.connector;

import io.memoryos.shared.ActorId;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface LarkCredentialService {
    /** Verifies the draft with Lark before anything is stored. */
    Created create(ActorId actorId, Draft draft);

    /** Replaces the App Secret of the same app, fencing every Source that uses the credential. */
    Replaced replaceSecret(ActorId actorId, CredentialId credentialId, long expectedRevision, Draft draft);

    void rename(ActorId actorId, CredentialId credentialId, long expectedRevision, String name);

    void delete(ActorId actorId, CredentialId credentialId, long expectedRevision);

    List<CredentialView> list(ActorId actorId);

    /** Re-checks a stored credential against Lark and records the bot it names. */
    TestResult test(ActorId actorId, CredentialId credentialId);

    record Draft(String name, LarkGateway.Region region, String appId, byte[] appSecret) implements AutoCloseable {
        public Draft {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(appId, "appId");
            appSecret = Objects.requireNonNull(appSecret, "appSecret").clone();
        }

        @Override public byte[] appSecret() { return appSecret.clone(); }

        @Override public void close() { Arrays.fill(appSecret, (byte) 0); }

        @Override public @NonNull String toString() { return "LarkDraft[redacted]"; }
    }

    record CredentialView(CredentialId id, String name, String region, String appId, String status,
                          @Nullable String botName, long credentialRevision, Instant createdAt, Instant updatedAt,
                          long sourceCount, List<String> actions) {
        public CredentialView { actions = List.copyOf(actions); }
    }

    /**
     * What Lark said about a working credential. {@code missingScopes} are the read scopes Lark refuses to the app's
     * tenant token, by name; a Source reads nothing of the kind a missing scope covers until it is granted.
     */
    record TestResult(String botName, Set<String> missingScopes) {
        public TestResult { missingScopes = Set.copyOf(missingScopes); }
    }

    record Created(CredentialId id, TestResult verification) {}

    record Replaced(long credentialRevision, TestResult verification) {}
}
