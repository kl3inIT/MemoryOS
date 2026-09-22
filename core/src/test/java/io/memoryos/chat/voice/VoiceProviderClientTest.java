package io.memoryos.chat.voice;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.chat.ChatException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceProviderClientTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final VoiceProviderClient client = new VoiceProviderClient(meters);
    private final AtomicReference<String> authorization = new AtomicReference<>("unset");
    private final AtomicInteger redirected = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/elsewhere/models", exchange -> {
            redirected.incrementAndGet();
            respond(exchange, 200, "{\"data\":[]}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void authorizedModelListingVerifiesEndpointAndCredential() {
        String base = serve(200, "{\"object\":\"list\",\"data\":[{\"id\":\"whisper-1\"}]}");
        client.verify(new VoiceConnectionService.Probe(VoiceProvider.OPENAI, base, "voice-secret"));
        assertEquals("Bearer voice-secret", authorization.get());
        assertEquals(1, meters.get("memoryos.chat.voice.request").tag("provider", "OPENAI").tag("operation", "verify")
                .tag("outcome", "succeeded").timer().count());
    }

    @Test
    void keylessCompatibleServerReceivesNoAuthorizationHeader() {
        String base = serve(200, "{\"data\":[]}");
        client.verify(new VoiceConnectionService.Probe(VoiceProvider.OPENAI_COMPATIBLE, base, ""));
        assertNull(authorization.get());
    }

    @Test
    void rejectedCredentialFailsWithoutDisclosingProviderPayload() {
        String base = serve(401, "{\"error\":\"voice-provider-diagnostic\"}");
        var failure = assertThrows(ChatException.class,
                () -> client.verify(new VoiceConnectionService.Probe(VoiceProvider.OPENAI, base, "wrong")));
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("diagnostic"));
        assertEquals(1, meters.get("memoryos.chat.voice.request").tag("outcome", "failed").timer().count());
    }

    @Test
    void responseThatIsNotAModelListIsNotAcceptedAsAProvider() {
        String base = serve(200, "<html>Welcome</html>");
        assertThrows(ChatException.class,
                () -> client.verify(new VoiceConnectionService.Probe(VoiceProvider.OPENAI_COMPATIBLE, base, "")));
    }

    @Test
    void redirectIsNotFollowedWithTheCredential() {
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere/models");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        assertThrows(ChatException.class,
                () -> client.verify(new VoiceConnectionService.Probe(VoiceProvider.OPENAI, base, "voice-secret")));
        assertEquals(0, redirected.get());
    }

    @Test
    void elevenLabsKeyIsVerifiedWithItsOwnHeaderAgainstTheModelList() {
        var header = new AtomicReference<String>();
        server.createContext("/v1/models", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("xi-api-key"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "[{\"model_id\":\"eleven_multilingual_v2\"}]");
        });
        client.verify(new VoiceConnectionService.Probe(VoiceProvider.ELEVENLABS,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "eleven-secret"));
        assertEquals("eleven-secret", header.get());
        assertNull(authorization.get());
    }

    @Test
    void azureKeyIsVerifiedAgainstTheVoiceListAndAnythingElseIsRejected() {
        var header = new AtomicReference<String>();
        var listing = new AtomicReference<>("﻿ [{\"ShortName\":\"vi-VN-HoaiMyNeural\"}]");
        server.createContext(AzureSpeech.VOICES_PATH, exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Key"));
            respond(exchange, 200, listing.get());
        });
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        client.verify(new VoiceConnectionService.Probe(VoiceProvider.AZURE, endpoint, "azure-secret"));
        assertEquals("azure-secret", header.get());
        listing.set("{\"error\":\"not a voice list\"}");
        assertThrows(ChatException.class,
                () -> client.verify(new VoiceConnectionService.Probe(VoiceProvider.AZURE, endpoint, "azure-secret")));
    }

    @Test
    void sonioxKeyIsVerifiedWithABearerTranscriptionListing() {
        var listing = new AtomicReference<>("{\"transcriptions\":[]}");
        var query = new AtomicReference<String>();
        server.createContext("/v1/transcriptions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, listing.get());
        });
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        client.verify(new VoiceConnectionService.Probe(VoiceProvider.SONIOX, base, "soniox-secret"));
        assertEquals("Bearer soniox-secret", authorization.get());
        assertEquals("limit=1", query.get());
        listing.set("{\"data\":[]}");
        assertThrows(ChatException.class,
                () -> client.verify(new VoiceConnectionService.Probe(VoiceProvider.SONIOX, base, "soniox-secret")));
    }

    private String serve(int status, String body) {
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, status, body);
        });
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
