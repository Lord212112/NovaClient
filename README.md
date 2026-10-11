# Nova Client (test client for your own server)

Fabric, Minecraft 1.21.11. Open the GUI with **Right Shift**.

## Build
1. https://fabricmc.net/develop -> 1.21.11, copy exact versions into `gradle.properties`.
2. JDK 21, `gradle build` (needs Gradle 9.x; or `./gradlew build` if you have the wrapper). After building, check the jar is not empty: `unzip -l build/libs/nova-client-2.6.1.jar` must list `fabric.mod.json` and `nova/*.class`. Jar: `build/libs/nova-client-2.6.1.jar` (not `-sources`). Put it in `mods/` with Fabric API.

## GUI
Icon tabs on the left, module cards in two columns. Left click = toggle, right click = fold out settings,
middle click = bind key (Esc cancels, Delete clears). Search top right. Config: `config/nova.json`.
Click sounds / accent colour: Client tab (GUI, Theme).

## Schematic builder (World > Schematic)
Put `.litematic` files in `.minecraft/schematics` or `config/nova/schematics`. Enable the module, pick the file,
"Load selected file", stand where you want it and press "Origin: my position" (or look at a block + "Origin: block I look at").
It turns smoothly to each face (Aim speed / Max turn), only clicks when the crosshair is on that face, stays in reach.
"Need N item" shows what is missing. In survival it opens chests/barrels within reach and takes the missing items; creative fills from nothing.
"Load into Litematica" (default on) hands the loaded file + origin + rotation to Litematica as a placement, so Litematica renders it (needs the Litematica mod; it follows origin/rotation/offset changes and is removed on Unload).
"Built-in preview" is the old outline renderer, off by default because it is slow; only use it without Litematica.
"Auto walk" walks (W + jump, smooth turning) to the nearest spot where the next block can be placed, then places as usual. "Show path" draws the route.

## Changes in 2.6.1
- GUI glow reworked to match the orange look: soft wide ambient glow around the window (smooth quadratic falloff, no more hard rings), brighter accent rim, enabled cards get a soft glow + accent border + top sheen, toggles, tabs and slider fills glow wider. Default accent is a warmer orange.

## Changes in 2.6.0
- Filler blocks (World > Schematic > "Filler blocks", on by default; pick the block with "Filler block"):
  - The route search can now bridge: where there is nothing to stand on it plans a filler block (flat bridge, or a two-block stair step up). Walking crouches and puts the block down before stepping onto it.
  - If the next pending block has nothing to click against (floating part, schematic starting in the air), the builder walks to the ground under it and builds a filler column up to it, then places the real block on top.
  - Filler blocks are never put on a cell that belongs to the schematic.
  - "Remove filler when done" breaks all filler blocks again once the whole schematic is built.
- The builder tries many targets before it says "No route".

## Changes in 2.5.1
- Route search now prefers the part that is already built: standing on built blocks is cheap, stepping off them is expensive, so the builder stays on the built part whenever a route on it exists.
- Route search tries the 24 nearest placeable blocks at once instead of one block at a time (a single unreachable block no longer stops the build), is limited to the area around the schematic, and has a bigger node budget.
- Walk goals use a 0.6 smaller reach so arriving a little off the spot centre still lets the block be placed.
- Placement: blocks with a facing (stairs, doors, gates, chests, furnaces, glazed terracotta) are only clicked once the server already has the new rotation; slab/stair/trapdoor halves are checked against where the crosshair really is, and faces that would give the wrong half are not used; interactive blocks (chests, doors, buttons, ...) are never used as the block to click against; solid blocks are not placed into the space you stand in.

## Changes in 2.5.0
- Auto walk never turns the camera: it presses forward/back/strafe keys relative to where you look, so it can aim at a block and walk at the same time (no look-forward / look-back cycles). It keeps walking while it places blocks in reach.
- Lava: cells next to lava are not used for routes, and walking stops with "Lava ahead" if the next step or what is under it is lava.
- "Sneak at edges": crouches while walking when the ground ahead is missing, except on steps where the route itself goes down (drops, ladders, water).

## Changes in 2.4.0
- Schematic is now loaded into Litematica automatically (reflection, no hard dependency); the built-in outline preview is off by default (it caused the lag).
- Progress recount runs in slices instead of one big pass, and out-of-reach blocks are rejected cheaply before planning.

## Changes in 2.3.0
- Schematic builder walks to chests/barrels (within ~16 blocks) when it runs out of material, takes what is missing, then goes back to building. Unreachable chests are skipped.
- Path finder now handles doors and fence gates (opens them on the way, not iron doors), ladders and vines (climb up/down) and water (wades/swims, drops into water).
- Blocks that need a facing (stairs, doors, gates, chests, furnaces, glazed terracotta) are now planned from the direction you will be looking when you click: the builder picks a click face and standing spot where that direction matches, instead of failing after turning.

## Changes in 2.2.0
- GUI keeps its tab, scroll position, search text and unfolded cards when you close and reopen it.
- Neon look: pulsing halo around the window, enabled modules, toggles, slider fills, selected tab and the title.
- Schematic builder: ghost preview of the schematic, auto walk with a simple path finder (flat / 1 up / up to 3 down), walk target selection, route preview, and blocks that can't be clicked are parked instead of blocking the build.

## Changes in 2.1.1
- ChunkPos is read through helpers (U.cx / U.cz) so it compiles whether ChunkPos is a record or a class with fields.
- Module errors in onEnable/onDisable are now written to the log instead of being swallowed.
- Right Shift key edge detection no longer goes stale while the GUI is open.
