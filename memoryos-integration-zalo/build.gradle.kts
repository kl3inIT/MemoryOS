plugins {
    `java-library`
    id("memoryos.java-conventions")
    id("memoryos.classpath-isolation")
}

description = "Zalo: everything that calls the Zalo Bot Platform."

// No platform SDK is maintained, so Zalo is called over shared.OutboundHttp; no other platform's SDK and no parsing.
classpathIsolation {
    forbiddenGroups.addAll("com.larksuite.oapi", "com.google.apis", "com.google.api-client", "com.microsoft.graph",
        "org.apache.tika", "ai.docling")
}

dependencies {
    api(project(":memoryos-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
