plugins {
    base
}

group = "vn.edu.swd392.vpmcp"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.named("build") {
    dependsOn(":vp-bridge-plugin:packagePlugin")
}
