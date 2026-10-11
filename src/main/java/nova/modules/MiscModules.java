package nova.modules;

import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.item.Items;
import nova.core.*;

public final class MiscModules {
    private MiscModules() {}

    public static class AutoRespawn extends Mod {
        public AutoRespawn() { super("AutoRespawn", Category.MISC, "Respawns instantly."); }
        @Override public void onTick() { if (mc.player.isDead()) mc.player.requestRespawn(); }
    }

    public static class AutoLog extends Mod {
        Setting.Num health = num("Health <=", 6, 1, 20, 1);
        public AutoLog() { super("AutoLog", Category.MISC, "Disconnects when your health is low."); }

        @Override public void onTick() {
            if (mc.player.getHealth() <= health.get()) {
                mc.getNetworkHandler().getConnection().disconnect(Text.literal("Nova AutoLog"));
                setEnabled(false);
            }
        }
    }

    public static class AntiAFK extends Mod {
        int t;
        public AntiAFK() { super("AntiAFK", Category.MISC, "Swings and jumps now and then."); }

        @Override public void onTick() {
            t++;
            if (t % 100 == 0) mc.player.swingHand(Hand.MAIN_HAND);
            if (t % 300 == 0 && mc.player.isOnGround()) mc.player.jump();
        }
    }

    public static class PearlThrow extends Mod {
        public PearlThrow() { super("PearlThrow", Category.MISC, "Throws an ender pearl from your hotbar, then turns itself off."); }
        @Override public void onEnable() {
            var inv = mc.player.getInventory();
            int old = inv.getSelectedSlot();
            if (U.selectSlot(s -> s.isOf(Items.ENDER_PEARL))) {
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                inv.setSelectedSlot(old);
            } else U.chat("No ender pearl in your hotbar.");
            enabled = false;
        }
    }
}
