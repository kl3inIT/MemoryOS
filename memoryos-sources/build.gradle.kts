plugins {
    `java-library`
    id("memoryos.java-conventions")
}

description = "Sources: the Lark, Google Drive and SharePoint adapters, and content extraction (Tika, Docling, OCR)."

dependencies {
    api(project(":memoryos-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.core)
    // RestClient for the Google Drive account consent's token exchange and for the OCR services.
    implementation(libs.spring.web)

    // Lark through its official SDK; requests run on LarkTransport (MEM-245).
    implementation(libs.lark.oapi)

    // Google Drive through Google's API clients; their OAuth library sends the token requests (MEM-226).
    implementation(libs.google.api.drive)
    implementation(libs.google.api.sheets)
    implementation(libs.google.api.docs)
    implementation(libs.google.api.admin.directory)
    // The Google Drive account consent's ID token is validated by Nimbus (MEM-227).
    implementation(libs.nimbus.oauth2.oidc.sdk)

    // Entra application tokens.
    implementation(libs.msal4j)
    // SharePoint through the Microsoft Graph SDK (MEM-226); its OkHttp transport is built in GraphTransport.
    implementation(libs.microsoft.graph)
    // A site page's text web parts are HTML.
    implementation(libs.jsoup)

    implementation(libs.docling.client)
    implementation(libs.tika.core)
    implementation(libs.imageio.webp)
    runtimeOnly(libs.pdfbox.jbig2)
    implementation(libs.tika.parser.pdf)
    implementation(libs.tika.parser.microsoft)
    implementation(libs.tika.parser.text)
    implementation(libs.tika.parser.html)
    implementation(libs.tika.parser.mail)
    implementation(libs.tika.parser.miscoffice)
    // Page pictures and the layout of a PDF, and the links inside a Drive Source's PDF, are read with PDFBox directly.
    implementation(libs.pdfbox)
    // Office files: the spreadsheet readers and the links inside a Drive Source's Office file.
    implementation(libs.poi.ooxml)
    implementation(libs.commons.csv)
    implementation(libs.commons.compress)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
