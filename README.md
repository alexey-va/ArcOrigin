# ArcOrigin

ArcOrigin is the standalone Paper plugin for RusCrafting's Origin worlds and content. It owns Origin gameplay while depending on ARC for host services and service registration.

The runtime and its Java API have independent immutable Maven coordinates:

- Runtime plugin: `ru.ruscrafting:ArcOrigin:0.1.79`
- Java API: `ru.ruscrafting.arc:arc-origin-api:0.1.1`

ARC shades the API once. ArcOrigin consumes it as `compileOnly`; add-ons should do the same and must not embed the API or shared provider implementations. Change `originApiVersion` before publishing a contract change. Runtime releases do not require an API version change.

## Build and verification

Use JDK 25. Unit tests use the shared Paper test kit. Redis integration tests run against disposable services and require Docker.

```bash
./gradlew test
./gradlew integrationTest
./gradlew shadowJar
./scripts/verify-shadow-jar.sh
```

CI runs the pinned `arc-core` consumer verifier, unit and integration tests, deterministic packaging, and JAR ownership checks. A green push or manual CI run on `main` publishes the runtime JAR/POM and API JAR, sources JAR, POM, and Gradle module metadata to the RusCrafting Maven repository. The publisher checks all immutable coordinates before upload and verifies public readback. There is no GitHub Release step.

For composite development, set `arcCoreDir` to a local `arc-core` checkout. CI resolves the immutable `2.7.79` release.

## Controlled NPC movement

Dining, ambient scenes and the furniture workshop share
`src/main/kotlin/ru/arc/npc/NpcRouteController.kt`; continuous path geometry is in
`NpcRouteMotion.kt`. ArcOrigin reads the existing host configuration through
`ArcOriginConfiguration.legacyDataPath()`: on Spawn these are still
`plugins/ARC/modules/origin-dining.yml`, `origin-scenes.yml` and
`origin-furniture-workshop.yml`, not an `ArcOrigin/modules` directory.

Routing uses eight-way A* with distance-weighted diagonal steps and the same
full-body swept collision check. `obstacle-clearance` (default 0.25 blocks) adds
a soft 25% cost near obstacles, preferring space for the body without closing
narrow passages. Ordered waypoints, forbidden cells, furniture obstacles and
per-profile surface/step limits remain authoritative. Each waypoint leg then
loses unnecessary bends only when the entire shortcut is clear. The configured
`path-shortcut-max-deviation` (default 1.0 block) bounds total shortcut and corner
departure from that leg's planned polyline; the corner budget is reserved first.
Both settings are read through the existing module reload lifecycle.
Shortcuts never skip between waypoint legs; transit-waypoint corners keep their
existing small rounding allowance, without a forced stop at each anchor.
Setting the departure budget to zero disables both path reshaping stages.
Short flat corners are
rounded only after checking the swept body against collision shapes and blocked
cells. `corner-smoothing-distance` bounds the approach; the existing
`corner-smoothing-lead` bounds departure from the original polyline. With the
usual 0.75 / 0.30 settings a quadratic corner departs by at most 0.1875 blocks.
Unsafe corners and height transitions stay sharp. Every physical movement step
is checked again; rejected steps stop instead of pushing into an obstacle.

The same swept-body checks apply to humans, horses and larger animals. Search
bounds constrain the path center; forbidden cells, adjacent furniture and native
collision shapes constrain the whole body. Consecutive transit legs share a path,
including group moves with the same ordered actors and route profile. Intermediate
waypoints do not stop the actors or apply an idle pose; a pickup, wait or other
scene action remains an explicit boundary.

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

## Player waiter work

`OriginWaiterGame` adds repeatable deliveries to the existing dining scene in
`modules/origin-dining.yml` (`waiter-game`). At brewery bartender Remi, NPC 405,
sneak and right-click to work; an ordinary right-click keeps the Citizens drink
shop. At restaurant bartender Mateo, NPC 439, right-click normally. Both are
Villagers and use the same guarded Bukkit/Citizens interaction path.

The bartender reserves one existing guest through `OriginSceneCoordinator`.
Visit that guest to take the order, return to the same bartender after ten
seconds of kitchen preparation, collect the dish, then deliver it to that exact
guest. The order expires after 180 seconds. Personal glow and the action bar
identify the next actor; the native dialog exposes pickup and cancellation.
Ordinary dining, the configured menu, table ownership and ambient meal lifecycle
remain owned by `OriginDiningModule`. A failed serving display cannot trigger
payment.

`OriginWaiterProgress` owns the transient ordered handoffs and exact request ID.
`OriginWaiterCarry` mounts one shared, non-pickupable packet ItemDisplay to the
player and updates its body-relative pose, including crouching. It reuses each
dining dish's verified GROUND transform, scale and surface correction; it never
adds an inventory item. Cancellation, timeout, leaving the venue, teleport,
quit and module reload release the reservation, remove the prop and clear
personal glow. Pending callbacks are fenced by the lifecycle epoch and request.

