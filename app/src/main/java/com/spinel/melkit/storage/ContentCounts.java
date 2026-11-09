package com.spinel.melkit.storage;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Counts complete content folders without reading file bodies or following aliases. */
public final class ContentCounts {
    private ContentCounts() {}
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_DEPTH = 64;

    public static final class Snapshot {
        public final int mods, maps, saves;
        private Snapshot(int mods, int maps, int saves) {
            this.mods = mods; this.maps = maps; this.saves = saves;
        }
    }

    public static Snapshot read(File gameRoot) throws IOException {
        ContentPaths policy = new ContentPaths(gameRoot);
        File root = policy.resolve("", true);
        if (!root.isDirectory()) throw new IOException("Game folder unavailable");
        File[] folders = root.listFiles();
        if (folders == null) throw new IOException("Cannot list game folder");
        int mods = 0, maps = 0, saves = 0;
        int[] visited = {0};
        for (File folder : folders) {
            String kind = folder.getName().toLowerCase(Locale.ROOT);
            String extension;
            switch (kind) {
                case "mods": extension = ".melmod"; break;
                case "maps": extension = ".melmap"; break;
                case "saves": extension = ".melsave"; break;
                default: continue;
            }
            if (!folder.isDirectory()) throw new IOException("Content folder unavailable");
            int count = count(policy, folder.getName(), extension, 0, visited);
            if (kind.equals("mods")) mods += count;
            else if (kind.equals("maps")) maps += count;
            else saves += count;
        }
        return new Snapshot(mods, maps, saves);
    }

    private static int count(ContentPaths policy, String path, String extension, int depth,
            int[] visited) throws IOException {
        if (depth > MAX_DEPTH || ++visited[0] > MAX_ENTRIES) throw new IOException("Count limit exceeded");
        File file = policy.resolve(path, false);
        File lexical = new File(policy.resolve("", true), path).getAbsoluteFile();
        if (!lexical.equals(file)) throw new SecurityException("Content alias cannot be counted");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot count content folder");
            int total = 0;
            for (File child : children) total += count(policy, path + "/" + child.getName(), extension, depth + 1, visited);
            return total;
        }
        if (!file.isFile()) throw new IOException("Content entry unavailable");
        return file.getName().toLowerCase(Locale.ROOT).endsWith(extension) ? 1 : 0;
    }
}
