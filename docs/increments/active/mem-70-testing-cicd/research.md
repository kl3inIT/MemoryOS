# Source-backed testing choices

Primary input: Philip Riecks, [Testing Spring Boot Applications Demystified, Spring I/O 2026](https://www.youtube.com/watch?v=DPi2Borv96I). The title and English automatic-caption track were verified against that exact video. On 2026-09-08, `youtube-transcript-api` fetched 1,382 timestamped caption segments ending at 52:30 after the direct caption endpoint returned an empty body. Automatic speech recognition can misrecognize technical names; corroborate those with the [speaker's pinned slide deck](https://github.com/rieckpil/talks/blob/07e021bbcafe91b8436ab8985ff9f9b2eea05046/testing-spring-boot-applications-demystified/slides/slides-spring-io-2026-04-14.pdf).

| Talk location / PDF page | Principle | MemoryOS decision |
| --- | --- | --- |
| 14:45–19:40 / pp. 16–22 | Unit isolation has limits: direct controller calls cannot establish HTTP validation, serialization or security | Keep fast validation/ordering tests and actual HTTP security integration; do not replace bearer/session tests with direct controller calls |
| 21:20–22:20 / pp. 28–29 | A slice loads the components relevant to one boundary | Preserve narrow repository tests without loading deployables; do not rewrite every existing integration test as a Spring slice |
| 29:40–32:10 / pp. 35–36 | Integration requires controlled infrastructure and reproducible state | Keep real migrated PostgreSQL and transport containers; required Docker failures cannot skip verification |
| 37:30–45:20 / pp. 46–51 | Reusing compatible contexts reduces startup cost; customizations affect cache identity | Move the three default-runtime smoke assertions into the existing bearer API context, removing one redundant context and its duplicate identity-server fixture |
| 45:21–46:55 / pp. 54–56 | Optimize context reuse first; parallel execution requires state isolation | Keep JVM class lifecycles sequential; bound frontend workers using local measurements |
| 47:10–48:50 / pp. 57–60 | Mutation exposes assertions that miss incorrect behavior despite line coverage | Use bounded regression/fault probes; do not add a blanket coverage percentage or PIT dependency in this increment |

The speaker's caching example is not a claimed MemoryOS speedup. CI publication, secrets, deployment serialization, rollback, exact-digest manifests and required-skip policy are MemoryOS design choices, informed separately by current GitHub/Docker documentation and the inspected OrgMemory implementation. Removing a Java-generated accessor test is a project-policy application, not a quotation attributed to the video.
