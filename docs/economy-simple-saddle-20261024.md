# Farm preparation simplification and private equipment — 2026-10-24

The requested native saddle replaces cross-ties, pad, bridle, girth, stirrups and manual untacking. Groom/saddle preparation has 9 paid manual actions (previously 29); lesson preparation has 10 (previously 30). Automatic arrival, ride completion and loan restoration do not create payment receipts. Other care flows remain rations2, water4 and cleaning13 actions.

The rate remains 20 Vault per accepted action; the shared limit remains 100 receipts per UUID per UTC day, across side jobs: at most 2,000 Vault per UUID-day. Thus a fully paid preparation pays180 instead of580 Vault; a lesson pays200 instead of600. Removing work removes its receipts; no replacement bonus is added. A shortened complete loop is not a reduction of the per-action rate.

Private shovel/brush/bucket/lead/saddle sets remove inter-worker waiting. Cart updates are fenced to one physics step per native server tick. These changes may increase availability and throughput; elapsed times and realized income have not been measured. Native saddle and borrowed tool props are temporary: original equipment is restored on completion, cancellation or lease loss. No permanent item grant is introduced.

## Before/after calculation

[Inputs](economy-simple-saddle-20261024.json) and [calculator output](economy-simple-saddle-20261024-result.json) use illustrative low/base/high participation1/3/10 and active hours0.25/0.75/2 per day. Potential preparation demand is3/7/21 orders per UUID-day, split normal2/5/14 and lesson1/2/7. These are sensitivity assumptions, not telemetry or a guarantee of fully paid orders after quota exhaustion.

Demand changes88/205/616 to28/65/196 manual receipts. With the unchanged daily cap, modeled Vault mint per UUID-day is1,760/2,000/2,000 before and560/1,300/2,000 after. Network-week mint is12,320/42,000/140,000 before and3,920/27,300/140,000 after. No burn, transfer, escrow or refund is added. Tokens, XP, crystals and permanent items each have zero change in their own units. Multi-UUID exposure remains linear at2,000 Vault per UUID-day; no new network weekly cap exists.

## Live baseline and limits

Read-only ARC Economy V2 baseline boundary1792189952170–1792794752172:3,086 records,31,385 operations,28 players and226 attempts. Vault mint67,918,051.94329402 / burn1,689,911.13 includes67,000,000 administrative mint, excluded from ordinary income. There are276 unclassified operations with224,138.36 absolute Vault amount. Tokens separately mint83 / burn15, with3 unclassified operations and15 absolute amount. This background does not measure ArcOrigin cadence; ArcEcoJobs buckets are a different owner.

The live20/action and100/day policy hash was51473a9539fcd0aeb7cf8e3109ad756c6396b8a361ab6e65faadb99c893aae87. Prices, reward chances, boosts, taxes and progression costs remain unchanged. Current EconomyShopGUI SELL and the planned SELL-to-contract transition remain separate; this task does not activate that transition. Native-client movement and multiplayer visual acceptance are separate from source and protocol checks.
