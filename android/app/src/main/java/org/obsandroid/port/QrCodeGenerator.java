// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Compact pure-Java ISO/IEC 18004 QR Code encoder (Byte mode, EC Level L, Versions 1..6, up to 134 UTF-8 bytes).
 */
public final class QrCodeGenerator {
    // Version 1..6 (EC Level L): total data codewords, ec codewords per block, blocks, alignment centers
    private static final int[] DATA_CODEWORDS_L = {0, 19, 34, 55, 80, 108, 136};
    private static final int[] EC_CODEWORDS_L   = {0,  7, 10, 15, 20,  26,  18};
    private static final int[] NUM_BLOCKS_L     = {0,  1,  1,  1,  1,   1,   2};
    private static final int[][] ALIGN_POS = {
        {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}
    };

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; ++i) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; ++i) GF_EXP[i] = GF_EXP[i - 255];
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    private static int[] rsGenerator(int degree) {
        int[] poly = new int[]{1};
        for (int i = 0; i < degree; ++i) {
            int[] next = new int[poly.length + 1];
            int factor = GF_EXP[i];
            for (int j = 0; j < poly.length; ++j) {
                next[j] ^= poly[j];
                next[j + 1] ^= gfMul(poly[j], factor);
            }
            poly = next;
        }
        return poly;
    }

    private static byte[] rsCompute(byte[] data, int ecLen) {
        int[] gen = rsGenerator(ecLen);
        int[] rem = new int[ecLen];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, ecLen - 1);
            rem[ecLen - 1] = 0;
            for (int j = 0; j < ecLen; ++j) {
                rem[j] ^= gfMul(gen[j + 1], factor);
            }
        }
        byte[] out = new byte[ecLen];
        for (int i = 0; i < ecLen; ++i) out[i] = (byte) rem[i];
        return out;
    }

    private static final class BitBuf {
        final byte[] bytes;
        int bitPos = 0;
        BitBuf(int maxBytes) { bytes = new byte[maxBytes]; }
        void append(int val, int bits) {
            for (int i = bits - 1; i >= 0; --i) {
                if (((val >>> i) & 1) != 0) {
                    bytes[bitPos >>> 3] |= (byte) (1 << (7 - (bitPos & 7)));
                }
                bitPos++;
            }
        }
    }

    public static Bitmap generateBitmap(String text, int pixelSize) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int version = -1;
        for (int v = 1; v <= 6; ++v) {
            // 4 bits mode + 8 bits len + payload + 4 bits terminator
            if (payload.length + 2 <= DATA_CODEWORDS_L[v]) {
                version = v;
                break;
            }
        }
        if (version < 0) return null;

        int dataCap = DATA_CODEWORDS_L[version];
        int ecPerBlock = EC_CODEWORDS_L[version];
        int numBlocks = NUM_BLOCKS_L[version];

        BitBuf bb = new BitBuf(dataCap);
        bb.append(0b0100, 4); // Byte mode
        bb.append(payload.length, 8);
        for (byte b : payload) bb.append(b & 0xFF, 8);
        int remBits = dataCap * 8 - bb.bitPos;
        bb.append(0, Math.min(4, remBits));
        while ((bb.bitPos & 7) != 0) bb.append(0, 1);
        int padIdx = 0;
        while (bb.bitPos < dataCap * 8) {
            bb.append((padIdx++ & 1) == 0 ? 0xEC : 0x11, 8);
        }

        // Split blocks, compute RS ECC, interleave
        int blockDataLen = dataCap / numBlocks;
        byte[][] dataBlocks = new byte[numBlocks][];
        byte[][] ecBlocks = new byte[numBlocks][];
        for (int b = 0; b < numBlocks; ++b) {
            dataBlocks[b] = Arrays.copyOfRange(bb.bytes, b * blockDataLen, (b + 1) * blockDataLen);
            ecBlocks[b] = rsCompute(dataBlocks[b], ecPerBlock);
        }
        byte[] finalCodewords = new byte[dataCap + ecPerBlock * numBlocks];
        int idx = 0;
        for (int i = 0; i < blockDataLen; ++i) {
            for (int b = 0; b < numBlocks; ++b) finalCodewords[idx++] = dataBlocks[b][i];
        }
        for (int i = 0; i < ecPerBlock; ++i) {
            for (int b = 0; b < numBlocks; ++b) finalCodewords[idx++] = ecBlocks[b][i];
        }

        int size = 17 + version * 4;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunc = new boolean[size][size];

        // Finder patterns + separators
        drawFinder(modules, isFunc, 0, 0);
        drawFinder(modules, isFunc, size - 7, 0);
        drawFinder(modules, isFunc, 0, size - 7);

        // Timing patterns
        for (int i = 8; i < size - 8; ++i) {
            boolean dark = (i % 2) == 0;
            modules[6][i] = dark; isFunc[6][i] = true;
            modules[i][6] = dark; isFunc[i][6] = true;
        }

        // Alignment patterns (v2..v6)
        int[] align = ALIGN_POS[version];
        for (int cy : align) {
            for (int cx : align) {
                if ((cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)) continue;
                for (int dy = -2; dy <= 2; ++dy) {
                    for (int dx = -2; dx <= 2; ++dx) {
                        int d = Math.max(Math.abs(dx), Math.abs(dy));
                        modules[cy + dy][cx + dx] = (d != 1);
                        isFunc[cy + dy][cx + dx] = true;
                    }
                }
            }
        }

        // Reserve format info areas & dark module
        for (int i = 0; i <= 8; ++i) {
            if (i != 6) {
                isFunc[8][i] = true;
                isFunc[i][8] = true;
            }
        }
        for (int i = 0; i < 8; ++i) isFunc[8][size - 1 - i] = true;
        for (int i = 0; i < 7; ++i) isFunc[size - 1 - i][8] = true;
        modules[size - 8][8] = true;
        isFunc[size - 8][8] = true;

        // Place data bits (zigzag) with mask pattern 0: (r + c) % 2 == 0
        int bitIdx = 0;
        int totalBits = finalCodewords.length * 8;
        boolean up = true;
        for (int col = size - 1; col >= 1; col -= 2) {
            if (col == 6) col--;
            for (int step = 0; step < size; ++step) {
                int row = up ? (size - 1 - step) : step;
                for (int dx = 0; dx < 2; ++dx) {
                    int c = col - dx;
                    if (isFunc[row][c]) continue;
                    boolean bit = false;
                    if (bitIdx < totalBits) {
                        bit = (((finalCodewords[bitIdx >>> 3] & 0xFF) >>> (7 - (bitIdx & 7))) & 1) != 0;
                        bitIdx++;
                    }
                    if (((row + c) & 1) == 0) bit = !bit; // Mask 0
                    modules[row][c] = bit;
                }
            }
            up = !up;
        }

        // Format info for EC Level L (01) + Mask 0 (000) -> 5-bit data = 01000 (0x08)
        int formatBits = computeFormatBits(0b01000);
        for (int i = 0; i < 15; ++i) {
            boolean bit = ((formatBits >>> i) & 1) != 0;
            // Around top-left
            if (i < 6) modules[i][8] = bit;
            else if (i < 8) modules[i + 1][8] = bit;
            else modules[size - 15 + i][8] = bit;

            // Top-right & bottom-left
            if (i < 8) modules[8][size - 1 - i] = bit;
            else if (i < 9) modules[8][15 - i] = bit;
            else modules[8][14 - i] = bit;
        }

        int quiet = 4;
        int totalMod = size + quiet * 2;
        int scale = Math.max(2, pixelSize / totalMod);
        int imgDim = totalMod * scale;
        Bitmap bmp = Bitmap.createBitmap(imgDim, imgDim, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[imgDim * imgDim];
        Arrays.fill(pixels, Color.WHITE);
        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                if (!modules[y][x]) continue;
                int py0 = (y + quiet) * scale;
                int px0 = (x + quiet) * scale;
                for (int dy = 0; dy < scale; ++dy) {
                    int rowOff = (py0 + dy) * imgDim + px0;
                    for (int dx = 0; dx < scale; ++dx) {
                        pixels[rowOff + dx] = Color.BLACK;
                    }
                }
            }
        }
        bmp.setPixels(pixels, 0, imgDim, 0, 0, imgDim, imgDim);
        return bmp;
    }

    private static void drawFinder(boolean[][] m, boolean[][] f, int x0, int y0) {
        int size = m.length;
        for (int dy = -1; dy <= 7; ++dy) {
            for (int dx = -1; dx <= 7; ++dx) {
                int x = x0 + dx, y = y0 + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                boolean dark = (dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6) &&
                    (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
                m[y][x] = dark;
                f[y][x] = true;
            }
        }
    }

    private static int computeFormatBits(int data5) {
        int rem = data5 << 10;
        for (int i = 14; i >= 10; --i) {
            if (((rem >>> i) & 1) != 0) rem ^= (0x537 << (i - 10));
        }
        return ((data5 << 10) | rem) ^ 0x5412;
    }

    private QrCodeGenerator() {}
}
