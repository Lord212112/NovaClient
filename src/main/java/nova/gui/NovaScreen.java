package nova.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import nova.core.*;
import nova.modules.ClientModules;
import nova.modules.ClientModules.Theme;

import java.util.*;

/** Rounded, animated window: icon tabs on the left, module cards in two columns, settings fold out inside the cards. */
public class NovaScreen extends Screen {
    private static final int SIDE = 34, HEAD = 30, CARD_HEAD = 20, ROW_B = 15, ROW_N = 23, FOOT = 18, PAD = 8, GAP = 6;
    private static final int BG = 0xE8110C0E, CARD = 0xFF181214, TRACK = 0xFF3A2F31, TEXT = 0xFFF0E9E6, DIM = 0xFF8E8380;

    private record Hit(int kind, Mod m, Setting<?> s, int x, int y, int w, int h) {}
    private static final class MA { float en, ex, hv; }

    private static final Map<Mod, MA> anims = new HashMap<>();
    private static final Map<Object, Float> smooth = new HashMap<>();
    private static final Set<Mod> expanded = new HashSet<>();
    private final List<Hit> hits = new ArrayList<>();
    private final EnumMap<Category, ItemStack> icons = new EnumMap<>(Category.class);

    private static Category cat = Category.COMBAT;
    private Mod binding;
    private Hit dragHit;
    private Setting.Num dragging;
    private static double scroll, scrollT;
    private static String search = "";
    private boolean searchFocus;
    private float open, oa;
    private long lastNs = System.nanoTime();
    private int px, py, pw, ph, oy, contentH, hoverTab = -1;

    public NovaScreen() { super(Text.literal("Nova")); }

