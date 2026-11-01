The Macros category contains the Anubis macro ports. Each entry starts disabled
and exposes its options in the ClickGUI.

| Module | Use |
| --- | --- |
| AH Sniper | Choose an item, maximum unit price, refresh timing and optional spend/purchase limits. Optional resale has its own price. |
| AH Sell | Configure items, enchantments and listing prices to sell matching inventory on the auction house. |
| Auto Sell | Keep selling through `/sell`, excluding the editable Never Sell list. |
| Order AH Trader | Configure products, restocking/order bids and auction listing prices. |
| Order Dropper | Select an order and number of pages to collect and drop. |
| Tp Macro | Send `/tpa` or `/tpahere` to the configured player at the selected interval. |
| Tpahere Kill Bot | Configure player discovery, request order, exclusions, nearby-player handling, combat, eating, selling and respawn options. LiquidBounce friends are excluded. |
| Panic Pay | Set a recipient for the current session. Enabling sells inventory and selected equipment, then pays the balance to that recipient. Disable to cancel. |
| Crystal Macro | Hold the use key with appropriate combat items to run the crystal/optional obsidian sequence. |
| Anchor Macro | Configure the anchor sequence, guard placement, delays and switch-back behavior. |
| Chat Macro | Configure up to eight keyboard/mouse bindings and messages or commands; choose Send or Open in Chat. |

Entries have an `Anubis` prefix. Configure their settings before enabling them.
Macros stop on disconnect and do not restore an enabled state after restarting.
They use DonutSMP's command and menu formats, so server menu changes can require updates.

Donut Utility also contains Donut Speedmine (removes the pause after completing a
survival block break) and Donut NoJumpDelay (releases held jump input while airborne
and allows it again on landing). Speedmine does not increase block damage per tick.

The supplied archive omits shared Anubis helpers. Those adapters were reconstructed
using LiquidBounce and Minecraft APIs; exact equivalence to the missing originals
and live server behavior have not been verified in game.
