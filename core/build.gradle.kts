plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(libs.spring.modulith.api)

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
