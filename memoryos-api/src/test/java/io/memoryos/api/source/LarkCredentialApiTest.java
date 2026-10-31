package io.memoryos.api.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkCredentialService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class LarkCredentialApiTest extends SourceProviderApiTest {
    private static LarkCredentialService.CredentialView view(CredentialId id, String name) {
        return new LarkCredentialService.CredentialView(id, name, "LARK", "cli_a1b2c3d4e5f6a7b8", "ACTIVE",
                "MemoryOS Test", List.of("im:chat:readonly"), 1L, Instant.now(), Instant.now(), 0L,
                List.of("rename", "replace_secret", "test", "delete"));
    }

    private static String body(String name) {
        return """
                {"name":"%s","region":"LARK","appId":"cli_a1b2c3d4e5f6a7b8","appSecret":"app-secret-value"}
                """.formatted(name);
    }

    @Test
    void listRequiresAuthenticationAndReturnsNoSecret() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(larkCredentials.list(any())).thenReturn(List.of(view(id, "Lark app")));
        mockMvc.perform(get("/api/credentials/lark").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.value().toString()))
                .andExpect(jsonPath("$[0].name").value("Lark app"))
                .andExpect(jsonPath("$[0].appId").value("cli_a1b2c3d4e5f6a7b8"))
                .andExpect(jsonPath("$[0].botName").value("MemoryOS Test"))
                .andExpect(jsonPath("$[0].missingScopes[0]").value("im:chat:readonly"))
                .andExpect(jsonPath("$[0].appSecret").doesNotExist());
        mockMvc.perform(get("/api/credentials/lark"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createPassesTheSecretOnAndReturnsCreated() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        var secret = new byte[1][];
        when(larkCredentials.create(any(), any())).thenAnswer(call -> {
            LarkCredentialService.Draft draft = call.getArgument(1);
            secret[0] = draft.appSecret();
            assertEquals("cli_a1b2c3d4e5f6a7b8", draft.appId());
            return id;
        });
        when(larkCredentials.list(any())).thenReturn(List.of(view(id, "Lark app")));
        mockMvc.perform(post("/api/credentials/lark")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Lark app")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.appSecret").doesNotExist());
        assertArrayEquals("app-secret-value".getBytes(StandardCharsets.UTF_8), secret[0]);
    }

    @Test
    void createRejectsAMalformedBodyBeforeTheService() throws Exception {
        for (String malformed : List.of(
                "{\"name\":\"Bad\",\"region\":\"LARK\",\"appId\":\"cli_a1b2c3d4e5f6a7b8\"}",
                "{\"name\":\"Bad\",\"region\":\"MARS\",\"appId\":\"cli_a1b2c3d4e5f6a7b8\",\"appSecret\":\"s\"}",
                "{\"name\":\"\",\"region\":\"LARK\",\"appId\":\"cli_a1b2c3d4e5f6a7b8\",\"appSecret\":\"s\"}")) {
            mockMvc.perform(post("/api/credentials/lark")
                            .with(authentication(owner))
                            .header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformed))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(larkCredentials);
    }

    @Test
    void replaceSecretRequiresIfMatch() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(larkCredentials.list(any())).thenReturn(List.of(view(id, "Lark app")));
        mockMvc.perform(put("/api/credentials/lark/{id}/secret", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Lark app")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/credentials/lark/{id}/secret", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Lark app")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.value().toString()));
        verify(larkCredentials).replaceSecret(any(), eq(id), eq(1L), any());
    }

    @Test
    void renameAndDeleteCarryTheRevision() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        mockMvc.perform(put("/api/credentials/lark/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isNoContent());
        verify(larkCredentials).rename(any(), eq(id), eq(1L), eq("Renamed"));
        mockMvc.perform(delete("/api/credentials/lark/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isNoContent());
        verify(larkCredentials).delete(any(), eq(id), eq(1L));
    }

    @Test
    void theTestEndpointReturnsTheBotAndTheMissingScopesSorted() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(larkCredentials.test(any(), eq(id))).thenReturn(new LarkCredentialService.TestResult("MemoryOS Test",
                Set.of("wiki:wiki:readonly", "drive:drive:readonly")));
        mockMvc.perform(post("/api/credentials/lark/{id}/test", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botName").value("MemoryOS Test"))
                .andExpect(jsonPath("$.missingScopes[0]").value("drive:drive:readonly"))
                .andExpect(jsonPath("$.missingScopes[1]").value("wiki:wiki:readonly"));
    }
}
