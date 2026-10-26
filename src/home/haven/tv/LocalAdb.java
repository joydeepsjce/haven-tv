package home.haven.tv;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Runs one shell command through the TV's own adb (network debugging on 127.0.0.1:5555), the way
 * Glassio does with dadb, so a few things only the shell may do (Android 14's "restricted
 * settings" app-op, WRITE_SECURE_SETTINGS) can be set from the remote, without a computer. The
 * first time, the TV asks "Allow debugging?" for Haven's own key. Plain Java, no libraries:
 * the ADB wire protocol (CNXN, AUTH, OPEN, WRTE, OKAY, CLSE) and its RSA key format.
 */
final class LocalAdb {

    static final class Result {
        final boolean ok;
        final String output, error;

        Result(boolean ok, String output, String error) {
            this.ok = ok;
            this.output = output;
            this.error = error;
        }
    }

    /** Why a run failed, for the setup screen. */
    static final String NOT_LISTENING = "not-listening", NOT_ALLOWED = "not-allowed";

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** ASN.1 prefix of a SHA-1 DigestInfo: adbd signs the 20-byte token as if it were a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    private LocalAdb() { }

    /** True when something is listening for adb on the TV itself. */
    static boolean listening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Runs {@code cmd} as the shell user. Blocking: waits up to {@code allowMs} for the user to
     * accept the TV's "Allow debugging?" prompt the first time.
     */
    static Result run(Context c, String cmd, long allowMs) {
        KeyPair keys;
        try {
            keys = keys(c);
        } catch (Exception e) {
            return new Result(false, "", "key: " + e);
        }
        try (Socket s = new Socket()) {
            try {
                s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            } catch (IOException e) {
                return new Result(false, "", NOT_LISTENING);
            }
            s.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean sentSignature = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) return new Result(false, "", "unexpected " + Integer.toHexString(m.cmd));
                if (!sentSignature) {
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    sentSignature = true;
                } else {
                    // Not known yet: offer our key; the TV shows its prompt and answers with CNXN once allowed.
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                    s.setSoTimeout((int) Math.max(1000, allowMs));
                    try {
                        Msg r = read(in);
                        if (r.cmd != CNXN) return new Result(false, "", NOT_ALLOWED);
                        break;
                    } catch (IOException e) {
                        return new Result(false, "", NOT_ALLOWED);
                    }
                }
            }
            s.setSoTimeout(30_000);
            int local = 1;
            send(out, OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m = read(in);
                if (m.cmd == OKAY) {
                    remote = m.arg0;
                } else if (m.cmd == WRTE) {
                    text.write(m.data);
                    send(out, OKAY, local, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE) {
                    send(out, CLSE, local, remote != 0 ? remote : m.arg0, new byte[0]);
                    break;
                }
            }
            return new Result(true, text.toString("UTF-8"), null);
        } catch (Exception e) {
            return new Result(false, "", String.valueOf(e));
        }
    }

    // ---------------------------------------------------------------- wire format

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > MAX_PAYLOAD * 4) throw new IOException("bad length");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- keys

    /** Haven's own adb key pair, made once and kept in its private files. */
    private static KeyPair keys(Context c) throws Exception {
        File dir = new File(c.getFilesDir(), "adb");
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub.der");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no space");
        try (OutputStream o = new FileOutputStream(priv)) { o.write(kp.getPrivate().getEncoded()); }
        try (OutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
        return kp;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.update(SHA1_PREFIX);
        return cipher.doFinal(token);
    }

    /** Android's adb public key: base64 of {len, n0inv, n[], rr[], e} (little-endian words) + " name". */
    private static byte[] publicKey(RSAPublicKey key) {
        int words = 2048 / 32;
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Haven@tv\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) {
            b.putInt(v.shiftRight(32 * i).and(mask).intValue());
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
