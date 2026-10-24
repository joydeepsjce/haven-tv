package home.haven.tv;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * Getting your own photos onto the TV without adb: upload them from a phone's browser (a small
 * page Haven serves on your Wi-Fi only while its dialog is open, behind a random code), or copy
 * them from a USB drive. Photos land in {@link Slideshow#myPhotosDir}.
 */
final class PhotoImport {

    private static final int PORT = 8765;
    private static final int MAX_BYTES = 30 * 1024 * 1024, MAX_PHOTOS = 300, MAX_USB = 100;

    private PhotoImport() { }

    // ---------------------------------------------------------------- upload from a phone

    /** A running upload page. {@link #stop} when the dialog closes. */
    static final class Upload {
        final String url;
        private final ServerSocket server;
        private final ExecutorService pool = Executors.newFixedThreadPool(3);
        private final File dir;
        private final String code;
        private final IntConsumer onAdded;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private int added;

        private Upload(ServerSocket server, File dir, String code, String url, IntConsumer onAdded) {
            this.server = server;
            this.dir = dir;
            this.code = code;
            this.url = url;
            this.onAdded = onAdded;
            pool.execute(this::accept);
        }

        void stop() {
            try {
                server.close();
            } catch (IOException ignored) { }
            pool.shutdownNow();
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    s.setSoTimeout(60_000);
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket s) {
            try (Socket sock = s; InputStream in = new BufferedInputStream(sock.getInputStream());
                 OutputStream out = sock.getOutputStream()) {
                String request = line(in);
                if (request == null) return;
                long length = -1;
                for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                    if (h.toLowerCase(Locale.US).startsWith("content-length:")) {
                        try {
                            length = Long.parseLong(h.substring(15).trim());
                        } catch (NumberFormatException ignored) { }
                    }
                }
                String[] parts = request.split(" ");
                if (parts.length < 2) return;
                String method = parts[0], path = parts[1];
                String base = "/" + code;
                if ("GET".equals(method) && (path.equals(base) || path.equals(base + "/"))) {
                    respond(out, "200 OK", "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8));
                } else if ("POST".equals(method) && path.startsWith(base + "/upload?name=")) {
                    String name = URLDecoder.decode(path.substring((base + "/upload?name=").length()), "UTF-8");
                    String result = save(in, length, name);
                    respond(out, result == null ? "200 OK" : "400 Bad Request", "text/plain; charset=utf-8",
                            (result == null ? "ok" : result).getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(out, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) { }
        }

        /** Saves one uploaded photo; returns null when it worked, or what went wrong. */
        private String save(InputStream in, long length, String name) throws IOException {
            if (length <= 0 || length > MAX_BYTES) return "Each photo must be under 30 MB.";
            if (count(dir) >= MAX_PHOTOS) {
                return "Haven already has " + MAX_PHOTOS + " of your photos.";
            }
            String clean = cleanName(name);
            if (clean == null) return "Only JPG, PNG and WebP photos.";
            if (!dir.isDirectory() && !dir.mkdirs()) return "Couldn't save on the TV.";
            File tmp = new File(dir, ".upload-" + System.nanoTime());
            try (OutputStream f = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long left = length;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    f.write(buf, 0, n);
                    left -= n;
                }
                if (left > 0) {
                    tmp.delete();
                    return "Upload was cut off.";
                }
            }
            if (!isPhoto(tmp)) {
                tmp.delete();
                return "That isn't a photo Haven can show.";
            }
            File dest = unique(dir, clean);
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                return "Couldn't save on the TV.";
            }
            synchronized (this) {
                added++;
            }
            int n = added;
            ui.post(() -> onAdded.accept(n));
            return null;
        }

        private static String page() {
            return "<!doctype html><html><head><meta charset=utf-8>"
                    + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<title>Haven: add wallpapers</title><style>"
                    + "body{font:16px system-ui,sans-serif;margin:0;padding:24px;background:#111418;color:#eee}"
                    + "h1{font-size:22px;font-weight:600}p{color:#aab}"
                    + "label{display:block;padding:18px;border-radius:12px;background:#2a2f38;text-align:center;font-size:18px}"
                    + "input{display:none}li{margin:6px 0}.ok{color:#8d8}.err{color:#f88}</style></head><body>"
                    + "<h1>Add wallpapers to Haven</h1><p>Pick photos from your phone. They appear on the TV"
                    + " straight away. Keep the dialog open on the TV until they're done.</p>"
                    + "<label>Choose photos<input id=f type=file accept='image/jpeg,image/png,image/webp' multiple></label>"
                    + "<ul id=l></ul><script>"
                    + "const f=document.getElementById('f'),l=document.getElementById('l');"
                    + "f.onchange=async()=>{for(const x of f.files){const li=document.createElement('li');"
                    + "li.textContent=x.name+'…';l.appendChild(li);try{const r=await fetch(location.pathname.replace(/\\/$/,'')"
                    + "+'/upload?name='+encodeURIComponent(x.name),{method:'POST',body:x});const t=await r.text();"
                    + "li.textContent=x.name+(r.ok?' ✓':' – '+t);li.className=r.ok?'ok':'err'}catch(e){"
                    + "li.textContent=x.name+' – failed';li.className='err'}}f.value=''};"
                    + "</script></body></html>";
        }
    }

    /** Starts the upload page; null when the TV has no network address or the port is busy. */
    static Upload startUpload(Context c, IntConsumer onAdded) {
        String ip = localAddress();
        File dir = Slideshow.myPhotosDir(c);
        if (ip == null || dir == null) return null;
        String code = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
        for (int port = PORT; port < PORT + 10; port++) {
            try {
                ServerSocket s = new ServerSocket(port);
                return new Upload(s, dir, code, "http://" + ip + (port == 80 ? "" : ":" + port) + "/" + code, onAdded);
            } catch (IOException ignored) { }
        }
        return null;
    }

    private static String localAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int ch; (ch = in.read()) >= 0; ) {
            if (ch == '\n') return b.toString().replace("\r", "");
            if (b.length() > 8192) return null;
            b.append((char) ch);
        }
        return b.length() > 0 ? b.toString() : null;
    }

    private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    // ---------------------------------------------------------------- USB drive

    /** Mounted USB drives (and SD cards). */
    static List<File> usbDrives(Context c) {
        List<File> out = new ArrayList<>();
        StorageManager sm = c.getSystemService(StorageManager.class);
        if (sm == null) return out;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            File d = v.getDirectory();
            if (d != null) out.add(d);
        }
        return out;
    }

    /**
     * Copies photos from the drives' folders (up to four levels deep) into Haven, skipping ones
     * already copied. Runs on the caller's thread; returns how many were added.
     */
    static int importFromUsb(Context c) {
        File dir = Slideshow.myPhotosDir(c);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return 0;
        List<File> found = new ArrayList<>();
        for (File drive : usbDrives(c)) collect(drive, 0, found);
        int added = 0;
        for (File src : found) {
            if (added >= MAX_USB || count(dir) >= MAX_PHOTOS) break;
            String clean = cleanName(src.getName());
            if (clean == null || src.length() > MAX_BYTES) continue;
            File same = new File(dir, clean);
            if (same.isFile() && same.length() == src.length()) continue;
            File dest = unique(dir, clean);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (IOException e) {
                dest.delete();
                continue;
            }
            if (isPhoto(dest)) added++;
            else dest.delete();
        }
        return added;
    }

    private static void collect(File d, int depth, List<File> out) {
        File[] files = d.listFiles();
        if (files == null || out.size() >= MAX_USB * 3) return;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < 4 && !f.getName().equalsIgnoreCase("Android") && !f.getName().equalsIgnoreCase("LOST.DIR")) {
                    collect(f, depth + 1, out);
                }
            } else if (Slideshow.isImage(f.getName()) && f.length() > 20_000) {
                out.add(f);
            }
        }
    }

    // ---------------------------------------------------------------- shared

    /** Deletes all of your added photos; returns how many. */
    static int deleteAll(Context c) {
        File dir = Slideshow.myPhotosDir(c);
        File[] files = dir != null ? dir.listFiles() : null;
        int n = 0;
        if (files != null) for (File f : files) if (Slideshow.isImage(f.getName()) && f.delete()) n++;
        return n;
    }

    /** A safe file name with an image extension, or null if it isn't one. */
    private static String cleanName(String name) {
        String n = new File(name.replace('\\', '/')).getName().replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (n.startsWith(".")) n = "_" + n;
        if (n.length() > 80) n = n.substring(n.length() - 80);
        return Slideshow.isImage(n) ? n : null;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        for (int i = 2; f.exists(); i++) f = new File(dir, name.substring(0, dot) + "-" + i + name.substring(dot));
        return f;
    }

    private static boolean isPhoto(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o.outWidth >= 320 && o.outHeight >= 180;
    }

    private static int count(File dir) {
        File[] files = dir.listFiles();
        int n = 0;
        if (files != null) for (File f : files) if (Slideshow.isImage(f.getName())) n++;
        return n;
    }
}
