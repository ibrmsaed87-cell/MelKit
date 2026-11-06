package com.spinel.melkit.share;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ContentBundleCheck {
    private static int checks;

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }

    private interface Action { void run() throws Exception; }

    private static void rejects(String expected, Action action) throws Exception {
        try {
            action.run();
        } catch (IOException error) {
            check(expected.equals(error.getMessage()), "Expected " + expected + ", got " + error);
            return;
        }
        throw new AssertionError("Expected " + expected);
    }

    private static ContentBundle.Source source(String path, long size, byte[] data) {
        return new ContentBundle.Source(path, size, () -> new ByteArrayInputStream(data), () -> {});
    }

    private static void forged(Path archive, String path, byte[] data, byte[] digest, boolean extra) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(data);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("MelKit.bundle"));
            DataOutputStream index = new DataOutputStream(zip);
            index.writeUTF("MELKIT-BUNDLE-1");
            index.writeInt(1);
            index.writeUTF(path);
            index.writeLong(data.length);
            index.write(digest);
            zip.closeEntry();
            if (extra) {
                zip.putNextEntry(new ZipEntry("Saves/Extra.melsave"));
                zip.write(data);
                zip.closeEntry();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("melkit-bundle-check-");
        try {
            byte[] mod = "mod".getBytes(StandardCharsets.UTF_8);
            byte[] save = "save".getBytes(StandardCharsets.UTF_8);
            String modPath = "Mods/فئة/مود.melmod", savePath = "Saves/Save.melsave";
            Path archive = temp.resolve("bundle.zip"), restored = temp.resolve("verified");
            ContentBundle.write(archive.toFile(), List.of(source(modPath, -1, mod), source(savePath, save.length, save)));
            check(ContentBundle.unpack(archive.toFile(), restored.toFile()) == 2, "Both file types restored");
            check(Arrays.equals(Files.readAllBytes(restored.resolve(modPath)), mod), "Unicode path and mod bytes preserved");
            check(Arrays.equals(Files.readAllBytes(restored.resolve(savePath)), save), "Save bytes preserved");
            rejects("SAVE_FAILED", () -> ContentBundle.unpack(archive.toFile(), restored.toFile()));
            check(Arrays.equals(Files.readAllBytes(restored.resolve(savePath)), save), "Existing import untouched");

            Path invalid = temp.resolve("invalid"), forged = temp.resolve("forged.zip");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(save);
            forged(forged, savePath, new byte[save.length], digest, false);
            rejects("CHECKSUM", () -> ContentBundle.unpack(forged.toFile(), invalid.toFile()));
            check(!Files.exists(invalid), "Failed hash leaves no partial import");
            forged(forged, "Saves/../../Outside.melsave", save, digest, false);
            rejects("INVALID_BUNDLE", () -> ContentBundle.unpack(forged.toFile(), invalid.toFile()));
            check(!Files.exists(invalid) && !Files.exists(temp.resolve("Outside.melsave")), "Escaping path rejected before extraction");
            forged(forged, savePath, save, digest, true);
            rejects("INVALID_BUNDLE", () -> ContentBundle.unpack(forged.toFile(), invalid.toFile()));

            rejects("INVALID_FILE", () -> ContentBundle.write(archive.toFile(), List.of(
                    source(savePath, save.length, save), source("saves/save.melsave", save.length, save))));
            rejects("FILE_CHANGED", () -> ContentBundle.write(archive.toFile(), List.of(source(savePath, save.length + 1, save))));
            rejects("INVALID_FILE", () -> ContentBundle.write(archive.toFile(), List.of()));
        } finally {
            ShareWire.deleteTree(temp.toFile());
        }
        System.out.println("Content bundle checks passed: " + checks);
    }
}
