import java.time.Duration

// What every MemoryOS Java module shares, so a module's own build file states only what is its own.
plugins {
    java
}

group = rootProject.group
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Name each deprecated API a build uses instead of javac's one-line summary, so a deprecation is seen before its removal.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

// Docker dependency layer: downloads the production build classpaths before sources are copied, so an
// unchanged dependency set is reused from the image build cache.
tasks.register("resolveDependencies") {
    notCompatibleWithConfigurationCache("Resolves project configurations at execution time")
    doLast {
        val names = setOf("compileClasspath", "runtimeClasspath", "productionRuntimeClasspath", "annotationProcessor")
        project.configurations.filter { it.name in names && it.isCanBeResolved }.forEach { it.resolve() }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    timeout = Duration.ofMinutes(10)
    reports.junitXml.apply {
        includeSystemOutLog = false
        includeSystemErrLog = false
    }
    systemProperty("memoryos.object-storage.s3.service-endpoint", "http://127.0.0.1:1")
    systemProperty("memoryos.object-storage.s3.upload-endpoint", "http://127.0.0.1:1")
    systemProperty("memoryos.object-storage.s3.region", "us-east-1")
    systemProperty("memoryos.object-storage.s3.bucket", "memoryos-test")
    systemProperty("memoryos.object-storage.s3.access-key", "test-access-key")
    systemProperty("memoryos.object-storage.s3.secret-key", "test-secret-key")
    systemProperty("memoryos.object-storage.s3.path-style-access", "true")
    systemProperty("memoryos.object-storage.s3.readiness-key", "system/readiness")
    systemProperty("management.health.object-storage.enabled", "false")
}
