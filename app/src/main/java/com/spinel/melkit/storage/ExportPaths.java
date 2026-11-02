package com.spinel.melkit.storage;

import java.io.File;
import java.io.IOException;

/** Keeps exported content together and reserves new files without overwriting copies. */
public final class ExportPaths {
    private ExportPaths() {}

    public static String relativeDirectory(String contentPath) {
        validate(contentPath);
        return "MelKit/" + contentPath.substring(0, contentPath.lastIndexOf('/') + 1);
    }

    public static String fileName(String contentPath) {
        validate(contentPath);
        return contentPath.substring(contentPath.lastIndexOf('/') + 1);
    }

    private static void validate(String path) {
        if (path == null || path.length() > 4096 || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new SecurityException("Invalid export path");
        }
        String[] parts = path.split("/", -1);
        if (parts.length < 2 || !ContentPaths.isContentFolder(parts[0])) {
            throw new SecurityException("A content file is required");
        }
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new SecurityException("Invalid export path");
            }
        }
    }

    public static File reserveLegacy(File downloads, String contentPath) throws IOException {
        String name = fileName(contentPath);
        File downloadsRoot = downloads.getCanonicalFile();
        File root = new File(downloadsRoot, "MelKit").getCanonicalFile();
        if (!downloadsRoot.equals(root.getParentFile())) throw new SecurityException("Outside Downloads");
        File original = new ContentPaths(root).resolve(contentPath, false);
        File directory = original.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create export folder");
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 0; index < 10000; index++) {
            String candidateName = index == 0 ? name : stem + " (" + index + ")" + extension;
            File candidate = new File(directory, candidateName).getCanonicalFile();
            if (!directory.equals(candidate.getParentFile())) throw new SecurityException("Outside export folder");
            if (candidate.createNewFile()) return candidate;
        }
        throw new IOException("Too many copies of this file");
    }
}
