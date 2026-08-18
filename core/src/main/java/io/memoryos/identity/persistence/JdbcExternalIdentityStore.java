package io.memoryos.identity.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityResolver;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public final class JdbcExternalIdentityStore implements ExternalIdentityResolver {

    private static final String SELECT_ACTOR = """
            SELECT actor_id
            FROM external_identity_bindings
            WHERE issuer = ? AND subject = ?
            """;

    private final DataSource dataSource;

    public JdbcExternalIdentityStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Optional<ActorId> resolve(ExternalIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        try (var connection = dataSource.getConnection()) {
            return findActorId(connection, identity).map(ActorId::new);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not resolve external identity", exception);
        }
    }

    private static Optional<UUID> findActorId(
            Connection connection,
            ExternalIdentity identity) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT_ACTOR)) {
            statement.setString(1, identity.issuer());
            statement.setString(2, identity.subject());
            try (var result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(UUID.fromString(result.getString("actor_id")))
                        : Optional.empty();
            }
        }
    }
}