`OriginSideJobRewards` owns one asynchronous Redis ledger at
`arc.origin-waiter-rewards.v1`. Each completed order durably consumes one slot
of the shared UTC-day quota and adds its frozen reward to the shift balance.
The configured defaults are 20 coins per order and 100 orders per UUID per UTC
day across all four jobs. Finishing, leaving, death or disconnect pays all
completed work in one transfer immediately after the durable claim is acknowledged;
unfinished work is cancelled. There is no payout cooldown. Idle time earns nothing.

The optional earnings/receipt fields preserve legacy receipts and unresolved
claims. Reload/startup recovers unclaimed balances; late earned-order writes
survive module closure and are also recovered at the next admission. Settlement
does not consume quota a second time, including across midnight. Unknown provider
outcomes retain the complete balance and block automatic replay; only a proven
pre-call failure can release the claim. A confirmed receipt requires the final
journal acknowledgement. The host reason is audit text, not a provider idempotency
key. There are no item rewards, XP or earning multipliers.

Focused verification covers both Villager entry routes, preserved trading,
duplicate clicks, exact recipient and kitchen, pickup timing, failed displays,
cleanup, late dialog replies, and durable payout failures:

```bash
./gradlew test --tests 'ru.arc.origin.OriginWaiter*Test' \
  --tests ru.arc.origin.OriginSideJobRewardsTest \
  --tests 'ru.arc.origin.OriginDining*Test' \
  --tests ru.arc.origin.scene.OriginSceneCoordinatorTest
```

Packet and model checks establish source behavior and geometry. They do not
establish native-client interaction, attachment motion or visual acceptance.

## Mount-yard and forge work

`OriginOddJobsModule` owns the shifts configured in `modules/origin-odd-jobs.yml`.
Right-click Senya (440), Edgar (349), Savva (351) or Luka (354) to start.
The employer assigns the next order automatically. Victor (365) retains the
auction entry; his retired paid-work route is not re-enabled.

Edgar rotates forging, cart transport and engraving without consecutive craft
families. Every smithing order starts with a separate request-bound stock item,
including repairs and matching horseshoes. Finished work returns to the
original employer. The cart route loads three baskets, requires player movement
to push the cart, unloads into the stock chest, then returns the empty cart.
Engraving progressively cuts the assigned word into a plate. Savva and Luka
also retain their specialist shifts.

Stable work takes an empty bucket from supplies, fills it at the actual water
cauldron and lets the selected animal drink. Hay remains inside Buran's stall
while he approaches and eats. Grooming and preparation orders first lead Wind
to the cross-tie station. The worker attaches two visible ropes to separate
highlighted wall fittings before brushing or saddling. Both ropes must be
removed before Wind can be escorted to Senya for handoff; grooming-only orders
retain their shorter care step without a saddle.
The existing mount-care boost and Bran's training challenge remain separate
activities with their existing rewards.

Scene anchors and actual workstation surfaces come from `origin-scenes.yml`.
Work reserves the required actors through the ambient scene coordinator, so
background scenes cannot reclaim an active work surface or animal. Packet
props, temporary supplies and guidance are fenced by player and request token.
Stable movement uses the existing collision-checked NPC route controller;
cleanup restores native animal equipment and holds the lease during the bounded
return route. Cancellation, expiry, departure and reload invalidate late callbacks.

All side jobs share the cashier described above. Existing receipts and
pending claims continue to count; adding a worksite does not reset the quota.
The default pooled cap is 2,000 coins per UUID per UTC day, reset at 03:00 Moscow
time; it is not a rolling 24-hour or network-wide limit. While working, the HUD
and employer dialog show earned money; only the final receipt says it was received.

Focused verification includes `OriginOddJob*Test`, `OriginWorkShiftTest` and
`OriginSideJob*Test`. Unit tests and offline geometry previews do not
establish native-client gameplay, rendered motion or an observed live payout.
## Daily quest integration

When ArcRanks is enabled, completed side-job orders (waiter, forge, mountyard,
auction) and successfully delivered workshop furniture report through the typed
`arc-ranks-api:0.1.0` service. Quota rejection, cancelled work and failed inventory
delivery do not count. The existing durable order/claim UUID and player UUID form
the event identity; retries retain it. Gameplay rewards and limits are unchanged.
Quest delivery retries at most three times after the first asynchronous attempt.
There is no durable publication outbox: a crash, reload or unavailable ArcRanks
service can leave a completed action without quest progress. ArcRanks owns and
embeds the API classes; ArcOrigin must not embed them.
