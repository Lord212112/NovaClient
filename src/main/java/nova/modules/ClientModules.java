package nova.modules;

import net.minecraft.client.gui.DrawContext;
import nova.core.*;

import java.util.ArrayList;
import java.util.List;

public final class ClientModules {
    private ClientModules() {}

    public static class Theme extends Mod {
        static Theme INSTANCE;
        Setting.Mode color = mode("Accent", "Orange", "Orange", "Purple", "Blue", "Red", "Green", "Pink", "Cyan", "Gold");

        public Theme() { super("Theme", Category.CLIENT, "Accent colour of the GUI and HUD."); INSTANCE = this; }

        public static int accent() {
            if (INSTANCE == null) return 0xFFFF855A;
            return switch (INSTANCE.color.value) {
                case "Blue" -> 0xFF4D8AFF;
                case "Red" -> 0xFFFF4D5A;
                case "Green" -> 0xFF3DDC84;
                case "Pink" -> 0xFFFF4DC4;
                case "Purple" -> 0xFF8A4DFF;
                case "Cyan" -> 0xFF35D0E8;
                case "Gold" -> 0xFFFFC53D;
                default -> 0xFFFF855A;
            };
        }
    }

    /** Sounds and animation of the click GUI. */
    public static class Gui extends Mod {
        static Gui INSTANCE;
        Setting.Bool sounds = bool("Click sounds", true);

        public Gui() { super("GUI", Category.CLIENT, "Sounds of this menu."); INSTANCE = this; }

        public static boolean sounds() { return INSTANCE == null || INSTANCE.sounds.get(); }
    }

    /** Small popups in the bottom right when a module is switched on or off. */
    public static class Notify extends Mod {
        static Notify INSTANCE;
        record N(String text, boolean on, long t0) {}
        static final List<N> LIST = new ArrayList<>();
        Setting.Num seconds = num("Duration (s)", 2.5, 1, 6, 0.5);

        public Notify() { super("Notifications", Category.CLIENT, "Popups when a module is toggled."); INSTANCE = this; }

        public static void push(String name, boolean on) {
            if (INSTANCE == null || !INSTANCE.enabled) return;
            LIST.add(0, new N(name, on, System.currentTimeMillis()));
            while (LIST.size() > 6) LIST.remove(LIST.size() - 1);
        }

        @Override public void onHud(DrawContext ctx) {
            int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            long now = System.currentTimeMillis();
            long life = (long) (seconds.get() * 1000);
            LIST.removeIf(n -> now - n.t0() > life);
            int acc = Theme.accent();
            for (int i = 0; i < LIST.size(); i++) {
                N n = LIST.get(i);
                long age = now - n.t0();
                float in = Gfx.ease(age / 260f);
                float out = 1f - Gfx.ease((age - (life - 300)) / 300f);
                String state = n.on() ? "enabled" : "disabled";
                int w = U.width(n.text()) + U.width(state) + 34, h = 20;
                int x = sw - w - 8 + Math.round((1 - in) * (w + 12)), y = sh - 44 - i * 24;
                float al = Math.min(in, out);
                Gfx.shadow(ctx, x, y, w, h, 5, al);
                Gfx.rrect(ctx, x, y, w, h, 5, Gfx.a(0xF0120D0F, al));
                int dot = n.on() ? 0xFF4DE08A : 0xFFFF5A5A;
                Gfx.rrect(ctx, x + 7, y + 7, 6, 6, 3, Gfx.a(dot, al));
                U.text(ctx, n.text(), x + 19, y + 6, Gfx.a(0xFFFFFFFF, al));
                U.text(ctx, state, x + 23 + U.width(n.text()), y + 6, Gfx.a(n.on() ? acc : 0xFF8E8380, al));
            }
        }
    }
}
