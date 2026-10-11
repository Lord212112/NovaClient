package nova.modules;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import nova.core.*;
import nova.modules.ClientModules.Theme;

import java.util.*;

public final class HudModules {
    private HudModules() {}

    public static class Watermark extends Mod {
        public Watermark() { super("Watermark", Category.HUD, "Client name and FPS."); }
        @Override public void onHud(DrawContext ctx) {
            String fps = mc.getCurrentFps() + " fps";
            int w = U.width("Nova") + U.width(fps) + 22;
            Gfx.rrect(ctx, 4, 4, w, 16, 5, 0xB0100C0E);
            Gfx.rrect(ctx, 4, 6, 2, 12, 1, Theme.accent());
            U.text(ctx, "Nova", 11, 8, Theme.accent());
            U.text(ctx, fps, 11 + U.width("Nova") + 7, 8, 0xFFD8D0CC);
        }
    }

    public static class ModuleList extends Mod {
        final Map<Mod, Float> anim = new HashMap<>();
        long lastNs = System.nanoTime();
        public ModuleList() { super("ModuleList", Category.HUD, "Lists your enabled modules."); }

        @Override public void onHud(DrawContext ctx) {
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 14);
            List<Mod> list = new ArrayList<>();
            for (Mod m : ModuleManager.MODULES) {
                if (m.category == Category.HUD || m.category == Category.CLIENT) continue;
                float a = anim.getOrDefault(m, 0f);
                a += ((m.enabled ? 1f : 0f) - a) * k;
                anim.put(m, a);
                if (a > 0.02f) list.add(m);
            }
            list.sort((a, b) -> U.width(b.name) - U.width(a.name));
            int sw = mc.getWindow().getScaledWidth(), y = 4, acc = Theme.accent();
            for (Mod m : list) {
                float a = Gfx.ease(anim.get(m));
                int w = U.width(m.name) + 10;
                int x = sw - w - 4 + Math.round((1 - a) * (w + 8));
                int h = Math.max(1, Math.round(13 * a));
                Gfx.rrect(ctx, x, y, w, h, 4, Gfx.a(0xB0100C0E, a));
                if (h > 8) {
                    Gfx.rrect(ctx, x + w - 2, y + 2, 2, h - 4, 1, Gfx.a(acc, a));
                    U.text(ctx, m.name, x + 5, y + 3, Gfx.a(0xFFFFFFFF, a));
                }
                y += h + 1;
            }
        }
    }

    public static class Coordinates extends Mod {
        public Coordinates() { super("Coordinates", Category.HUD, "Shows your XYZ."); }
        @Override public void onHud(DrawContext ctx) {
            var p = mc.player;
            U.text(ctx, String.format("XYZ %d %d %d", (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())),
                    4, mc.getWindow().getScaledHeight() - 12, 0xFFFFFFFF);
        }
    }

    public static class SpeedHud extends Mod {
        Vec3d prev = Vec3d.ZERO; double bps;
        public SpeedHud() { super("SpeedHud", Category.HUD, "Blocks per second."); }
        @Override public void onEnable() { prev = mc.player != null ? U.pos(mc.player) : Vec3d.ZERO; }
        @Override public void onTick() {
            Vec3d now = U.pos(mc.player);
            double dx = now.x - prev.x, dz = now.z - prev.z;
            bps = Math.sqrt(dx * dx + dz * dz) * 20;
            prev = now;
        }
        @Override public void onHud(DrawContext ctx) {
            U.text(ctx, String.format("%.2f b/s", bps), 4, mc.getWindow().getScaledHeight() - 22, 0xFFFFFFFF);
        }
    }

    public static class PingHud extends Mod {
        public PingHud() { super("PingHud", Category.HUD, "Your latency."); }
        @Override public void onHud(DrawContext ctx) {
            var h = mc.getNetworkHandler();
            if (h == null) return;
            var e = h.getPlayerListEntry(mc.player.getUuid());
            if (e != null) U.text(ctx, e.getLatency() + " ms", 4, mc.getWindow().getScaledHeight() - 32, 0xFFFFFFFF);
        }
    }

    public static class ArmorHud extends Mod {
        public ArmorHud() { super("ArmorHud", Category.HUD, "Armor pieces above the hotbar."); }
        @Override public void onHud(DrawContext ctx) {
            int x = mc.getWindow().getScaledWidth() / 2 + 10, y = mc.getWindow().getScaledHeight() - 74;
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (EquipmentSlot s : slots) {
                ItemStack st = mc.player.getEquippedStack(s);
                if (!st.isEmpty()) { ctx.drawItem(st, x, y); ctx.drawStackOverlay(mc.textRenderer, st, x, y); }
                x += 18;
            }
        }
    }

    public static class PlayerRadar extends Mod {
        Setting.Num range = num("Range", 128, 16, 512, 16);
        public PlayerRadar() { super("PlayerRadar", Category.HUD, "Nearby players and distance."); }
        @Override public void onHud(DrawContext ctx) {
            List<PlayerEntity> list = new ArrayList<>();
            for (PlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.distanceTo(mc.player) <= range.get()) list.add(p);
            list.sort(Comparator.comparingDouble(p -> p.distanceTo(mc.player)));
            int y = 16;
            for (int i = 0; i < Math.min(6, list.size()); i++) {
                var p = list.get(i);
                U.text(ctx, p.getName().getString() + " " + (int) p.distanceTo(mc.player) + "m", 4, y, 0xFFFFFFFF);
                y += 10;
            }
        }
    }

    public static class PotionHud extends Mod {
        public PotionHud() { super("PotionHud", Category.HUD, "Active status effects."); }
        @Override public void onHud(DrawContext ctx) {
            int y = mc.getWindow().getScaledHeight() / 2 - 20;
            for (StatusEffectInstance e : mc.player.getStatusEffects()) {
                int secs = e.getDuration() / 20;
                U.text(ctx, e.getEffectType().value().getName().getString() + " " + (e.getAmplifier() + 1)
                        + " " + (secs / 60) + ":" + String.format("%02d", secs % 60), 4, y, 0xFFFFFFFF);
                y += 10;
            }
        }
    }
}
