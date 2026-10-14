# ArcOrigin repository guidance

- Keep the ArcOrigin runtime plugin and `arc-origin-api` independently versioned.
- ARC owns and shades `ru.arc.origin.api`; ArcOrigin compiles against the API but must not package it.
- Do not depend on the full ARC plugin JAR. Use only the public `arc-core` modules and server-provided APIs required by this plugin.
- Keep Paper, Citizens, Denizen, ItemsAdder, PacketEvents, RedisEconomy, and other server-provided plugin APIs `compileOnly`; do not shade those classes.
- Do not package ARC-owned `ru.arc.paper.api` contracts or SQL, Redis, and metrics providers. Keep the production JAR verifier in CI.
- Keep every `arc-core` coordinate on the single version in `arc-core-consumer.toml`.
- API coordinates are immutable. Bump `originApiVersion` for a public API change; plugin runtime releases can advance independently.
- Integration tests use disposable services and Docker. CI publishes only after the architecture verifier, unit tests, integration tests, and deterministic JAR checks pass.

Use JDK 25. Do not publish from local builds. Main-branch CI is the only publisher and compares every existing Maven file before uploading any missing file, then verifies anonymous readback checksums.
