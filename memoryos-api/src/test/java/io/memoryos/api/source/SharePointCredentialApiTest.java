package io.memoryos.api.source;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointCredentialService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SharePointCredentialApiTest extends SourceProviderApiTest {
    private static SharePointCredentialService.CredentialView view(CredentialId id, String name) {
        return new SharePointCredentialService.CredentialView(id, name, UUID.randomUUID(), UUID.randomUUID(),
                "GLOBAL", "CLIENT_SECRET", "ACTIVE", null, null, "tenant.sharepoint.com", 1L,
                Instant.now(), Instant.now(), 0L, List.of("rename", "replace_authentication", "test", "delete"));
    }

    private static String secretBody(String name) {
        return """
                {"name":"%s","directoryId":"%s","clientId":"%s","cloud":"GLOBAL","authMethod":"CLIENT_SECRET","clientSecret":"secret"}
                """.formatted(name, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void listRequiresAuthenticationAndReturnsNoSecrets() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(sharePointCredentials.list(any())).thenReturn(List.of(view(id, "Entra app")));
        mockMvc.perform(get("/api/credentials/sharepoint").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.value().toString()))
                .andExpect(jsonPath("$[0].name").value("Entra app"))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$[0].certificate").doesNotExist());
        mockMvc.perform(get("/api/credentials/sharepoint"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturnsCreated() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(sharePointCredentials.create(any(), any())).thenReturn(id);
        when(sharePointCredentials.list(any())).thenReturn(List.of(view(id, "Entra app")));
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Entra app")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.value().toString()));
    }

    @Test
    void createRejectsInvalidSecretAndDirectory() throws Exception {
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"directoryId\":\"not-a-guid\",\"clientId\":\"%s\",\"cloud\":\"GLOBAL\",\"authMethod\":\"CLIENT_SECRET\",\"clientSecret\":\"secret\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_SHAREPOINT_DIRECTORY_INVALID"));
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"directoryId\":\"%s\",\"clientId\":\"%s\",\"cloud\":\"GLOBAL\",\"authMethod\":\"CLIENT_SECRET\"}"
                                .formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_SHAREPOINT_SECRET_INVALID"));
    }

    @Test
    void replaceAuthenticationRequiresIfMatch() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(sharePointCredentials.list(any())).thenReturn(List.of(view(id, "Entra app")));
        mockMvc.perform(put("/api/credentials/sharepoint/{id}/authentication", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Entra app")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/credentials/sharepoint/{id}/authentication", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Entra app")))
                .andExpect(status().isOk());
        verify(sharePointCredentials).replaceAuthentication(any(), eq(id), eq(1L), any());
    }

    @Test
    void renameAndDeleteCarryTheRevision() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        mockMvc.perform(put("/api/credentials/sharepoint/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isNoContent());
        verify(sharePointCredentials).rename(any(), eq(id), eq(1L), eq("Renamed"));
        mockMvc.perform(delete("/api/credentials/sharepoint/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isNoContent());
        verify(sharePointCredentials).delete(any(), eq(id), eq(1L));
    }

    @Test
    void testEndpointReturnsResult() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(sharePointCredentials.test(any(), eq(id))).thenReturn(new SharePointCredentialService.TestResult(true, "tenant.sharepoint.com"));
        mockMvc.perform(post("/api/credentials/sharepoint/{id}/test", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allSitesReadable").value(true))
                .andExpect(jsonPath("$.tenantHost").value("tenant.sharepoint.com"));
    }
}
