import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

plugins {
    java
}

description = "Java 11 Visual Paradigm OpenAPI bridge"

val vpInstallDir = providers.gradleProperty("vpInstallDir")
    .orElse("C:/Program Files/Visual Paradigm 18.1")
val vpOpenApiJar = vpInstallDir.map { file("$it/lib/openapi.jar") }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

dependencies {
    compileOnly(files(vpOpenApiJar))
    implementation(libs.jackson2.databind)
    implementation(libs.undertow.core)

    testImplementation(libs.junit.jupiter5)
    testRuntimeOnly(libs.junit.platform.launcher5)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    classpath += files(vpOpenApiJar)
}

tasks.jar {
    archiveBaseName = "swd392-vp-bridge"
}

val stagePlugin = tasks.register<Sync>("stagePlugin") {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("plugin/swd392-vp-mcp"))

    from("src/main/plugin") {
        include("plugin.xml", "plugin.properties")
    }
    from(tasks.jar) {
        into("lib")
        rename { "swd392-vp-bridge.jar" }
    }
    from(configurations.runtimeClasspath) {
        into("lib")
        rename { name ->
            name.replace(Regex("-\\d[^/]*\\.jar$"), ".jar")
        }
    }
}

val packagePlugin = tasks.register<Zip>("packagePlugin") {
    group = "distribution"
    description = "Packages the Java 11 Visual Paradigm bridge plugin."
    dependsOn(stagePlugin)
    into("vn.edu.swd392.vpmcp") {
        from(stagePlugin)
    }
    archiveFileName = "swd392-vp-mcp-plugin-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

tasks.assemble {
    dependsOn(packagePlugin)
}

tasks.register<Sync>("installPlugin") {
    group = "distribution"
    description = "Copies the bridge into the current user's Visual Paradigm plugin directory."
    dependsOn(stagePlugin)
    val defaultUserPluginsDir = providers.environmentVariable("APPDATA")
        .map { "$it/VisualParadigm/plugins" }
    val pluginsDir = providers.gradleProperty("vpPluginsDir")
        .orElse(defaultUserPluginsDir)
    into(pluginsDir.map { file("$it/vn.edu.swd392.vpmcp") })
    from(stagePlugin)
}
