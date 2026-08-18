plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.webmvc)

    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
