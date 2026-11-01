package io.memoryos.shared;

/**
 * The category of a file (IMAGE, SPREADSHEET, PRESENTATION, DOCUMENT or OTHER) as a SQL expression over its media
 * type and name. It is derived rather than stored, so it cannot drift from the file and a new type needs no
 * migration; every listing that filters by category uses this one rule.
 */
public final class FileCategorySql {
    private FileCategorySql() {}

    /** A {@code CASE} expression yielding the category name of the row's media type and filename columns. */
    public static String caseExpression(String mediaTypeColumn, String filenameColumn) {
        return """
                CASE
                    WHEN %1$s LIKE 'image/%%' THEN 'IMAGE'
                    WHEN %1$s IN ('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                                  'application/vnd.ms-excel','text/csv','text/tab-separated-values')
                         OR lower(%2$s) ~ '\\.(xlsx|xlsm|xls|csv|tsv)$' THEN 'SPREADSHEET'
                    WHEN %1$s IN ('application/vnd.openxmlformats-officedocument.presentationml.presentation',
                                  'application/vnd.ms-powerpoint')
                         OR lower(%2$s) ~ '\\.(pptx|ppt)$' THEN 'PRESENTATION'
                    WHEN %1$s IN ('application/pdf','application/msword','text/markdown','text/plain',
                                  'application/vnd.openxmlformats-officedocument.wordprocessingml.document')
                         OR lower(%2$s) ~ '\\.(pdf|docx|doc|md|txt|rtf|odt)$' THEN 'DOCUMENT'
                    ELSE 'OTHER'
                END""".formatted(mediaTypeColumn, filenameColumn);
    }
}
