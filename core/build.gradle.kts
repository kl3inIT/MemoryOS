plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(libs.spring.modulith.api)
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.jdbc)

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
