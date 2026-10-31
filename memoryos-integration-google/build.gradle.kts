plugins {
    `java-library`
    id("memoryos.java-conventions")
    id("memoryos.classpath-isolation")
}

description = "Google Workspace: Drive, Docs, Sheets and the Directory groups of a Drive Source."

// Only its own platform SDK; parsing belongs to extraction (ADR 0027).
classpathIsolation {
    forbiddenGroups.addAll("com.larksuite.oapi", "com.microsoft.graph", "org.apache.tika", "ai.docling")
}

dependencies {
    api(project(":memoryos-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.core)
    // Google Drive through Google's API clients; their OAuth library sends the token requests (MEM-226).
    implementation(libs.google.api.drive)
    implementation(libs.google.api.sheets)
    implementation(libs.google.api.docs)
    implementation(libs.google.api.admin.directory)
    // The Google Drive account consent: token exchange over RestClient; the ID token is validated by Nimbus (MEM-227).
    implementation(libs.spring.web)
    implementation(libs.nimbus.oauth2.oidc.sdk)
    // Links inside an Office file or a PDF of a Drive Source are read offline (OfflineGoogleDriveLinkReader).
    implementation(libs.poi.ooxml)
    implementation(libs.pdfbox)
    implementation(libs.commons.csv)
    implementation(libs.commons.compress)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
