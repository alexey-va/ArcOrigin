# Origin scene runtime

Forge and mount-yard ambient choreography uses this runtime. Restaurant service
and player-facing shops keep their own state and only preempt ambient leases.

## Owners

- `OriginScenePlan`: validated configuration, authored step IDs and per-cycle
  timeout. `max-duration-seconds` is optional; its default is twice the authored
  asynchronous duration plus 60 seconds for recovery. The watchdog counts server
  ticks, so low TPS does not turn an otherwise progressing scene into a wall-clock
  timeout. An invalid reload keeps the previously loaded plan running.
- `OriginSceneCoordinator`: actor exclusion and oldest-due work selection.
- `OriginSceneExecution`: `NEW → RUNNING → RETURNING → FINISHED`, scoped delayed
  actions, watchdog, failure recovery and one terminal lease release. It uses
  core `LifecycleTaskScope`; it does not implement another scheduler. Failed
  native cleanup enters `RECOVERING` and retains the actor lease for up to three
  attempts, 20 ticks apart. Shutdown drains retries synchronously. Exhaustion
  reports `recovery-incomplete`, never a successful completion.
- `OriginSceneResources`: original Citizens equipment, native animal poses,
  open container lids and transient BlockDisplays. Ownership is recorded before
  mutation where the native API permits. Cleanup attempts every resource,
  removes successes and retains failures for an explicit retry. Pose snapshots
  remain owned through the return route so a temporarily cleared sit/graze can
  be restored after navigation. An originally empty hand is a real snapshot,
  not a missing entry.
- `OriginAmbientScenesModule`: native execution adapter, route controller,
  audience/player-priority policy, configuration replacement and status.

All native operations and state transitions run on the Paper main thread.
Exceptions report `ORIGIN_SCENE phase=FAILED` with scene, cycle, authored step,
run ID and throwable. Recovery cannot guarantee a successful native operation
when the world/provider itself rejects it; failures remain explicit rather than
being reported as successful completion. After the bounded recovery budget is
exhausted the actor lease is released, so a provider failure can leave native
state unrecovered and requires operator attention; this is not durable crash
recovery. Fatal JVM errors are not swallowed.

## Living scene steps

The existing `MOVE`, `WAIT`, and `BLOCK_DISPLAY` forms remain valid. The
following additions are bounded and lease-scoped:

- `BLOCK_DISPLAY` may use `follow-actor-id` instead of `surface`/`anchor`.
  `follow-offset` is an actor-local `x,y,z` offset rotated by actor yaw; the
  existing `offset`, scale, origin, and rotation fields keep their prior
  display semantics. The display refreshes through the cycle's
  `OriginSceneExecution` scope and is fenced on interruption.
- `POSE` accepts `STAND`, `SIT`, `CAT_LIE`, or `HORSE_GRAZE`. The runtime uses
  Paper's native `Sittable`, `Cat`, and `AbstractHorse` APIs, captures the first
  observed state, clears movement-blocking pose before a move, and restores it
  on completion, interruption, or reload.
- `DISMOUNT actor-id` leaves the vehicle, ejects passengers, and removes the
  pair from mount bookkeeping so return routing includes both actors after an
  explicit dismount.
- `MOVE_GROUP` accepts `actor-ids` plus either one shared `anchor` or a
  same-length `anchors` list, a declared `route-profile`, and bounded
  `timeout-ticks` (20..1200). Routes start together; one unavailable actor or
  failed route stops all members and aborts the step. The barrier advances only
  after every route succeeds.

## Operator readback

`/arc npccycle status [scene] [cycle]` uses the existing administrator permission
and never starts or interrupts work. Each bounded `ORIGIN_SCENE_STATUS` line
contains the phase, authored step, progress, actor IDs, owned display count,
elapsed milliseconds, remaining cooldown, wait reason and last result.

`/arc npccycle forge master-horseshoe` starts an existing cycle. Runtime overrides
live in the ops repository at `classic/plugins/ARC/modules/origin-scenes.yml`;
bundled defaults are `src/main/resources/modules/origin-scenes.yml`. Keep both
schemas compatible. The figure authoring helper is `scripts/forge_figures.py`.

Living-stable authoring uses `scripts/mount_yard_scene.py` and the multipart
models in `scripts/mount_yard_models.py`. The helper emits an apply_patch patch
for only the mount-yard section; it preserves unrelated forge overrides.
Run `python3 -B -m unittest discover -s scripts -p 'test_mount_yard*.py'` for
model budgets, leased attachments, native pose compatibility and repeatability.
Native actor identities and physical support blocks are recorded in ops
`docs/knowledge/npc-scene-origin-mounts.md` and `assets/origin-mount-yard/`.

## Focused verification

```sh
./gradlew test \
  --tests ru.arc.origin.scene.OriginSceneExecutionTest \
  --tests ru.arc.origin.scene.OriginSceneResourcesTest \
  --tests ru.arc.origin.scene.OriginScenePlanTest \
  --tests ru.arc.origin.scene.OriginScenePropContractTest \
  --tests ru.arc.origin.scene.OriginSceneCoordinatorTest \
  --tests ru.arc.commands.arc.subcommands.NpcCycleSubCommandTest
```

These tests prove execution transitions and calls at native seams, not the
Minecraft client's rendering. After activation, use the status command and
phase logs to check a real cycle; visual acceptance remains a separate check.
