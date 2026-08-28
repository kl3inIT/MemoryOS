plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter)
    implementation(libs.tika.core)
    implementation(libs.tika.parser.pdf)
    implementation(libs.tika.parser.microsoft)
    implementation(libs.tika.parser.text)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
