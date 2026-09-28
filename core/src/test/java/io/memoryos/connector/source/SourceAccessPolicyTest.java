package io.memoryos.connector.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceType;
import org.junit.jupiter.api.Test;

class SourceAccessPolicyTest {
    @Test
    void defaultsFollowTheSourceTypeAndAuthority() {
        assertEquals(SourceAccess.PUBLIC, SourceAccessPolicy.access(SourceType.FILE, true, null));
        assertEquals(SourceAccess.PRIVATE, SourceAccessPolicy.access(SourceType.FILE, false, null));
        assertEquals(SourceAccess.SYNC, SourceAccessPolicy.access(SourceType.GOOGLE_DRIVE, true, null));
        assertEquals(SourceAccess.SYNC, SourceAccessPolicy.access(SourceType.GOOGLE_DRIVE, false, null));
    }

    @Test
    void autoSyncNeedsGoogleDriveAndPublicNeedsGlobalAuthority() {
        assertThrows(SourceException.class, () -> SourceAccessPolicy.access(SourceType.FILE, true, SourceAccess.SYNC));
        for (var type : SourceType.values()) {
            assertThrows(SourceException.class, () -> SourceAccessPolicy.access(type, false, SourceAccess.PUBLIC));
            assertEquals(SourceAccess.PRIVATE, SourceAccessPolicy.access(type, false, SourceAccess.PRIVATE));
            assertEquals(SourceAccess.PUBLIC, SourceAccessPolicy.access(type, true, SourceAccess.PUBLIC));
        }
        assertEquals(SourceAccess.SYNC, SourceAccessPolicy.access(SourceType.GOOGLE_DRIVE, false, SourceAccess.SYNC));
    }
}
