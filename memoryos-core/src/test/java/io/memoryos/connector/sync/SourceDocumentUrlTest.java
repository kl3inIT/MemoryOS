package io.memoryos.connector.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import io.memoryos.connector.SourceType;
import io.memoryos.connector.googledrive.GoogleDriveConnectionService;
import io.memoryos.connector.googledrive.GoogleDriveSyncAdapter;
import io.memoryos.connector.googledrive.GoogleGroupSynchronizer;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveAclRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSourceRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSyncRepository;
import io.memoryos.connector.lark.LarkConnectionService;
import io.memoryos.connector.lark.LarkDirectorySynchronizer;
import io.memoryos.connector.lark.LarkSyncAdapter;
import io.memoryos.connector.lark.persistence.JdbcLarkAclRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSourceRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSyncRepository;
import io.memoryos.connector.sharepoint.SharePointConnectionService;
import io.memoryos.connector.sharepoint.SharePointGroupSynchronizer;
import io.memoryos.connector.sharepoint.SharePointSyncAdapter;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointAclRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceDocumentUrlTest {
    private final SourceSyncAdapterRegistry adapters = new SourceSyncAdapterRegistry(List.of(
            new GoogleDriveSyncAdapter(mock(JdbcGoogleDriveSyncRepository.class), mock(JdbcGoogleDriveSourceRepository.class),
                    mock(JdbcGoogleDriveAclRepository.class), mock(GoogleDriveConnectionService.class),
                    mock(GoogleGroupSynchronizer.class)),
            new SharePointSyncAdapter(mock(JdbcSharePointSyncRepository.class), mock(JdbcSharePointSourceRepository.class),
                    mock(JdbcSourceSyncRepository.class), mock(SharePointConnectionService.class),
                    mock(JdbcSharePointAclRepository.class), mock(SharePointGroupSynchronizer.class)),
            new LarkSyncAdapter(mock(JdbcLarkSyncRepository.class), mock(JdbcLarkSourceRepository.class),
                    mock(LarkConnectionService.class), mock(JdbcLarkAclRepository.class),
                    mock(LarkDirectorySynchronizer.class))));

    @Test
    void googleDriveOpensItsFileIdWhateverAddressWasRecorded() {
        assertEquals("https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp", adapters.documentUrl(SourceType.GOOGLE_DRIVE,
                "1AbCdEfGhIjKlMnOp", "https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view"));
        assertNull(adapters.documentUrl(SourceType.GOOGLE_DRIVE, "../etc", null));
        assertNull(adapters.documentUrl(SourceType.GOOGLE_DRIVE, null, "https://drive.google.com/file/d/x/view"));
    }

    @Test
    void sharePointOpensTheAddressGraphReturnedOnlyOnASharePointHost() {
        String file = "https://contoso.sharepoint.com/sites/HR/Shared%20Documents/Leave.docx";
        String page = "https://Contoso.SharePoint.com/sites/HR/SitePages/Welcome.aspx";
        assertEquals(file, adapters.documentUrl(SourceType.SHAREPOINT, "01ABC", file));
        assertEquals(page, adapters.documentUrl(SourceType.SHAREPOINT, "page-1", page));
        for (String rejected : List.of("http://contoso.sharepoint.com/a.docx", "https://sharepoint.com.evil.test/a.docx",
                "https://user@contoso.sharepoint.com/a.docx", "https://contoso.sharepoint.com/a b.docx")) {
            assertNull(adapters.documentUrl(SourceType.SHAREPOINT, "01ABC", rejected), rejected);
        }
        assertNull(adapters.documentUrl(SourceType.SHAREPOINT, "01ABC", null));
    }

    @Test
    void larkOpensTheAddressLarkReturnedOnlyOnALarkHost() {
        String page = "https://tenant.jp.larksuite.com/wiki/NodeToken";
        String feishu = "https://tenant.feishu.cn/docx/DocToken";
        assertEquals(page, adapters.documentUrl(SourceType.LARK, "DocToken", page));
        assertEquals(feishu, adapters.documentUrl(SourceType.LARK, "DocToken", feishu));
        for (String rejected : List.of("http://tenant.larksuite.com/wiki/x", "https://larksuite.com.evil.test/wiki/x",
                "https://user@tenant.larksuite.com/wiki/x", "https://evil-larksuite.com/wiki/x",
                "https://tenant.larksuite.com/wiki/a b")) {
            assertNull(adapters.documentUrl(SourceType.LARK, "DocToken", rejected), rejected);
        }
        assertNull(adapters.documentUrl(SourceType.LARK, "DocToken", null));
    }

    @Test
    void anUploadedFileHasNoProviderLink() {
        assertNull(adapters.documentUrl(SourceType.FILE, "file", "https://files.test/handbook.pdf"));
    }
}
