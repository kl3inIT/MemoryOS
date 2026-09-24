package io.memoryos.usage.report;

import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.usage.AiCostException;
import io.memoryos.usage.AiCostService;
import io.memoryos.usage.persistence.UsageReportRepository.Claim;
import io.memoryos.usage.persistence.UsageReportRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Usage reports (Onyx usage report export): a model manager requests one for a period; the Worker builds a ZIP of
 * {@code usage_by_user.csv}, {@code users.csv} and {@code usage_report.pdf} and stores it for every manager to download.
 */
@Service
public class UsageReportService {
    private static final Logger LOG = LoggerFactory.getLogger(UsageReportService.class);
    public static final int MAX_ATTEMPTS = 3;
    public static final int LIST_LIMIT = 50;
    static final Duration LEASE = Duration.ofMinutes(10);
    static final String MEDIA_TYPE = "application/zip";

    private final IamAuthorization authorization;
    private final UsageReportRepository reports;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public UsageReportService(IamAuthorization authorization, UsageReportRepository reports, ObjectWriteService writes,
                              ObjectStorage storage, PlatformTransactionManager transactionManager) {
        this.authorization = authorization; this.reports = reports; this.writes = writes; this.storage = storage;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Download(ObjectContent content, String filename) {}

    /** Queues a report for a UTC day range, bounded like the AI costs page. */
    @Transactional
    public UsageReport request(ActorId reader, LocalDate from, LocalDate to) {
        var tenant = manager(reader);
        new AiCostService.Range(from, to, null, false, null, null);
        return reports.insert(tenant, UUID.randomUUID(), reader.value(), from, to);
    }

    /** The Tenant's reports, newest first, whoever requested them. */
    @Transactional(readOnly = true)
    public List<UsageReport> list(ActorId reader) {
        return reports.list(manager(reader), LIST_LIMIT);
    }

    @Transactional(readOnly = true)
    public Download open(ActorId reader, UUID id) {
        var report = reports.find(manager(reader), id)
                .filter(found -> found.status() == UsageReportStatus.READY && found.objectKey() != null)
                .orElseThrow(AiCostException::reportNotFound);
        return new Download(storage.open(new ObjectKey(report.objectKey())), filename(report.from(), report.to()));
    }

    /**
     * Builds the oldest waiting report, if any; the Worker calls this on a fixed delay. Returns whether one was
     * claimed, so a caller may drain a queue.
     */
    public boolean buildNext() {
        int abandoned = reports.failAbandoned(MAX_ATTEMPTS);
        if (abandoned > 0) LOG.warn("Usage reports failed after {} attempts: {}", MAX_ATTEMPTS, abandoned);
        var claimed = reports.claim(LEASE, MAX_ATTEMPTS);
        if (claimed.isEmpty()) return false;
        var claim = claimed.get();
        try {
            build(claim);
        } catch (RuntimeException e) {
            LOG.error("Usage report {} failed on attempt {}", claim.id(), claim.attempts(), e);
            reports.markFailed(claim.tenant(), claim.id(), claim.attempts(), MAX_ATTEMPTS, "The report could not be generated.");
        }
        return true;
    }

    private void build(Claim claim) {
        var archive = archive(claim);
        var tenant = new TenantId(claim.tenant());
        var staged = writes.stage(tenant, new ObjectWriteService.Specification(filename(claim.from(), claim.to()), MEDIA_TYPE, false),
                archive.bytes());
        boolean adopted = false;
        try {
            adopted = Boolean.TRUE.equals(tx.execute(ignored -> {
                writes.adopt(tenant, staged);
                if (reports.markReady(claim.tenant(), claim.id(), claim.attempts(), staged.object().id().value(),
                        staged.object().key().value(), archive.bytes().length, archive.hasPdf())) return true;
                // Another Worker took over after this lease lapsed; it owns the outcome.
                throw new LeaseLost();
            }));
        } catch (LeaseLost ignored) {
            LOG.warn("Usage report {} lease lapsed before it was stored", claim.id());
        } finally {
            if (!adopted) writes.discard(tenant, staged);
        }
    }

    record Archive(byte[] bytes, boolean hasPdf) {}

    /** The CSV and the PDF are built from the same rows, so their totals reconcile (Onyx). */
    Archive archive(Claim claim) {
        var builder = UsageReportData.builder(reports.tenantName(claim.tenant()), claim.from(), claim.to());
        var active = new HashSet<UUID>();
        var out = new ByteArrayOutputStream();
        boolean hasPdf = false;
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("usage_by_user.csv"));
            var usage = UsageReportCsv.usage(zip);
            reports.exportRows(claim.tenant(), claim.from(), claim.to(), row -> {
                usage.add(row);
                builder.add(row);
                if (row.actor() != null) active.add(row.actor());
            });
            usage.flush();
            zip.closeEntry();

            var members = reports.members(claim.tenant());
            zip.putNextEntry(new ZipEntry("users.csv"));
            UsageReportCsv.users(zip, members, active);
            zip.closeEntry();

            // A render failure must not cost the manager their CSV export (Onyx).
            byte[] pdf = null;
            try {
                pdf = UsageReportPdf.render(builder.build(members));
            } catch (RuntimeException e) {
                LOG.error("Usage report {} PDF could not be rendered; the ZIP ships without it", claim.id(), e);
            }
            if (pdf != null) {
                zip.putNextEntry(new ZipEntry("usage_report.pdf"));
                zip.write(pdf);
                zip.closeEntry();
                hasPdf = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Archive(out.toByteArray(), hasPdf);
    }

    static String filename(LocalDate from, LocalDate to) {
        return "usage-report_" + from + "_" + to + ".zip";
    }

    private java.util.UUID manager(ActorId reader) {
        return authorization.require(reader, IamCapability.MODELS_MANAGE, false).tenantId().value();
    }

    private static final class LeaseLost extends RuntimeException {
        LeaseLost() { super(null, null, false, false); }
    }
}
