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
        if (!supported(path) || expectedBytes < 0 || expectedSha == null
                || !expectedSha.matches("[a-f0-9]{64}")) throw new SecurityException("Invalid restore request");
        if (!gameRoot.isDirectory()) throw new IOException("Game folder missing");
        ContentPaths paths = new ContentPaths(gameRoot);
        File target = paths.resolve(path, false);
        if (target.exists()) throw new AlreadyExists();
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
            if (!target.createNewFile()) throw new AlreadyExists();
            reserved = true;
            // This replaces only the empty reservation owned by this operation.
            if (!staging.renameTo(target)) throw new IOException("Cannot publish restored file");
            verify(target, expectedBytes, expectedSha);
            success = true;
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