    @Override protected void init() {
        pw = Math.min(width - 30, 600);
        ph = Math.min(height - 30, 360);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void removed() { Config.save(); }

    private int cx() { return px + SIDE + PAD; }
    private int cw() { return pw - SIDE - PAD * 2; }
    private int ctop() { return py + oy + HEAD; }
    private int cbot() { return py + oy + ph - 16; }
    private int A(int c) { return Gfx.a(c, oa); }
    private static float pulse() { return 1f; }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private float sm(Object key, float target, float k) {
        float v = smooth.getOrDefault(key, target);
        v += (target - v) * k;
        smooth.put(key, v);
        return v;
    }

    private void click(float pitch) {
        if (!ClientModules.Gui.sounds()) return;
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    private ItemStack icon(Category c) {
        return icons.computeIfAbsent(c, k -> new ItemStack(switch (k) {
            case COMBAT -> Items.DIAMOND_SWORD;
            case MOVEMENT -> Items.DIAMOND_BOOTS;
            case PLAYER -> Items.PLAYER_HEAD;
            case RENDER -> Items.ENDER_EYE;
            case WORLD -> Items.GRASS_BLOCK;
            case MISC -> Items.COMPARATOR;
            case HUD -> Items.NAME_TAG;
            case CLIENT -> Items.REDSTONE;
        }));
    }

    private List<Mod> visible() {
        if (search.isBlank()) return ModuleManager.in(cat);
        List<Mod> out = new ArrayList<>();
        String q = search.toLowerCase();
        for (Mod m : ModuleManager.MODULES) if (m.name.toLowerCase().contains(q)) out.add(m);
        return out;
    }

    private static int settingsH(Mod m) {
        int h = 3;
        for (Setting<?> s : m.settings) h += s instanceof Setting.Num ? ROW_N : ROW_B;
        return h + FOOT + 3;
    }

    private static String keyName(int k) {
        if (k < 0) return "None";
        if (k >= 65 && k <= 90) return String.valueOf((char) k);
        if (k >= 48 && k <= 57) return String.valueOf((char) k);
        if (k >= 290 && k <= 301) return "F" + (k - 289);
        return switch (k) {
            case 32 -> "Space"; case 340 -> "LShift"; case 344 -> "RShift"; case 341 -> "LCtrl";
            case 345 -> "RCtrl"; case 342 -> "LAlt"; case 346 -> "RAlt"; case 258 -> "Tab";
            default -> "Key" + k;
        };
    }

    private static String fmt(Setting.Num n) { return n.step >= 1 ? String.valueOf(n.getInt()) : String.format("%.2f", n.get()); }

    private static void chevron(DrawContext c, int cx, int cy, boolean up, int color) {
        int a = up ? 2 : -1, b = up ? -1 : 2;
        Gfx.line(c, cx - 3, cy + a, cx, cy + b, color);
        Gfx.line(c, cx, cy + b, cx + 3, cy + a, color);
    }

    // ---------- render ----------
    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        float k = 1f - (float) Math.exp(-dt * 16f);
        open += (1f - open) * (1f - (float) Math.exp(-dt * 11f));
        oa = Gfx.ease(Math.min(1f, open * 1.05f));
        oy = Math.round((1f - oa) * 14f);
        int acc = Theme.accent();
        hits.clear();
        int x0 = px, y0 = py + oy;

        float pl = pulse();
        Gfx.shadow(ctx, x0, y0, pw, ph, 8, oa);
        Gfx.glow(ctx, x0, y0, pw, ph, 8, acc, oa * 0.22f, 6);
        Gfx.rrectBorder(ctx, x0, y0, pw, ph, 8, A(Gfx.mix(0xFF2B2124, acc, 0.14f)), A(Gfx.mix(BG, acc, 0.03f)));

        Gfx.rrect(ctx, x0 + 4, y0 + 4, SIDE - 6, ph - 8, 6, A(0x55000000));
        Category[] cats = Category.values();
        hoverTab = -1;
        for (int i = 0; i < cats.length; i++) {
            int tx = x0 + 6, ty = y0 + 10 + i * 26;
            boolean sel = cats[i] == cat && search.isBlank();
            boolean hov = in(mx, my, tx, ty, 24, 24);
            if (hov) hoverTab = i;
            float s = sm("tab" + i, sel ? 1f : 0f, k), h = sm("tabh" + i, hov ? 1f : 0f, k);
            if (s > 0.02f) Gfx.glow(ctx, tx, ty, 24, 24, 6, acc, s * oa * 0.6f, 4);
            if (s > 0.02f) Gfx.rrect(ctx, tx, ty, 24, 24, 6, A(Gfx.a(acc, 0.22f * s)));
            if (h > 0.02f) Gfx.rrect(ctx, tx, ty, 24, 24, 6, A(Gfx.a(0xFFFFFFFF, 0.06f * h)));
            if (s > 0.02f) { Gfx.glow(ctx, tx - 3, ty + 6, 2, 12, 1, acc, s * oa * 0.6f, 3); Gfx.rrect(ctx, tx - 3, ty + 6, 2, 12, 1, A(Gfx.a(acc, s))); }
            ctx.drawItem(icon(cats[i]), tx + 4, ty + 4);
        }
        int rx = x0 + 6, ry = y0 + ph - 32;
        boolean rh = in(mx, my, rx, ry, 24, 24);
        float rhv = sm("reset", rh ? 1f : 0f, k);
        Gfx.rrect(ctx, rx, ry, 24, 24, 6, A(Gfx.a(0xFFFF5A5A, 0.05f + 0.22f * rhv)));
        ctx.drawItem(new ItemStack(Items.BARRIER), rx + 4, ry + 4);

        int hx = x0 + SIDE + PAD;
        U.glowText(ctx, "Nova", hx, y0 + 11, A(acc), oa * 0.4f);
        U.text(ctx, search.isBlank() ? cat.label : "Search", hx + U.width("Nova "), y0 + 11, A(DIM));
        int sw = 120, sx = x0 + pw - sw - PAD, sy = y0 + 7;
        float sf = sm("search", searchFocus ? 1f : 0f, k);
        Gfx.rrect(ctx, sx, sy, sw, 16, 6, A(Gfx.mix(0xFF2B2124, acc, sf * 0.8f)));
        Gfx.rrect(ctx, sx + 1, sy + 1, sw - 2, 14, 5, A(0xFF140F11));
        boolean empty = search.isEmpty() && !searchFocus;
        String shown = empty ? "Search..." : search + (searchFocus && (System.currentTimeMillis() / 500) % 2 == 0 ? "|" : "");
        while (U.width(shown) > sw - 10 && shown.length() > 1) shown = shown.substring(1);
        U.text(ctx, shown, sx + 5, sy + 4, A(empty ? DIM : TEXT));

        int cx = cx(), cw = cw(), top = ctop(), bot = cbot();
        int cols = cw >= 340 ? 2 : 1;
        int colW = (cw - GAP * (cols - 1)) / cols;
        float[] colY = new float[cols];
        String hint = null;
        ctx.enableScissor(cx - 8, top, cx + cw + 8, bot);
        for (Mod m : visible()) {
            MA a = anims.computeIfAbsent(m, mm -> { MA n = new MA(); n.en = mm.enabled ? 1f : 0f; return n; });
            a.en += ((m.enabled ? 1f : 0f) - a.en) * k;
            a.ex += ((expanded.contains(m) ? 1f : 0f) - a.ex) * k;
            float ex = Gfx.ease(a.ex);
            int h = CARD_HEAD + Math.round(settingsH(m) * ex);
            int ci = 0;
            for (int c = 1; c < cols; c++) if (colY[c] < colY[ci]) ci = c;
            int x = cx + ci * (colW + GAP);
            int y = top - (int) Math.round(scroll) + (int) colY[ci];
            colY[ci] += h + GAP;
            if (y + h < top - 2 || y > bot + 2) continue;

            boolean hov = in(mx, my, x, y, colW, CARD_HEAD) && my >= top && my < bot;
            a.hv += ((hov ? 1f : 0f) - a.hv) * k;
            if (hov) hint = m.desc;
            drawCard(ctx, m, a, ex, x, y, colW, h, mx, my, acc, k);
            hits.add(new Hit(0, m, null, x, y, colW, CARD_HEAD));
        }
        ctx.disableScissor();
        contentH = (int) Math.max(0, Math.max(colY[0], cols > 1 ? colY[1] : 0) - GAP);
        int viewH = bot - top;
        scrollT = Math.max(0, Math.min(scrollT, Math.max(0, contentH - viewH)));
        scroll += (scrollT - scroll) * (1f - (float) Math.exp(-dt * 18f));
        if (contentH > viewH) {
            int bh = Math.max(20, viewH * viewH / contentH);
            int by = top + (int) ((viewH - bh) * (scroll / (contentH - viewH)));
            Gfx.rrect(ctx, x0 + pw - 5, by, 2, bh, 1, A(Gfx.a(acc, 0.8f)));
        }

        U.text(ctx, hint != null ? hint : "Left click: toggle   Right click: settings   Middle click: bind key   RShift: close",
                hx, y0 + ph - 12, A(DIM));

        if (hoverTab >= 0) {
            String l = cats[hoverTab].label;
            int tw = U.width(l) + 10, tx = x0 + SIDE + 2, ty = y0 + 10 + hoverTab * 26 + 4;
            Gfx.rrect(ctx, tx, ty, tw, 16, 5, A(0xF0241A1C));
            U.text(ctx, l, tx + 5, ty + 4, A(TEXT));
        } else if (rh) {
            Gfx.rrect(ctx, x0 + SIDE + 2, ry + 4, U.width("Reset ALL") + 10, 16, 5, A(0xF0241A1C));
            U.text(ctx, "Reset ALL", x0 + SIDE + 7, ry + 8, A(TEXT));
        }
    }

    private void drawCard(DrawContext ctx, Mod m, MA a, float ex, int x, int y, int w, int h, int mx, int my, int acc, float k) {
        int tint = (acc & 0x00FFFFFF) | 0xFF000000;
        int bg = Gfx.mix(CARD, tint, 0.11f * a.en + 0.04f * a.hv);
        int border = Gfx.mix(0xFF281F22, tint, 0.55f * a.en + 0.14f * a.hv);
        if (a.en > 0.03f) Gfx.glow(ctx, x, y, w, h, 5, tint, oa * a.en * 0.55f, 4);
        Gfx.rrectBorder(ctx, x, y, w, h, 5, A(border), A(bg));

        int nameCol = Gfx.mix(0xFF4F4849, 0xFFFFFFFF, a.en);
        if (a.en > 0.5f) U.glowText(ctx, m.name, x + 8, y + 6, A(nameCol), oa * a.en * 0.3f);
        else U.text(ctx, m.name, x + 8, y + 6, A(nameCol));
        int tx = x + w - 38, ty = y + 5;
        if (a.en > 0.03f) Gfx.glow(ctx, tx, ty, 20, 10, 5, tint, oa * a.en * 0.9f, 3);
        Gfx.rrect(ctx, tx, ty, 20, 10, 5, A(Gfx.mix(TRACK, tint, a.en)));
        int kx = tx + 1 + Math.round(10 * a.en);
        Gfx.rrect(ctx, kx, ty + 1, 8, 8, 4, A(Gfx.mix(0xFFB8AEAB, 0xFFFFFFFF, a.en)));
        chevron(ctx, x + w - 9, y + 9, ex > 0.5f, A(DIM));
        String bind = binding == m ? "..." : m.key >= 0 ? "[" + keyName(m.key) + "]" : "";
        if (!bind.isEmpty()) U.text(ctx, bind, x + w - 44 - U.width(bind), y + 6, A(binding == m ? acc : DIM));

        if (ex < 0.02f) return;
        boolean live = expanded.contains(m) && ex > 0.9f;
        float ra = oa * ex * (0.4f + 0.6f * a.en);
        ctx.enableScissor(x + 1, y + CARD_HEAD, x + w - 1, y + h - 1);
        Gfx.rrect(ctx, x + 6, y + CARD_HEAD - 1, w - 12, 1, 0, Gfx.a(0xFF2B2124, ra));
        int yy = y + CARD_HEAD + 2;
        for (Setting<?> s : m.settings) {
            if (s instanceof Setting.Bool b) {
                if (in(mx, my, x + 4, yy, w - 8, ROW_B)) Gfx.rrect(ctx, x + 4, yy, w - 8, ROW_B, 4, Gfx.a(0xFFFFFFFF, 0.04f * ra));
                U.text(ctx, s.name, x + 9, yy + 4, Gfx.a(TEXT, ra));
                float v = sm(s, b.get() ? 1f : 0f, k);
                int bx = x + w - 8 - 18;
                if (v > 0.03f) Gfx.glow(ctx, bx, yy + 3, 18, 9, 4, tint, ra * v * 0.7f, 3);
                Gfx.rrect(ctx, bx, yy + 3, 18, 9, 4, Gfx.a(Gfx.mix(TRACK, tint, v), ra));
                Gfx.rrect(ctx, bx + 1 + Math.round(9 * v), yy + 4, 7, 7, 3, Gfx.a(0xFFFFFFFF, ra));
                if (live) hits.add(new Hit(1, m, s, x + 4, yy, w - 8, ROW_B));
                yy += ROW_B;
            } else if (s instanceof Setting.Mode md) {
                if (in(mx, my, x + 4, yy, w - 8, ROW_B)) Gfx.rrect(ctx, x + 4, yy, w - 8, ROW_B, 4, Gfx.a(0xFFFFFFFF, 0.04f * ra));
                U.text(ctx, s.name, x + 9, yy + 4, Gfx.a(TEXT, ra));
                String t = "< " + md.value + " >";
                U.text(ctx, t, x + w - 9 - U.width(t), yy + 4, Gfx.a(acc, ra));
                if (live) hits.add(new Hit(2, m, s, x + 4, yy, w - 8, ROW_B));
                yy += ROW_B;
            } else if (s instanceof Setting.Num n) {
                U.text(ctx, s.name, x + 9, yy + 3, Gfx.a(TEXT, ra));
                String v = fmt(n);
                U.text(ctx, v, x + w - 9 - U.width(v), yy + 3, Gfx.a(DIM, ra));
                int tx0 = x + 9, tw = w - 18, ty0 = yy + 15;
                float ratio = (float) ((n.get() - n.min) / (n.max - n.min));
                float sr = sm(s, ratio, dragging == n ? 0.6f : k);
                Gfx.rrect(ctx, tx0, ty0, tw, 3, 1, Gfx.a(0xFF30282A, ra));
                int fw = Math.max(0, Math.round(tw * sr));
                if (fw > 0) {
                    Gfx.glow(ctx, tx0, ty0, Math.max(2, fw), 3, 1, acc, ra * 0.7f, 3);
                    Gfx.rrect(ctx, tx0, ty0, Math.max(2, fw), 3, 1, Gfx.a(acc, ra));
                }
                Gfx.rrect(ctx, tx0 + fw - 3, ty0 - 2, 7, 7, 3, Gfx.a(0xFFFFFFFF, ra));
                if (live) hits.add(new Hit(3, m, s, tx0, yy + 8, tw, 13));
                yy += ROW_N;
            } else if (s instanceof Setting.Action) {
                boolean hov = in(mx, my, x + 8, yy + 1, w - 16, ROW_B - 2);
                Gfx.rrect(ctx, x + 8, yy + 1, w - 16, ROW_B - 2, 4, Gfx.a(hov ? Gfx.mix(0xFF2B2124, tint, 0.35f) : 0xFF241A1C, ra));
                U.text(ctx, s.name, x + w / 2 - U.width(s.name) / 2, yy + 4, Gfx.a(TEXT, ra));
                if (live) hits.add(new Hit(4, m, s, x + 8, yy + 1, w - 16, ROW_B - 2));
                yy += ROW_B;
            }
        }
        int half = (w - 22) / 2;
        boolean h1 = in(mx, my, x + 8, yy + 3, half, 13), h2 = in(mx, my, x + 14 + half, yy + 3, half, 13);
        Gfx.rrect(ctx, x + 8, yy + 3, half, 13, 4, Gfx.a(h1 ? 0xFF7A2A35 : 0xFF4A1F28, ra));
        U.text(ctx, "Reset", x + 8 + half / 2 - U.width("Reset") / 2, yy + 6, Gfx.a(TEXT, ra));
        Gfx.rrect(ctx, x + 14 + half, yy + 3, half, 13, 4, Gfx.a(h2 ? Gfx.mix(0xFF2B2124, tint, 0.35f) : 0xFF241A1C, ra));
        String bt = binding == m ? "Press a key..." : "Bind: " + keyName(m.key);
        U.text(ctx, bt, x + 14 + half + half / 2 - U.width(bt) / 2, yy + 6, Gfx.a(TEXT, ra));
        if (live) {
            hits.add(new Hit(5, m, null, x + 8, yy + 3, half, 13));
            hits.add(new Hit(6, m, null, x + 14 + half, yy + 3, half, 13));
        }
        ctx.disableScissor();
    }

    // ---------- input ----------
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        if (binding != null) { binding = null; click(0.8f); return true; }
        int y0 = py + oy;
        int sw = 120, sx = px + pw - sw - PAD, sy = y0 + 7;
        searchFocus = in(mx, my, sx, sy, sw, 16);
        if (searchFocus) { click(1.0f); return true; }

        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            if (in(mx, my, px + 6, y0 + 10 + i * 26, 24, 24)) {
                cat = cats[i]; scroll = scrollT = 0; search = "";
                click(1.1f);
                return true;
            }
        }
        if (in(mx, my, px + 6, y0 + ph - 32, 24, 24)) {
            ModuleManager.resetAll(); expanded.clear(); click(0.7f);
            return true;
        }

