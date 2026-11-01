// Fails `check` when a forbidden library reaches a module's runtime classpath.
//
// Nothing fails to compile when an `implementation` turns into an `api`, or when a library grows a transitive
// dependency, so a platform SDK can reach `memoryos-core` or another platform's module unnoticed. Resolution is
// the only place that shows it (ADR 0027).
//
// Usage: `classpathIsolation { forbiddenGroups.addAll("com.larksuite.oapi") }`.
interface ClasspathIsolation {
    val forbiddenGroups: SetProperty<String>
}

val isolation = extensions.create<ClasspathIsolation>("classpathIsolation")

val resolvedGroups = provider {
    configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents
        .mapNotNull { it.moduleVersion?.group }
        .toSet()
}

val checkRuntimeClasspathIsolation = tasks.register("checkRuntimeClasspathIsolation") {
    group = "verification"
    description = "Fails if a forbidden library is on the runtime classpath."
    val forbidden = isolation.forbiddenGroups
    val resolved = resolvedGroups
    val module = project.path
    inputs.property("forbidden", forbidden)
    inputs.property("resolved", resolved)
    doLast {
        val found = resolved.get().intersect(forbidden.get())
        if (found.isNotEmpty()) {
            throw GradleException("$module must not have ${found.sorted()} on its runtime classpath")
        }
    }
}

tasks.named("check") {
    dependsOn(checkRuntimeClasspathIsolation)
}
