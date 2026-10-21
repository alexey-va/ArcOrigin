# ArcOrigin stable care variety economy assessment

The care-variety changes do not alter the reward contract. Feeding and water orders use a varying assigned horse; after water is delivered, the next order can begin while the horse drinks for 3.5 seconds under an actor lease. Cross-tie preparation is a separate order type and also varies the assigned horse. It uses four clicks—wall, horse, another wall in either order, then horse—replacing the old two-click preparation, so the change adds two clicks. No actual order both removes the water wait and adds tie clicks. Repey the donkey and Plyusha the llama receive ambient placement/routing only. None of those actions, the background drink, or ambient routes adds a payable event.

The source baseline is ArcOrigin 0.1.54 at commit `b124baa523c06ce90f5dbf417a1c962dfb0ac77f`; the planned target version is 0.1.55. [`OriginSideJobRewardPolicy.kt`](../src/main/kotlin/ru/arc/origin/OriginSideJobRewardPolicy.kt) defaults to 20 Vault per accepted whole order and 100 orders per UUID per UTC date. [`OriginSideJobRewards.kt`](../src/main/kotlin/ru/arc/origin/OriginSideJobRewards.kt) shares quota/request identities across WAITER, MOUNTYARD, FORGE, and AUCTION; completed work accrues to the shift and is settled once. In a separate live read on 2026-10-22, classic reported ArcOrigin 0.1.53 enabled/OK and `plugins/ARC/modules/origin-side-job-rewards.yml` still had `reward-per-order: 20` and `daily-order-limit: 100`. The live plugin is older than both source baseline and planned target; no deployment is claimed.

For matched order counts, the new order flow leaves Vault and tokens unchanged. These counts are scenario inputs carried forward for comparison, total all four shared job types, and are not observed care-variant activity. No active-hours or orders-per-hour estimate is made.

| Scenario | Participants (UUIDs) | Assumed total accepted orders / UUID-day | Vault / UUID-day before → after | Network Vault mint / week before → after | Remaining shared orders to cap; Vault headroom |
|---|---:|---:|---:|---:|---:|
| Low | 5 | 10 | 200 → 200 | 7,000 → 7,000 | 90; 1,800 |
| Base | 20 | 40 | 800 → 800 | 112,000 → 112,000 | 60; 1,200 |
| High | 50 | 100 | 2,000 → 2,000 | 700,000 → 700,000 | 0; 0 |

The per-player ceiling remains 100 shared orders / 2,000 Vault per UTC day; there is no network weekly budget. At matched completions the delta is zero. If shorter critical-path time results in additional accepted whole orders, each is still worth 20 Vault only until the shared daily cap. The headroom column is arithmetic, not a forecast or new budget. Tokens, XP, crystals, permanent items, costs, taxes, and multipliers do not change.

The time sensitivities stay separate because water and cross-tie preparation are separate order types. For a water order, the critical-path duration changes by −3.5 seconds if the prior wait blocked the next order. For a cross-tie preparation order, duration increases by `2t`: the sequence grows from two to four clicks, adding two. Here `t` is the hypothetical full elapsed cost of one added click, including travel and response.

| Hypothetical elapsed cost per added tie click | Added time to cross-tie preparation order |
|---:|---:|
| 0.25 s | +0.5 s |
| 0.50 s | +1.0 s |
| 1.00 s | +2.0 s |

The 3.5 seconds saved applies only to a water order; added click time applies only to a separate preparation order. No single order combines these effects, and no combined cadence inference is made. These are hypothetical sensitivity points, not observed click latency, route time, or job throughput. Horse variation may change travel time, and order-type frequency is unknown. No income-per-hour claim follows. One accepted whole order must credit once; the leased background drink and its callback must never credit again. After delivery, verify the shared counter, one-credit order completion, and one shift settlement, then measure water-order wait and preparation clicks separately before making any cadence claim.

## Live ledger limits

The fresh read-only classic ledger was schema v2, `network-shared server-player shards`, with an exact 168-hour window from 2026-10-14T21:14:00.565Z to 2026-10-21T21:14:00.567Z. It covered 3,533 records, 35,140 operations, and 29 players; no crossing or future rows were excluded. Vault recorded 67,712,925.79 minted and 2,754,018.04 burned; tokens recorded 63 minted and 21 burned. Legacy aggregate totals are mixed-currency and are not combined.

`admin_command` minted 67,001,499 Vault from two players; one account represented 99.9978% of that source. Source classification leaves 151 records/operations and 191,265.75 absolute amount unclassified; classified operation and amount ratios were 99.5703% and 99.9089%. The jobs-reward breakdown contained ArcEcoJobs components only, so it cannot establish ArcOrigin orders or cadence. Ranked shop income was 59,464.73 Vault, but the adminShopSales result was truncated/incomplete. EconomyShopGUI SELL remains a separate current flow; the planned SELL-to-contract transition is outside this assessment.
