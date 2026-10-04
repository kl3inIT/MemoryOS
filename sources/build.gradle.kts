plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.docling.client)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.core)
    implementation(libs.msal4j)
    // SharePoint through the Microsoft Graph SDK (MEM-226); its OkHttp transport is built in GraphTransport.
    implementation(libs.microsoft.graph)
    // Google Drive through Google's API clients; their OAuth library sends the token requests (MEM-226).
    implementation(libs.google.api.drive)
    implementation(libs.google.api.sheets)
    implementation(libs.google.api.docs)
    implementation(libs.google.api.admin.directory)
    // The Google Drive account consent: token exchange over RestClient and ID-token validation.
    implementation(libs.spring.web)
    implementation(libs.spring.security.oauth2.jose)
    implementation(libs.tika.core)
    implementation(libs.imageio.webp)
    implementation(libs.tika.parser.pdf)
    implementation(libs.tika.parser.microsoft)
    implementation(libs.tika.parser.text)
    implementation(libs.tika.parser.html)
    implementation(libs.tika.parser.mail)
    implementation(libs.tika.parser.miscoffice)
    implementation(libs.poi.ooxml)
    implementation(libs.commons.csv)
    implementation(libs.commons.compress)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
