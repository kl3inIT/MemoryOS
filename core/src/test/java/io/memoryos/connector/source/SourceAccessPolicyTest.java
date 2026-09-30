package io.memoryos.connector.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import org.junit.jupiter.api.Test;

class SourceAccessPolicyTest {
    @Test
    void defaultsFollowProviderPermissionsAndAuthority() {
        assertEquals(SourceAccess.PUBLIC, SourceAccessPolicy.access(false, true, null));
        assertEquals(SourceAccess.PRIVATE, SourceAccessPolicy.access(false, false, null));
        assertEquals(SourceAccess.SYNC, SourceAccessPolicy.access(true, true, null));
        assertEquals(SourceAccess.SYNC, SourceAccessPolicy.access(true, false, null));
    }

    @Test
    void autoSyncNeedsProviderPermissionsAndPublicNeedsGlobalAuthority() {
        assertThrows(SourceException.class, () -> SourceAccessPolicy.access(false, true, SourceAccess.SYNC));
        for (boolean permissionSync : new boolean[] {false, true}) {
            assertThrows(SourceException.class, () -> SourceAccessPolicy.access(permissionSync, false, SourceAccess.PUBLIC));
            assertEquals(SourceAccess.PRIVATE, SourceAccessPolicy.access(permissionSync, false, SourceAccess.PRIVATE));
            assertEquals(SourceAccess.PUBLIC, SourceAccessPolicy.access(permissionSync, true, SourceAccess.PUBLIC));
        }
        assertEquals(SourceAccess.SYNC, SourceAccessPolicy.access(true, false, SourceAccess.SYNC));
    }
}
