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
    // Modulith/ArchUnit metadata, the full persistence corpus and the OOXML schema type system the biên bản
    // renderer loads all live in one test JVM; a gigabyte stopped being enough once every capability had tests, and
    // 1.5 GB ran out on CI (2026-09-27) as the two forks split the classes differently. Two forks at 2 GB fit the runner.
    maxHeapSize = "2g"
    // TEMPORARY diagnosis of the CI OOM: dump the class histogram and the threads where the report upload finds them.
    val reports = layout.buildDirectory.dir("test-results/test").get().asFile
    jvmArgs("-XX:OnOutOfMemoryError=jcmd %p GC.class_histogram -all > $reports/oom-histogram-%p.xml;jcmd %p Thread.print > $reports/oom-threads-%p.xml")
    // Core is the longest test task; two JVMs each own a PostgreSQL container and template (TestDatabase).
    maxParallelForks = 2
    // Opt-in measurement must rerun when enabled instead of reusing a skipped result.
    inputs.property("memoryosSearchAuthzMeasure", providers.environmentVariable("MEMORYOS_SEARCH_AUTHZ_MEASURE").orElse("false"))
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
    // The OpenAI provider owns its OkHttp transport for raw-call cancellation (OpenAiCancellation).
    implementation(libs.okhttp)
    implementation(libs.mcp)
    implementation(libs.opensearch.java)
    implementation(libs.httpclient5)
    implementation(libs.jsoup)
    implementation(libs.pdfbox)
    implementation(libs.poi.ooxml)
    implementation(libs.commons.csv)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.security.crypto)
    implementation(libs.jakarta.persistence.api)
    implementation(libs.spring.data.jpa)
    implementation(libs.hibernate.core)
    compileOnly(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.context)
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
    // In-process Streamable HTTP MCP servers for client tests.
    testImplementation(libs.tomcat.embed.core)
    testImplementation(libs.embabel.platform)
    testImplementation(libs.spring.ai.model.tool)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}
