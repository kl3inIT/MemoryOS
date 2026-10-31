plugins {
    `java-library`
    id("memoryos.java-conventions")
    id("memoryos.classpath-isolation")
}

description = "Lark Suite: everything that calls Lark's Open API."

// Only its own platform SDK; parsing belongs to extraction (ADR 0027).
classpathIsolation {
    forbiddenGroups.addAll("com.google.apis", "com.google.api-client", "com.microsoft.graph", "org.apache.tika", "ai.docling")
}

dependencies {
    api(project(":memoryos-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.core)
    // Lark through its official SDK; requests run on LarkTransport (MEM-245).
    implementation(libs.lark.oapi)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
