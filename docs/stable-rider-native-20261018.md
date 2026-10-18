# Native stable rider movement

The live 0.1.43 and 0.1.44 care tasks accepted the horse at Miron and immediately assigned the next task. Native packets confirmed Miron as a passenger, but the horse did not move while mounted. The bounded recovery dismounted Miron, returned him home and walked the horse back to its stall. Neither attempt is a successful patrol.

The active Spawn platform is Purpur 1.21.11 build 2568 (`f57bd86`), SHA-256 `34ad0d95aa1210df89def3b344bd57d09a2fe497156876772103baea96d8df21`, with Citizens 2.0.43-SNAPSHOT build 4238, SHA-256 `4d57b6a957d43ae1595dd7d207f8c431428b6f06027e4a1de119da781330cd76`. The active Citizens adapter is `v1_21_R7`. The earlier explanation based on the JAR's `v26_1_R1` adapter did not describe the running server.

The active server bytecode selects a PLAYER passenger as the controller of a saddled horse. `Player.isClientAuthoritative()` returns true, and the Citizens fake player does not override it. The horse therefore fails the effective-AI/simulation gate before its custom server AI tick; changing Controllable does not remove that gate. This explains the observed zero movement with a PLAYER rider in 0.1.44.

Miron (Citizens ID 461, UUID `c7147327-693f-46c3-8159-27847509fcfe`) was changed through the native Citizens API from PLAYER to MANNEQUIN, preserving identity, name, location and other specifications. Citizens supports MANNEQUIN as a human-shaped living entity with a profile, without the PLAYER authority gate. ArcOrigin 0.1.44 already accepts a LivingEntity rider, so this change needs no new runtime JAR. The mounted patrol with the new type remains unverified: the owner stopped bot QA and will perform gameplay acceptance personally. No successful patrol is claimed from static bytecode evidence.

0.1.44 also fixes a separate route-monitor defect: a closed route's starting point cannot count as its final destination until intermediate waypoints are consumed. It snapshots and restores the temporary Controllable trait and movement-speed changes on mounted cleanup, including despawn/replacement while the original lease remains valid. The focused regression run passed 21 mountyard and 41 route-controller tests; full CI, delivery and activation are recorded in the operations receipt.

The enhanced viewer does not currently render MANNEQUIN bodies or their profile skins. Its missing human model is not native-client appearance evidence. The owner requested no further bot QA; the QA client was disconnected on 18 October 2026.
