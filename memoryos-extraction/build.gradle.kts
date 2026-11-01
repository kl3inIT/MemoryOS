plugins {
    `java-library`
    id("memoryos.java-conventions")
    id("memoryos.classpath-isolation")
}

description = "Content extraction: Tika, Docling, OCR adapters and the spreadsheet readers."

// Extraction names no platform (ADR 0027).
classpathIsolation {
    forbiddenGroups.addAll("com.larksuite.oapi", "com.google.apis", "com.google.api-client", "com.microsoft.graph")
}

dependencies {
    api(project(":memoryos-core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.core)
    implementation(libs.docling.client)
    // RestClient for the OCR services.
    implementation(libs.spring.web)
    implementation(libs.tika.core)
    implementation(libs.imageio.webp)
    runtimeOnly(libs.pdfbox.jbig2)
    implementation(libs.tika.parser.pdf)
    implementation(libs.tika.parser.microsoft)
    implementation(libs.tika.parser.text)
    implementation(libs.tika.parser.html)
    implementation(libs.tika.parser.mail)
    implementation(libs.tika.parser.miscoffice)
    // Page pictures and the layout of a PDF are read with PDFBox directly.
    implementation(libs.pdfbox)
    implementation(libs.poi.ooxml)
    implementation(libs.commons.csv)
    implementation(libs.commons.compress)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