        if (my < ctop() || my >= cbot() || mx < cx() - 2 || mx > cx() + cw() + 2) return super.mouseClicked(click, doubled);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!in(mx, my, h.x(), h.y(), h.w(), h.h())) continue;
            switch (h.kind()) {
                case 0 -> {
                    if (btn == 0) { h.m().toggle(); click(h.m().enabled ? 1.25f : 0.85f); }
                    else if (btn == 1) { if (!expanded.remove(h.m())) expanded.add(h.m()); click(1.0f); }
                    else if (btn == 2) { binding = h.m(); click(1.0f); }
                }
                case 1 -> { Setting.Bool b = (Setting.Bool) h.s(); b.value = !b.value; click(b.value ? 1.2f : 0.9f); }
                case 2 -> { ((Setting.Mode) h.s()).cycle(btn == 1 ? -1 : 1); click(1.05f); }
                case 3 -> { if (btn == 0) { dragging = (Setting.Num) h.s(); dragHit = h; updateDrag(mx); click(1.3f); } }
                case 4 -> { ((Setting.Action) h.s()).run.run(); click(1.0f); }
                case 5 -> { h.m().reset(); click(0.8f); }
                case 6 -> { binding = h.m(); click(1.0f); }
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private void updateDrag(double mx) {
        if (dragging == null || dragHit == null) return;
        double ratio = Math.max(0, Math.min(1, (mx - dragHit.x()) / dragHit.w()));
        dragging.set(dragging.min + ratio * (dragging.max - dragging.min));
    }

    @Override public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) { updateDrag(click.x()); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override public boolean mouseReleased(Click click) {
        dragging = null; dragHit = null;
        return super.mouseReleased(click);
    }

    @Override public boolean mouseScrolled(double mx, double my, double h, double v) {
        scrollT = Math.max(0, Math.min(scrollT - v * 28, Math.max(0, contentH - (cbot() - ctop()))));
        return true;
    }

    @Override public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (binding != null) {
            if (k == 261) binding.key = -1;
            else if (k != 256) binding.key = k;
            binding = null;
            click(1.0f);
            return true;
        }
        if (searchFocus) {
            if (k == 256) { searchFocus = false; return true; }
            if (k == 259 && !search.isEmpty()) { search = search.substring(0, search.length() - 1); scroll = scrollT = 0; }
            return true;
        }
        if (k == 344) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override public boolean charTyped(CharInput input) {
        if (searchFocus && input.codepoint() >= 32) {
            search += Character.toString(input.codepoint());
            scroll = scrollT = 0;
            return true;
        }
        return super.charTyped(input);
    }
}
