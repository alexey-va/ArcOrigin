# Native stable rider movement

The live 0.1.43 care task accepted the horse at Miron and immediately assigned the next task. Native packets confirmed Miron as the horse's passenger, but the horse did not move during the 60-second mounted phase. The bounded recovery then dismounted Miron, returned him home and walked the horse back to its stall. This was a failed patrol, not a successful ride.

The exact Spawn Citizens runtime is 2.0.43-SNAPSHOT build 4238 (JAR SHA-256 `4d57b6a957d43ae1595dd7d207f8c431428b6f06027e4a1de119da781330cd76`). Its horse adapter requires enabled `Controllable` with a passenger to simulate movement on the server. The trait itself yields while the navigator is active. This was checked against the runtime bytecode and the [official HorseController source](https://github.com/CitizensDev/Citizens2/blob/2ce418ef6fca809a04132cde29f76bae5c7cbe8b/v26_1_R1/src/main/java/net/citizensnpcs/nms/v26_1_R1/entity/HorseController.java).

0.1.44 enables the trait before mounting and restores its original presence/enabled state on dismount, revocation, cleanup or rejected mount. Cleanup also restores the persistent NPC trait after despawn or native-entity replacement while its original lease and NPC identity remain current; it does not alter a replacement entity or a new owner. It also restores the original movement-speed attribute, which Citizens changes during this mode. The existing collision-checked route remains in charge; no teleportation is used to complete it.

A separate route-monitor defect hid the stationary circular route from stall detection: its start was near its final coordinates even though intermediate points remained. Endpoint admission now requires the strategy to have reached its final remaining waypoint. This applies both to terminal success and the turning-at-destination stall exemption.

The focused regression run on JDK 25 first produced three expected assertion failures, then passed all 21 mountyard and 41 route-controller tests after the correction. Unit tests do not prove native mounted physics; full CI, delivery, activation and the repeated live patrol are recorded separately in the operations receipt.
