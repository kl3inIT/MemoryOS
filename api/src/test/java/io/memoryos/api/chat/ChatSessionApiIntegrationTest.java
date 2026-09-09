package io.memoryos.api.chat;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.memoryos.api.ApiPostgresDatabase;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.core.util.Json;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.PricingModel;
import com.embabel.chat.UserMessage;
import io.memoryos.chat.execution.ChatModelBinding;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.iam.TenantId;
import org.springframework.ai.chat.prompt.ChatOptions;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "memoryos.chat.provider.api-key=test-only-model-is-mocked",
        "memoryos.chat.stream.heartbeat=100ms",
        "springdoc.api-docs.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.owner-subject=chat-owner",
        "memoryos.initial-tenant.slug=chat",
        "memoryos.initial-tenant.display-name=Chat",
        "memoryos.initial-tenant.change-reference=MEM-11-TEST",
})
@AutoConfigureMockMvc
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatSessionApiIntegrationTest {
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER = "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000024");
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ChatExecutionProperties limits;
    @Autowired
    private StreamBufferWriter streams;
    @Autowired
    private ChatModelExecutor executor;
    @LocalServerPort
    private int port;
    @MockitoBean(name = "chatProviderModel")
    private ChatModel model;
    private ActorAuthenticationToken actor;
    private ActorAuthenticationToken other;

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        ApiPostgresDatabase.configure(registry);
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> BROWSER_ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> BROWSER_ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> BROWSER_ISSUER + "/jwks");
        registry.add("memoryos.identity.keycloak.admin.server-url", () -> "http://127.0.0.1:1");
        registry.add("memoryos.identity.keycloak.admin.client-secret", () -> "test-provisioner-secret");
        registry.add(
                "memoryos.identity.keycloak.admin.action-redirect-uri",
                () -> "http://127.0.0.1/invite/activate"
        );
    }

    @AfterAll
    static void stopIdentityServer() {
        IDENTITY_SERVER.stop(0);
    }

    @BeforeEach
    void actors() {
        actor = actor();
        other = actor();
    }

    @Test
    void createReloadListAndHistoryUseRealPersistenceAndOwnerAuthorization() throws Exception {
        var result = mockMvc.perform(post("/api/chat/sessions").with(authentication(actor)).with(csrf()).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Private session\"}"))
                .andExpect(status().isCreated()).andReturn();
        var session = Json.mapper().readTree(result.getResponse().getContentAsString());
        String id = session.path("id").asText();
        mockMvc.perform(get("/api/chat/sessions/" + id).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Private session"));
        mockMvc.perform(get("/api/chat/sessions/" + id + "/messages").with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/chat/sessions").with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/chat/sessions").with(authentication(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/chat/sessions/" + id).with(authentication(other)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT_UNAVAILABLE"));
        mockMvc.perform(get("/api/chat/sessions/" + id + "/messages").with(authentication(other)))
                .andExpect(status().isNotFound());
        jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor")
                .param("actor", actor.getPrincipal().actorId().value()).update();
        // Existing global membership filter rejects the request before the Chat controller.
        mockMvc.perform(get("/api/chat/sessions/" + id).with(authentication(actor)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingSessionCsrfInvalidInputsAndInvalidSend() throws Exception {
        mockMvc.perform(get("/api/chat/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/chat/sessions").with(authentication(actor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/chat/sessions").with(authentication(actor)).with(csrf()).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/chat/sessions?limit=100000").with(authentication(actor)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/chat/sessions/" + UUID.randomUUID() + "/messages")
                        .with(authentication(actor)).with(csrf()).header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM chat_message m JOIN chat_session s ON s.id = m.session_id WHERE m.status = 'RUNNING' AND s.owner_actor_id = :actor")
                .param("actor", actor.getPrincipal().actorId().value()).query(Long.class).single());
    }

    @Test
    void sendUsesNativeRunnerPersistsUsageAndRetriesWithoutAnotherModelCall() throws Exception {
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertTrue(prompt.getInstructions().stream().anyMatch(message -> "Question".equals(message.getText())));
            return Flux.just(response("Answer", "stop", 12));
        });
        var session = create();
        String request = UUID.randomUUID().toString();
        var reply = send(session, request);
        String id = reply.path("assistantMessageId").asText();
        awaitOutcome(id, "COMPLETED");
        assertEquals("Answer", history(session).get(1).path("content").asText());
        assertEquals(reply, send(session, request));
        verify(model, times(1)).stream(any(Prompt.class));
        assertEquals(12L, jdbc.sql("SELECT input_tokens FROM chat_message WHERE id = :id")
                .param("id", UUID.fromString(id)).query(Long.class).single());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM chat_message WHERE id = :id AND cost_usd IS NULL")
                .param("id", UUID.fromString(id)).query(Long.class).single());
    }

    @Test
    void eofPersistsPartialAsFailedAndAllowsNextTurn() throws Exception {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("Partial", "", 0)));
        var session = create();
        var reply = send(session, UUID.randomUUID().toString());
        awaitOutcome(reply.path("assistantMessageId").asText(), "FAILED");
        assertEquals("Partial", history(session).get(1).path("content").asText());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM chat_message WHERE session_id = :session AND status = 'RUNNING'")
                .param("session", UUID.fromString(session.path("id").asText())).query(Long.class).single());
    }

    @Test
    void ownerStopCancelsProviderAndKeepsPartialWhileOtherActorIsDenied() throws Exception {
        var streaming = new CountDownLatch(1);
        var canceled = new CountDownLatch(1);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("Partial", "", 0)),
                Flux.defer(() -> {
                    streaming.countDown();
                    return Flux.never();
                })).doOnCancel(canceled::countDown));
        var session = create();
        var reply = send(session, UUID.randomUUID().toString());
        assertTrue(streaming.await(8, TimeUnit.SECONDS));
        String path = "/api/chat/sessions/" + session.path("id").asText() + "/messages/"
                + reply.path("assistantMessageId").asText() + "/cancel";
        mockMvc.perform(post(path).with(authentication(other)).with(csrf()).header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(path).with(authentication(actor)).with(csrf()).header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted());
        assertTrue(canceled.await(8, TimeUnit.SECONDS));
        awaitOutcome(reply.path("assistantMessageId").asText(), "CANCELED");
        assertEquals("Partial", history(session).get(1).path("content").asText());
    }

    @Test
    void sseReplaysCommittedOutcomeAndChecksOwnerAndCursor() throws Exception {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("Answer 😀", "stop", 12)));
        var session = create();
        var reply = send(session, UUID.randomUUID().toString());
        String id = reply.path("assistantMessageId").asText();
        awaitOutcome(id, "COMPLETED");
        String path = "/api/chat/sessions/" + session.path("id").asText() + "/messages/" + id + "/events";
        mockMvc.perform(get(path).with(authentication(other))).andExpect(status().isNotFound());
        mockMvc.perform(get(path).with(authentication(actor)).header("Last-Event-ID", UUID.randomUUID() + ":1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(path).with(authentication(actor)).header("Last-Event-ID", id + ":9999"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(path + "?after=" + id + ":0").with(authentication(actor)).header("Last-Event-ID", id + ":1"))
                .andExpect(status().isBadRequest());
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            String events = "http://127.0.0.1:" + port + path;
            String token = token(actor);
            var response = http.send(httpRequest(events, token).build(), HttpResponse.BodyHandlers.ofString(UTF_8));
            assertEquals(200, response.statusCode());
            String frames = response.body();
            assertTrue(frames.contains("event:text-delta"), frames);
            assertTrue(frames.contains("Answer 😀"), frames);
            assertTrue(frames.contains("event:outcome"), frames);
            assertTrue(frames.contains("\"status\":\"COMPLETED\""), frames);
            assertEquals("no", response.headers().firstValue("X-Accel-Buffering").orElseThrow());
            var resumed = http.send(httpRequest(events, token).header("Last-Event-ID", id + ":1").build(),
                    HttpResponse.BodyHandlers.ofString(UTF_8));
            assertEquals(200, resumed.statusCode());
            String tail = resumed.body();
            assertFalse(tail.contains("event:text-delta"), tail);
            assertTrue(tail.contains("event:outcome"), tail);
        }
    }

    @Test
    void alternateNativeBindingUsesSameExecutorWithIsolatedPerTurnAccounting() {
        var provider = mock(ChatModel.class);
        when(provider.stream(any(Prompt.class))).thenAnswer(call -> {
            var options = call.getArgument(0, Prompt.class).getOptions();
            assertNotNull(options);
            assertEquals("fixture-model", options.getModel());
            assertEquals(0.25, options.getTemperature());
            return Flux.just(response("Answer", "stop", 12));
        });
        var service = new SpringAiLlmService("fixture-model", "fixture-provider", provider,
                (_, name) -> ChatOptions.builder().model(name).temperature(0.25).build(),
                null, List.of(), PricingModel.usdPer1MTokens(1, 2));
        var binding = new ChatModelBinding(service, prompt -> prompt);
        for (int turn = 0; turn < 2; turn++) {
            var setup = new ChatTurnSetup(UUID.randomUUID(), UUID.randomUUID(), actor.getPrincipal().actorId(),
                    new TenantId(TENANT), "fixture-model", List.of(new UserMessage("Question")), Instant.now().plusSeconds(10), binding);
            var accounting = new AtomicReference<ChatModelExecutor.Accounting>();
            var answer = new StringBuilder();
            executor.execute(setup, () -> {}, Mono.never(), answer::append, accounting::set);
            assertEquals("Answer", answer.toString());
            assertEquals(12L, accounting.get().input());
            assertEquals(12L, accounting.get().output());
            var cost = accounting.get().cost();
            assertNotNull(cost);
            assertEquals(0.000036, cost, 0.000000001);
        }
        verify(provider, times(2)).stream(any(Prompt.class));
    }

    @Test
    void nginxHttpDisconnectReconnectAndStopUseIndependentExecution() throws Exception {
        var ready = new CountDownLatch(1);
        var canceled = new CountDownLatch(1);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("Partial", "", 0)),
                Flux.defer(() -> { ready.countDown(); return Flux.never(); })).doOnCancel(canceled::countDown));
        Testcontainers.exposeHostPorts(port);
        Path web = Path.of("../web");
        String image = Files.readAllLines(web.resolve("Dockerfile")).stream().filter(line -> line.startsWith("FROM nginx:"))
                .map(line -> line.split(" ")[1]).findFirst().orElseThrow();
        String nginx = Files.readString(web.resolve("nginx.conf"))
                .replace("proxy_pass $memoryos_api;", "proxy_pass http://host.testcontainers.internal:" + port + ";")
                .replace("${MEMORYOS_OBJECT_STORAGE_CONNECT_SRC}", "");
        try (var proxy = new GenericContainer<>(image);
             var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            proxy.withExposedPorts(8080).withCopyToContainer(Transferable.of(nginx), "/etc/nginx/nginx.conf");
            proxy.start();
            String base = "http://" + proxy.getHost() + ":" + proxy.getMappedPort(8080);
            String token = token(actor);
            var created = http.send(httpRequest(base + "/api/chat/sessions", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"HTTP streaming\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode());
            var session = Json.mapper().readTree(created.body());
            String messages = base + "/api/chat/sessions/" + session.path("id").asText() + "/messages";
            String body = Json.mapper().createObjectNode().put("parentMessageId", session.path("rootMessageId").asText())
                    .put("clientRequestId", UUID.randomUUID().toString()).put("text", "Question").toString();
            var sent = http.send(httpRequest(messages, token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, sent.statusCode());
            String id = Json.mapper().readTree(sent.body()).path("assistantMessageId").asText();
            String events = messages + "/" + id + "/events";
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(events)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, http.send(httpRequest(events, token(other)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var live = http.send(httpRequest(events, token).build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, live.statusCode());
            try (var input = new BufferedReader(new InputStreamReader(live.body(), UTF_8))) {
                String frame = readFrame(input);
                assertTrue(frame.contains("event:text-delta"), frame);
                assertTrue(frame.contains("Partial"), frame);
                assertEquals("RUNNING", jdbc.sql("SELECT status FROM chat_message WHERE id = :id")
                        .param("id", UUID.fromString(id)).query(String.class).single());
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> streams.readerCount() == 0);
            assertEquals(1, canceled.getCount());
            var resumed = http.send(httpRequest(events, token).header("Last-Event-ID", id + ":1").build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var input = new BufferedReader(new InputStreamReader(resumed.body(), UTF_8))) {
                var stopped = http.send(httpRequest(messages + "/" + id + "/cancel", token)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(202, stopped.statusCode());
                assertTrue(canceled.await(5, TimeUnit.SECONDS));
                String frame;
                do { frame = readFrame(input); } while (frame.startsWith(":"));
                assertTrue(frame.contains("event:outcome"), frame);
                assertTrue(frame.contains("\"status\":\"CANCELED\""), frame);
                assertFalse(frame.contains("event:text-delta"), frame);
            }
            awaitOutcome(id, "CANCELED");
            assertEquals("Partial", jdbc.sql("SELECT content FROM chat_message WHERE id = :id")
                    .param("id", UUID.fromString(id)).query(String.class).single());
            verify(model, times(1)).stream(any(Prompt.class));
        }
    }

    private static HttpRequest.Builder httpRequest(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("X-MemoryOS-CSRF", "1");
    }

    private static String readFrame(BufferedReader input) throws IOException {
        var frame = new StringBuilder();
        for (String line; (line = input.readLine()) != null;) {
            if (line.isEmpty() && !frame.isEmpty()) return frame.toString();
            if (!line.isEmpty()) frame.append(line).append('\n');
        }
        throw new IOException("Stream closed before the expected event");
    }

    private String token(ActorAuthenticationToken authentication) throws JOSEException {
        String subject = authentication.getPrincipal().actorId().value().toString();
        jdbc.sql("INSERT INTO external_identity_bindings (issuer, subject, actor_id) VALUES (:issuer, :subject, :actor) ON CONFLICT DO NOTHING")
                .param("issuer", BROWSER_ISSUER).param("subject", subject).param("actor", UUID.fromString(subject)).update();
        var claims = new JWTClaimsSet.Builder().issuer(BROWSER_ISSUER).subject(subject).audience("memoryos-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(120))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("chat-http-test").generate(); }
        catch (JOSEException failure) { throw new IllegalStateException(failure); }
    }

    @Test
    void emptyFinalAnswerFailsInsteadOfSavingFalseCompletion() throws Exception {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("", "stop", 12)));
        var reply = send(create(), UUID.randomUUID().toString());
        String id = reply.path("assistantMessageId").asText();
        awaitOutcome(id, "FAILED");
        assertEquals("CHAT_EMPTY_RESPONSE", jdbc.sql("SELECT failure_code FROM chat_message WHERE id = :id")
                .param("id", UUID.fromString(id)).query(String.class).single());
    }

    @Test
    void lateCompletionCannotOverwritePersistedDeadlineOutcome() throws Exception {
        var streaming = new CountDownLatch(1);
        var complete = Sinks.<ChatResponse>one();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("Partial", "", 0)),
                Flux.defer(() -> { streaming.countDown(); return complete.asMono(); })));
        var session = create();
        var reply = send(session, UUID.randomUUID().toString());
        String id = reply.path("assistantMessageId").asText();
        assertTrue(streaming.await(8, TimeUnit.SECONDS));
        jdbc.sql("UPDATE chat_message SET deadline_at = clock_timestamp() - interval '1 second', status = 'FAILED', failure_code = 'CHAT_INTERRUPTED', finished_at = clock_timestamp() WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
        complete.tryEmitValue(response(" late", "stop", 12));
        awaitOutcome(id, "FAILED");
        String path = "/api/chat/sessions/" + session.path("id").asText() + "/messages/" + id + "/events";
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = http.send(httpRequest("http://127.0.0.1:" + port + path, token(actor)).build(),
                    HttpResponse.BodyHandlers.ofString(UTF_8));
            assertEquals(200, response.statusCode());
            String frames = response.body();
            assertTrue(frames.contains("\"status\":\"FAILED\""), frames);
            assertFalse(frames.contains("\"status\":\"COMPLETED\""), frames);
        }
        assertEquals("CHAT_INTERRUPTED", jdbc.sql("SELECT failure_code FROM chat_message WHERE id = :id")
                .param("id", UUID.fromString(id)).query(String.class).single());
    }

    private JsonNode create() throws Exception {
        return Json.mapper().readTree(mockMvc.perform(post("/api/chat/sessions").with(authentication(actor)).with(csrf())
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Execution\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode send(JsonNode session, String request) throws Exception {
        var body = Json.mapper().createObjectNode().put("parentMessageId", session.path("rootMessageId").asText())
                .put("clientRequestId", request).put("text", "Question");
        return Json.mapper().readTree(mockMvc.perform(post("/api/chat/sessions/" + session.path("id").asText() + "/messages")
                .with(authentication(actor)).with(csrf()).header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode history(JsonNode session) throws Exception {
        return Json.mapper().readTree(mockMvc.perform(get("/api/chat/sessions/" + session.path("id").asText() + "/messages")
                .with(authentication(actor))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void awaitOutcome(String id, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(expected,
                jdbc.sql("SELECT status FROM chat_message WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single()));
    }

    private static ChatResponse response(String text, String reason, int tokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(reason).build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(tokens, tokens)).build());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MEMORYOS_CHAT_LIVE_TEST", matches = "true")
    void realProviderRunsThroughSendNativeRunnerAndPersistedHistory() throws Exception {
        String key = System.getenv("SPRING_AI_OPENAI_API_KEY");
        assertTrue(key != null && !key.isBlank(), "SPRING_AI_OPENAI_API_KEY is required for this explicitly enabled check");
        var configuration = new OpenAiChatProviderConfiguration();
        var client = configuration.chatOpenAiClient(key, "https://api.openai.com/v1", limits);
        var sync = configuration.chatOpenAiSyncClient(key, "https://api.openai.com/v1", limits);
        var meters = new SimpleMeterRegistry();
        try {
            var provider = configuration.chatProviderModel(client, sync, key,
                    ObservationRegistry.NOOP, meters);
            when(model.stream(any(Prompt.class))).thenAnswer(call -> provider.stream(call.getArgument(0, Prompt.class)));
            var session = create();
            var reply = send(session, UUID.randomUUID().toString());
            String id = reply.path("assistantMessageId").asText();
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertEquals("COMPLETED",
                    jdbc.sql("SELECT status FROM chat_message WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single()));
            assertFalse(history(session).get(1).path("content").asText().isBlank());
            assertTrue(jdbc.sql("SELECT input_tokens FROM chat_message WHERE id = :id")
                    .param("id", UUID.fromString(id)).query(Long.class).single() > 0);
        } finally {
            client.close();
            sync.close();
            meters.close();
        }
    }

    private ActorAuthenticationToken actor() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", id).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", TENANT).param("actor", id).update();
        return new ActorAuthenticationToken(new IdentityContext(new ActorId(id)));
    }

    private static HttpServer startIdentityServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.createContext("/.well-known/openid-configuration", exchange -> {
                String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
                byte[] body = """
                        {
                          "issuer": "%s",
                          "authorization_endpoint": "%s/authorize",
                          "token_endpoint": "%s/token",
                          "jwks_uri": "%s/jwks",
                          "userinfo_endpoint": "%s/userinfo",
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }
                        """.formatted(issuer, issuer, issuer, issuer, issuer).getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var responseBody = exchange.getResponseBody()) {
                    responseBody.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start test identity server", exception);
        }
    }
}
