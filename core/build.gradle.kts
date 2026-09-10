import java.time.Duration

plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    // Native Embabel method tools use reflected parameter names for their JSON schema and binding.
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    // The full PostgreSQL/migration corpus and real OpenSearch startup exceed ten minutes on a cold host.
    timeout = Duration.ofMinutes(15)
}

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(libs.spring.modulith.api)
    implementation(platform(libs.spring.boot.dependencies))
    annotationProcessor(libs.spring.boot.configuration.processor)
    implementation(platform(libs.aws.sdk.bom))
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.ai.openai)
    implementation(libs.embabel.api)
    implementation(libs.embabel.openai)
    implementation(libs.opensearch.java)
    implementation(libs.httpclient5)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.security.crypto)
    implementation(libs.jakarta.persistence.api)
    compileOnly(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.databind)
    implementation(libs.keycloak.admin.client)
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.url.connection.client)

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.database.postgresql)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.h2)
    testImplementation(libs.embabel.platform)
    testImplementation(libs.spring.ai.model.tool)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
