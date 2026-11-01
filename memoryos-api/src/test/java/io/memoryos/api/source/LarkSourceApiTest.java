package io.memoryos.api.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkSourceService.Configuration;
import io.memoryos.connector.LarkSourceService.Root;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.memoryos.connector.LarkSourceService.Scope;
import io.memoryos.connector.LarkSourceService.SelectionReceipt;
import io.memoryos.connector.LarkSourceService.SpaceOption;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationStatus;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class LarkSourceApiTest extends SourceProviderApiTest {
    private static final UUID SOURCE = UUID.fromString("7f3d2c1a-0b9e-4d8c-a7f6-5e4d3c2b1a09");
    private static final UUID CREDENTIAL = UUID.fromString("2a1b0c9d-8e7f-4a6b-9c5d-4e3f2a1b0c9d");

    private static SourceOperationView operation(SourceOperationType type) {
        return new SourceOperationView(new SourceOperationId(UUID.randomUUID()), type,
                SourceOperationStatus.NOT_STARTED, Instant.now(), null, null);
    }

    private static SelectionReceipt receipt() {
        return new SelectionReceipt(new SourceId(SOURCE), operation(SourceOperationType.VALIDATE_LARK_SELECTION));
    }

    private static Configuration configuration() {
        return new Configuration(new SourceId(SOURCE), new CredentialId(CREDENTIAL), "Lark app", "ACTIVE", 2L, 4L, 2L,
                30, 7L, false, null, false, null, null);
    }

    private static String scope() {
        return """
                {"roots":[{"kind":"WIKI_SPACE","value":"7695460898437074458"},
                  {"kind":"DRIVE_FOLDER","value":"https://tenant.larksuite.com/drive/folder/fldDriveTest01"}],
                 "syncIntervalMinutes":30}""";
    }

    private static String createBody() {
        return """
                {"requestId":"%s","name":"Lark","credentialId":"%s","scope":%s,"access":"PRIVATE"}
                """.formatted(UUID.randomUUID(), CREDENTIAL, scope());
    }

    @Test
    void creatingASourceAcceptsTheScopeForVerification() throws Exception {
        var passed = new Scope[1];
        when(larkSources.create(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            passed[0] = call.getArgument(4);
            assertEquals(SourceAccess.PRIVATE, call.getArgument(5));
            return receipt();
        });

        mockMvc.perform(post("/api/sources/lark")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()))
                .andExpect(jsonPath("$.operation.type").value("VALIDATE_LARK_SELECTION"));
        assertEquals(new Scope(List.of(new Root(RootKind.WIKI_SPACE, "7695460898437074458"),
                new Root(RootKind.DRIVE_FOLDER, "https://tenant.larksuite.com/drive/folder/fldDriveTest01")), 30),
                passed[0]);
    }

    @Test
    void aMalformedScopeIsRefusedBeforeTheService() throws Exception {
        for (String scope : List.of(
                "{\"roots\":[],\"syncIntervalMinutes\":30}",
                "{\"roots\":[{\"kind\":\"SITE\",\"value\":\"x\"}],\"syncIntervalMinutes\":30}",
                "{\"roots\":[{\"kind\":\"WIKI_SPACE\",\"value\":\"\"}],\"syncIntervalMinutes\":30}",
                "{\"roots\":[{\"kind\":\"WIKI_SPACE\",\"value\":\"7695460898437074458\"}],\"syncIntervalMinutes\":0}")) {
            mockMvc.perform(post("/api/sources/lark")
                            .with(authentication(owner))
                            .header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"%s\",\"name\":\"Lark\",\"credentialId\":\"%s\",\"scope\":%s,\"access\":\"PUBLIC\"}"
                                    .formatted(UUID.randomUUID(), CREDENTIAL, scope)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(larkSources);
    }

    @Test
    void replacingTheScopeNeedsTheRevision() throws Exception {
        when(larkSources.replaceScope(any(), any(), any(), anyLong(), anyLong(), any())).thenReturn(receipt());
        String body = "{\"requestId\":\"%s\",\"expectedCredentialRevision\":2,\"scope\":%s}"
                .formatted(UUID.randomUUID(), scope());

        mockMvc.perform(put("/api/sources/{id}/lark/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/sources/{id}/lark/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()));
        verify(larkSources).replaceScope(any(), any(), eq(new SourceId(SOURCE)), eq(3L), eq(2L), any());
    }

    @Test
    void configurationRootsSpacesAndReceiptAreReadable() throws Exception {
        when(larkSources.configuration(any(), any())).thenReturn(configuration());
        when(larkSources.roots(any(), any())).thenReturn(List.of(
                new RootView(RootKind.WIKI_SPACE, "7695460898437074458", "MemoryOS Test"),
                new RootView(RootKind.DRIVE_FOLDER, "fldDriveTest01", null)));
        when(larkSources.spaces(any(), eq(new CredentialId(CREDENTIAL)))).thenReturn(List.of(
                new SpaceOption("7695460898437074458", "MemoryOS Test")));
        when(larkSources.selectionRequest(any(), any())).thenReturn(receipt());

        mockMvc.perform(get("/api/sources/{id}/lark", SOURCE).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.rootCount").value(2))
                .andExpect(jsonPath("$.syncIntervalMinutes").value(30))
                .andExpect(jsonPath("$.credentialName").value("Lark app"));
        mockMvc.perform(get("/api/sources/{id}/lark/roots", SOURCE).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("WIKI_SPACE"))
                .andExpect(jsonPath("$[0].displayName").value("MemoryOS Test"))
                .andExpect(jsonPath("$[1].token").value("fldDriveTest01"));
        mockMvc.perform(get("/api/sources/lark/credentials/{id}/spaces", CREDENTIAL).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].spaceId").value("7695460898437074458"))
                .andExpect(jsonPath("$[0].name").value("MemoryOS Test"));
        mockMvc.perform(get("/api/sources/lark/selection-requests/{id}", UUID.randomUUID()).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()));
        mockMvc.perform(get("/api/sources/{id}/lark", SOURCE)).andExpect(status().isUnauthorized());
    }

    @Test
    void scheduleAndPauseCarryTheScheduleRevision() throws Exception {
        when(larkSources.updateSchedule(any(), any(), anyLong(), anyInt())).thenReturn(configuration());
        when(larkSources.setPaused(any(), any(), anyLong(), anyBoolean())).thenReturn(configuration());

        mockMvc.perform(put("/api/sources/{id}/lark/schedule", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncIntervalMinutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""));
        verify(larkSources).updateSchedule(any(), any(), eq(7L), eq(60));
        mockMvc.perform(post("/api/sources/{id}/lark/pause", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7,\"paused\":true}"))
                .andExpect(status().isOk());
        verify(larkSources).setPaused(any(), any(), eq(7L), eq(true));
    }

    @Test
    void synchronizeSchedulesARun() throws Exception {
        when(larkSources.synchronize(any(), any())).thenReturn(operation(SourceOperationType.SYNC_SOURCE));
        mockMvc.perform(post("/api/sources/{id}/lark/sync", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("SYNC_SOURCE"));
    }
}
