plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(libs.spring.modulith.api)

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
