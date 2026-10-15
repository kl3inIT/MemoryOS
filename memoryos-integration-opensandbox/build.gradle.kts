plugins {
    `java-library`
    id("memoryos.java-conventions")
    id("memoryos.classpath-isolation")
}

description = "OpenSandbox: the runtime of the Chat sandbox (MEM-270, ADR 0029)."

// Only its own platform SDK; parsing belongs to extraction (ADR 0027).
classpathIsolation {
    forbiddenGroups.addAll("com.larksuite.oapi", "com.google.apis", "com.google.api-client", "com.microsoft.graph",
            "org.apache.tika", "ai.docling")
}

dependencies {
    api(project(":memoryos-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    // OpenSandbox through its official SDK, which owns its OkHttp transport (4.12.0, the version core already resolves).
    implementation(libs.opensandbox.sandbox)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
