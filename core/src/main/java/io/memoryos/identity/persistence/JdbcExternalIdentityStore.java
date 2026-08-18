package io.memoryos.identity.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityBindingConflictException;
import io.memoryos.identity.ExternalIdentityBindingProvisioner;
import io.memoryos.identity.ExternalIdentityResolver;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public final class JdbcExternalIdentityStore
        implements ExternalIdentityResolver, ExternalIdentityBindingProvisioner {

    private static final String INSERT_ACTOR = """
            INSERT INTO actors (id)
            VALUES (?)
            ON CONFLICT DO NOTHING
            """;
    private static final String INSERT_BINDING = """
            INSERT INTO external_identity_bindings (issuer, subject, actor_id)
            VALUES (?, ?, ?)
            ON CONFLICT DO NOTHING
            """;
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
            throw persistenceFailure("resolve external identity", exception);
        }
    }

    @Override
    public ProvisioningResult provision(ExternalIdentity identity, ActorId actorId) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertActor(connection, actorId.value());
                var inserted = insertBinding(connection, identity, actorId.value());
                var storedActorId = findActorId(connection, identity).orElseThrow(
                        () -> new IllegalStateException("external identity binding did not persist"));
                if (!storedActorId.equals(actorId.value())) {
                    throw new ExternalIdentityBindingConflictException();
                }
                connection.commit();
                return inserted ? ProvisioningResult.CREATED : ProvisioningResult.UNCHANGED;
            } catch (RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw persistenceFailure("provision external identity binding", exception);
            }
        } catch (SQLException exception) {
            throw persistenceFailure("open identity persistence transaction", exception);
        }
    }

    private static void insertActor(Connection connection, UUID actorId) throws SQLException {
        try (var statement = connection.prepareStatement(INSERT_ACTOR)) {
            statement.setObject(1, actorId);
            statement.executeUpdate();
        }
    }

    private static boolean insertBinding(
            Connection connection,
            ExternalIdentity identity,
            UUID actorId) throws SQLException {
        try (var statement = connection.prepareStatement(INSERT_BINDING)) {
            statement.setString(1, identity.issuer());
            statement.setString(2, identity.subject());
            statement.setObject(3, actorId);
            return statement.executeUpdate() == 1;
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

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static IllegalStateException persistenceFailure(String operation, SQLException cause) {
        return new IllegalStateException("Could not " + operation, cause);
    }
}
