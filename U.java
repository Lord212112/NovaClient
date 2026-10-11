package nova.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class U {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private U() {}

    /** Entity position via getX/getY/getZ (does not depend on what getPos is called in this version). */
    public static Vec3d pos(Entity e) { return new Vec3d(e.getX(), e.getY(), e.getZ()); }

    /** Chunk coordinates without touching ChunkPos.x / .x() (it is a record in newer versions, a class with fields in older ones). */
    public static int cx(ChunkPos p) { return p.getStartX() >> 4; }
    public static int cz(ChunkPos p) { return p.getStartZ() >> 4; }

    public static boolean fwd() { return mc.options.forwardKey.isPressed(); }
    public static boolean back() { return mc.options.backKey.isPressed(); }
    public static boolean left() { return mc.options.leftKey.isPressed(); }
    public static boolean right() { return mc.options.rightKey.isPressed(); }
    public static boolean jump() { return mc.options.jumpKey.isPressed(); }
    public static boolean sneak() { return mc.options.sneakKey.isPressed(); }
    public static boolean moving() { return fwd() || back() || left() || right(); }

    public static Vec3d moveDir(double speed) {
        double f = (fwd() ? 1 : 0) - (back() ? 1 : 0);
        double s = (left() ? 1 : 0) - (right() ? 1 : 0);
        if (f == 0 && s == 0) return Vec3d.ZERO;
        double len = Math.sqrt(f * f + s * s);
        f /= len; s /= len;
        double r = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(r), cos = Math.cos(r);
        return new Vec3d((-sin * f + cos * s) * speed, 0, (cos * f + sin * s) * speed);
    }

    public static void chat(String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("\u00a75[Nova]\u00a7r " + s), false);
    }

    public static void text(DrawContext c, String s, int x, int y, int color) { c.drawText(mc.textRenderer, s, x, y, color, true); }
    /** Text with a soft coloured halo. */
    public static void glowText(DrawContext c, String s, int x, int y, int color, float strength) {
        int halo = Gfx.a((color & 0x00FFFFFF) | 0xFF000000, 0.22f * Math.min(1f, strength));
        c.drawText(mc.textRenderer, s, x - 1, y, halo, false);
        c.drawText(mc.textRenderer, s, x + 1, y, halo, false);
        c.drawText(mc.textRenderer, s, x, y - 1, halo, false);
        c.drawText(mc.textRenderer, s, x, y + 1, halo, false);
        c.drawText(mc.textRenderer, s, x, y, color, true);
    }

    public static int width(String s) { return mc.textRenderer.getWidth(s); }

    public static float[] rotTo(Vec3d t) {
        Vec3d e = mc.player.getEyePos();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    public static boolean place(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (mc.world.getBlockState(n).isReplaceable()) continue;
            Direction face = d.getOpposite();
            Vec3d hit = Vec3d.ofCenter(n).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, n, false));
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    public static boolean selectSlot(Predicate<ItemStack> p) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (p.test(inv.getStack(i))) { inv.setSelectedSlot(i); return true; }
        return false;
    }

    // ---------- smooth aiming helpers ----------
    public static double jitter(double amt) { return 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * amt; }
    public static double rand(double lo, double hi) { return lo + ThreadLocalRandom.current().nextDouble() * (hi - lo); }

    /** Smallest rotation step the mouse can produce with the current sensitivity. */
    public static double mouseStep() {
        double s = mc.options.getMouseSensitivity().getValue();
        double f = s * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }

    /** Moves the rotation a share of the remaining angle per tick (capped), instead of snapping. */
    public static float[] smoothRot(float yaw, float pitch, float ty, float tp, double speedPct, double maxStep,
                                    boolean humanize, boolean mouseFix) {
        double dy = MathHelper.wrapDegrees(ty - yaw), dp = tp - pitch;
        double f = Math.max(0.01, Math.min(1.0, speedPct / 100.0));
        double sy = dy * f, sp = dp * f;
        double mag = Math.hypot(sy, sp);
        if (mag > maxStep) { sy *= maxStep / mag; sp *= maxStep / mag; }
        if (humanize && Math.hypot(dy, dp) > 1.5) {
            sy += ThreadLocalRandom.current().nextGaussian() * 0.12;
            sp += ThreadLocalRandom.current().nextGaussian() * 0.08;
        }
        if (mouseFix) {
            double step = mouseStep();
            sy = Math.round(sy / step) * step;
            sp = Math.round(sp / step) * step;
        }
        return new float[]{yaw + (float) sy, MathHelper.clamp(pitch + (float) sp, -90f, 90f)};
    }

    public static double angleTo(Vec3d t) {
        Vec3d look = mc.player.getRotationVec(1.0F);
        Vec3d d = t.subtract(mc.player.getEyePos()).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, look.dotProduct(d)))));
    }

    private static double clampSafe(double v, double lo, double hi) {
        if (lo > hi) return (lo + hi) / 2;
        return Math.max(lo, Math.min(hi, v));
    }

    /** A point in the target's hitbox close to where you already look (little turning needed). */
    public static Vec3d aimPoint(Entity e) {
        Vec3d eye = mc.player.getEyePos();
        Box b = e.getBoundingBox();
        double m = 0.12, h = b.maxY - b.minY;
        return new Vec3d(clampSafe(eye.x, b.minX + m, b.maxX - m),
                         clampSafe(eye.y, b.minY + h * 0.25, b.minY + h * 0.85),
                         clampSafe(eye.z, b.minZ + m, b.maxZ - m));
    }

    public static boolean lookingAt(Entity e, double range) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(mc.player.getRotationVec(1.0F).multiply(range));
        return e.getBoundingBox().expand(0.05).raycast(eye, end).isPresent();
    }
}
