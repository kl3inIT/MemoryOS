package io.memoryos.mcp;

import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.McpClientGrant;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.mcp.persistence.JdbcMcpEndpointCallRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MEM-209: the MCP endpoint's activity log, as Glean keeps one for its MCP servers: one entry per tool call with the
 * person, the app they called through, the tool and how it ended, never the query or a document. Administrators read
 * it with {@code MCP_MANAGE}; {@link McpEndpointCallRetention} keeps 90 days of it. The audit trail records only the
 * administrators' changes.
 */
@Service
public class McpEndpointActivity {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpEndpointActivity.class);
    private static final int MAX_PAGE = 100;

    private final JdbcMcpEndpointCallRepository calls;
    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final TransactionTemplate separate;
    private final Clock clock = Clock.systemUTC();

    public McpEndpointActivity(JdbcMcpEndpointCallRepository calls, TenantAccessResolver tenants,
                               IamAuthorization authorization, PlatformTransactionManager transactions) {
        this.calls = calls;
        this.tenants = tenants;
        this.authorization = authorization;
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public enum Outcome { SUCCESS, REFUSED, FAILED, RATE_LIMITED }

    public record Call(UUID id, Instant occurredAt, UUID actorId, @Nullable String actorName, @Nullable String actorEmail,
                       String clientId, McpClientGrant.Client client, String clientName, String tool, Outcome outcome) {}

    public record Page(List<Call> calls, @Nullable String next) {}

    /** Every filter is optional; {@code person} matches part of the person's name or e-mail address. */
    public record Filter(@Nullable Instant from, @Nullable Instant to, McpClientGrant.@Nullable Client client,
                         @Nullable String tool, @Nullable Outcome outcome, @Nullable String person) {
        public Filter {
            person = person == null || person.isBlank() ? null : person.strip();
            if (person != null && person.length() > 200) throw McpException.invalid("Search text is too long.");
        }
    }

    public record Count(String key, String name, long calls, long people) {}

    public record Day(LocalDate day, long calls, long people) {}

    public record Insights(int days, long calls, long people, long failed, long rateLimited, List<Count> apps,
                           List<Count> tools, List<Day> daily) {}

    /**
     * Records one call in a transaction of its own; a failure is logged and swallowed, so the log can never fail or
     * slow a tool call beyond its own write.
     */
    public void record(ActorId actor, String clientId, String tool, Outcome outcome) {
        try {
            tenants.findActiveTenant(actor).ifPresent(tenant -> separate.executeWithoutResult(ignored -> calls.insert(
                    tenant, actor.value(), bounded(clientId, 512), McpClientGrant.Client.of(clientId).name(),
                    bounded(tool, 64), outcome.name(), clock.instant())));
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("event", "mcp_endpoint.activity.record_failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("MCP endpoint call not recorded");
        }
    }

    @Transactional(readOnly = true)
    public Page page(ActorId viewer, Filter filter, @Nullable String cursor, int size) {
        var tenant = authorization.require(viewer, IamCapability.MCP_MANAGE, false).tenantId();
        if (size < 1 || size > MAX_PAGE) throw McpException.invalid("Ask for 1 to 100 calls.");
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw McpException.invalid("The period must end after it starts.");
        }
        var rows = calls.page(tenant, new JdbcMcpEndpointCallRepository.Filter(filter.from(), filter.to(),
                        filter.client() == null ? null : filter.client().name(), filter.tool(),
                        filter.outcome() == null ? null : filter.outcome().name(), filter.person()),
                decode(cursor), size + 1);
        var page = new ArrayList<Call>(Math.min(rows.size(), size));
        for (var row : rows.subList(0, Math.min(rows.size(), size))) {
            page.add(new Call(row.id(), row.occurredAt(), row.actorId(), row.actorName(), row.actorEmail(),
                    row.clientId(), McpClientGrant.Client.valueOf(row.clientKind()),
                    McpClientGrant.Client.name(row.clientId()), row.tool(), Outcome.valueOf(row.outcome())));
        }
        String next = rows.size() > size ? encode(page.getLast()) : null;
        return new Page(page, next);
    }

    @Transactional(readOnly = true)
    public Insights insights(ActorId viewer, int days) {
        var tenant = authorization.require(viewer, IamCapability.MCP_MANAGE, false).tenantId();
        if (days != 7 && days != 30) throw McpException.invalid("Show 7 or 30 days.");
        Instant since = LocalDate.ofInstant(clock.instant(), clock.getZone()).minusDays(days - 1L)
                .atStartOfDay(clock.getZone()).toInstant();
        var totals = calls.totals(tenant, since);
        return new Insights(days, totals.calls(), totals.users(), totals.failed(), totals.rateLimited(),
                calls.byClient(tenant, since).stream().map(group -> new Count(group.key(),
                        McpClientGrant.Client.name(group.key()), group.calls(), group.users())).toList(),
                calls.byTool(tenant, since).stream().map(group -> new Count(group.key(), group.key(), group.calls(),
                        group.users())).toList(),
                calls.byDay(tenant, since).stream().map(day -> new Day(day.day(), day.calls(), day.users())).toList());
    }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String encode(Call call) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (call.occurredAt() + "|" + call.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static JdbcMcpEndpointCallRepository.@Nullable Cursor decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            return new JdbcMcpEndpointCallRepository.Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException invalid) {
            throw McpException.invalid("The page cursor is not one this list gave.");
        }
    }
}
