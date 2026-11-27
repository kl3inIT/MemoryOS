# Shorter CI for #957

The baseline has Gradle parallel execution, its build cache and three core test JVMs. The longest core and API jobs still run their entire module suites serially across classes or within a small set of forks. Split those suites across runners with Gradle's test class filters and retain each module's `check` tasks. Use historical JUnit reports from run 45641361015 to group slow packages and give all other packages a catch-all shard.

Use Buildx Bake to solve API and worker targets in parallel and share the Dockerfile's Gradle stage. Keycloak has its own build context and smoke test, so it can run beside Bake. Publish the two unsmoked targets directly from BuildKit on main, check their revision and source labels by digest, and pass the same component references to the existing release job. Keycloak keeps its local image smoke and verified push.

Spring Boot's `.github/workflows/build-pull-request.yml` runs verification with separate main orchestration. Here the single CI workflow retains the required-check name and selects PR image jobs by inputs. Move the production-build browser fixture suite to main and dispatch, where it must pass even when the PR's tree already passed the dev-server browser shards. Increase dev-server shards so broad browser changes use more runners.

A dispatch on the PR branch exercises the all-area build graph without writing releases or deploying. CI timings come from GitHub job timestamps. Push-only publication and staging remain part of post-merge verification.
