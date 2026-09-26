package io.memoryos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Production ran from 2026-09-22 without exporting telemetry: every switch named the staging profile alone. Each
 * deployed environment's profiles must turn on export, structured logs and the OTLP log appender; a local run must not.
 */
class DeployedTelemetryProfilesTest {
    private static final List<Set<String>> DEPLOYED = List.of(Set.of("staging"), Set.of("production"), Set.of("production", "staging"));
    private static final Set<String> LOCAL = Set.of();

    @Test
    void theTelemetryDocumentActivatesInEveryDeployedEnvironment() throws IOException {
        String profile = null;
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(resource("memoryos-observability.yaml"))) {
            if (document instanceof Map<?, ?> root && path(root, "management", "otlp", "metrics", "export", "enabled") == Boolean.TRUE)
                profile = (String) path(root, "spring", "config", "activate", "on-profile");
        }
        var expression = Profiles.of(profile);
        DEPLOYED.forEach(active -> assertTrue(expression.matches(active::contains), "no export for " + active));
        assertFalse(expression.matches(LOCAL::contains));
    }

    @Test
    void structuredAndOtlpLogsFollowTheSameProfiles() throws IOException {
        String logback = new String(resource("logback-spring.xml").readAllBytes(), StandardCharsets.UTF_8);
        var names = Pattern.compile("<springProfile name=\"([^\"]+)\">").matcher(logback).results().map(m -> m.group(1)).toList();
        assertEquals(3, names.size());
        for (var active : DEPLOYED) {
            assertFalse(Profiles.of(names.get(0)).matches(active::contains), "plain console in " + active);
            assertTrue(Profiles.of(names.get(1)).matches(active::contains) && Profiles.of(names.get(2)).matches(active::contains));
        }
        assertTrue(Profiles.of(names.get(0)).matches(LOCAL::contains));
        var appender = Profiles.of(OpenTelemetryLoggingConfiguration.class.getAnnotation(Profile.class).value());
        DEPLOYED.forEach(active -> assertTrue(appender.matches(active::contains)));
    }

    private static InputStream resource(String name) {
        return DeployedTelemetryProfilesTest.class.getClassLoader().getResourceAsStream(name);
    }

    private static Object path(Map<?, ?> root, String... keys) {
        Object value = root;
        for (var key : keys) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = map.get(key);
        }
        return value;
    }
}
