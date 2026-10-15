# ArcOrigin

ArcOrigin is the standalone Paper plugin for RusCrafting's Origin worlds and content. It owns Origin gameplay while depending on ARC for host services and service registration.

The runtime and its Java API have independent immutable Maven coordinates:

- Runtime plugin: `ru.ruscrafting:ArcOrigin:0.1.0`
- Java API: `ru.ruscrafting.arc:arc-origin-api:0.1.0`

ARC shades the API once. ArcOrigin consumes it as `compileOnly`; add-ons should do the same and must not embed the API or shared provider implementations. Change `originApiVersion` before publishing a contract change. Runtime releases do not require an API version change.

## Build and verification

Use JDK 25. Unit tests use the shared Paper test kit. Redis integration tests run against disposable services and require Docker.

```bash
./gradlew test
./gradlew integrationTest
./gradlew shadowJar
./scripts/verify-shadow-jar.sh
```

CI runs the pinned `arc-core` consumer verifier, unit and integration tests, deterministic packaging, and JAR ownership checks. A green push to `main` publishes the runtime JAR/POM and API JAR, sources JAR, POM, and Gradle module metadata to the RusCrafting Maven repository. The publisher checks all immutable coordinates before upload and verifies public readback. There is no GitHub Release step.

For composite development, set `arcCoreDir` to a local `arc-core` checkout. CI resolves the immutable `2.7.22` release.

## Controlled NPC movement

Dining, ambient scenes and the furniture workshop share
`src/main/kotlin/ru/arc/npc/NpcRouteController.kt`; continuous path geometry is in
`NpcRouteMotion.kt`. ArcOrigin reads the existing host configuration through
`ArcOriginConfiguration.legacyDataPath()`: on Spawn these are still
`plugins/ARC/modules/origin-dining.yml`, `origin-scenes.yml` and
`origin-furniture-workshop.yml`, not an `ArcOrigin/modules` directory.

Four-way routing, ordered waypoints, forbidden cells, furniture obstacles and
per-profile surface/step limits remain authoritative. Short flat corners are
rounded only after checking the swept body against collision shapes and blocked
cells. `corner-smoothing-distance` bounds the approach; the existing
`corner-smoothing-lead` bounds departure from the original polyline. With the
usual 0.75 / 0.30 settings a quadratic corner departs by at most 0.1875 blocks.
Unsafe corners and height transitions stay sharp. Every physical movement step
is checked again; rejected steps stop instead of pushing into an obstacle.

The same swept-body checks apply to humans, horses and larger animals. Search
bounds constrain the path center; forbidden cells, adjacent furniture and native
collision shapes constrain the whole body. Consecutive transit legs share a path;
a pickup, wait or other scene action remains an explicit boundary.

One NPC rotation owner turns physical and rendered body/head together,
starting from the actual body yaw and following velocity with the per-tick yaw cap.
Stationary scene actions share that owner and tick budget with navigation, so
serving, gestures and cleanup cannot snap the body or double-turn during a handoff.
Final authored poses and workshop focus points are reached with the same bounded
turn before the scene advances; no endpoint snap is needed.
`heading-look-ahead-cells` is retained for configuration compatibility but no
longer drives a separate direction that can disagree with the moving body.
NPC step height is still restricted only during the route and restored afterward.
Focused verification: `./gradlew test --tests ru.arc.npc.NpcRouteControllerTest
--tests ru.arc.npc.NpcRouteMotionTest --tests ru.arc.npc.NpcRouteRotationTest`. Passing geometry tests does not establish
native-client visual acceptance.
