plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Keep the SDK aligned with the instrumentation train; Boot still owns autoconfiguration.
extra["opentelemetry.version"] = libs.versions.opentelemetry.get()

// Springdoc uses the Jakarta artifact; the non-Jakarta jar contains duplicate annotation classes.
configurations.configureEach {
    exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":connector"))
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.embabel.platform)
    implementation(libs.embabel.openai)
    implementation(libs.spring.ai.model.tool)
    implementation(platform(libs.arconia.bom))
    implementation(platform(libs.otel.instrumentation.bom))
    implementation(libs.arconia.multitenancy.web)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.otel.logback.appender)
    runtimeOnly(libs.micrometer.registry.otlp)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.webmvc)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.session.jdbc)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    testAndDevelopmentOnly(platform(libs.arconia.bom))
    testAndDevelopmentOnly(libs.arconia.dev.services.postgresql)

    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    systemProperty("arconia.dev.services.postgresql.enabled", "false")
    inputs.file(rootProject.file("openapi.yml"))
    inputs.property(
        "memoryosOpenApiWrite",
        providers.environmentVariable("MEMORYOS_OPENAPI_WRITE").orElse("false"),
    )
    inputs.property("memoryosChatLive", providers.environmentVariable("MEMORYOS_CHAT_LIVE_TEST").orElse("false"))
    inputs.property("memoryosChatGroundingLive", providers.environmentVariable("MEMORYOS_CHAT_GROUNDING_LIVE_TEST").orElse("false"))
}

springBoot {
    mainClass = "io.memoryos.api.MemoryOsApiApplication"
}
