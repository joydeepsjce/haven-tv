package home.haven.tv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Updates from GitHub Releases. Haven checks the latest release now and then; installing
 * downloads the APK, checks it's Haven, newer, and signed with the same key as the installed
 * copy, then hands it to Android's installer, which asks you to confirm. Nothing installs silently.
 */
final class Updater {

    static final String LATEST = "https://api.github.com/repos/joydeepsjce/haven-tv/releases/latest";
    private static final long CHECK_EVERY_MS = 12 * 3600_000L;
    private static final int MAX_APK = 60 * 1024 * 1024;

    static final class Release {
        final String version, apkUrl;
        final long size;

        Release(String version, String apkUrl, long size) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.size = size;
        }
    }

    private Updater() { }

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** At most every 12 hours; blocking, so call off the main thread. Null when there's nothing newer. */
    static Release checkIfDue(Context c) {
        SharedPreferences p = c.getSharedPreferences("haven", Context.MODE_PRIVATE);
        long last = p.getLong("updateCheckedAt", 0);
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return null;
        p.edit().putLong("updateCheckedAt", System.currentTimeMillis()).apply();
        try {
            return check(c);
        } catch (Exception e) {
            return null;
        }
    }

    /** The latest release if it's newer than this copy of Haven; null if not. Blocking. */
    static Release check(Context c) throws Exception {
        JSONObject r = new JSONObject(new String(get(LATEST, 1024 * 1024), StandardCharsets.UTF_8));
        String version = r.optString("tag_name", "").replaceFirst("^[vV]", "");
        JSONArray assets = r.optJSONArray("assets");
        String url = null;
        long size = 0;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name", "");
            if (!name.toLowerCase().endsWith(".apk")) continue;
            if (url == null || name.equalsIgnoreCase("haven.apk")) {
                url = a.optString("browser_download_url", null);
                size = a.optLong("size", 0);
            }
        }
        if (url == null || compare(version, installedVersion(c)) <= 0) return null;
        // Remembered so Haven Settings still offers it after a restart, before the next check.
        c.getSharedPreferences("haven", Context.MODE_PRIVATE).edit()
                .putString("updateVersion", version).putString("updateUrl", url).putLong("updateSize", size).apply();
        return new Release(version, url, size);
    }

    /** The update found by an earlier check, if it's still newer than this copy. */
    static Release saved(Context c) {
        SharedPreferences p = c.getSharedPreferences("haven", Context.MODE_PRIVATE);
        String version = p.getString("updateVersion", null), url = p.getString("updateUrl", null);
        if (version == null || url == null || compare(version, installedVersion(c)) <= 0) return null;
        return new Release(version, url, p.getLong("updateSize", 0));
    }

    /** Compares dotted versions numerically: 4.10 is newer than 4.9. */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    /** Downloads the APK to Haven's cache, reporting percent done. Blocking. */
    static File download(Context c, Release r, IntConsumer percent) throws IOException {
        File dir = new File(c.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No space for the update");
        File apk = new File(dir, "haven.apk");
        HttpURLConnection h = open(r.apkUrl);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : r.size, done = 0;
            byte[] buf = new byte[64 * 1024];
            int last = -1;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                if (done > MAX_APK) throw new IOException("The download is too large");
                int pc = total > 0 ? (int) (done * 100 / total) : 0;
                if (pc != last) percent.accept(last = pc);
            }
        } finally {
            h.disconnect();
        }
        return apk;
    }

    /** Null when the APK is a newer Haven signed like this one; otherwise what's wrong, in words. */
    static String verify(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        PackageInfo update = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (update == null || !c.getPackageName().equals(update.packageName)) return "The download isn't Haven.";
        try {
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (update.getLongVersionCode() <= mine.getLongVersionCode()) return "You already have this version.";
            if (update.signingInfo == null || mine.signingInfo == null
                    || !Arrays.equals(update.signingInfo.getApkContentsSigners(), mine.signingInfo.getApkContentsSigners())) {
                return "This copy of Haven was signed with a different key (for example, you built it yourself), "
                        + "so Android won't update it from GitHub. Install the update with adb instead.";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "Couldn't read the installed Haven.";
        }
        return null;
    }

    /** Whether Android lets Haven install apps; if not, opens the setting (or says where it is). */
    static boolean canInstall(Context c) {
        if (c.getPackageManager().canRequestPackageInstalls()) return true;
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            Toast.makeText(c, "Allow Haven to install apps, then go back and choose Install again", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(c, "Allow Haven under Settings > Privacy > Security & restrictions > Unknown sources, then try again",
                    Toast.LENGTH_LONG).show();
        }
        return false;
    }

    /** Hands the APK to Android's installer, which shows its own confirmation. */
    static void install(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session s = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("haven.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(c, InstallReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pi.getIntentSender());
        }
    }

    /** Android's installer reports back here: show its confirmation, or say why it failed. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Update didn't install" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        return open(url, null);
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(30_000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "Haven (Android TV launcher)");
        if (accept != null) h.setRequestProperty("Accept", accept);
        if (h.getResponseCode() >= 400) {
            h.disconnect();
            throw new IOException("HTTP " + h.getResponseCode());
        }
        return h;
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection h = open(url, "application/vnd.github+json");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("Response too large");
            }
            return out.toByteArray();
        } finally {
            h.disconnect();
        }
    }
}
