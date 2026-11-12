package com.spinel.melkit.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Restores one verified copy to a new filename; existing game files are never replaced. */
public final class NewFileRestore {
    private NewFileRestore() {}
    public static class AlreadyExists extends IOException {}
    public static class SourceChanged extends IOException {}
    public static class PartialCopy extends IOException {}

    public static boolean supported(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return (lower.startsWith("maps/") && lower.endsWith(".melmap"))
            || (lower.startsWith("mods/") && lower.endsWith(".melmod"))
            || (lower.startsWith("saves/") && lower.endsWith(".melsave"))
            || (lower.startsWith("worlds/") && lower.endsWith(".melworld"));
    }

    public static void restore(File gameRoot, String path, InputStream source,
            long expectedBytes, String expectedSha) throws Exception {
        restoreNew(gameRoot, path, source, expectedBytes, expectedSha, false);
    }

    /** Keeps both files, reserving a numbered name only after verifying the source. */
    public static String restoreAsCopy(File gameRoot, String path, InputStream source,
            long expectedBytes, String expectedSha) throws Exception {
        return restoreNew(gameRoot, path, source, expectedBytes, expectedSha, true);
    }

    private static String restoreNew(File gameRoot, String path, InputStream source,
            long expectedBytes, String expectedSha, boolean asCopy) throws Exception {
        return restoreScoped(gameRoot, path, source, expectedBytes, expectedSha, asCopy, false);
    }

    /** Quarantine recovery only: permits unknown extensions inside the same scoped content folders. */
    static void restoreQuarantined(File gameRoot, String path, InputStream source,
            long expectedBytes, String expectedSha) throws Exception {
        restoreScoped(gameRoot, path, source, expectedBytes, expectedSha, false, true);
    }

    private static String restoreScoped(File gameRoot, String path, InputStream source,
            long expectedBytes, String expectedSha, boolean asCopy, boolean quarantine) throws Exception {
        if ((!quarantine && !supported(path)) || expectedBytes < 0 || expectedSha == null
                || !expectedSha.matches("[a-f0-9]{64}")) throw new SecurityException("Invalid restore request");
        if (!gameRoot.isDirectory()) throw new IOException("Game folder missing");
        ContentPaths paths = new ContentPaths(gameRoot);
        File target = paths.resolve(path, false);
        if (!asCopy && target.exists()) throw new AlreadyExists();
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create game content folder");
        }
        target = paths.resolve(path, false);
        File staging = File.createTempFile(".melkit-restore-", ".tmp", target.getParentFile());
        boolean reserved = false;
        boolean success = false;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            try (FileOutputStream output = new FileOutputStream(staging)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > expectedBytes) throw new SourceChanged();
                    output.write(buffer, 0, count);
                    sha.update(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (bytes != expectedBytes || !hex(sha.digest()).equals(expectedSha)) throw new SourceChanged();
            verify(staging, expectedBytes, expectedSha);
            // Reserve atomically, including a second collision check after copying.
            if (asCopy) {
                String directory = path.substring(0, path.lastIndexOf('/') + 1);
                String name = path.substring(path.lastIndexOf('/') + 1);
                int dot = name.lastIndexOf('.');
                String stem = name.substring(0, dot);
                String extension = name.substring(dot);
                for (int index = 1; index < 10000; index++) {
                    String candidate = directory + stem + " (" + index + ")" + extension;
                    File next = paths.resolve(candidate, false);
                    if (next.createNewFile()) {
                        target = next;
                        path = candidate;
                        reserved = true;
                        break;
                    }
                }
                if (!reserved) throw new IOException("Too many restored copies");
            } else if (!target.createNewFile()) throw new AlreadyExists();
            reserved = true;
            // This replaces only the empty reservation owned by this operation.
            if (!staging.renameTo(target)) throw new IOException("Cannot publish restored file");
            verify(target, expectedBytes, expectedSha);
            success = true;
            return path;
        } finally {
            boolean cleaned = true;
            if (!success && reserved && target.exists()) cleaned = target.delete();
            if (staging.exists()) cleaned = staging.delete() && cleaned;
            if (!cleaned) throw new PartialCopy();
        }
    }

    private static void verify(File file, long bytes, String hash) throws Exception {
        if (file.length() != bytes) throw new SourceChanged();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        if (!hex(sha.digest()).equals(hash)) throw new SourceChanged();
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
