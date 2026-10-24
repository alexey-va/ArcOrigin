# Farm: completed-order20 / saddling50 — 2026-10-24

Owner decision: charge no action receipts for new work. A completed ordinary farm order accrues20 Vault once. GROOM/SADDLE/LESSON each contain native saddle preparation and accrue50 Vault once. Cancelled partial work earns no new credit. Arrivals, auto-untie, loan cleanup and route callbacks never mint money. Shift-end settlement remains once through the existing Redis journal.

## Before and after

Active baseline was ArcOrigin0.1.67/source0699c55: rations2 paid actions=40 Vault; water4=80; cleanup13=260; normal preparation9=180; lesson10=200. New totals are20/20/20/50/50 Vault. Restored cross-ties add four physical actions and no separate pay. Every new order consumes one shared receipt instead of one per action. A completed order UUID must deduplicate retries, repeated clicks and late completion callbacks.

The configured shared cap remains100 receipts per UUID per UTC date across all four side jobs. Thus the all-saddling upper bound becomes5,000 Vault/day, compared with the old2,000 per-action ceiling. This is an explicit upper-bound increase, not a claim all possible daily mint decreases. There is no separate monetary or network weekly budget. Reusing multiple UUIDs remains linear:10 all-saddling accounts can request350,000 Vault/week versus140,000 before. No new cap or access restriction is imposed without owner request.

[Inputs](economy-farm-per-order-20261024.json) and [calculation](economy-farm-per-order-20261024-result.json) assume low/base/high participation1/3/10, active hours0.5/2/5 and20 potential orders/hour:10/40/100 requested orders/day. The mix is80% ordinary and20% preparation; ordinary expected old actions5 (40%rations,40%water,20%cleanup), preparation9.5 (half normal, half lesson). These are sensitivity assumptions, not actual durations or guaranteed fully paid orders when the old action quota is exhausted.

Before capped mint is1,180/2,000/2,000 Vault per UUID-day; after260/1,040/2,600. Network-week mint8,260/42,000/140,000 becomes1,820/21,840/182,000. The high-case increase follows the change in what consumes quota. Additional player visibility/private tools and restored ties may affect throughput in opposite directions; no timing benefit is measured or assumed.

Other ArcOrigin jobs retain their20 Vault per completed order. Dated inventory comparators: ArcRanks ordinary targets50–175 Vault each (Oct1 activation/Oct20 catalog refresh); tokens on rare targets stay a different currency. A historical Sep7 horse-mount basket is50,000 Vault entry and420,000 through L3, later recorded unchanged Oct2 but not reverified as today's catalog. Illustrative gross earning days for50,000 are42.37/25/25 before versus192.31/48.08/19.23 after; for420,000 are355.93/210/210 versus1615.39/403.85/161.54. These divide assumed gross job mint only. Disposable income, other costs and today's progression basket are unverified, so actual saving time and equipment affordability are unknown. The borrowed saddle/lead/brush are not priced permanent equipment.

## Recovery and other currencies

Previously earned20-action balances, paid receipts, unresolved provider claims and immutable failed writes preserve their original amounts and deduplication identities. Never reset daily count, purge, reprice, or replay an uncertain Vault attempt to implement a new rate. New amounts must survive failed acknowledgement, module replacement, settlement and recovery. Supported codec validation already accepts frozen aggregate earnings independent of today's price; preserve that compatibility.

Tokens, XP, EliteMobs crystals and permanent items each have0 change in their own units. No new MONEY multiplier, tax, price, boost or farm perk is introduced. Original saddle equipment and temporary tools are restored; no loan item becomes sellable. Current EconomyShopGUI SELL and the planned future SELL-to-contract transition remain separate and unchanged.

## Fresh baseline and post-delivery check

Read-only168h window1792219870102..1792824670103:3,061records/36,133operations/28players/223attempts. Vault mint67,899,388.36938506 and burn1,657,449.7100000004 include67,000,000 administrative mint (excluded from ordinary income),225unknown operations/201,647.92 absolute amount. Tokens separately mint81/burn15,3unknown operations/15absolute amount. ArcEcoJobs and activity-hour proxies do not establish ArcOrigin order cadence or ordinary session duration.

Verify source/game tests, old-record recovery, published and active hashes, runtime20/50 policy and one exact post-activation ledger boundary. No ordinary-client smoothness or two-client visual acceptance is implied by code or static preview checks.
