package home.haven.tv;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder for short text such as a URL: byte mode, error correction level M,
 * versions 1 to 9 (up to 180 bytes). Follows ISO/IEC 18004; the structure mirrors Project
 * Nayuki's well-known reference encoder. Plain Java, so it can be tested off the TV.
 */
final class QrCode {

    private static final int[] ECC_PER_BLOCK = {10, 16, 26, 18, 24, 16, 18, 22, 22};
    private static final int[] BLOCKS = {1, 1, 1, 2, 2, 4, 4, 4, 5};

    final int size;
    private final boolean[][] modules, function;

    private QrCode(int version) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
    }

    /** True when the module at column {@code x}, row {@code y} is dark. */
    boolean dark(int x, int y) {
        return modules[y][x];
    }

    /** Encodes {@code text}; throws if it doesn't fit in version 9. */
    static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (dataCodewords(version) * 8 < 4 + 8 + data.length * 8) {
            if (++version > 9) throw new IllegalArgumentException("Text too long for a QR code here");
        }
        // Mode (byte = 0100), 8-bit length, the bytes, terminator, then pad bytes.
        int capacity = dataCodewords(version) * 8;
        BitBuffer bb = new BitBuffer(capacity);
        bb.append(4, 4);
        bb.append(data.length, 8);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacity; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);

        QrCode q = new QrCode(version);
        q.drawFunctionPatterns(version);
        q.drawCodewords(q.addEccAndInterleave(bb.bytes(), version));
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            q.applyMask(mask);
            q.drawFormatBits(mask);
            long p = q.penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                best = mask;
            }
            q.applyMask(mask); // XOR again to undo
        }
        q.applyMask(best);
        q.drawFormatBits(best);
        return q;
    }

    // ---------------------------------------------------------------- function patterns

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] align = alignmentPositions(version);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue;
                for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                    set(align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }
        drawFormatBits(0); // reserves the format areas; redrawn after masking
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(a, b, bit);
                set(b, a, bit);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int x = cx + dx, y = cy + dy;
            if (x < 0 || x >= size || y < 0 || y >= size) continue;
            int d = Math.max(Math.abs(dx), Math.abs(dy));
            set(x, y, d != 2 && d != 4);
        }
    }

    private void drawFormatBits(int mask) {
        int data = mask; // level M is 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static int[] alignmentPositions(int version) {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = (version * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = version * 4 + 17 - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // ---------------------------------------------------------------- data

    private static int rawModules(int version) {
        int r = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int n = version / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (version >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int version) {
        return rawModules(version) / 8 - ECC_PER_BLOCK[version - 1] * BLOCKS[version - 1];
    }

    private byte[] addEccAndInterleave(byte[] data, int version) {
        int blocks = BLOCKS[version - 1], ecc = ECC_PER_BLOCK[version - 1];
        int raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = rsDivisor(ecc);
        byte[][] out = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] block = java.util.Arrays.copyOf(dat, shortLen + 1);
            byte[] rem = rsRemainder(dat, divisor);
            System.arraycopy(rem, 0, block, block.length - ecc, ecc);
            out[i] = block;
        }
        byte[] result = new byte[raw];
        int n = 0;
        for (int i = 0; i < out[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - ecc || j >= shortBlocks) result[n++] = out[j][i];
            }
        }
        return result;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            boolean invert;
            switch (mask) {
                case 0: invert = (x + y) % 2 == 0; break;
                case 1: invert = y % 2 == 0; break;
                case 2: invert = x % 3 == 0; break;
                case 3: invert = (x + y) % 3 == 0; break;
                case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
            }
            if (!function[y][x] && invert) modules[y][x] = !modules[y][x];
        }
    }

    /** Rules 1, 2 and 4 of the standard's mask scoring; enough to avoid the worst patterns. */
    private long penalty() {
        long p = 0;
        for (int y = 0; y < size; y++) {
            int run = 1;
            for (int x = 1; x <= size; x++) {
                if (x < size && modules[y][x] == modules[y][x - 1]) run++;
                else {
                    if (run >= 5) p += run - 2;
                    run = 1;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            int run = 1;
            for (int y = 1; y <= size; y++) {
                if (y < size && modules[y][x] == modules[y - 1][x]) run++;
                else {
                    if (run >= 5) p += run - 2;
                    run = 1;
                }
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (modules[y][x]) dark++;
            if (x < size - 1 && y < size - 1) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10L;
    }

    // ---------------------------------------------------------------- Reed-Solomon over GF(256)

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] r = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(divisor[i] & 0xFF, factor);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static final class BitBuffer {
        private final byte[] buf;
        int length;

        BitBuffer(int capacityBits) {
            buf = new byte[(capacityBits + 7) / 8];
        }

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--, length++) {
                if (((value >>> i) & 1) != 0) buf[length >>> 3] |= (byte) (0x80 >>> (length & 7));
            }
        }

        byte[] bytes() {
            return buf;
        }
    }
}
