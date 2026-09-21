package io.memoryos.usage.report;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class UsageReportCsvTest {
    @ParameterizedTest
    @ValueSource(strings = {"=SUM(A1)", "+1", "-1", "@cmd", "\tx", "\rx"})
    void everyFormulaPrefixIsNeutralized(String value) {
        assertEquals("'" + value, UsageReportCsv.guard(value));
    }

    @Test
    void ordinaryTextAndEmptyValuesAreUntouched() {
        assertEquals("Trần Thu Hà", UsageReportCsv.guard("Trần Thu Hà"));
        assertEquals("gpt-5.1", UsageReportCsv.guard("gpt-5.1"));
        assertEquals("", UsageReportCsv.guard(""));
        assertNull(UsageReportCsv.guard(null));
    }
}
