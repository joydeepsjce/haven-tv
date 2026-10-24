package home.haven.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The home-screen layout. Every key lives in exactly one place: the dock, a folder, the hidden
 * list, or the app grid (the default for anything not placed elsewhere).
 */
final class Store {

    static final String FOLDER = "folder:";

    static final class Folder {
        String name;
        final List<String> items = new ArrayList<>();
    }

    final List<String> dock = new ArrayList<>();
    /** Grid order chosen by the user; keys not listed follow in their default order. */
    final List<String> order = new ArrayList<>();
    final Map<String, Folder> folders = new LinkedHashMap<>();
    final Set<String> hidden = new LinkedHashSet<>();
    /** Packages launched from Haven, newest first (app-switcher fallback without usage access). */
    final List<String> recent = new ArrayList<>();
    boolean sounds;
    /** Whether Haven should keep its Home-button service switched on (it re-arms it if something turns it off). */
    boolean homeKey;
    /** Keep system animations at 0.5x (TCL Safety Guard's optimise resets them). */
    boolean fastAnim;
    /** Tint focus rings and glow with the wallpaper's colour. */
    boolean dynColor;
    /** Wallpaper brightness in percent (55–100). */
    int brightness;
    /** Background blur: 0 off, 1 low, 2 medium, 3 high. */
    int blurLevel;
    /** Show the Top movies and Trending shows rows. */
    boolean mediaRows;
    /** Letter shown in the account badge (Google doesn't let other apps read the account name). */
    String accountLetter;
    /** Names the user gave apps, by key. */
    final Map<String, String> renames = new LinkedHashMap<>();

    /**
     * Copy of the layout kept in a system setting. TCL Safety Guard's deep clean clears Haven's app
     * data (it isn't on TCL's protected list); the system setting survives, so the layout comes back.
     */
    private static final String BACKUP_KEY = "haven_layout_backup";

    private final Context c;
    private final SharedPreferences p;

    Store(Context c) {
        this.c = c.getApplicationContext();
        p = c.getSharedPreferences("haven", Context.MODE_PRIVATE);
        if (!p.contains("dock")) restoreBackup();
        load();
    }

