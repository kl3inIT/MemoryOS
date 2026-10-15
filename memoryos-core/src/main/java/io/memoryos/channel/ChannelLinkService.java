package io.memoryos.channel;

import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.channel.persistence.JdbcChannelLinkRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A member's account on a platform that names no e-mail, such as Zalo, linked once to their MemoryOS account
 * (MEM-263). The member makes a code in MemoryOS and sends it to the bot; the platform account that sends a live code
 * is linked to the member who made it. A code made in a signed-in session can only be misused by its owner handing
 * it over, where a link or code started on the platform could be forwarded to a colleague who confirms it.
 */
@Service
public class ChannelLinkService {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelLinkService.class);
    /** No 0, O, 1 or I, which read alike: 32 characters, so eight give 40 bits. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 8;
    static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    /** Wrong codes a sender may send in an hour before the bot stops hearing them for the rest of it. */
    static final int WRONG_CODES = 5;
    static final Duration WRONG_CODE_WINDOW = Duration.ofHours(1);
    /** How often one sender is told how to link. */
    static final Duration NOTICE_INTERVAL = Duration.ofMinutes(1);
    /** The reasons a message is settled with, by which wrong codes and notices are counted. */
    static final String WRONG_CODE = "LINK_CODE_INVALID";
    static final String LINK_REQUIRED = "LINK_REQUIRED";
    private static final Pattern CODE_SHAPE = Pattern.compile("[" + ALPHABET + "]{" + CODE_LENGTH + "}");

    private final IamAuthorization authorization;
    private final JdbcChannelConnectionRepository connections;
    private final JdbcChannelLinkRepository links;
    private final ChannelAdapters adapters;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public ChannelLinkService(IamAuthorization authorization, JdbcChannelConnectionRepository connections,
                              JdbcChannelLinkRepository links, ChannelAdapters adapters,
                              PlatformTransactionManager transactionManager) {
        this.authorization = authorization;
        this.connections = connections;
        this.links = links;
        this.adapters = adapters;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A new code for the member to send the bot, replacing the one they made before. */
    public ChannelLinkCode code(ActorId actor, UUID connectionId) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        var code = newCode();
        var expiresAt = clock.instant().plus(CODE_LIFETIME);
        transactions.executeWithoutResult(_ -> {
            var tenant = member(actor);
            linkable(tenant, connectionId);
            links.saveCode(tenant, connectionId, actor, hash(code), expiresAt);
        });
        return new ChannelLinkCode(code, expiresAt);
    }

    /** The Tenant's bots that take a linked account, with when the member linked theirs on each. */
    public List<ChannelLink> links(ActorId actor) {
        return Objects.requireNonNull(transactions.execute(_ -> {
            var tenant = member(actor);
            var linked = links.linksOf(tenant, actor);
            return connections.list(tenant).stream().filter(connection -> adapters.takesLinks(connection.provider()))
                    .map(connection -> new ChannelLink(connection.id(), connection.name(), connection.provider(),
                            linked.get(connection.id())))
                    .toList();
        }));
    }

    /** Removes the member's link on the bot, and any code they made for it; nothing when there is none. */
    public void unlink(ActorId actor, UUID connectionId) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        transactions.executeWithoutResult(_ -> {
            var tenant = member(actor);
            linkable(tenant, connectionId);
            links.unlink(tenant, connectionId, actor);
        });
    }

    /** The active member the platform user linked on this bot. */
    Optional<ActorId> linked(UUID connection, String externalUserId) {
        return links.linkedActor(connection, externalUserId);
    }

    /** What sending a code did. */
    enum Redemption {
        LINKED,
        /** No live code of this bot is the one sent: wrong, used up, expired or another bot's alike. */
        NOT_LIVE,
        /** The sender's account is linked to another member, who unlinks it first; the code stays live. */
        ALREADY_LINKED
    }

    /**
     * Links the sender of a live code to the member who made it. An account already linked to another member is not
     * moved: the code is put back and stays its member's, so nobody's account changes hands without its member
     * unlinking it.
     */
    Redemption redeem(UUID connection, String externalUserId, String code) {
        var now = clock.instant();
        var redemption = Objects.requireNonNull(transactions.execute(status -> {
            var found = links.redeem(connection, hash(code), now).orElse(null);
            if (found == null) return Redemption.NOT_LIVE;
            if (links.link(found.tenant(), connection, externalUserId, found.actor(), now)) return Redemption.LINKED;
            status.setRollbackOnly();
            return Redemption.ALREADY_LINKED;
        }));
        if (redemption == Redemption.LINKED) {
            LOG.atInfo().addKeyValue("event", "channel.link.created").addKeyValue("connection_id", connection)
                    .log("Channel account linked");
        }
        return redemption;
    }

    /**
     * The sender's codes within the hour that did not link: the wrong ones and those still being tried, which are
     * recorded as wrong until they link.
     */
    int attempts(UUID connection, String externalUserId) {
        return links.settledWith(connection, externalUserId, WRONG_CODE, clock.instant().minus(WRONG_CODE_WINDOW));
    }

    /** Whether the sender was not told how to link within the last minute. */
    boolean noticeDue(UUID connection, String externalUserId) {
        return links.settledWith(connection, externalUserId, LINK_REQUIRED, clock.instant().minus(NOTICE_INTERVAL)) == 0;
    }

    /**
     * The code a message carries, alone on it, upper-cased and with hyphens dropped; empty for any other text. A blank
     * inside is kept, so two words are never read as a code.
     */
    static Optional<String> codeIn(String text) {
        String candidate = text.strip().replace("-", "").toUpperCase(Locale.ROOT);
        return CODE_SHAPE.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    private String newCode() {
        var code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return code.toString();
    }

    private TenantId member(ActorId actor) {
        return authorization.require(actor, IamCapability.CHAT_WRITE, false).tenantId();
    }

    /** A bot of the Tenant whose members link an account; any other is not found to them. */
    private void linkable(TenantId tenant, UUID connectionId) {
        var connection = connections.find(tenant, connectionId).orElseThrow(ChannelException::notFound);
        if (!adapters.takesLinks(connection.provider())) throw ChannelException.notFound();
    }

    private static byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
