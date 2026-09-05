plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Keep the SDK aligned with the instrumentation train; Boot still owns autoconfiguration.
extra["opentelemetry.version"] = libs.versions.opentelemetry.get()

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.arconia.bom))
    implementation(platform(libs.otel.instrumentation.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.otel.logback.appender)
    runtimeOnly(libs.micrometer.registry.otlp)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.db.scheduler.spring.boot4)
    runtimeOnly(project(":connector"))
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.commons.pool2)
    testAndDevelopmentOnly(platform(libs.arconia.bom))
    testAndDevelopmentOnly(libs.arconia.dev.services.redis)
    testImplementation(platform(libs.aws.sdk.bom))
    testImplementation(libs.aws.sdk.s3)
    testImplementation(libs.aws.sdk.auth)
    testImplementation(libs.aws.sdk.url.connection.client)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

sourceSets.main {
    resources.srcDir(rootProject.file("config/observability"))
}
