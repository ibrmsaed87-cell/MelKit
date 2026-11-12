package com.spinel.melkit.share;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class VerifiedBundleOverwriteCheck {
    private static int checks;
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private interface Action { void run() throws Exception; }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); } catch (IOException error) { check(code.equals(error.getMessage()), "Expected " + code + ", got " + error); return; }
        throw new AssertionError("Expected " + code);
    }
    private static ContentBundle.Source source(String name, byte[] bytes) {
        return new ContentBundle.Source(name, bytes.length, () -> new ByteArrayInputStream(bytes), () -> {});
    }
    private static final class Document implements VerifiedBundleOverwrite.Target {
        final File file; int opens; boolean failFirst, deny, corruptFirst, failRestore;
        Document(File file) { this.file = file; }
        public InputStream open() throws Exception { return new FileInputStream(file); }
        public OutputStream overwrite() throws Exception {
            opens++;
            if (deny) throw new SecurityException("read-only");
            if (failRestore && opens > 1) throw new IOException("provider offline");
            OutputStream output = new FileOutputStream(file, false);
            if (failFirst && opens == 1) return new FilterOutputStream(output) {
                public void write(byte[] bytes, int off, int count) throws IOException {
                    out.write(bytes, off, Math.min(count, 7)); throw new IOException("interrupted write");
                }
            };
            if (corruptFirst && opens == 1) return new FilterOutputStream(output) {
                public void write(byte[] bytes, int off, int count) throws IOException { out.write(new byte[count]); }
            };
            return output;
        }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("melkit-overwrite-check-");
        try {
            File original = directory.resolve("original.zip").toFile(), edited = directory.resolve("edited.zip").toFile();
            byte[] oldContent = new byte[4096]; Arrays.fill(oldContent, (byte)5);
            ContentBundle.write(original, List.of(source("Mods/Original.melmod", oldContent)));
            ContentBundle.write(edited, List.of(source("Maps/جديد.melmap", new byte[]{1,2,3})));
            byte[] oldZip = Files.readAllBytes(original.toPath()), newZip = Files.readAllBytes(edited.toPath());
            File backup = directory.resolve("backup.zip").toFile(); Files.copy(original.toPath(), backup.toPath());
            File destination = directory.resolve("same-document.zip").toFile(); Files.copy(original.toPath(), destination.toPath());
            Document target = new Document(destination);
            VerifiedBundleOverwrite.replace(original, edited, () -> new FileInputStream(backup), target);
            check(Arrays.equals(newZip, Files.readAllBytes(destination.toPath())), "Same document contains exact shorter edited ZIP without trailing bytes");
            ContentBundle.verify(destination); checks++;
            check(Arrays.equals(oldZip, Files.readAllBytes(backup.toPath())), "Original durable backup unchanged after success");
            check(Arrays.equals(oldZip, Files.readAllBytes(original.toPath())), "Editor snapshot remains unchanged");

            Files.write(destination.toPath(), oldZip); Document partial = new Document(destination); partial.failFirst = true;
            rejects("BUNDLE_UPDATE_RESTORED", () -> VerifiedBundleOverwrite.replace(original, edited, () -> new FileInputStream(backup), partial));
            check(Arrays.equals(oldZip, Files.readAllBytes(destination.toPath())), "Partial write restores original bytes automatically");
            check(partial.opens == 2, "Rollback writes once after the failed attempt");

            Document corrupt = new Document(destination); corrupt.corruptFirst = true;
            rejects("BUNDLE_UPDATE_RESTORED", () -> VerifiedBundleOverwrite.replace(original, edited, () -> new FileInputStream(backup), corrupt));
            check(Arrays.equals(oldZip, Files.readAllBytes(destination.toPath())), "Provider corruption detected by readback and original restored");

            Document denied = new Document(destination); denied.deny = true;
            rejects("BUNDLE_UPDATE_RESTORED", () -> VerifiedBundleOverwrite.replace(original, edited, () -> new FileInputStream(backup), denied));
            check(Arrays.equals(oldZip, Files.readAllBytes(destination.toPath())), "Denied write preserves original");
            check(denied.opens == 1, "No attempted rollback write when untouched source is intact");

            Document wrongBackup = new Document(destination);
            rejects("CHECKSUM", () -> VerifiedBundleOverwrite.replace(original, edited, () -> new ByteArrayInputStream(new byte[]{9}), wrongBackup));
            check(wrongBackup.opens == 0, "No writes without independently verified backup");

            Files.write(destination.toPath(), newZip); Document changed = new Document(destination);
            rejects("FILE_CHANGED", () -> VerifiedBundleOverwrite.replace(original, edited, () -> new FileInputStream(backup), changed));
            check(changed.opens == 0 && Arrays.equals(newZip, Files.readAllBytes(destination.toPath())), "Externally changed source is not overwritten");

            Files.write(destination.toPath(), oldZip); Document offline = new Document(destination); offline.failFirst = true; offline.failRestore = true;
            rejects("BUNDLE_RECOVERY_REQUIRED", () -> VerifiedBundleOverwrite.replace(original, edited, () -> new FileInputStream(backup), offline));
            check(Arrays.equals(oldZip, Files.readAllBytes(backup.toPath())), "Unavailable provider cannot damage external recovery backup");
            ContentBundle.verify(backup); checks++;
        } finally { ShareWire.deleteTree(directory.toFile()); }
        System.out.println("Bundle URI overwrite checks: " + checks + " passed");
    }
}
