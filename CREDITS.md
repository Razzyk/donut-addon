# Source credits

Module algorithms adapted from m1919810's SlimefunHelper (CC0-1.0):
https://github.com/m1919810/SlimefunHelper/tree/fe5ed1545d7b607746d038a8523da08f0b6753aa

- NoFall.java: NoFallFuckGrimTest2 (the source also credits the LeavesHack author).
- Velocity.java and FakeBlockManager.java: legacy grounded Grim velocity and action acknowledgements.

Build scaffolding adapted from CCBlueX's LiquidBounce-Addon-Template (GPL-3.0-or-later):
https://github.com/CCBlueX/LiquidBounce-Addon-Template/tree/b67a6be64dd8076b2046be7da18e05dd0e7e4e79

Compiled against CCBlueX LiquidBounce v0.41.0, Minecraft 26.3:
https://github.com/CCBlueX/LiquidBounce/tree/826ee0e1656a11f1f3a57075c81a485b4699a754

The Donut code and build scaffolding retain GPL-3.0-or-later. The original SlimefunHelper CC0 license
is included separately in licenses/SlimefunHelper-CC0.txt. LiquidBounce and Minecraft are
runtime dependencies and are not bundled in the addon jar.

MV basefinding and utility modules were adapted from the user-supplied MVADDON.jar
(MV Addon 1.0.0, Minecraft 1.21.11 / Meteor), by Mulveo and Laith, for this addon.
Supplied jar SHA-256: `a0dc4bcb07dc655ac0feab71d85a8189aa7be0a2d405de4bd1c10ef26339d322`.
Their original license and copyright are preserved separately in licenses/MV-Addon.txt;
the GPL notice above does not relicense that code. The port excludes CustomHud,
SpotifyHud, StaffHud, RegionMap, Themes, their custom interface and font assets.

The small inventory and movement adapters follow the methods used from Meteor's utilities:
https://github.com/MeteorDevelopment/meteor-client/blob/master/src/main/java/meteordevelopment/meteorclient/utils/player/InvUtils.java
https://github.com/MeteorDevelopment/meteor-client/blob/master/src/main/java/meteordevelopment/meteorclient/utils/player/PlayerUtils.java

GrimAirPlace was adapted from miles352's meteor-stashhunting-addon
(Jefff's Mods), requested branch `1.21.1`, exact commit
`5f4c8d08b1d0f46c8d54998713217f72bb18f8da`:
https://github.com/miles352/meteor-stashhunting-addon/tree/5f4c8d08b1d0f46c8d54998713217f72bb18f8da

Attribution and the source branch's license status are recorded in
licenses/Stash-Hunting-NOTICE.txt; the surrounding GPL notice does not relicense this
upstream material. ElytraFlyPlusPlus and its flight hooks were removed in 1.3.0.

RegionMap was adapted from the user-supplied `region-map-0.1.0.jar`, by Jeff,
for Meteor / Minecraft 1.21.11. Supplied jar SHA-256:
`7efd5aae02cdbe6ebe26c7c4e825fcfe0dae862a3fdd8463040c5dceab925347`.
Its CC0 license is preserved verbatim in `licenses/Region-Map-CC0.txt`.
This is the separately supplied region/shard map, rather than MVADDON's excluded
RegionMap implementation. No Meteor runtime or duplicate addon is bundled.
