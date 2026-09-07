package io.memoryos.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.memoryos.connector.SourceException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoogleDriveLinkTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://drive.google.com/drive/folders/file_123-abc?usp=sharing",
            "https://drive.google.com/drive/u/0/folders/file_123-abc",
            "https://drive.google.com/file/d/file_123-abc/view",
            "https://drive.google.com/open?id=file_123-abc",
            "https://docs.google.com/document/d/file_123-abc/edit#heading=h.123",
            "https://docs.google.com/spreadsheets/d/file_123-abc/edit?gid=0",
            "https://docs.google.com/presentation/d/file_123-abc/edit"
    })
    void resolvesSupportedGoogleLinkForms(String link) {
        assertThat(DefaultGoogleDriveSourceService.fileId(link)).isEqualTo("file_123-abc");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file_123-abc",
            "http://drive.google.com/drive/folders/file_123-abc",
            "https://drive.google.com.evil.test/drive/folders/file_123-abc",
            "https://owner@drive.google.com/drive/folders/file_123-abc",
            "https://drive.google.com:8443/drive/folders/file_123-abc",
            "https://drive.google.com/drive/u/0/my-drive",
            "https://drive.google.com/drive/folders/root",
            "https://drive.google.com/drive/folders/file%2Fother",
            "https://drive.google.com/open?id=first&id=second",
            "https://docs.google.com/document/d/e/published-id/pub"
    })
    void rejectsUntrustedAmbiguousAndAccountWideLinks(String link) {
        assertThatThrownBy(() -> DefaultGoogleDriveSourceService.fileId(link))
                .isInstanceOf(SourceException.class);
    }
}
