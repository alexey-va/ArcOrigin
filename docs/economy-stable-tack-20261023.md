# Stable equipment and tack: economy assessment

The physical rack, borrowed tools and detailed tack sequence remain part of an existing MOUNTYARD order. Taking a tool, grooming, placing the pad or saddle, tightening the girth, fitting the bridle, adjusting stirrups and returning equipment do not create payable events. The whole accepted order is credited once, after its required work and returns; the existing shift settlement remains the payment owner.

The source baseline was ArcOrigin 0.1.57 at `f111396`; the live policy captured on 2026-10-23 set 20 Vault per accepted order and 100 shared orders per UUID per UTC day. The loaded plugin reported 0.1.57 at that capture; its source hash was not established by that version read. The subsequent 0.1.58 turn/cart/pickup hotfix leaves this policy unchanged. WAITER, MOUNTYARD, FORGE and AUCTION share the quota. The ceiling is 2,000 Vault per UUID-day; no network weekly budget is added.

The [assessment input](economy-stable-tack-20261023.json) and [calculator result](economy-stable-tack-20261023-result.json) use matched total side-job completion counts, rather than a measured tack-job rate:

| Scenario | Participants | Assumed orders per UUID-day | Vault per UUID-day, before → after | Network Vault per week, before → after |
| --- | ---: | ---: | ---: | ---: |
| Low | 5 | 10 | 200 → 200 | 7,000 → 7,000 |
| Base | 20 | 40 | 800 → 800 | 112,000 → 112,000 |
| High | 50 | 100 | 2,000 → 2,000 | 700,000 → 700,000 |

Tokens remain zero and are a separate currency. XP, crystals, permanent gear, prices, taxes and multipliers do not change. Borrowed equipment cannot become an ordinary inventory reward, crafting material or saleable output. Preparation and lesson orders receive twice the configured order lifetime (480 seconds with the current 240-second default), bounded by the existing explicit shift hard stop. Other orders keep their original lifetime, and individual clicks do not extend a deadline. The configured preparation tour visits about 205–216 blocks of waypoint segments and can include the existing 60-second automatic ride limit; those source distances are a planning aid, not a measured player route or completion time. The new physical returns and post-ride untacking change the order's elapsed time; neither that duration nor the order-type mix has been measured. The matched-count table therefore establishes unchanged value and limits, not unchanged income per hour. Each additional accepted order can add 20 Vault only until the existing shared cap; repeated substeps or callbacks must never add credit.

The fresh ledger covered exactly 2026-10-16T00:04:21.585Z–2026-10-23T00:04:21.585Z, with 3,393 records, 34,013 operations and 28 players. It recorded 67,917,586.30477443 Vault minted and 2,581,843.3499999987 Vault burned; tokens separately recorded 59 minted and 15 burned. A single administrative issuance accounts for 67,000,000 Vault and is excluded from ordinary-income reasoning. Unclassified activity is 131 operations / 189,691.78 Vault and, separately, 3 operations / 15 tokens. The jobs breakdown identifies ArcEcoJobs and cannot establish ArcOrigin cadence. Current shop SELL income of 55,995.33 Vault is a separate flow; the owner's future SELL-to-contract transition remains outside this change.

Focused verification must preserve one credit for a complete order, no credit for an unfinished or cancelled order, no duplicated borrowed item on repeated clicks, restoration on quit/reload, and one settlement for the shift. Source checks, rendered model previews, delivered JARs, loaded runtime and native-client acceptance are separate evidence.
