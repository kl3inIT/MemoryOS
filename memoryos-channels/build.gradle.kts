plugins {
    java
    id("memoryos.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Keep the SDK aligned with the instrumentation train; Boot still owns autoconfiguration.
extra["opentelemetry.version"] = libs.versions.opentelemetry.get()

dependencies {
    implementation(project(":memoryos-core"))
    implementation(platform(libs.otel.instrumentation.bom))
    implementation(platform(libs.spring.ai.bom))
    // A turn runs on the agent platform, as in the API.
    implementation(libs.embabel.platform)
    implementation(libs.embabel.openai)
    implementation(libs.embabel.byok)
    implementation(libs.spring.ai.model.tool)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.otel.logback.appender)
    runtimeOnly(libs.micrometer.registry.otlp)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    // A turn's search reaches every external Source type's adapter, so every platform is here; the Lark one also
    // carries the bot's adapter.
    runtimeOnly(project(":memoryos-integration-lark"))
    runtimeOnly(project(":memoryos-integration-opensandbox"))
    runtimeOnly(project(":memoryos-integration-google"))
    runtimeOnly(project(":memoryos-integration-microsoft"))
    // MEM-263: the Zalo bot, which no Source type uses.
    runtimeOnly(project(":memoryos-integration-zalo"))
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.database.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// A local run is a development run; staging and production images select their own profiles.
tasks.bootRun {
    systemProperty("spring.profiles.active", "development")
}
