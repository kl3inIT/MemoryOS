package io.memoryos.usage.report;

import io.memoryos.usage.persistence.UsageReportRepository.ExportRow;
import io.memoryos.usage.persistence.UsageReportRepository.Member;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.jspecify.annotations.Nullable;

/** The report's CSV files, with Onyx's column order where a column exists in both. */
final class UsageReportCsv {
    static final String[] USAGE_HEADER = {"user_id", "user_email", "user_name", "groups", "day", "model", "flow", "provider",
            "data_boundary", "calls", "unpriced_calls", "input_tokens", "output_tokens", "cache_read_tokens", "image_count",
            "audio_seconds", "cost_usd"};
    static final String[] USERS_HEADER = {"user_id", "user_email", "user_name", "groups", "is_active", "used_in_period"};

    /** Characters that spreadsheets read as the start of a formula (Onyx {@code _FORMULA_PREFIX_CHARS}). */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private final CSVPrinter printer;

    private UsageReportCsv(OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        // A byte-order mark, so Excel opens Vietnamese names as UTF-8 instead of the system code page.
        writer.write('﻿');
        printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
    }

    static UsageReportCsv usage(OutputStream out) {
        return open(out, USAGE_HEADER);
    }

    static void users(OutputStream out, Collection<Member> members, Set<?> activeActors) {
        var csv = open(out, USERS_HEADER);
        for (var member : members)
            csv.print(member.actor(), guard(member.email()), guard(member.name()), guard(String.join("; ", member.groups())),
                    "ACTIVE".equals(member.status()), activeActors.contains(member.actor()));
        csv.flush();
    }

    void add(ExportRow row) {
        print(row.actor(), guard(row.email()), row.actor() == null ? "system" : guard(row.name()),
                guard(String.join("; ", row.groups())), row.day(), guard(row.model()), row.flow(), guard(row.provider()),
                row.boundary(), row.calls(), row.unknownCostCalls(), row.inputTokens(), row.outputTokens(),
                row.cacheReadTokens(), row.imageCount(), row.audioSeconds().toPlainString(), row.cost().toPlainString());
    }

    /** Leaves the underlying stream open: the report's ZIP writes the next entry to it. */
    void flush() {
        try { printer.flush(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** User-controlled text only: numbers, dates and enum values are written as they are. */
    static @Nullable String guard(@Nullable String value) {
        if (value == null || value.isEmpty()) return value;
        return FORMULA_PREFIXES.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static UsageReportCsv open(OutputStream out, String[] header) {
        try {
            var csv = new UsageReportCsv(out);
            csv.printer.printRecord((Object[]) header);
            return csv;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void print(Object... values) {
        try { printer.printRecord(values); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
