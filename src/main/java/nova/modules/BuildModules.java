package nova.modules;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import nova.core.*;
import nova.modules.ClientModules.Theme;
import nova.schem.Litematic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class BuildModules {
    private BuildModules() {}

    /**
     * Builds a loaded .litematic by placing blocks the way a player would: turns smoothly towards the face it clicks,
     * only clicks when the crosshair is really on that face, stays inside reach and needs line of sight.
     * Shows the schematic as coloured ghost outlines and can walk to wherever the next block can be placed from.
     * Put .litematic files in .minecraft/schematics (Litematica's folder) or config/nova/schematics.
     */
    public static class Schematic extends Mod {
        Setting.Mode file = mode("File", "None", "None");
        Setting.Action refresh = action("Refresh file list", this::refresh);
        Setting.Action load = action("Load selected file", this::loadSelected);
        Setting.Action originHere = action("Origin: my position", this::originHere);
        Setting.Action originLook = action("Origin: block I look at", this::originLook);
        Setting.Action unload = action("Unload", this::unload);
        Setting.Mode anchor = mode("Origin is", "Corner", "Corner", "Center");
        Setting.Mode rotation = mode("Rotation", "0", "0", "90", "180", "270");
        Setting.Num offX = num("Offset X", 0, -64, 64, 1), offY = num("Offset Y", 0, -64, 64, 1), offZ = num("Offset Z", 0, -64, 64, 1);
        Setting.Mode order = mode("Order", "Bottom-up", "Bottom-up", "Nearest");
        Setting.Num reach = num("Reach", 4.5, 2, 6, 0.1);
        Setting.Num perTick = num("Blocks / tick", 1, 1, 6, 1);
        Setting.Num delay = num("Delay (ticks)", 1, 0, 10, 1);
        Setting.Bool rotate = bool("Rotate", true), ray = bool("Line of sight check", true), humanize = bool("Humanize", true),
                matchFacing = bool("Match facing", true), creative = bool("Creative fill", true);
        Setting.Bool restock = bool("Take items from chests", true);
        Setting.Num aimSpeed = num("Aim speed", 40, 5, 100, 1), maxTurn = num("Max turn / tick", 25, 2, 90, 1);
        Setting.Bool outline = bool("Show outline", true), showNext = bool("Show next block", true), progress = bool("Progress HUD", true);
        Setting.Bool useLitematica = bool("Load into Litematica", true), showSchem = bool("Built-in preview", false), walk = bool("Auto walk", true), edgeSafety = bool("Sneak at edges", true), showPath = bool("Show path", true);
        Setting.Num ghostRange = num("Preview range", 12, 6, 48, 1), ghostMax = num("Preview blocks", 40, 10, 400, 10);

        record Plan(int idx, BlockPos pos, BlockPos against, Direction face, Vec3d hit, Item item, BlockState state) {}

        Litematic schem;
        List<Litematic.Entry> view = new ArrayList<>();
        String viewRot = "";
        int vx, vy, vz;
        BlockPos origin;
        final BitSet done = new BitSet();
        int firstPending, doneCount, tick, cd, curIdx = -1;
        final Map<Item, Integer> missing = new LinkedHashMap<>();
        BlockPos nextPos, chestPos;
        int rcd, openTries, restockTick;
        final Set<BlockPos> emptied = new HashSet<>();
        String status = "";

        // block index (view is sorted by y, z, x so the packed keys are ascending -> binary search gives the entry index)
        long[] keys = new long[0];
        final BitSet surf = new BitSet();

        // Litematica bridge
        Object litePlacement;
        String liteKey = "", liteFailKey = "";
        boolean liteWarned;
        int liteTick;
        Object liteWorld;

        // incremental recount
        int recPos = -1, recCount;
        BitSet recDone = new BitSet();
        Map<Item, Integer> recNeed = new HashMap<>();

        // ghost preview
        record Ghost(BlockPos pos, int color) {}
        private record G(double d, int i) {}
        List<Ghost> ghosts = List.of();
        int ghostTick;

        // walking
        List<BlockPos> path = List.of();
        int pathIdx, walkIdx = -1, stuck, pathTicks, walkCd, doorCd, walkClock, failIdx = -1, failCount;
        Vec3d lastPos;
        BlockPos walkChest, foundChest;
        boolean keysHeld;
        String walkStatus = "";
        final Map<Integer, Integer> badTargets = new HashMap<>();

        public Schematic() { super("Schematic", Category.WORLD, "Auto builds a loaded .litematic file."); }

        private List<Path> dirs() {
            return List.of(FabricLoader.getInstance().getGameDir().resolve("schematics"),
                           FabricLoader.getInstance().getConfigDir().resolve("nova").resolve("schematics"));
        }

        private Path find(String name) {
            for (Path d : dirs()) { Path p = d.resolve(name); if (Files.exists(p)) return p; }
            return null;
        }

        void refresh() {
            List<String> names = new ArrayList<>();
            for (Path d : dirs()) {
                try { Files.createDirectories(d); } catch (Exception ignored) {}
                try (Stream<Path> st = Files.list(d)) {
                    st.filter(f -> f.getFileName().toString().endsWith(".litematic"))
                      .forEach(f -> { String n = f.getFileName().toString(); if (!names.contains(n)) names.add(n); });
                } catch (Exception ignored) {}
            }
            Collections.sort(names);
            if (names.isEmpty()) names.add("None");
            file.setModes(names.toArray(new String[0]));
            status = names.get(0).equals("None") ? "No .litematic files found in the schematics folder." : names.size() + " file(s) found.";
            U.chat(status);
        }

        void loadSelected() {
            Path p = file.is("None") ? null : find(file.value);
            if (p == null) { status = "Pick a file first."; U.chat(status); return; }
            try {
                schem = Litematic.load(p);
                viewRot = "";
                rebuildView();
                status = "Loaded " + schem.name + " (" + schem.blocks.size() + " blocks, " + schem.sx + "x" + schem.sy + "x" + schem.sz + ")";
                U.chat(status + (origin == null ? ". Now set the origin." : ""));
            } catch (Throwable t) {
                schem = null; view.clear();
                status = "Could not load: " + t.getMessage();
                U.chat(status);
            }
        }

        void unload() { schem = null; view.clear(); done.clear(); nextPos = null; curIdx = -1; ghosts = List.of(); keys = new long[0]; surf.clear(); resetWalk(); removeLite(); status = "Unloaded."; }

        void originHere() {
            if (mc.player == null) return;
            origin = mc.player.getBlockPos();
            resetProgress();
            U.chat("Origin set to " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
        }

        void originLook() {
            if (mc.player == null) return;
            if (mc.crosshairTarget instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK) {
                origin = h.getBlockPos().offset(h.getSide());
                resetProgress();
                U.chat("Origin set to " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
            } else U.chat("Look at a block first.");
        }

        private void resetProgress() { done.clear(); firstPending = 0; doneCount = 0; curIdx = -1; tick = 0; recPos = -1; ghosts = List.of(); badTargets.clear(); resetWalk(); }

        void rebuildView() {
            if (schem == null) return;
            viewRot = rotation.value;
            int rot = Integer.parseInt(viewRot) / 90;
            BlockRotation br = switch (rot) { case 1 -> BlockRotation.CLOCKWISE_90; case 2 -> BlockRotation.CLOCKWISE_180; case 3 -> BlockRotation.COUNTERCLOCKWISE_90; default -> BlockRotation.NONE; };
            int sx = schem.sx, sz = schem.sz;
            vx = rot % 2 == 0 ? sx : sz; vy = schem.sy; vz = rot % 2 == 0 ? sz : sx;
            List<Litematic.Entry> out = new ArrayList<>(schem.blocks.size());
            for (Litematic.Entry e : schem.blocks) {
                int x = e.x(), z = e.z(), nx, nz;
                switch (rot) {
                    case 1 -> { nx = sz - 1 - z; nz = x; }
                    case 2 -> { nx = sx - 1 - x; nz = sz - 1 - z; }
                    case 3 -> { nx = z; nz = sx - 1 - x; }
                    default -> { nx = x; nz = z; }
                }
                out.add(new Litematic.Entry(nx, e.y(), nz, rot == 0 ? e.state() : e.state().rotate(br)));
            }
            Litematic.sort(out);
            view = out;
            buildIndex();
            resetProgress();
        }

        private static long key(int x, int y, int z) { return ((long) y << 42) | ((long) z << 21) | (long) x; }

        private int idxAt(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= vx || y >= vy || z >= vz) return -1;
            int i = Arrays.binarySearch(keys, key(x, y, z));
            return i >= 0 ? i : -1;
        }

        private void buildIndex() {
            int n = view.size();
            keys = new long[n];
            for (int i = 0; i < n; i++) { Litematic.Entry e = view.get(i); keys[i] = key(e.x(), e.y(), e.z()); }
            surf.clear();
            for (int i = 0; i < n; i++) {
                Litematic.Entry e = view.get(i);
                int x = e.x(), y = e.y(), z = e.z();
                if (idxAt(x + 1, y, z) < 0 || idxAt(x - 1, y, z) < 0 || idxAt(x, y + 1, z) < 0
                        || idxAt(x, y - 1, z) < 0 || idxAt(x, y, z + 1) < 0 || idxAt(x, y, z - 1) < 0) surf.set(i);
            }
        }

        private boolean pending(BlockPos w) {
            BlockPos b = base();
            if (b == null) return false;
            int i = idxAt(w.getX() - b.getX(), w.getY() - b.getY(), w.getZ() - b.getZ());
            return i >= 0 && !done.get(i);
        }

        private BlockPos base() {
            if (origin == null) return null;
            int cx = anchor.is("Center") ? -(vx / 2) : 0, cz = anchor.is("Center") ? -(vz / 2) : 0;
            return origin.add(offX.getInt() + cx, offY.getInt(), offZ.getInt() + cz);
        }

        private BlockPos world(Litematic.Entry e) { return base().add(e.x(), e.y(), e.z()); }

        @Override public void onEnable() { refresh(); if (schem == null && !file.is("None")) loadSelected(); }
        @Override public void onDisable() { nextPos = null; curIdx = -1; ghosts = List.of(); resetWalk(); }

        private int findSlot(Item it) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(it)) return i;
            return -1;
        }

        private boolean canGet(Item it) { return findSlot(it) >= 0 || (creative.get() && mc.player.isCreative()); }

        private boolean prepare(Item it) {
            var p = mc.player; var inv = p.getInventory();
            int s = findSlot(it);
            if (s < 0) {
                if (!(creative.get() && p.isCreative())) return false;
                mc.interactionManager.clickCreativeStack(new ItemStack(it), 36 + inv.getSelectedSlot());
                return true;
            }
            if (s < 9) { inv.setSelectedSlot(s); return true; }
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, s, inv.getSelectedSlot(), SlotActionType.SWAP, p);
            return true;
        }

        private static boolean skipState(BlockState s) {
            if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
            if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) return true;
            return s.getBlock() instanceof FluidBlock;
        }

        private static Direction needFacing(BlockState s) {
            if (!s.contains(Properties.HORIZONTAL_FACING)) return null;
            Direction f = s.get(Properties.HORIZONTAL_FACING);
            Block b = s.getBlock();
            if (b instanceof StairsBlock || b instanceof DoorBlock || b instanceof FenceGateBlock) return f;
            if (b instanceof ChestBlock || b instanceof AbstractFurnaceBlock || b instanceof GlazedTerracottaBlock) return f.getOpposite();
            return null;
        }

        private static boolean top(BlockState s) {
            return (s.contains(Properties.SLAB_TYPE) && s.get(Properties.SLAB_TYPE) == SlabType.TOP)
                    || (s.contains(Properties.BLOCK_HALF) && s.get(Properties.BLOCK_HALF) == BlockHalf.TOP);
        }

        private static boolean bottom(BlockState s) {
            return (s.contains(Properties.SLAB_TYPE) && s.get(Properties.SLAB_TYPE) == SlabType.BOTTOM)
                    || (s.contains(Properties.BLOCK_HALF) && s.get(Properties.BLOCK_HALF) == BlockHalf.BOTTOM);
        }

        private static Direction faceOf(BlockPos pos, Vec3d hit) {
            Vec3d d = hit.subtract(Vec3d.ofCenter(pos));
            double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
            if (ax >= ay && ax >= az) return d.x > 0 ? Direction.EAST : Direction.WEST;
            if (ay >= az) return d.y > 0 ? Direction.UP : Direction.DOWN;
            return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        private Plan plan(int i, Vec3d eye) { return plan(i, eye, false, 0); }

        private Plan plan(int i, Vec3d eye, boolean ignoreFacing) { return plan(i, eye, ignoreFacing, 0); }

        /** Blocks that open a screen / toggle when right clicked: clicking against them would not place anything. */
        private static boolean interactive(BlockState s) {
            Block b = s.getBlock();
            return b instanceof BlockWithEntity || b instanceof CraftingTableBlock || b instanceof DoorBlock || b instanceof TrapdoorBlock
                    || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof AnvilBlock
                    || b instanceof NoteBlock;
        }

        /** Horizontal direction you face when looking from eye to hit; null when that is too close to a diagonal / straight up or down to be reliable. */
        private static Direction facingFrom(Vec3d eye, Vec3d hit) {
            double dx = hit.x - eye.x, dz = hit.z - eye.z, ax = Math.abs(dx), az = Math.abs(dz), mx = Math.max(ax, az);
            if (mx < 0.6 || Math.abs(ax - az) < 0.15 * mx) return null;
            return ax > az ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }

        /** margin > 0 = planning for a spot we have not reached yet: shrink the reach so small position errors on arrival do not matter. */
        private Plan plan(int i, Vec3d eye, boolean ignoreFacing, double margin) {
            Litematic.Entry e = view.get(i);
            BlockPos wp = world(e);
            double rr = reach.get() - margin;
            if (Vec3d.ofCenter(wp).squaredDistanceTo(eye) > (rr + 1.0) * (rr + 1.0)) return null;
            BlockState want = e.state(), cur = mc.world.getBlockState(wp);
            if (cur.getBlock() == want.getBlock()) { if (!done.get(i)) { done.set(i); doneCount++; } return null; }
            if (skipState(want)) { done.set(i); return null; }
            if (!cur.isReplaceable()) return null;
            Item item = want.getBlock().asItem();
            if (item == Items.AIR) { done.set(i); return null; }
            if (!canGet(item)) return null;
            // never place a solid block into the space we are standing in (the server refuses it and the block "ghosts")
            if (margin == 0 && !want.getCollisionShape(mc.world, wp).isEmpty() && mc.player.getBoundingBox().intersects(new Box(wp))) return null;
            Direction need = needFacing(want);
            Direction.Axis axis = want.contains(Properties.AXIS) ? want.get(Properties.AXIS) : null;

            Plan best = null; double bd = Double.MAX_VALUE;
            for (Direction d : Direction.values()) {
                BlockPos n = wp.offset(d);
                BlockState ns = mc.world.getBlockState(n);
                if (ns.isReplaceable() || !ns.getFluidState().isEmpty() || interactive(ns)) continue;
                Direction face = d.getOpposite();
                if (axis != null && face.getAxis() != axis) continue;
                // clicking the top of a block always gives the bottom half, clicking the underside always the top half
                if (face == Direction.UP && top(want)) continue;
                if (face == Direction.DOWN && bottom(want)) continue;
                double yOff = face.getAxis().isHorizontal() ? (top(want) ? 0.25 : bottom(want) ? -0.25 : 0) : 0;
                Vec3d hit = Vec3d.ofCenter(n).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5 + yOff, face.getOffsetZ() * 0.5);
                if (!ignoreFacing && matchFacing.get() && need != null && facingFrom(eye, hit) != need) continue;
                Vec3d fv = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
                if (eye.subtract(hit).dotProduct(fv) <= 0) continue;
                double dd = eye.distanceTo(hit);
                if (dd > rr) continue;
                if (ray.get()) {
                    BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, hit, RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, mc.player));
                    if (rh.getType() == HitResult.Type.BLOCK && !rh.getBlockPos().equals(n)) continue;
                }
                if (dd < bd) { bd = dd; best = new Plan(i, wp, n, face, hit, item, want); }
            }
            return best;
        }

        private Plan pick(Set<BlockPos> skip) {
            Vec3d eye = mc.player.getEyePos();
            if (curIdx >= 0 && curIdx < view.size() && !done.get(curIdx) && !bad(curIdx)) {
                Plan pl = plan(curIdx, eye);
                if (pl != null && !skip.contains(pl.pos())) return pl;
            }
            curIdx = -1;
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            BlockPos b = base();
            int lowY = (int) Math.floor(eye.y - reach.get() - 2) - b.getY(), highY = (int) Math.ceil(eye.y + reach.get() + 2) - b.getY();
            int lo = 0, hi = view.size();
            while (lo < hi) { int m = (lo + hi) >>> 1; if (view.get(m).y() < lowY) lo = m + 1; else hi = m; }
            Plan best = null; double bs = Double.MAX_VALUE; int bestY = Integer.MIN_VALUE;
            for (int i = Math.max(lo, firstPending); i < view.size(); i++) {
                Litematic.Entry e = view.get(i);
                if (e.y() > highY) break;
                if (best != null && order.is("Bottom-up") && e.y() > bestY) break;
                if (done.get(i) || bad(i)) continue;
                double qx = b.getX() + e.x() + 0.5 - eye.x, qy = b.getY() + e.y() + 0.5 - eye.y, qz = b.getZ() + e.z() + 0.5 - eye.z, qr = reach.get() + 1.0;
                if (qx * qx + qy * qy + qz * qz > qr * qr) continue;
                Plan pl = plan(i, eye);
                if (pl == null || skip.contains(pl.pos())) continue;
                double s = Vec3d.ofCenter(pl.pos()).squaredDistanceTo(eye);
                if (best == null || s < bs) { best = pl; bs = s; bestY = e.y(); }
            }
            if (best != null) curIdx = best.idx();
            return best;
        }

        /** Recounts progress / missing items in slices of a few thousand blocks per tick (a full pass in one tick caused lag spikes). */
        private void recount() {
            if (base() == null) { recPos = -1; return; }
            if (recPos < 0) { recPos = 0; recDone = new BitSet(); recCount = 0; recNeed = new HashMap<>(); }
            int end = Math.min(view.size(), recPos + 3000);
            for (; recPos < end; recPos++) {
                Litematic.Entry e = view.get(recPos);
                BlockState cur = mc.world.getBlockState(world(e));
                if (cur.getBlock() == e.state().getBlock() || skipState(e.state())) { recDone.set(recPos); recCount++; continue; }
                Item it = e.state().getBlock().asItem();
                if (it != Items.AIR) recNeed.merge(it, 1, Integer::sum);
            }
            if (recPos < view.size()) return;
            recPos = -1;
            done.clear(); done.or(recDone); doneCount = recCount; firstPending = 0;
            missing.clear();
            if (creative.get() && mc.player.isCreative()) return;
            var inv = mc.player.getInventory();
            for (var en : recNeed.entrySet()) {
                int have = 0;
                for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(en.getKey())) have += inv.getStack(i).getCount();
                if (have < en.getValue()) missing.put(en.getKey(), en.getValue() - have);
            }
        }

        // ------------------------------------------------------------------ Litematica bridge
        private static java.lang.reflect.Method method(Class<?> c, String name, int params) {
            for (var m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) return m;
            return null;
        }

        private static Object liteManager() throws Exception {
            return Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager").invoke(null);
        }

        private void removeLite() {
            Object pl = litePlacement;
            litePlacement = null; liteKey = "";
            if (pl == null) return;
            try {
                Object mgr = liteManager();
                var rm = method(mgr.getClass(), "removeSchematicPlacement", 1);
                if (rm != null) rm.invoke(mgr, pl);
            } catch (Throwable ignored) {}
        }

        /** Keeps a Litematica placement in sync with the file / origin / rotation chosen here, so Litematica renders the schematic. */
        private void syncLitematica() {
            if (!useLitematica.get() || schem == null || origin == null || file.is("None")) return;
            if (!FabricLoader.getInstance().isModLoaded("litematica")) {
                if (!liteWarned) { liteWarned = true; U.chat("Litematica is not installed - turn on \"Built-in preview\" to see the schematic."); }
                return;
            }
            if (liteWorld != mc.world) { liteWorld = mc.world; litePlacement = null; liteKey = ""; }   // Litematica drops placements when the world changes
            BlockPos b = base();
            int rot = Integer.parseInt(rotation.value) / 90;
            // Litematica rotates around the placement origin, this module rotates the box around its corner: shift the origin to match
            BlockPos lo = switch (rot) {
                case 1 -> b.add(vx - 1, 0, 0);
                case 2 -> b.add(vx - 1, 0, vz - 1);
                case 3 -> b.add(0, 0, vz - 1);
                default -> b;
            };
            String key = file.value + "|" + lo.getX() + "," + lo.getY() + "," + lo.getZ() + "|" + rot;
            if (key.equals(liteKey) || key.equals(liteFailKey)) return;
            removeLite();
            try {
                Path path = find(file.value);
                if (path == null) throw new IllegalStateException("file not found");
                Class<?> sc = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
                Object schematic = sc.getMethod("createFromFile", java.io.File.class, String.class)
                        .invoke(null, path.getParent().toFile(), path.getFileName().toString());
                if (schematic == null) throw new IllegalStateException("Litematica could not read the file");
                try {
                    Object holder = Class.forName("fi.dy.masa.litematica.data.SchematicHolder").getMethod("getInstance").invoke(null);
                    var add = method(holder.getClass(), "addSchematic", 2);
                    if (add != null) add.invoke(holder, schematic, true);
                } catch (Throwable ignored) {}
                Class<?> pc = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
                var create = method(pc, "createFor", 5);
                if (create == null) throw new IllegalStateException("unsupported Litematica version");
                Object placement = create.invoke(null, schematic, lo, schem.name, true, true);
                if (rot != 0) {
                    BlockRotation br = rot == 1 ? BlockRotation.CLOCKWISE_90 : rot == 2 ? BlockRotation.CLOCKWISE_180 : BlockRotation.COUNTERCLOCKWISE_90;
                    var sr = method(pc, "setRotation", 2);
                    if (sr != null) sr.invoke(placement, br, null);
                }
                Object mgr = liteManager();
                var add = method(mgr.getClass(), "addSchematicPlacement", 2);
                if (add == null) throw new IllegalStateException("unsupported Litematica version");
                add.invoke(mgr, placement, false);
                litePlacement = placement; liteKey = key; liteFailKey = "";
                U.chat("Sent " + schem.name + " to Litematica.");
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                liteFailKey = key;
                U.chat("Could not hand the schematic to Litematica (" + c.getClass().getSimpleName() + ": " + c.getMessage() + "). Turn on \"Built-in preview\" instead.");
            }
        }

        // ------------------------------------------------------------------ ghost preview
        private boolean bad(int i) { Integer u = badTargets.get(i); return u != null && u > walkClock; }

        private int lowerBound(int y) {
            int lo = 0, hi = view.size();
            while (lo < hi) { int m = (lo + hi) >>> 1; if (view.get(m).y() < y) lo = m + 1; else hi = m; }
            return lo;
        }

        private static int colorOf(BlockState s) {
            int h = Registries.BLOCK.getId(s.getBlock()).hashCode();
            float hue = ((h >>> 2) & 1023) / 1023f * 6f, c = 0.45f + ((h >>> 14) & 7) / 7f * 0.4f;
            float x = c * (1f - Math.abs(hue % 2f - 1f)), m = 1f - c;
            float[] f = switch ((int) hue) {
                case 0 -> new float[]{c, x, 0}; case 1 -> new float[]{x, c, 0}; case 2 -> new float[]{0, c, x};
                case 3 -> new float[]{0, x, c}; case 4 -> new float[]{x, 0, c}; default -> new float[]{c, 0, x};
            };
            return 0xFF000000 | (Math.round((f[0] + m) * 255) << 16) | (Math.round((f[1] + m) * 255) << 8) | Math.round((f[2] + m) * 255);
        }

        /** Collects the nearest not-yet-built surface blocks around you; the HUD draws them as coloured outlines. */
        private void updateGhosts() {
            BlockPos b = base();
            if (b == null || view.isEmpty()) { ghosts = List.of(); return; }
            Vec3d eye = mc.player.getEyePos();
            double rng = ghostRange.get(), r2 = rng * rng;
            int lowY = (int) Math.floor(eye.y - rng) - b.getY(), highY = (int) Math.ceil(eye.y + rng) - b.getY();
            List<G> found = new ArrayList<>();
            for (int i = lowerBound(lowY); i < view.size(); i++) {
                Litematic.Entry e = view.get(i);
                if (e.y() > highY) break;
                if (done.get(i) || !surf.get(i)) continue;
                double dx = b.getX() + e.x() + 0.5 - eye.x, dy = b.getY() + e.y() + 0.5 - eye.y, dz = b.getZ() + e.z() + 0.5 - eye.z;
                double d = dx * dx + dy * dy + dz * dz;
                if (d > r2) continue;
                if (mc.world.getBlockState(world(e)).getBlock() == e.state().getBlock()) continue;
                found.add(new G(d, i));
            }
            found.sort(Comparator.comparingDouble(G::d));
            int max = ghostMax.getInt();
            List<Ghost> out = new ArrayList<>(Math.min(max, found.size()));
            for (int k = 0; k < found.size() && k < max; k++) {
                Litematic.Entry e = view.get(found.get(k).i());
                out.add(new Ghost(world(e), colorOf(e.state())));
            }
            ghosts = out;
        }

        // ------------------------------------------------------------------ walking
        private boolean isWater(BlockPos p) { return mc.world.getBlockState(p).isOf(Blocks.WATER); }

        private boolean climbable(BlockPos p) {
            BlockState s = mc.world.getBlockState(p);
            return s.isOf(Blocks.LADDER) || s.isOf(Blocks.VINE);
        }

        /** Doors and fence gates a player can open by hand (not iron). */
        private static boolean door(BlockState s) {
            return (s.getBlock() instanceof DoorBlock && !s.isOf(Blocks.IRON_DOOR)) || s.getBlock() instanceof FenceGateBlock;
        }

        /** Can the body occupy this cell? Doors (opened on the way), ladders/vines and water count; lava, fire, cobweb etc. do not. */
        private boolean passable(BlockPos p) {
            BlockState s = mc.world.getBlockState(p);
            if (door(s) || s.isOf(Blocks.LADDER) || s.isOf(Blocks.VINE) || s.isOf(Blocks.WATER)) return true;
            if (!s.getFluidState().isEmpty()) return false;
            if (s.getBlock() instanceof AbstractFireBlock || s.isOf(Blocks.COBWEB) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.POWDER_SNOW)) return false;
            return s.getCollisionShape(mc.world, p).isEmpty();
        }

        /** Can you be at this feet cell: solid ground below, or hanging on a ladder / floating in water. */
        private boolean standable(BlockPos feet) {
            if (!passable(feet) || !passable(feet.up())) return false;
            boolean ok;
            if (climbable(feet) || isWater(feet)) ok = true;
            else {
                BlockPos g = feet.down();
                BlockState s = mc.world.getBlockState(g);
                ok = !s.getCollisionShape(mc.world, g).isEmpty() && !(s.getBlock() instanceof CactusBlock) && !s.isOf(Blocks.MAGMA_BLOCK);
            }
            return ok && !lavaNear(feet);
        }

        private boolean isLava(BlockPos p) { return mc.world.getBlockState(p).isOf(Blocks.LAVA); }

        /** Lava beside the cell (at feet or ground level): such cells are never used on a route. */
        private boolean lavaNear(BlockPos feet) {
            for (Direction d : Direction.values()) {
                if (!d.getAxis().isHorizontal()) continue;
                BlockPos n = feet.offset(d);
                if (isLava(n) || isLava(n.down()) || isLava(n.up())) return true;
            }
            return false;
        }

        private record PN(BlockPos pos, double cost) {}

        /** Is the floor under this feet cell a block of the schematic that is already built? */
        private boolean builtFloor(BlockPos feet) {
            BlockPos b = base();
            if (b == null) return false;
            BlockPos g = feet.down();
            int i = idxAt(g.getX() - b.getX(), g.getY() - b.getY(), g.getZ() - b.getZ());
            return i >= 0 && done.get(i);
        }

        /** The search is limited to the area around the schematic (plus the area around the start) so the node budget is not wasted on the whole world. */
        private boolean inArea(BlockPos p, BlockPos start) {
            if (Math.abs(p.getX() - start.getX()) <= 16 && Math.abs(p.getZ() - start.getZ()) <= 16 && Math.abs(p.getY() - start.getY()) <= 12) return true;
            BlockPos b = base();
            if (b == null) return true;
            return p.getX() >= b.getX() - 20 && p.getX() <= b.getX() + vx + 20
                    && p.getZ() >= b.getZ() - 20 && p.getZ() <= b.getZ() + vz + 20
                    && p.getY() >= b.getY() - 10 && p.getY() <= b.getY() + vy + 10;
        }

        /**
         * Cheapest-route search (Dijkstra) over standable cells: flat, 1 up (jump), up to 3 down, up/down along ladders and vines, and up/down/sideways
         * through water. Standing on the part that is already built is cheap; stepping off it costs a lot, so the route stays on the built part whenever
         * that is possible. Returns the cells to walk to, empty when the start already is a goal, null when none is reachable.
         */
        private List<BlockPos> findPath(BlockPos start, Predicate<BlockPos> goal, int max) {
            PriorityQueue<PN> q = new PriorityQueue<>(Comparator.comparingDouble(PN::cost));
            Map<BlockPos, BlockPos> prev = new HashMap<>();
            Map<BlockPos, Double> best = new HashMap<>();
            q.add(new PN(start, 0)); prev.put(start, start); best.put(start, 0.0);
            int nodes = 0;
            while (!q.isEmpty() && nodes < max) {
                PN cur = q.poll();
                BlockPos c = cur.pos();
                if (cur.cost() > best.getOrDefault(c, Double.MAX_VALUE)) continue;   // outdated queue entry
                nodes++;
                if (goal.test(c)) {
                    List<BlockPos> out = new ArrayList<>();
                    for (BlockPos t = c; !t.equals(start); t = prev.get(t)) out.add(t);
                    Collections.reverse(out);
                    return out;
                }
                List<BlockPos> next = new ArrayList<>(6);
                for (Direction d : Direction.values()) {
                    if (!d.getAxis().isHorizontal()) continue;
                    BlockPos n = c.offset(d);
                    if (standable(n)) next.add(n);
                    else if (standable(n.up()) && passable(c.up(2))) next.add(n.up());
                    else if (passable(n) && passable(n.up())) {
                        for (int k = 1; k <= 3; k++) {
                            BlockPos m = n.down(k);
                            if (standable(m)) { next.add(m); break; }
                            if (!passable(m)) break;
                        }
                    }
                }
                boolean wet = isWater(c), clim = climbable(c);
                if ((wet || clim) && standable(c.up())) next.add(c.up());
                if ((wet || clim || isWater(c.down()) || climbable(c.down())) && standable(c.down())) next.add(c.down());
                boolean onBuilt = builtFloor(c);
                for (BlockPos to : next) {
                    if (!inArea(to, start)) continue;
                    double step = 1.0 + Math.abs(to.getY() - c.getY()) * 0.5;
                    if (!builtFloor(to)) step += onBuilt ? 8.0 : 0.4;   // leaving the built part is expensive
                    double nc = cur.cost() + step;
                    if (nc < best.getOrDefault(to, Double.MAX_VALUE)) {
                        best.put(to, nc); prev.put(to, c);
                        q.add(new PN(to, nc));
                    }
                }
            }
            return null;
        }

        /** Can entry t be placed from standing on cell c (including the facing it needs from that angle)? */
        private boolean goalFor(int t, BlockPos c) {
            BlockPos wp = world(view.get(t));
            if (c.equals(wp) || c.up().equals(wp) || pending(c) || pending(c.up())) return false;
            Vec3d eye = new Vec3d(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
            return plan(t, eye, false, 0.6) != null;
        }

        /** Could this block be placed at all right now (item obtainable, something to click against)? */
        private boolean wantable(int i) {
            Litematic.Entry e = view.get(i);
            BlockPos wp = world(e);
            BlockState want = e.state(), cur = mc.world.getBlockState(wp);
            if (cur.getBlock() == want.getBlock() || skipState(want) || !cur.isReplaceable()) return false;
            Item item = want.getBlock().asItem();
            if (item == Items.AIR || !canGet(item)) return false;
            for (Direction d : Direction.values()) {
                BlockState ns = mc.world.getBlockState(wp.offset(d));
                if (!ns.isReplaceable() && ns.getFluidState().isEmpty()) return true;
            }
            return false;
        }

        /** The nearest placeable entries of the layer being built (up to cap): the route search tries all of them, not just one. */
        private List<Integer> candidates(int cap) {
            Vec3d pe = mc.player.getEyePos();
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            List<G> found = new ArrayList<>();
            int layer = Integer.MIN_VALUE, scanned = 0;
            for (int i = firstPending; i < view.size(); i++) {
                if (done.get(i)) continue;
                Litematic.Entry e = view.get(i);
                if (layer != Integer.MIN_VALUE && order.is("Bottom-up") && e.y() > layer) break;
                if (++scanned > 30000) break;
                if (bad(i) || !wantable(i)) continue;
                if (layer == Integer.MIN_VALUE) layer = e.y();
                found.add(new G(Vec3d.ofCenter(world(e)).squaredDistanceTo(pe), i));
            }
            found.sort(Comparator.comparingDouble(G::d));
            List<Integer> out = new ArrayList<>();
            for (int k = 0; k < Math.min(cap, found.size()); k++) out.add(found.get(k).i());
            return out;
        }

        // ---- restock trips
        private List<BlockPos> nearbyChests() {
            List<BlockPos> out = new ArrayList<>();
            BlockPos pc = mc.player.getBlockPos();
            for (int x = -16; x <= 16; x++) for (int y = -8; y <= 8; y++) for (int z = -16; z <= 16; z++) {
                BlockPos bp = pc.add(x, y, z);
                if (emptied.contains(bp)) continue;
                Block b = mc.world.getBlockState(bp).getBlock();
                if (b instanceof ChestBlock || b instanceof BarrelBlock) out.add(bp.toImmutable());
            }
            Vec3d eye = mc.player.getEyePos();
            out.sort(Comparator.comparingDouble(c -> Vec3d.ofCenter(c).squaredDistanceTo(eye)));
            return out.size() > 24 ? new ArrayList<>(out.subList(0, 24)) : out;
        }

        private boolean chestGoal(BlockPos chest, BlockPos c) {
            if (c.equals(chest) || c.up().equals(chest) || pending(c) || pending(c.up())) return false;
            Vec3d eye = new Vec3d(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5), ctr = Vec3d.ofCenter(chest);
            if (eye.distanceTo(ctr) > reach.get() - 0.6) return false;
            if (ray.get()) {
                BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, ctr, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, mc.player));
                if (rh.getType() == HitResult.Type.BLOCK && !rh.getBlockPos().equals(chest)) return false;
            }
            return true;
        }

        /** Out of material: walk to the nearest chest/barrel that has not been emptied. False when there is nothing to walk to (or you are already in reach and openChest takes over). */
        private boolean chestWalk(BlockPos feet) {
            if (!restock.get() || missing.isEmpty() || mc.player.isCreative() || mc.currentScreen != null) return false;
            List<BlockPos> chests = nearbyChests();
            if (chests.isEmpty()) { walkStatus = "No chest left to restock from"; walkCd = 40; return false; }
            List<BlockPos> np = findPath(feet, c -> {
                for (BlockPos ch : chests) if (chestGoal(ch, c)) { foundChest = ch; return true; }
                return false;
            }, 6000);
            if (np == null) { walkStatus = "No route to a chest"; walkCd = 40; return false; }
            if (np.isEmpty()) return false;
            path = np; pathIdx = 0; stuck = 0; pathTicks = 0; lastPos = null; walkIdx = -1; walkChest = foundChest;
            return true;
        }

        // ---- keys / state
        private void stopKeys() {
            if (!keysHeld) return;
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            keysHeld = false;
        }

        /** True when the ground right ahead (in the walking direction) is missing: you would step off the block. */
        private boolean edgeAhead(Vec3d pos, double dx, double dz, double hd) {
            if (hd < 0.05) return false;
            double reachAhead = Math.min(0.6, hd + 0.3);
            BlockPos g = BlockPos.ofFloored(pos.x + dx / hd * reachAhead, pos.y - 0.5, pos.z + dz / hd * reachAhead);
            BlockState s = mc.world.getBlockState(g);
            return s.getCollisionShape(mc.world, g).isEmpty() && !s.isOf(Blocks.WATER) && !climbable(g);
        }

        private void resetWalk() {
            stopKeys(); path = List.of(); pathIdx = 0; walkIdx = -1; walkChest = null; stuck = 0; pathTicks = 0; walkCd = 0; doorCd = 0;
            walkStatus = ""; lastPos = null;
        }

        private void dropPath(int badFor) {
            if (badFor > 0) {
                if (walkIdx >= 0) badTargets.put(walkIdx, walkClock + badFor);
                if (walkChest != null) emptied.add(walkChest);   // could not get there: treat it as empty for now
            }
            stopKeys(); path = List.of(); pathIdx = 0; walkIdx = -1; walkChest = null; stuck = 0; pathTicks = 0; lastPos = null;
        }

        /** Opens closed doors / fence gates on the next few cells of the route. */
        private void openDoors() {
            if (doorCd > 0) { doorCd--; return; }
            Vec3d eye = mc.player.getEyePos();
            for (int j = pathIdx; j < Math.min(path.size(), pathIdx + 3); j++) {
                BlockPos c = path.get(j);
                for (BlockPos q : new BlockPos[]{c, c.up()}) {
                    BlockState s = mc.world.getBlockState(q);
                    if (!door(s) || !s.contains(Properties.OPEN) || s.get(Properties.OPEN)) continue;
                    Vec3d ctr = Vec3d.ofCenter(q);
                    if (eye.distanceTo(ctr) > 4.2) continue;
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(ctr, faceOf(q, eye), q, false));
                    mc.player.swingHand(Hand.MAIN_HAND);
                    doorCd = 6;
                    return;
                }
            }
        }

        /** Called when nothing can be placed from where you stand. Returns true while it is busy walking. */
        private boolean walkStep() {
            var p = mc.player;
            if (!walk.get()) { resetWalk(); return false; }

            // the target got built (or the plan is stale) while we were underway
            if (!path.isEmpty() && walkIdx >= 0) {
                Litematic.Entry te = view.get(walkIdx);
                if (done.get(walkIdx) || mc.world.getBlockState(world(te)).getBlock() == te.state().getBlock()) dropPath(0);
            }

            if (path.isEmpty() || pathIdx >= path.size()) {
                stopKeys();
                if (!path.isEmpty()) { path = List.of(); pathIdx = 0; walkChest = null; }   // arrived: let pick() / openChest() work from here
                if (walkCd > 0) { walkCd--; return false; }
                walkStatus = "";
                BlockPos feet = p.getBlockPos();
                List<Integer> cand = candidates(24);
                if (cand.isEmpty()) {
                    walkIdx = -1;
                    if (chestWalk(feet)) { walkStatus = "Walking to chest"; return true; }
                    if (walkCd == 0) walkCd = 4;
                    return false;
                }
                walkChest = null;
                final int[] found = {-1};
                final double rq = (reach.get() + 1.0) * (reach.get() + 1.0);
                List<BlockPos> np = findPath(feet, c -> {
                    for (int t : cand) {
                        if (Vec3d.ofCenter(world(view.get(t))).squaredDistanceTo(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5) > rq) continue;
                        if (goalFor(t, c)) { found[0] = t; return true; }
                    }
                    return false;
                }, 6000);
                if (np == null) {   // none of the nearest blocks can be reached: park them and look at others next time
                    for (int t : cand) badTargets.put(t, walkClock + 200);
                    walkStatus = "No route to next block"; walkCd = 20; walkIdx = -1;
                    return false;
                }
                if (np.isEmpty()) {   // in reach already, yet pick() found nothing from the exact spot: park it briefly
                    if (found[0] >= 0) badTargets.put(found[0], walkClock + 80);
                    walkIdx = -1;
                    return false;
                }
                walkIdx = found[0]; path = np; pathIdx = 0; stuck = 0; pathTicks = 0; lastPos = null;
            }

            BlockPos wp = path.get(pathIdx);
            if (!standable(wp)) { dropPath(0); walkCd = 1; return false; }   // route changed (something got placed), plan again
            openDoors();
            Vec3d pos = U.pos(p);
            double dx = wp.getX() + 0.5 - pos.x, dz = wp.getZ() + 0.5 - pos.z, hd = Math.hypot(dx, dz);
            if (hd < 0.4 && pos.y > wp.getY() - 0.3 && pos.y < wp.getY() + 1.2) {
                pathIdx++;
                stuck = 0;
                if (pathIdx >= path.size()) { stopKeys(); path = List.of(); pathIdx = 0; walkChest = null; walkStatus = "Arrived"; return true; }
                wp = path.get(pathIdx);
                dx = wp.getX() + 0.5 - pos.x; dz = wp.getZ() + 0.5 - pos.z; hd = Math.hypot(dx, dz);
            }
            if (hd > 4.5 || ++pathTicks > 600) { dropPath(100); walkCd = 3; return false; }   // pushed off the route / took too long

            if (lastPos != null && pos.squaredDistanceTo(lastPos) < 0.0004) {
                if (++stuck > 25) { dropPath(120); walkCd = 5; return false; }
            } else stuck = 0;
            lastPos = pos;

            // never walk into lava: stop if the next step (or what is under it) is lava
            if (hd > 1e-3) {
                BlockPos la = BlockPos.ofFloored(pos.x + dx / hd * 0.8, pos.y, pos.z + dz / hd * 0.8);
                if (isLava(la) || isLava(la.down()) || isLava(la.up())) { dropPath(300); walkCd = 10; walkStatus = "Lava ahead"; return false; }
            }

            BlockPos fb = p.getBlockPos();
            boolean vertical = hd < 0.7 && wp.getY() != fb.getY();
            boolean onLadder = climbable(fb) || climbable(fb.up());
            boolean up = wp.getY() > fb.getY();
            boolean drop = wp.getY() < fb.getY();
            boolean jump = (p.isOnGround() && ((up && hd < 1.6) || p.horizontalCollision))
                    || (onLadder && up)                                   // climb
                    || (p.isTouchingWater() && wp.getY() >= fb.getY());  // swim / stay afloat
            // crouch so you do not walk off a block, unless the route itself goes down there
            boolean sneak = edgeSafety.get() && !drop && !onLadder && !p.isTouchingWater() && p.isOnGround() && edgeAhead(pos, dx, dz, hd);

            // The camera is never turned for walking: the keys are chosen relative to where you currently look,
            // so aiming at a block to place and walking to the next spot happen at the same time without head swings.
            boolean kf = false, kb = false, kl = false, kr = false;
            if (hd > (vertical ? 0.25 : 0.12)) {
                float yawT = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float diff = MathHelper.wrapDegrees(yawT - p.getYaw()), ad = Math.abs(diff);
                kf = ad < 67.5f;
                kb = ad > 112.5f;
                kr = diff > 22.5f && diff < 157.5f;
                kl = diff < -22.5f && diff > -157.5f;
            }
            mc.options.forwardKey.setPressed(kf);
            mc.options.backKey.setPressed(kb);
            mc.options.leftKey.setPressed(kl);
            mc.options.rightKey.setPressed(kr);
            mc.options.jumpKey.setPressed(jump);
            mc.options.sneakKey.setPressed(sneak);
            keysHeld = true;
            walkStatus = (walkChest != null ? "Walking to chest " : "Walking to block ") + (path.size() - pathIdx) + " steps";
            return true;
        }

        // ------------------------------------------------------------------ restock from chests
        private boolean handleContainer() {
            if (chestPos == null || !(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h) || mc.currentScreen == null) return false;
            if (rcd > 0) { rcd--; return true; }
            int n = h.getRows() * 9;
            for (int i = 0; i < n; i++) {
                ItemStack st = h.getSlot(i).getStack();
                if (st.isEmpty() || !missing.containsKey(st.getItem())) continue;
                mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                int left = missing.get(st.getItem()) - st.getCount();
                if (left <= 0) missing.remove(st.getItem()); else missing.put(st.getItem(), left);
                rcd = 2 + (humanize.get() ? (int) Math.round(U.rand(0, 2)) : 0);
                return true;
            }
            emptied.add(chestPos);
            mc.player.closeHandledScreen();
            chestPos = null; rcd = 6; tick = 0;
            return true;
        }

        private void openChest() {
            if (!restock.get() || missing.isEmpty() || mc.currentScreen != null || mc.player.isCreative()) return;
            if (rcd > 0) { rcd--; return; }
            if (restockTick++ % 600 == 599) emptied.clear();
            var p = mc.player;
            Vec3d eye = p.getEyePos();
            double rr = reach.get() - 0.3;
            BlockPos target = null; double bd = Double.MAX_VALUE;
            BlockPos pc = p.getBlockPos();
            for (int x = -5; x <= 5; x++) for (int y = -5; y <= 5; y++) for (int z = -5; z <= 5; z++) {
                BlockPos bp = pc.add(x, y, z);
                if (emptied.contains(bp)) continue;
                Block b = mc.world.getBlockState(bp).getBlock();
                if (!(b instanceof ChestBlock) && !(b instanceof BarrelBlock)) continue;
                double d = Vec3d.ofCenter(bp).distanceTo(eye);
                if (d > rr || d >= bd) continue;
                if (ray.get()) {
                    BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, Vec3d.ofCenter(bp), RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, p));
                    if (rh.getType() == HitResult.Type.BLOCK && !rh.getBlockPos().equals(bp)) continue;
                }
                bd = d; target = bp;
            }
            if (target == null) return;
            if (!target.equals(chestPos)) { chestPos = target; openTries = 0; }
            if (openTries++ > 40) { emptied.add(target); chestPos = null; return; }
            Vec3d hit = Vec3d.ofCenter(target);
            if (rotate.get()) {
                float[] t = U.rotTo(hit);
                float[] r = U.smoothRot(p.getYaw(), p.getPitch(), t[0], t[1], aimSpeed.get(), maxTurn.get(), humanize.get(), true);
                p.setYaw(r[0]); p.setPitch(r[1]);
                Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
                Optional<Vec3d> h = new Box(target).raycast(eye, end);
                if (h.isEmpty()) return;
                hit = h.get();
            }
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, faceOf(target, hit), target, false));
            p.swingHand(Hand.MAIN_HAND);
            rcd = 6;
        }

        @Override public void onTick() {
            walkClock++;
            if (schem == null || origin == null || mc.player == null || mc.world == null) { nextPos = null; if (ghosts.size() > 0) ghosts = List.of(); stopKeys(); return; }
            if (mc.currentScreen != null) stopKeys();
            if (handleContainer()) return;
            if (!rotation.value.equals(viewRot)) rebuildView();
            if (showSchem.get()) { if (ghostTick++ % 8 == 0) updateGhosts(); } else if (!ghosts.isEmpty()) ghosts = List.of();
            if (liteTick++ % 10 == 0) syncLitematica();
            if (recPos >= 0 || tick++ % 40 == 0) recount();
            if (mc.currentScreen != null) return;
            if (cd > 0) {   // placing is throttled, walking is not
                cd--;
                if (!path.isEmpty() && pathIdx < path.size()) walkStep();
                return;
            }

            Set<BlockPos> placed = new HashSet<>();
            int limit = rotate.get() ? 1 : perTick.getInt();
            for (int n = 0; n < limit; n++) {
                Plan plan = pick(placed);
                if (n == 0) {
                    boolean pathActive = !path.isEmpty() && pathIdx < path.size();
                    if (plan != null && pathActive && plan.idx() == walkIdx) { dropPath(0); pathActive = false; }   // target is in reach already
                    boolean walking = false;
                    if (pathActive || plan == null) walking = walkStep();   // keep walking while blocks in reach are placed
                    else stopKeys();
                    if (plan == null) {
                        nextPos = walking && walkIdx >= 0 && walkIdx < view.size() ? world(view.get(walkIdx)) : null;
                        if (!walking) openChest();
                        break;
                    }
                    if (!walking) walkStatus = "";
                }
                if (plan == null) break;
                nextPos = plan.pos();
                if (!placeOne(plan)) {
                    // a block we can never seem to click gets parked for a while instead of blocking everything
                    if (failIdx == plan.idx()) {
                        if (++failCount > 40) { badTargets.put(plan.idx(), walkClock + 200); curIdx = -1; failCount = 0; }
                    } else { failIdx = plan.idx(); failCount = 1; }
                    break;
                }
                failCount = 0;
                placed.add(plan.pos());
                cd = delay.getInt() + (humanize.get() && delay.getInt() > 0 ? (int) Math.round(U.rand(0, 1.5)) : 0);
            }
        }

        private boolean placeOne(Plan pl) {
            var p = mc.player;
            if (!prepare(pl.item())) return false;
            Vec3d eye = p.getEyePos();
            Vec3d hit = pl.hit();
            Direction face = pl.face();
            if (rotate.get()) {
                float yaw0 = p.getYaw(), pitch0 = p.getPitch();   // = the rotation the server already knows (it was sent at the start of this tick)
                float[] t = U.rotTo(pl.hit());
                float[] r = U.smoothRot(p.getYaw(), p.getPitch(), t[0], t[1], aimSpeed.get(), maxTurn.get(), humanize.get(), true);
                p.setYaw(r[0]); p.setPitch(r[1]);
                Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
                Optional<Vec3d> h = new Box(pl.against()).raycast(eye, end);
                if (h.isEmpty()) return false;
                hit = h.get();
                face = faceOf(pl.against(), hit);
                if (!pl.against().offset(face).equals(pl.pos())) return false;
                Direction need = needFacing(pl.state());
                if (matchFacing.get() && need != null) {
                    if (p.getHorizontalFacing() != need) return false;
                    // blocks with a facing take it from the SERVER's idea of our rotation, which is still the old one: wait until it is (almost) there
                    if (Math.abs(MathHelper.wrapDegrees(r[0] - yaw0)) > 3f || Math.abs(r[1] - pitch0) > 3f) return false;
                }
                // slabs / stairs / trapdoors on a side face: the half depends on where on the face we click
                if (face.getAxis().isHorizontal()) {
                    double fy = hit.y - pl.against().getY();
                    if (top(pl.state()) && fy < 0.55) return false;
                    if (bottom(pl.state()) && fy > 0.45) return false;
                }
            }
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, pl.against(), false));
            p.swingHand(Hand.MAIN_HAND);
            return true;
        }

        @Override public void onHud(DrawContext ctx) {
            int acc = Theme.accent();
            Proj pr = Proj.begin();
            if (pr != null && schem != null && origin != null) {
                BlockPos b = base();
                if (outline.get()) pr.wire(ctx, new Box(b.getX(), b.getY(), b.getZ(), b.getX() + vx, b.getY() + vy, b.getZ() + vz), Gfx.a(acc, 0.8f));
                if (showSchem.get()) {
                    for (Ghost g : ghosts) {
                        if (pr.project(Vec3d.ofCenter(g.pos())) == null) continue;
                        pr.wire(ctx, new Box(g.pos()).expand(-0.03), Gfx.a(g.color(), 0.9f));
                    }
                }
                if (showPath.get() && walk.get() && !path.isEmpty()) {
                    for (int i = Math.max(0, pathIdx - 1); i < path.size() - 1; i++) {
                        BlockPos a = path.get(i), c = path.get(i + 1);
                        pr.line3(ctx, new Vec3d(a.getX() + 0.5, a.getY() + 0.1, a.getZ() + 0.5), new Vec3d(c.getX() + 0.5, c.getY() + 0.1, c.getZ() + 0.5), Gfx.a(acc, 0.9f));
                    }
                }
                if (showNext.get() && nextPos != null) pr.wire(ctx, new Box(nextPos).expand(0.003), 0xFFFFFFFF);
            }
            if (!progress.get()) return;
            int x = 6, w = 150;
            List<String> lines = new ArrayList<>();
            if (schem == null) lines.add("No schematic loaded");
            else if (origin == null) lines.add("Set the origin");
            for (var en : missing.entrySet()) {
                if (lines.size() >= 4) break;
                lines.add("Need " + en.getValue() + " " + Registries.ITEM.getId(en.getKey()).getPath().replace('_', ' '));
            }
            if (walk.get() && !walkStatus.isEmpty()) lines.add(walkStatus);
            int h = 28 + (schem != null ? 10 : 0) + lines.size() * 10;
            int y = mc.getWindow().getScaledHeight() - h - 40;
            Gfx.rrect(ctx, x, y, w, h, 5, 0xB0100C0E);
            Gfx.rrect(ctx, x, y + 5, 2, h - 10, 1, acc);
            U.text(ctx, schem == null ? "Schematic" : schem.name, x + 8, y + 5, 0xFFFFFFFF);
            int yy = y + 17;
            if (schem != null) {
                int total = Math.max(1, view.size());
                float pct = doneCount / (float) total;
                Gfx.rrect(ctx, x + 8, yy, w - 16, 4, 2, 0xFF2A2124);
                Gfx.rrect(ctx, x + 8, yy, Math.max(4, Math.round((w - 16) * pct)), 4, 2, acc);
                U.text(ctx, doneCount + " / " + total + " (" + Math.round(pct * 100) + "%)", x + 8, yy + 7, 0xFFB8AEAB);
                yy += 17;
            }
            for (String l : lines) { U.text(ctx, l, x + 8, yy, l.startsWith("Need") ? 0xFFFFA23A : 0xFFB8AEAB); yy += 10; }
        }
    }
}
