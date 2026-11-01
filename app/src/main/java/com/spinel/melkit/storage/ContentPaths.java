package com.spinel.melkit.storage;

import java.io.File;
import java.io.IOException;

/** Bounds privileged reads to known content folders of one game. */
public final class ContentPaths {
    private final File root;

    public ContentPaths(File gameFilesRoot) throws IOException {
        root = gameFilesRoot.getCanonicalFile();
    }

    public static boolean isContentFolder(String name) {
        return "Mods".equalsIgnoreCase(name) || "Saves".equalsIgnoreCase(name)
                || "Maps".equalsIgnoreCase(name) || "Worlds".equalsIgnoreCase(name);
    }

    public File resolve(String relativePath, boolean allowRoot) throws IOException {
        if (relativePath == null || relativePath.length() > 4096
                || relativePath.startsWith("/") || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf('\0') >= 0) {
            throw new SecurityException("Invalid content path");
        }
        if (relativePath.isEmpty()) {
            if (!allowRoot) throw new SecurityException("A content file is required");
            return root;
        }
        String[] parts = relativePath.split("/", -1);
        if (!isContentFolder(parts[0])) throw new SecurityException("Outside content folders");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new SecurityException("Invalid content path");
            }
        }
        File candidate = new File(root, relativePath).getCanonicalFile();
        if (!candidate.getPath().startsWith(root.getPath() + File.separator)) {
            throw new SecurityException("Outside game content");
        }
        // A symlink must not cross into another top-level directory of the game.
        String resolvedRelative = candidate.getPath().substring(root.getPath().length() + 1);
        if (!isContentFolder(resolvedRelative.split("/", 2)[0])) {
            throw new SecurityException("Outside content folders");
        }
        return candidate;
    }
}
