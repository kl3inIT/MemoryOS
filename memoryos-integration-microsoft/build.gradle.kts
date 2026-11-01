plugins {
    `java-library`
    id("memoryos.java-conventions")
    id("memoryos.classpath-isolation")
}

description = "Microsoft 365: SharePoint through Microsoft Graph."

// Only its own platform SDK; parsing belongs to extraction (ADR 0027).
classpathIsolation {
    forbiddenGroups.addAll("com.larksuite.oapi", "com.google.apis", "com.google.api-client", "org.apache.tika", "ai.docling")
}

dependencies {
    api(project(":memoryos-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.core)
    // Entra application tokens.
    implementation(libs.msal4j)
    // SharePoint through the Microsoft Graph SDK (MEM-226); its OkHttp transport is built in GraphTransport.
    implementation(libs.microsoft.graph)
    // A site page's text web parts are HTML.
    implementation(libs.jsoup)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
