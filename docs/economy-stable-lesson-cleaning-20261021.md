# ArcOrigin stable lesson and cart cleanup economy assessment

No rate or limit change is proposed. The mounted lesson and three-pile cleanup are MOUNTYARD order variants under ArcOrigin's existing shared side-job policy. A whole accepted order is worth 20 Vault; partial route points, scooping, loading, unloading and returning the empty cart have no separate payouts. Completed orders accrue to the shift balance and settle once when the shift ends.

The source default in [`OriginSideJobRewardPolicy.kt`](../src/main/kotlin/ru/arc/origin/OriginSideJobRewardPolicy.kt) is 20 Vault per order and 100 completed orders per UUID per UTC day. [`OriginSideJobRewards.kt`](../src/main/kotlin/ru/arc/origin/OriginSideJobRewards.kt) shares the quota across WAITER, MOUNTYARD, FORGE and AUCTION and deposits the frozen shift balance once. The active classic plugin read on 2026-10-21 reported ArcOrigin 0.1.53 enabled/ok. Its live `plugins/ARC/modules/origin-side-job-rewards.yml` matched 20 and 100, SHA-256 `51473a9539fcd0aeb7cf8e3109ad756c6396b8a361ab6e65faadb99c893aae87`.

The low/base/high rows are sensitivity inputs, not observed cadence. They carry forward 10/40/100 total accepted side-job orders per UUID-day, assuming 0.25/1/2 active hours and 40/40/50 orders per active hour. The mounted eight-point route and return, and the three-pile scoop/load/unload/empty-cart sequence have no measured completion times. Before and after counts are held equal to isolate the unchanged reward contract.

| Scenario | Participants (UUIDs) | Active hours / UUID-day | Assumed total orders / UUID-day | Vault / UUID-day before → after | Network Vault mint / week before → after |
|---|---:|---:|---:|---:|---:|
| Low | 5 | 0.25 | 10 | 200 → 200 | 7,000 → 7,000 |
| Base | 20 | 1 | 40 | 800 → 800 | 112,000 → 112,000 |
| High | 50 | 2 | 100 | 2,000 → 2,000 | 700,000 → 700,000 |

These totals cover the shared policy across all four job types; the two MOUNTYARD variants are not independent quotas. At matched completed-order counts the delta is zero. If the variants change total accepted orders, the marginal amount is 20 Vault per additional order while the UUID has fewer than 100 orders that UTC day; the shared cap prevents any additional policy payout after order 100. There is no network weekly cap, so network totals scale with the number of participating UUIDs. The scenario participant counts are not measured activity.

The separate `tokens` calculation is zero before and after. No player XP, EliteMobs crystals, redeemable items or permanent tools are awarded. The temporary shovel/cart props do not create a purchase, resale, contract or salvage path. The fixed Origin reward path reads no EcoJobs MONEY multiplier; no boost, tax, cost or other indirect source changes here. EconomyShopGUI SELL remains a current, separate flow; the planned SELL-to-contract transition is outside this assessment. No price or progression cost changes, and this mixed ledger cannot support a disposable-income or days-to-save estimate.

## Live ledger limits

The read-only `arc_ops_server(server="classic", economy_hours=168, economy_limit=5)` response was schema v2, `ledgerScope=network-shared server-player shards`, with an exact boundary from 2026-10-14 19:29:23.421Z through 2026-10-21 19:29:23.422Z. It covered 3,546 records, 35,273 operations and 29 players; no crossing or future rows were excluded. Per-currency totals were 67,714,058.47 Vault minted / 2,754,018.04 burned, and 63 tokens minted / 21 burned. The response marks aggregate totals as legacy mixed-currency, so the currencies stay separate here.

Vault is dominated by 67,001,499 of `admin_command` mint from two players; one accounts for 99.9978% of that source's mint. Another 146 Vault operations totalling 191,244.75 in absolute amount are unclassified (classified amount ratio 99.9089%). The ledger's job-reward components identify ArcEcoJobs work only and do not establish Origin order cadence. Ranked shop income is 59,464.73 Vault, but the adminShopSales result is truncated and incomplete. These observations are not used as ordinary-player earnings or as a route-duration estimate.

After delivery, verify each new variant credits exactly one MOUNTYARD order only when its whole sequence is accepted, that both consume the same 100-order UUID/UTC-day counter as the other jobs, and that only the normal end-of-shift settlement deposits Vault. Measure accepted completions per active hour before changing any cadence or balance claim; source checks and this sensitivity model do not prove live or native-client behavior.