    private void restoreBackup() {
        String json = Settings.Global.getString(c.getContentResolver(), BACKUP_KEY);
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject b = new JSONObject(json);
            p.edit()
                    .putString("dock", b.optString("dock", ""))
                    .putString("order", b.optString("order", ""))
                    .putString("hidden", b.optString("hidden", ""))
                    .putString("folders", b.optString("folders", "{}"))
                    .putBoolean("sounds", b.optBoolean("sounds", true))
                    .putBoolean("homeKey", b.optBoolean("homeKey", true))
                    .putBoolean("fastAnim", b.optBoolean("fastAnim", true))
                    .putBoolean("dynColor", b.optBoolean("dynColor", true))
                    .putInt("brightness", b.optInt("brightness", 100))
                    .putInt("blurLevel", b.optInt("blurLevel", 2))
                    .putBoolean("mediaRows", b.optBoolean("mediaRows", true))
                    .putString("renames", b.optString("renames", "{}"))
                    .putString("accountLetter", b.optString("accountLetter", ""))
                    .commit();
        } catch (JSONException ignored) { }
    }

    /** Deletes the layout copy in the system settings (before uninstalling). */
    void forgetBackup() {
        writeBackup(null);
    }

    private void writeBackup(JSONObject b) {
        if (!Keeper.canWrite(c)) return;
        try {
            Settings.Global.putString(c.getContentResolver(), BACKUP_KEY, b == null ? null : b.toString());
        } catch (SecurityException ignored) { }
    }

    boolean hasDock() {
        return p.contains("dock");
    }

    void load() {
        dock.clear(); order.clear(); folders.clear(); hidden.clear(); recent.clear();
        split(p.getString("dock", ""), dock);
        split(p.getString("order", ""), order);
        split(p.getString("recent", ""), recent);
        List<String> h = new ArrayList<>();
        split(p.getString("hidden", ""), h);
        hidden.addAll(h);
        sounds = p.getBoolean("sounds", true);
        homeKey = p.getBoolean("homeKey", true);
        fastAnim = p.getBoolean("fastAnim", true);
        dynColor = p.getBoolean("dynColor", true);
        brightness = p.getInt("brightness", 100);
        blurLevel = p.getInt("blurLevel", 2);
        mediaRows = p.getBoolean("mediaRows", true);
        accountLetter = p.getString("accountLetter", "");
        renames.clear();
        try {
            JSONObject r = new JSONObject(p.getString("renames", "{}"));
            for (Iterator<String> it = r.keys(); it.hasNext(); ) {
                String k = it.next();
                renames.put(k, r.getString(k));
            }
        } catch (JSONException ignored) { }
        try {
            JSONObject all = new JSONObject(p.getString("folders", "{}"));
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject o = all.getJSONObject(id);
                Folder f = new Folder();
                f.name = o.optString("name", "Folder");
                JSONArray items = o.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) f.items.add(items.getString(i));
                folders.put(id, f);
            }
        } catch (JSONException ignored) { }
    }

    void save() {
        JSONObject all = new JSONObject();
        try {
            for (Map.Entry<String, Folder> e : folders.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("name", e.getValue().name);
                o.put("items", new JSONArray(e.getValue().items));
                all.put(e.getKey(), o);
            }
        } catch (JSONException ignored) { }
        String d = TextUtils.join("\n", dock), o = TextUtils.join("\n", order), h = TextUtils.join("\n", hidden);
        String r = new JSONObject(renames).toString();
        p.edit()
                .putString("dock", d)
                .putString("order", o)
                .putString("hidden", h)
                .putString("folders", all.toString())
                .putBoolean("sounds", sounds)
                .putBoolean("homeKey", homeKey)
                .putBoolean("fastAnim", fastAnim)
                .putBoolean("dynColor", dynColor)
                .putInt("brightness", brightness)
                .putInt("blurLevel", blurLevel)
                .putBoolean("mediaRows", mediaRows)
                .putString("renames", r)
                .putString("accountLetter", accountLetter)
                .apply();
        try {
            writeBackup(new JSONObject()
                    .put("dock", d).put("order", o).put("hidden", h).put("folders", all.toString())
                    .put("sounds", sounds).put("homeKey", homeKey).put("fastAnim", fastAnim)
                    .put("dynColor", dynColor).put("brightness", brightness).put("blurLevel", blurLevel)
                    .put("mediaRows", mediaRows).put("renames", r).put("accountLetter", accountLetter));
        } catch (JSONException ignored) { }
    }

    void reset() {
        p.edit().remove("dock").remove("order").remove("hidden").remove("folders").remove("renames").apply();
        writeBackup(null);
        load();
    }

    void noteLaunch(String pkg) {
        recent.remove(pkg);
        recent.add(0, pkg);
        while (recent.size() > 12) recent.remove(recent.size() - 1);
        p.edit().putString("recent", TextUtils.join("\n", recent)).apply();
    }

    /** "dock", "hidden", a folder key, or null for the grid. */
    String placeOf(String key) {
        if (dock.contains(key)) return "dock";
        if (hidden.contains(key)) return "hidden";
        for (Map.Entry<String, Folder> e : folders.entrySet()) {
            if (e.getValue().items.contains(key)) return FOLDER + e.getKey();
        }
        return null;
    }

    void removeEverywhere(String key) {
        dock.remove(key);
        order.remove(key);
        hidden.remove(key);
        for (Folder f : folders.values()) f.items.remove(key);
    }

    String newFolder(String name) {
        String id = Long.toString(System.currentTimeMillis(), 36);
        Folder f = new Folder();
        f.name = name;
        folders.put(id, f);
        return id;
    }

    static boolean isFolder(String key) {
        return key.startsWith(FOLDER);
    }

    static String folderId(String key) {
        return key.substring(FOLDER.length());
    }

    static void move(List<String> list, String key, int to) {
        list.remove(key);
        list.add(Math.max(0, Math.min(list.size(), to)), key);
    }

    private static void split(String s, List<String> out) {
        if (s == null || s.isEmpty()) return;
        for (String k : s.split("\n")) if (!k.isEmpty() && !out.contains(k)) out.add(k);
    }
}
