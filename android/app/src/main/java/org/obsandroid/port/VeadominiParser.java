// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Native Android parser for Veadotube Mini (.veadomini v1.4 ZIP+YAML, v1.3c GZIP/binary embedded PNGs,
 * and plain PNGTuber .zip bundles).
 */
public final class VeadominiParser {
    private static final String TAG = "ObsVeadomini";

    public static final class AvatarState {
        public String name = "Default";
        public Bitmap closed;
        public Bitmap open;
        public Bitmap blinkClosed;
        public Bitmap blinkOpen;
        public boolean bounce = true;

        void recycle() {
            // Bitmaps may be shared across states, so Avatar.recycle() frees the unique set
        }
    }

    public static final class Avatar {
        public final List<AvatarState> states = new ArrayList<>();
        final List<Bitmap> ownedBitmaps = new ArrayList<>();

        public AvatarState getState(int index) {
            if (states.isEmpty()) return null;
            int idx = Math.max(0, Math.min(index, states.size() - 1));
            return states.get(idx);
        }

        public void recycle() {
            for (Bitmap b : ownedBitmaps) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            ownedBitmaps.clear();
            states.clear();
        }
    }

    public static Avatar load(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        File f = new File(filePath);
        if (!f.isFile() || !f.canRead()) return null;
        try {
            byte[] head = new byte[4];
            try (InputStream in = new FileInputStream(f)) {
                if (in.read(head) < 2) return null;
            }
            // ZIP archive (.veadomini v1.4+ or .zip bundle)
            if (head[0] == 'P' && head[1] == 'K') {
                return loadFromZip(f);
            }
            // GZIP or raw binary (.veadomini v1.3c / older veadotube mini)
            byte[] raw = readAllBytes(f, 64 * 1024 * 1024);
            if ((head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
                raw = gunzip(raw);
            }
            return loadFromBinaryBlob(raw);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to parse .veadomini: " + filePath, t);
            return null;
        }
    }

    private static Avatar loadFromZip(File zipFile) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String yamlText = null;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int r;
                while ((r = zis.read(buf)) != -1) baos.write(buf, 0, r);
                byte[] bytes = baos.toByteArray();
                String lower = name.toLowerCase(Locale.US);
                if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".json")) {
                    if (yamlText == null || lower.contains("veado") || lower.contains("avatar")) {
                        yamlText = new String(bytes, StandardCharsets.UTF_8);
                    }
                } else {
                    entries.put(name, bytes);
                }
            }
        }

        Avatar avatar = new Avatar();
        Map<String, Bitmap> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> kv : entries.entrySet()) {
            Bitmap bmp = decodeImageBytes(kv.getValue());
            if (bmp != null) {
                decoded.put(kv.getKey(), bmp);
                avatar.ownedBitmaps.add(bmp);
            }
        }

        if (decoded.isEmpty()) {
            Log.w(TAG, "No decodable images found inside zip: " + zipFile.getName());
            return avatar;
        }

        // Try parsing states from YAML / JSON metadata if present
        if (yamlText != null && !yamlText.isEmpty()) {
            parseStatesFromYaml(yamlText, decoded, avatar);
        }

        // Fallback heuristic: map images by filename keywords or order (0=closed, 1=open, 2=blinkClosed, 3=blinkOpen)
        if (avatar.states.isEmpty()) {
            AvatarState st = new AvatarState();
            st.name = "Principal";
            List<Bitmap> ordered = new ArrayList<>(decoded.values());
            for (Map.Entry<String, Bitmap> kv : decoded.entrySet()) {
                String k = kv.getKey().toLowerCase(Locale.US);
                boolean isBlink = k.contains("blink") || k.contains("parpad") || k.contains("eye");
                boolean isOpen = k.contains("open") || k.contains("talk") || k.contains("habla") || k.contains("mouth");
                if (isBlink && isOpen && st.blinkOpen == null) st.blinkOpen = kv.getValue();
                else if (isBlink && st.blinkClosed == null) st.blinkClosed = kv.getValue();
                else if (isOpen && st.open == null) st.open = kv.getValue();
                else if (!isBlink && !isOpen && st.closed == null) st.closed = kv.getValue();
            }
            if (st.closed == null && ordered.size() > 0) st.closed = ordered.get(0);
            if (st.open == null && ordered.size() > 1) st.open = ordered.get(1);
            if (st.blinkClosed == null && ordered.size() > 2) st.blinkClosed = ordered.get(2);
            if (st.blinkOpen == null && ordered.size() > 3) st.blinkOpen = ordered.get(3);
            if (st.open == null) st.open = st.closed;
            avatar.states.add(st);
        }

        Log.i(TAG, "Loaded .veadomini ZIP: " + avatar.states.size() + " states, " + decoded.size() + " images");
        return avatar;
    }

    private static Bitmap lookupBitmap(Map<String, Bitmap> decoded, String ref) {
        if (ref == null) return null;
        String clean = ref.trim().replace("\"", "").replace("'", "").replace('\\', '/');
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean)) return null;
        if (decoded.containsKey(clean)) return decoded.get(clean);
        for (Map.Entry<String, Bitmap> kv : decoded.entrySet()) {
            String k = kv.getKey();
            if (k.equalsIgnoreCase(clean) || k.endsWith("/" + clean) || k.startsWith(clean + ".")) {
                return kv.getValue();
            }
            String base = k.contains("/") ? k.substring(k.lastIndexOf('/') + 1) : k;
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            if (base.equalsIgnoreCase(clean) || stem.equalsIgnoreCase(clean)) {
                return kv.getValue();
            }
        }
        return null;
    }

    private static void parseStatesFromYaml(String yaml, Map<String, Bitmap> decoded, Avatar avatar) {
        // Parse state blocks in veadomini.yml (e.g. "- name: ...", "closed:", "open:", "blink:", "blinkOpen:")
        String[] lines = yaml.split("\\r?\\n");
        AvatarState cur = null;
        Pattern kvPat = Pattern.compile("^\\s*-?\\s*([a-zA-Z0-9_-]+)\\s*:\\s*(.*)$");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            boolean newItem = trimmed.startsWith("- ");
            Matcher m = kvPat.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1).toLowerCase(Locale.US);
            String val = m.group(2).trim();

            if (newItem && (key.equals("id") || key.equals("name") || key.equals("closed") || key.equals("mouthclosed"))) {
                if (cur != null && (cur.closed != null || cur.open != null)) {
                    if (cur.closed == null) cur.closed = cur.open;
                    if (cur.open == null) cur.open = cur.closed;
                    avatar.states.add(cur);
                }
                cur = new AvatarState();
            }
            if (cur == null && (key.equals("closed") || key.equals("open") || key.equals("mouthclosed") || key.equals("mouthopen"))) {
                cur = new AvatarState();
            }
            if (cur == null) continue;

            if (key.equals("name") && !val.isEmpty()) {
                cur.name = val.replace("\"", "").replace("'", "");
            } else if (key.equals("closed") || key.equals("mouthclosed") || key.equals("idle")) {
                Bitmap b = lookupBitmap(decoded, val);
                if (b != null) cur.closed = b;
            } else if (key.equals("open") || key.equals("mouthopen") || key.equals("talk") || key.equals("talking")) {
                Bitmap b = lookupBitmap(decoded, val);
                if (b != null) cur.open = b;
            } else if (key.equals("blink") || key.equals("blinkclosed") || key.equals("closedblink") || key.equals("wink")) {
                Bitmap b = lookupBitmap(decoded, val);
                if (b != null) cur.blinkClosed = b;
            } else if (key.equals("blinkopen") || key.equals("openblink") || key.equals("talkblink")) {
                Bitmap b = lookupBitmap(decoded, val);
                if (b != null) cur.blinkOpen = b;
            }
        }
        if (cur != null && (cur.closed != null || cur.open != null)) {
            if (cur.closed == null) cur.closed = cur.open;
            if (cur.open == null) cur.open = cur.closed;
            avatar.states.add(cur);
        }
    }

    private static Avatar loadFromBinaryBlob(byte[] raw) {
        Avatar avatar = new Avatar();
        List<Bitmap> extracted = extractEmbeddedImages(raw);
        for (Bitmap b : extracted) avatar.ownedBitmaps.add(b);
        if (extracted.isEmpty()) {
            Log.w(TAG, "No embedded PNG/WebP/GIF streams found in binary .veadomini (" + raw.length + " bytes)");
            return avatar;
        }
        // Group extracted images in chunks of 4 (closed, open, blinkClosed, blinkOpen) or 2
        int stride = (extracted.size() >= 4 && extracted.size() % 4 == 0) ? 4
                   : (extracted.size() >= 4) ? 4
                   : (extracted.size() >= 2 ? 2 : 1);
        int stateIdx = 1;
        for (int i = 0; i < extracted.size(); i += stride) {
            AvatarState st = new AvatarState();
            st.name = "Estado " + stateIdx++;
            st.closed = extracted.get(i);
            st.open = (i + 1 < extracted.size()) ? extracted.get(i + 1) : st.closed;
            st.blinkClosed = (stride >= 4 && i + 2 < extracted.size()) ? extracted.get(i + 2) : null;
            st.blinkOpen = (stride >= 4 && i + 3 < extracted.size()) ? extracted.get(i + 3) : null;
            avatar.states.add(st);
        }
        Log.i(TAG, "Loaded legacy/binary .veadomini: " + avatar.states.size() + " states, " + extracted.size() + " images");
        return avatar;
    }

    private static List<Bitmap> extractEmbeddedImages(byte[] data) {
        List<Bitmap> out = new ArrayList<>();
        int n = data.length;
        int i = 0;
        while (i + 16 < n) {
            // PNG signature: 89 50 4E 47 0D 0A 1A 0A
            if ((data[i] & 0xFF) == 0x89 && data[i + 1] == 'P' && data[i + 2] == 'N' && data[i + 3] == 'G'
                && data[i + 4] == 0x0D && data[i + 5] == 0x0A && data[i + 6] == 0x1A && data[i + 7] == 0x0A) {
                int end = findPngEnd(data, i + 8);
                if (end > i) {
                    Bitmap b = BitmapFactory.decodeByteArray(data, i, end - i);
                    if (b != null) out.add(b);
                    i = end;
                    continue;
                }
            }
            // WEBP signature: RIFF <size> WEBP
            if (data[i] == 'R' && data[i + 1] == 'I' && data[i + 2] == 'F' && data[i + 3] == 'F'
                && data[i + 8] == 'W' && data[i + 9] == 'E' && data[i + 10] == 'B' && data[i + 11] == 'P') {
                int riffLen = (data[i + 4] & 0xFF) | ((data[i + 5] & 0xFF) << 8)
                    | ((data[i + 6] & 0xFF) << 16) | ((data[i + 7] & 0xFF) << 24);
                int total = riffLen + 8;
                if (total > 16 && i + total <= n) {
                    Bitmap b = BitmapFactory.decodeByteArray(data, i, total);
                    if (b != null) out.add(b);
                    i += total;
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    private static int findPngEnd(byte[] data, int pos) {
        int n = data.length;
        while (pos + 12 <= n) {
            int len = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (len < 0 || pos + 12L + len > n) return -1;
            boolean isIend = data[pos + 4] == 'I' && data[pos + 5] == 'E'
                && data[pos + 6] == 'N' && data[pos + 7] == 'D';
            pos += 12 + len;
            if (isIend) return pos;
        }
        return -1;
    }

    private static Bitmap decodeImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return null;
        Bitmap direct = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (direct != null) return direct;
        // Handle .vimg or wrapped image containers by scanning for embedded PNG/WebP
        List<Bitmap> embedded = extractEmbeddedImages(bytes);
        return embedded.isEmpty() ? null : embedded.get(0);
    }

    private static byte[] readAllBytes(File f, int maxBytes) throws Exception {
        int cap = (int) Math.min(f.length(), maxBytes);
        byte[] out = new byte[cap];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, r;
            while (off < cap && (r = in.read(out, off, cap - off)) > 0) off += r;
        }
        return out;
    }

    private static byte[] gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = gis.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        }
    }

    private VeadominiParser() {}
}
