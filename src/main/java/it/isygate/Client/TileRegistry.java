package it.isygate.client;

import com.google.gson.*;
import javafx.geometry.Rectangle2D;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

/**
 * Registro centralizzato per risolvere il path delle GIF dei tile,
 * con supporto a contesti (overworld, city, dungeon), alias e viewport.
 *
 * Manifesto atteso in classpath: /maps/tiles.json
 */
public final class TileRegistry {
    private TileRegistry() {
    }

    private static Manifest M;
    private static final String DEFAULT_MANIFEST = "/maps/tiles.json";

    // ---- Types ----
    private static class Manifest {
        Map<String, String> contexts = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        Map<String, TileDef> tiles = new HashMap<>();
    }

    private static class TileDef {
        JsonElement file; // può essere stringa o {context->filename}
        Boolean collidable;
        Integer z;
        JsonElement viewport; // raw dal manifest
    }

    // ---- API ----
    public static synchronized void load() {
        try (InputStream in = TileRegistry.class.getResourceAsStream(DEFAULT_MANIFEST)) {
            if (in == null) {
                System.err.println("[TileRegistry] Manifest non trovato: " + DEFAULT_MANIFEST);
                M = new Manifest();
                setDefaults();
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();

            Manifest m = new Manifest();
            // contexts
            if (root.has("contexts")) {
                JsonObject ctx = root.getAsJsonObject("contexts");
                for (String k : ctx.keySet())
                    m.contexts.put(k, ctx.get(k).getAsString());
            } else {
                setDefaultsInto(m);
            }

            // aliases
            if (root.has("aliases")) {
                JsonObject al = root.getAsJsonObject("aliases");
                for (String k : al.keySet())
                    m.aliases.put(k, al.get(k).getAsString());
            }

            // tiles
            if (root.has("tiles")) {
                JsonObject tiles = root.getAsJsonObject("tiles");
                for (String id : tiles.keySet()) {
                    JsonElement te = tiles.get(id);
                    TileDef td = new TileDef();

                    if (te.isJsonPrimitive()) {
                        td.file = te.getAsJsonPrimitive();
                    } else {
                        JsonObject to = te.getAsJsonObject();
                        if (to.has("file"))
                            td.file = to.get("file");
                        if (to.has("collidable"))
                            td.collidable = to.get("collidable").getAsBoolean();
                        if (to.has("z"))
                            td.z = to.get("z").getAsInt();
                        if (to.has("viewport"))
                            td.viewport = to.get("viewport"); // salva raw
                    }
                    m.tiles.put(id, td);
                }
            }

            M = m;
            System.out.println("[TileRegistry] Caricato manifest con " + M.tiles.size() + " tile.");
        } catch (Exception e) {
            System.err.println("[TileRegistry] Errore di parsing manifest: " + e.getMessage());
            M = new Manifest();
            setDefaults();
        }
    }

    public static String getPath(int tileId, String context) {
        if (M == null)
            load();
        String canonical = canonicalId(tileId);
        TileDef d = M.tiles.get(canonical);
        if (d == null)
            return null;

        // file come stringa unica → vale per tutti i contesti
        if (d.file != null && d.file.isJsonPrimitive()) {
            String fname = d.file.getAsString();
            return baseDir(context) + fname;
        }

        // file per-contesto
        if (d.file != null && d.file.isJsonObject()) {
            JsonObject fo = d.file.getAsJsonObject();
            String fname = null;
            if (fo.has(context))
                fname = fo.get(context).getAsString();
            else if (fo.has("default"))
                fname = fo.get("default").getAsString();
            if (fname == null)
                return null;
            if (fname.startsWith("/"))
                return fname;
            return baseDir(context) + fname;
        }

        return null;
    }

    public static String canonicalId(int tileId) {
        String s = Integer.toString(tileId);
        if (M != null && M.aliases != null)
            return M.aliases.getOrDefault(s, s);
        return s;
    }

    /**
     * Restituisce il viewport da applicare a questo tile in un certo contesto.
     */
    public static Rectangle2D getViewport(int tileId, String context) {
        if (M == null)
            load();
        String cid = canonicalId(tileId);
        TileDef d = M.tiles.get(cid);
        if (d == null)
            return null;

        // 1) dal manifest
        Rectangle2D vp = viewportFromManifest(d.viewport, context);
        if (vp != null)
            return vp;

        // 2) default
        String ctx = (context == null ? "overworld" : context.toLowerCase());
        if ("city".equals(ctx) || "dungeon".equals(ctx)) {
            boolean isWallish = (d.z != null && d.z > 0) || (d.collidable != null && d.collidable);
            if (isWallish) {
                return new Rectangle2D(0, 0, 19, 19);
            } else {
                return new Rectangle2D(26, 26, 19, 19);
            }
        }
        return null;
    }

    // ---- Internals ----
    private static Rectangle2D viewportFromManifest(JsonElement vp, String context) {
        if (vp == null)
            return null;
        try {
            if (vp.isJsonArray()) {
                // formato: [x,y,w,h]
                JsonArray arr = vp.getAsJsonArray();
                if (arr.size() == 4) {
                    return new Rectangle2D(
                            arr.get(0).getAsDouble(),
                            arr.get(1).getAsDouble(),
                            arr.get(2).getAsDouble(),
                            arr.get(3).getAsDouble());
                }
            } else if (vp.isJsonObject()) {
                JsonObject obj = vp.getAsJsonObject();
                // caso oggetto diretto {x,y,w,h}
                if (obj.has("x") && obj.has("y") && obj.has("w") && obj.has("h")) {
                    return new Rectangle2D(
                            obj.get("x").getAsDouble(),
                            obj.get("y").getAsDouble(),
                            obj.get("w").getAsDouble(),
                            obj.get("h").getAsDouble());
                }
                // caso per contesto {city:[..], dungeon:[..], default:[..]}
                String ctx = (context == null ? "overworld" : context.toLowerCase());
                String[] keys = new String[] { ctx, "default" };
                for (String k : keys) {
                    if (obj.has(k)) {
                        JsonElement ve = obj.get(k);
                        if (ve.isJsonArray()) {
                            JsonArray arr = ve.getAsJsonArray();
                            if (arr.size() == 4) {
                                return new Rectangle2D(
                                        arr.get(0).getAsDouble(),
                                        arr.get(1).getAsDouble(),
                                        arr.get(2).getAsDouble(),
                                        arr.get(3).getAsDouble());
                            }
                        } else if (ve.isJsonObject()) {
                            JsonObject sub = ve.getAsJsonObject();
                            if (sub.has("x") && sub.has("y") && sub.has("w") && sub.has("h")) {
                                return new Rectangle2D(
                                        sub.get("x").getAsDouble(),
                                        sub.get("y").getAsDouble(),
                                        sub.get("w").getAsDouble(),
                                        sub.get("h").getAsDouble());
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static Image getImage(int tileId, String ctx) {
        String path = getPath(tileId, ctx);
        if (path == null) {
            path = ctx.equals("overworld") ? "/maps/icons/0.gif" : "/maps/client/0Y.gif";
        }
        try (InputStream in = TileRegistry.class.getResourceAsStream(path)) {
            if (in != null) {
                return new Image(in);
            }
        } catch (Exception e) {
            System.err.println("[TileRegistry] Errore caricando immagine " + path + ": " + e.getMessage());
        }
        return new javafx.scene.image.WritableImage(1, 1); // fallback trasparente
    }

    private static String baseDir(String context) {
        if (M == null || M.contexts == null)
            return "/maps/icons/";
        return M.contexts.getOrDefault(context, "/maps/icons/");
    }

    private static void setDefaults() {
        if (M == null)
            M = new Manifest();
        setDefaultsInto(M);
    }

    private static void setDefaultsInto(Manifest mm) {
        mm.contexts.put("overworld", "/maps/icons/");
        mm.contexts.put("city", "/maps/client/");
        mm.contexts.put("dungeon", "/maps/client/");
    }

    /**
     * Restituisce il viewport per un file immagine in un dato contesto (city,
     * dungeon...).
     * Cerca nel manifest il tile che ha quel file associato e ritorna il suo
     * viewport.
     */
    public static Rectangle2D getViewportForFile(String fileName, String ctx) {
        if (M == null)
            load();
        if (M.tiles == null)
            return null;

        for (Map.Entry<String, TileDef> e : M.tiles.entrySet()) {
            TileDef def = e.getValue();
            if (def == null || def.file == null)
                continue;

            // caso file come stringa unica
            if (def.file.isJsonPrimitive()) {
                String fname = def.file.getAsString();
                if (fname.equalsIgnoreCase(fileName)) {
                    return viewportFromManifest(def.viewport, ctx);
                }
            }
            // caso file per contesto
            else if (def.file.isJsonObject()) {
                JsonObject fo = def.file.getAsJsonObject();
                if (fo.has(ctx)) {
                    String fname = fo.get(ctx).getAsString();
                    if (fname.equalsIgnoreCase(fileName)) {
                        return viewportFromManifest(def.viewport, ctx);
                    }
                }
            }
        }
        return null;
    }
}
