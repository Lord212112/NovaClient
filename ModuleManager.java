package nova.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import nova.gui.NovaScreen;
import nova.modules.*;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class ModuleManager {
    public static final List<Mod> MODULES = new ArrayList<>();
    public static boolean quiet;
    private static final Map<Mod, Boolean> keyDown = new HashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger("nova");
    private static boolean guiKeyDown;
    private ModuleManager() {}

    private static void add(Mod... ms) { MODULES.addAll(Arrays.asList(ms)); }

    public static void init() {
        add(new CombatModules.KillAura(), new CombatModules.AimAssist(), new CombatModules.TriggerBot(),
            new CombatModules.AutoClicker(), new CombatModules.AutoTotem(), new CombatModules.AutoArmor(),
            new CombatModules.AutoWeapon(), new CombatModules.Surround(),
            new DefenseModules.AntiBot(), new DefenseModules.AutoBow(), new DefenseModules.AutoShield(),
            new DefenseModules.AutoGapple(), new DefenseModules.AutoPotion());
        add(new MovementModules.Flight(), new MovementModules.Speed(), new MovementModules.Sprint(),
            new MovementModules.Step(), new MovementModules.HighJump(), new MovementModules.AutoJump(),
            new MovementModules.Parkour(), new MovementModules.AutoWalk(), new MovementModules.Sneak(),
            new MovementModules.Glide(), new MovementModules.Spider(), new MovementModules.FastClimb(),
            new MovementModules.Jesus(), new MovementModules.FastFall(), new MovementModules.LongJump());
        add(new PlayerModules.NoFall(), new PlayerModules.Scaffold(), new PlayerModules.AutoEat(),
            new PlayerModules.AutoTool(), new PlayerModules.ChestStealer(), new PlayerModules.AutoReplenish());
        add(new RenderModules.ESP(), new RenderModules.Tracers(), new RenderModules.StorageESP(),
            new RenderModules.OreESP(), new RenderModules.HoleESP(), new RenderModules.GlowESP(),
            new RenderModules.Fullbright(), new RenderModules.NoBob(), new RenderModules.FovChanger());
        add(new BuildModules.Schematic(), new WorldModules.Nuker(), new WorldModules.OreScan(),
            new WorldModules.StashFinder(), new WorldModules.BaseFinder(), new WorldModules.SpawnerFinder());
        add(new MiscModules.AutoRespawn(), new MiscModules.AutoLog(), new MiscModules.AntiAFK(), new MiscModules.PearlThrow());
        add(new HudModules.Watermark(), new HudModules.ModuleList(), new HudModules.Coordinates(),
            new HudModules.ArmorHud(), new HudModules.PlayerRadar(), new HudModules.PotionHud(),
            new HudModules.SpeedHud(), new HudModules.PingHud());
        add(new ClientModules.Theme(), new ClientModules.Gui(), new ClientModules.Notify());
        for (Mod m : MODULES) if (m.category == Category.CLIENT) m.enabled = true;
    }

    public static void resetAll() {
        quiet = true;
        for (Mod m : MODULES) { m.reset(); if (m.category == Category.CLIENT) m.enabled = true; }
        quiet = false;
    }

    public static List<Mod> in(Category c) {
        List<Mod> out = new ArrayList<>();
        for (Mod m : MODULES) if (m.category == c) out.add(m);
        return out;
    }

    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Proj.tick();
        if (mc.player == null || mc.world == null) return;

        long w = mc.getWindow().getHandle();
        boolean g = GLFW.glfwGetKey(w, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean opened = false;
        if (mc.currentScreen == null && g && !guiKeyDown) { mc.setScreen(new NovaScreen()); opened = true; }
        guiKeyDown = g;
        if (mc.currentScreen == null && !opened) {
            for (Mod m : MODULES) {
                if (m.key < 0) continue;
                boolean d = GLFW.glfwGetKey(w, m.key) == GLFW.GLFW_PRESS;
                if (d && !keyDown.getOrDefault(m, false)) m.toggle();
                keyDown.put(m, d);
            }
        }

        for (Mod m : MODULES) {
            if (!m.enabled) continue;
            try { m.onTick(); }
            catch (Throwable t) { LOG.error("Mod {} crashed and was disabled", m.name, t); m.setEnabled(false); }
        }
    }

    public static void hud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        for (Mod m : MODULES) {
            if (!m.enabled) continue;
            try { m.onHud(ctx); } catch (Throwable ignored) {}
        }
    }
}
