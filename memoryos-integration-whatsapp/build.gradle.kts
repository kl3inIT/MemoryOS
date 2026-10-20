plugins {
    `java-library`
    id("memoryos.java-conventions")
    id("memoryos.classpath-isolation")
}

description = "WhatsApp: everything that reaches WhatsApp, through the whatsmeow bridge in bridge/."

// The protocol is whatsmeow's, in the Go bridge this module starts; no other platform's SDK and no parsing.
classpathIsolation {
    forbiddenGroups.addAll("com.larksuite.oapi", "com.google.apis", "com.google.api-client", "com.microsoft.graph",
        "org.apache.tika", "ai.docling")
}

dependencies {
    api(project(":memoryos-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.jackson.databind)
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.gfm.strikethrough)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
