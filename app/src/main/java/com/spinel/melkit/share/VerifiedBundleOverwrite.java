package com.spinel.melkit.share;

import java.io.*;
import java.security.MessageDigest;

/** Verified document-URI replacement. A durable original is required before the first write. */
public final class VerifiedBundleOverwrite {
    private VerifiedBundleOverwrite() {}
    public interface Source { InputStream open() throws Exception; }
    public interface Target extends Source { OutputStream overwrite() throws Exception; }

    public static void replace(File original, File edited, Source backup, Target target) throws Exception {
        ContentBundle.verify(original);
        ContentBundle.verify(edited);
        byte[] before = digest(() -> new FileInputStream(original));
        byte[] after = digest(() -> new FileInputStream(edited));
        requireSame(before, digest(backup), "CHECKSUM");
        requireSame(before, digest(target), "FILE_CHANGED");
        try {
            write(() -> new FileInputStream(edited), target);
            requireSame(after, digest(target), "CHECKSUM");
        } catch (Exception failure) {
            // Restore even after cancellation. The independently verified backup is never removed.
            boolean interrupted = Thread.interrupted();
            try {
                // If opening for write was rejected, the original may still be intact.
                if (!MessageDigest.isEqual(before, digest(target))) {
                    write(backup, target);
                    requireSame(before, digest(target), "CHECKSUM");
                }
            } catch (Exception restoreFailure) {
                IOException recovery = new IOException("BUNDLE_RECOVERY_REQUIRED", failure);
                recovery.addSuppressed(restoreFailure);
                throw recovery;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            throw new IOException("BUNDLE_UPDATE_RESTORED", failure);
        }
    }

    private static void write(Source source, Target target) throws Exception {
        try (InputStream input = source.open(); OutputStream output = target.overwrite()) {
            ContentBundle.copy(input, output, ContentBundle.MAX_ARCHIVE, null);
        }
    }
    private static byte[] digest(Source source) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = source.open()) { ContentBundle.copy(input, null, ContentBundle.MAX_ARCHIVE, hash); }
        return hash.digest();
    }
    private static void requireSame(byte[] expected, byte[] actual, String code) throws IOException {
        if (!MessageDigest.isEqual(expected, actual)) throw new IOException(code);
    }
}
