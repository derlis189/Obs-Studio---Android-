// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public final class BibleDatabase {
    private static final String TAG = "ObsBible";

    public static final String[] BOOK_NAMES = {
        "Génesis", "Éxodo", "Levítico", "Números", "Deuteronomio", "Josué", "Jueces", "Rut",
        "1 Samuel", "2 Samuel", "1 Reyes", "2 Reyes", "1 Crónicas", "2 Crónicas", "Esdras", "Nehemías", "Ester",
        "Job", "Salmos", "Proverbios", "Eclesiastés", "Cantares", "Isaías", "Jeremías", "Lamentaciones", "Ezequiel", "Daniel",
        "Oseas", "Joel", "Amós", "Abdías", "Jonás", "Miqueas", "Nahúm", "Habacuc", "Sofonías", "Hageo", "Zacarías", "Malaquías",
        "Mateo", "Marcos", "Lucas", "Juan", "Hechos", "Romanos", "1 Corintios", "2 Corintios", "Gálatas", "Efesios",
        "Filipenses", "Colosenses", "1 Tesalonicenses", "2 Tesalonicenses", "1 Timoteo", "2 Timoteo", "Tito", "Filemón",
        "Hebreos", "Santiago", "1 Pedro", "2 Pedro", "1 Juan", "2 Juan", "3 Juan", "Judas", "Apocalipsis"
    };

    public static final class VerseEntry {
        public final int book;
        public final int chapter;
        public final int verse;
        public final String text;
        final String norm;

        VerseEntry(int book, int chapter, int verse, String text) {
            this.book = book;
            this.chapter = chapter;
            this.verse = verse;
            this.text = text;
            this.norm = normalize(text);
        }

        public String ref() {
            String bn = (book >= 0 && book < BOOK_NAMES.length) ? BOOK_NAMES[book] : "";
            return bn + " " + chapter + ":" + verse;
        }
    }

    private static volatile boolean sLoaded = false;
    private static final List<VerseEntry> sAll = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private static final List<List<String>>[] sBooks = new List[66];

    public static synchronized void ensureLoaded(Context ctx) {
        if (sLoaded || ctx == null) return;
        for (int i = 0; i < 66; ++i) sBooks[i] = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("bible/rv1909.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int t1 = line.indexOf('\t');
                if (t1 <= 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 <= t1) continue;
                int t3 = line.indexOf('\t', t2 + 1);
                if (t3 <= t2) continue;
                int b = Integer.parseInt(line.substring(0, t1));
                int c = Integer.parseInt(line.substring(t1 + 1, t2));
                int v = Integer.parseInt(line.substring(t2 + 1, t3));
                String txt = line.substring(t3 + 1);
                if (b < 0 || b >= 66 || c < 1 || v < 1) continue;
                while (sBooks[b].size() < c) sBooks[b].add(new ArrayList<>());
                List<String> ch = sBooks[b].get(c - 1);
                while (ch.size() < v) ch.add("");
                ch.set(v - 1, txt);
                sAll.add(new VerseEntry(b, c, v, txt));
            }
            sLoaded = true;
            Log.i(TAG, "Loaded offline Spanish Bible: " + sAll.size() + " verses");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load offline Bible", t);
        }
    }

    public static int getChapterCount(Context ctx, int bookIdx) {
        ensureLoaded(ctx);
        if (bookIdx < 0 || bookIdx >= 66 || sBooks[bookIdx] == null) return 1;
        return Math.max(1, sBooks[bookIdx].size());
    }

    public static int getVerseCount(Context ctx, int bookIdx, int chapter) {
        ensureLoaded(ctx);
        if (bookIdx < 0 || bookIdx >= 66 || sBooks[bookIdx] == null) return 1;
        int ci = chapter - 1;
        if (ci < 0 || ci >= sBooks[bookIdx].size()) return 1;
        return Math.max(1, sBooks[bookIdx].get(ci).size());
    }

    public static String getRangeText(Context ctx, int bookIdx, int chapter, int vStart, int vEnd) {
        ensureLoaded(ctx);
        if (bookIdx < 0 || bookIdx >= 66 || sBooks[bookIdx] == null) return "";
        int ci = Math.max(0, Math.min(sBooks[bookIdx].size() - 1, chapter - 1));
        if (ci < 0 || sBooks[bookIdx].isEmpty()) return "";
        List<String> verses = sBooks[bookIdx].get(ci);
        if (verses.isEmpty()) return "";
        int a = Math.max(1, Math.min(verses.size(), vStart));
        int b = Math.max(a, Math.min(verses.size(), Math.max(a, vEnd)));
        StringBuilder sb = new StringBuilder();
        for (int v = a; v <= b; ++v) {
            if (sb.length() > 0) sb.append(' ');
            if (b > a) sb.append(v).append(' ');
            sb.append(verses.get(v - 1));
        }
        return sb.toString();
    }

    public static String formatRef(int bookIdx, int chapter, int vStart, int vEnd) {
        int bi = Math.max(0, Math.min(BOOK_NAMES.length - 1, bookIdx));
        String ref = BOOK_NAMES[bi] + " " + Math.max(1, chapter) + ":" + Math.max(1, vStart);
        if (vEnd > vStart) ref += "-" + vEnd;
        return ref;
    }

    public static int[] stepVerse(Context ctx, int bookIdx, int chapter, int verse, int delta) {
        ensureLoaded(ctx);
        int b = Math.max(0, Math.min(65, bookIdx));
        int c = Math.max(1, Math.min(getChapterCount(ctx, b), chapter));
        int v = Math.max(1, Math.min(getVerseCount(ctx, b, c), verse)) + delta;
        if (v > getVerseCount(ctx, b, c)) {
            if (c < getChapterCount(ctx, b)) { c++; v = 1; }
            else if (b < 65) { b++; c = 1; v = 1; }
            else { v = getVerseCount(ctx, b, c); }
        } else if (v < 1) {
            if (c > 1) { c--; v = getVerseCount(ctx, b, c); }
            else if (b > 0) { b--; c = getChapterCount(ctx, b); v = getVerseCount(ctx, b, c); }
            else { v = 1; }
        }
        return new int[]{b, c, v};
    }

    public static List<VerseEntry> search(Context ctx, String query, int maxResults) {
        ensureLoaded(ctx);
        List<VerseEntry> out = new ArrayList<>();
        if (query == null || query.trim().length() < 2) return out;
        String q = normalize(query.trim());
        // Check direct reference like "Juan 3:16"
        for (int bi = 0; bi < BOOK_NAMES.length; ++bi) {
            String bn = normalize(BOOK_NAMES[bi]);
            if (q.startsWith(bn + " ")) {
                String rest = q.substring(bn.length()).trim();
                String[] parts = rest.split("[:\\s]+");
                if (parts.length >= 2) {
                    try {
                        int ch = Integer.parseInt(parts[0]);
                        int vs = Integer.parseInt(parts[1].split("-")[0]);
                        String t = getRangeText(ctx, bi, ch, vs, vs);
                        if (!t.isEmpty()) {
                            out.add(new VerseEntry(bi, ch, vs, t));
                            return out;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        for (VerseEntry e : sAll) {
            if (e.norm.contains(q)) {
                out.add(e);
                if (out.size() >= maxResults) break;
            }
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase(Locale.ROOT);
    }

    private BibleDatabase() {}
}
