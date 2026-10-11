package nova.core;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    private Config() {}
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("nova.json"); }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            for (Mod m : ModuleManager.MODULES) {
                JsonObject o = new JsonObject();
                o.addProperty("key", m.key);
                JsonObject s = new JsonObject();
                for (Setting<?> st : m.settings) { if (st instanceof Setting.Action) continue; s.add(st.name, st.save()); }
                o.add("settings", s);
                root.add(m.name, o);
            }
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void load() {
        try {
            if (!Files.exists(path())) return;
            JsonObject root = JsonParser.parseString(Files.readString(path())).getAsJsonObject();
            for (Mod m : ModuleManager.MODULES) {
                if (!root.has(m.name)) continue;
                JsonObject o = root.getAsJsonObject(m.name);
                if (o.has("key")) m.key = o.get("key").getAsInt();
                if (o.has("settings")) {
                    JsonObject s = o.getAsJsonObject("settings");
                    for (Setting<?> st : m.settings) if (s.has(st.name)) {
                        try { st.load(s.get(st.name)); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
