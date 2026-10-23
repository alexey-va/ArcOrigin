# Side-job admission and reliable farm work — 2026-10-23

The owner requested work in every game mode, recovery of stuck cleaning carts,
automatic final parking, and more eligible animals and pens. Admission no
longer rejects Creative or Spectator solely by mode; it retains world, range,
identity, lease, temporary inventory and shared cashier checks. Changing mode
is not a completion or a payment. Native Spectator interaction input remains
unobserved; server admission does not promise that the client emits every click.

## Before and after

Live policy read on Spawn: `reward-per-order: 20`, shared
`daily-order-limit: 100`, policy SHA-256
`51473a9539fcd0aeb7cf8e3109ad756c6396b8a361ab6e65faadb99c893aae87`.
MOUNTYARD counts accepted meaningful action receipts, other families count
orders. The same UUID/UTC-day ceiling remains 2,000 Vault across families.
A completed cleanup still creates 13 receipts / 260 Vault before the remaining
common allowance. Final home parking uses the existing PARK_CART receipt;
arrival, waiting, retries and cart recovery create no additional receipt.
Earned actions survive a later cancellation; no bonus or new quota is added.

For the formerly rejected current-mode cohort, low/base/high assume 1/3/10
participating UUIDs, 60/120/200 accepted actions per active hour, and
0.25/0.5/1 active hour per day. These are sensitivity assumptions, not observed
cadence. They imply 15/60/200 potential receipts per UUID-day. With otherwise
unused common allowance, mint for this cohort changes from 0 to
300/1,200/2,000 Vault per UUID-day and 2,100/25,200/140,000 Vault per network-week.
A UUID could already switch to Survival, so this is not a forecast of wholly
new network participants. Ten UUIDs could still reach 20,000 Vault per day;
there is no global weekly budget or newly introduced multiaccount rule.

Creative movement, cart recovery and more valid pens can shorten travel or
restore previously blocked completions. The resulting real hourly throughput
is unknown. Neighboring jobs retain the same common budget. MONEY boosts,
prices, taxes and permanent equipment grants are unchanged. Premium tokens,
player XP and crystals separately remain zero; borrowed props remain temporary
and non-saleable. No currency conversion is assumed. Current SELL and the
owner's future SELL-to-contract transition remain separate and unchanged.
No progression prices change and no savings-time claim follows from this data.

The [input](economy-all-mode-side-jobs-20261023.json) and
[calculator result](economy-all-mode-side-jobs-20261023-result.json) preserve
currencies separately. The calculator completed successfully before the
admission edit.

## Observations and verification boundaries

Read-only ledger interval: 2026-10-16T15:04:12.188Z to
2026-10-23T15:04:12.188Z (since 1792163052188, generated 1792767852188).
Vault mint was 67,933,901.32570809 and burn 2,440,764.22 across 33,960
operations. Administrative mint of 67,000,000 is excluded from ordinary-income
interpretation. Vault has 276 unclassified operations / 224,138.36 absolute
units. Tokens separately: mint 81, burn 15, 53 operations, including 3
unclassified operations / 15 absolute units. There were 30 observed players;
activity buckets and ArcEcoJobs categories do not establish ArcOrigin play hours
or an action rate. These gaps prevent a measured network-emission prediction.

Focused checks cover mode changes, accepted receipt identity, final parking,
cart floor recovery, actor eligibility and lease cleanup. Source verification,
publication, disk delivery, activation and ordinary Minecraft-client behavior
are separate evidence layers. Post-delivery ledger observation must retain its
exact interval and cannot establish causality without relevant player activity.
