package io.memoryos.usage;

import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.usage.persistence.AiCostQueries;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The "Chi phí AI" administration reads: model managers see the Tenant's AI usage and known costs. */
@Service
public class AiCostService {
    public static final int MAX_DAYS = 366;
    public static final int MAX_ROWS = 200;

    private final IamAuthorization authorization;
    private final AiCostQueries queries;

    public AiCostService(IamAuthorization authorization, AiCostQueries queries) {
        this.authorization = authorization;
        this.queries = queries;
    }

    /** Days are UTC and inclusive; {@code actor} narrows to one person, {@code system} to work without a person. */
    public record Range(LocalDate from, LocalDate to, @Nullable UUID actor, boolean system, @Nullable String model,
                        @Nullable AiUsageFlow flow) {
        public Range {
            if (from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS)
                throw AiCostException.invalid("Choose a period of at most 366 days.");
            if (actor != null && system) throw AiCostException.invalid("Choose a person or system work, not both.");
            if (model != null && (model.isBlank() || model.length() > 200)) throw AiCostException.invalid("Invalid model filter.");
        }
    }

    public record Detail(AiCostTotals totals, List<AiCostDay> daily, List<AiCostRow> models,
                         List<AiCostRow> flows, List<AiCostRow> providers) {}

    @Transactional(readOnly = true)
    public AiCostTotals summary(ActorId reader, Range range) {
        return queries.totals(scope(reader, range));
    }

    @Transactional(readOnly = true)
    public List<AiCostDay> daily(ActorId reader, Range range, AiCostSplit split) {
        return queries.daily(scope(reader, range), split);
    }

    @Transactional(readOnly = true)
    public List<AiCostRow> breakdown(ActorId reader, Range range, AiCostDimension dimension, int limit) {
        if (limit < 1 || limit > MAX_ROWS) throw AiCostException.invalid("Row limit must be between 1 and 200.");
        return queries.breakdown(scope(reader, range), dimension, limit);
    }

    /** One person's, or system work's, costs as Onyx's per-user usage detail. */
    @Transactional(readOnly = true)
    public Detail detail(ActorId reader, Range range) {
        if (range.actor() == null && !range.system()) throw AiCostException.invalid("Choose a person or system work.");
        var scope = scope(reader, range);
        return new Detail(queries.totals(scope), queries.daily(scope, AiCostSplit.NONE),
                queries.breakdown(scope, AiCostDimension.MODEL, MAX_ROWS),
                queries.breakdown(scope, AiCostDimension.FLOW, MAX_ROWS),
                queries.breakdown(scope, AiCostDimension.PROVIDER, MAX_ROWS));
    }

    /**
     * The caller's own costs, as Onyx Settings › Usage ({@code GET /user/usage}): any Chat reader, and only their own
     * rows whatever the range names.
     */
    @Transactional(readOnly = true)
    public Detail mine(ActorId reader, LocalDate from, LocalDate to) {
        var tenant = authorization.require(reader, IamCapability.CHAT_READ, false).tenantId().value();
        var range = new Range(from, to, reader.value(), false, null, null);
        var scope = new AiCostQueries.Scope(tenant, range.from(), range.to(), reader.value(), null, null, false);
        return new Detail(queries.totals(scope), queries.daily(scope, AiCostSplit.NONE),
                queries.breakdown(scope, AiCostDimension.MODEL, MAX_ROWS),
                queries.breakdown(scope, AiCostDimension.FLOW, MAX_ROWS),
                queries.breakdown(scope, AiCostDimension.PROVIDER, MAX_ROWS));
    }

    private AiCostQueries.Scope scope(ActorId reader, Range range) {
        var tenant = authorization.require(reader, IamCapability.MODELS_MANAGE, false).tenantId().value();
        return new AiCostQueries.Scope(tenant, range.from(), range.to(), range.actor(), range.model(), range.flow(), range.system());
    }
}
