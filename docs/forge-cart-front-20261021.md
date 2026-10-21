# Forge cart in front of the worker

The odd-job cart now uses Savva's ambient posture: one block ahead of the worker, handles back towards the worker. `origin-scenes.yml` uses `follow-offset: 0,0.5625,1.0` and item yaw offset 180 degrees. The attached cart's bed rotation is `Y(-bodyYaw)`; its additional item rotation cancels the native ItemDisplay Y+180 base. Saving item yaw `bodyYaw+180` preserves both cart and cargo poses when the cart detaches for unloading. The existing horizontal arrival radius 4 and vertical allowance 3 remain unchanged.

Grounding used the existing analyzer and textured renderer from `itemsadder-item-display-grounding`, the local ItemsAdder declaration and the existing cached server pack. Identity: `elitecreatures:medieval_market_decoration_v1_cart_2`, PAPER, CMD 10747, model `elitecreatures:medieval_market_decoration_v1/cart_2`, resolved model SHA-256 `06fcde3cc7fdd62b8bcd5920dbd806c3867dd719abcae279c8fc11f58a3d425c`. Texture `medeivalmarket_02.png`, SHA-256 `7e8419ca2b032b3bf3e0df682dd3d45c7489ea052e63fcc837c2aeba0a0404ee`, is resolved with its real UVs and face rotations.

Both calculations use GROUND context (model translation `0,3,0` and scale `.25`), entity scale 3, identity right rotation and native item base Y+180. The representative carry fixture has player feet `0,69,0`, body yaw 0 and standing height 1.8; its support plane is Y=69. This verifies the unchanged lift on a flat mathematical support, not the terrain along a live player route. Earlier native parking-point evidence remains in [native-lead-cart-20261018.md](native-lead-cart-20261018.md).

| State | Entity position | Entity translation | Left quaternion XYZW | Entity yaw | Ground residual |
| --- | --- | --- | --- | --- | --- |
| Attached | `0,70.8,0` (native passenger origin) | `0,-1.2375,1` | `0,1,0,0` | 0 | 0 |
| Released | `0,69.5625,1` | `0,0,0` | `0,0,0,1` | 180 | 0 |

Both reports classify as `grounded`. World bounds agree within `1.43e-14` blocks: X `[-.5859375,.5859375]`, Y `[69,70.01585879859127]`, Z `[-.08467963675931811,1.9135910517934134]`. The handles are the model's negative-Z arms (elements 10 and 13), so their tips return to the worker's side. No pitch, roll, model, scale or lift was changed.

Reproduce from the workspace, setting `skill` to the installed grounding skill directory and `pack` to the existing cached pack directory:

```sh
python3 -B "$skill/scripts/analyze_itemsadder_display.py" --itemsadder-root ruscrafting-ops/classic/plugins/ItemsAdder --resource-pack "$pack" --item-id elitecreatures:medieval_market_decoration_v1_cart_2 --context ground --scale 3,3,3 --yaw 0 --entity-position 0,70.8,0 --translation 0,-1.2375,1 --left-quaternion 0,1,0,0 --surface-y 69 --output /private/tmp/forge-cart-front-20261021.json --report
python3 -B "$skill/scripts/render_itemsadder_display.py" /private/tmp/forge-cart-front-20261021.json --output /private/tmp/forge-cart-front-20261021.html
```

For the released report, use yaw 180, entity position `0,69.5625,1`, translation zero and identity left quaternion. Local report: `/private/tmp/forge-cart-front-park-20261021.json`. Cached pack: `task-worktrees/restaurant-waiter-ops-20261016/scripts/player-bot/.cache/resource-packs/server/a536d0169661c8a1d62ba0e2634a4778cd8e5783a96acebf375147c4253acd8b`.

The tests cover forward placement and handle direction at six headings, the world transform of the cart and every cargo display across carry-to-unload at five headings, and the unchanged arrival boundary. The textured HTML was generated, but browser security rejected its local `file://` URL, so it was not visually observed. The report warning `active_client_pack_identity_not_verified` remains; no native-client rendering, animation or gameplay acceptance is claimed.
