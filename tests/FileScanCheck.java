package com.spinel.melkit.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class FileScanCheck {
    private static int checks;

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }

    interface Action { void run() throws Exception; }

    private static void rejects(Class<? extends Throwable> type, Action action, String label) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError(label, failure);
            checks++;
            return;
        }
        throw new AssertionError(label);
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static byte[] archive(String data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(payload);
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("Data");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            entry.setTime(1_700_000_000_000L);
            zip.putNextEntry(entry);
            zip.write(payload);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static FileScan.Finding finding(FileScan.Result result, String path) {
        return result.findings.stream().filter(item -> item.path.equals(path)).findFirst().orElseThrow(AssertionError::new);
    }

    private static boolean noFinding(FileScan.Result result, String path) {
        return result.findings.stream().noneMatch(item -> item.path.equals(path));
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("melkit-scan-check-");
        try {
            Path game = temp.resolve("game");
            Path quarantine = temp.resolve("Download/MelKit/Quarantine");
            String original = "Maps/A.melmap", duplicate = "Maps/nested/نسخة.melmap";
            String broken = "Maps/Broken.melmap", repeatedBroken = "Maps/RepeatedBroken.melmap";
            String empty = "Worlds/Empty.melworld", foreign = "Saves/Foreign.melsave";
            byte[] bytes = archive("aaaa"), different = archive("bbbb");
            write(game.resolve(original), bytes);
            write(game.resolve(duplicate), bytes);
            write(game.resolve("Maps/SameSize.melmap"), different);
            check(bytes.length == different.length, "Same-size fixtures differ in content");
            byte[] truncated = {80, 75, 3, 4, 0, 1, 2};
            write(game.resolve(broken), truncated);
            write(game.resolve(repeatedBroken), truncated);
            byte[] badCrc = bytes.clone();
            int nameSize = (badCrc[26] & 255) + ((badCrc[27] & 255) << 8);
            int extraSize = (badCrc[28] & 255) + ((badCrc[29] & 255) << 8);
            badCrc[30 + nameSize + extraSize] ^= 1;
            write(game.resolve("Maps/BadCrc.melmap"), badCrc);
            write(game.resolve(empty), new byte[0]);
            write(game.resolve(foreign), new byte[]{1, 2, 3});
            write(game.resolve("Mods/Unknown.txt"), new byte[0]);
            write(game.resolve("Mods/Json.melmod"), "\ufeff  {\"mod\":1}".getBytes(StandardCharsets.UTF_8));
            write(game.resolve("Saves/BadJson.melsave"), "{\"save\":".getBytes(StandardCharsets.UTF_8));
            write(game.resolve("Worlds/Progress/profile/Data.json"), new byte[0]);
            write(game.resolve("Preferences/Outside.melsave"), new byte[0]);
            Path outside = temp.resolve("outside.melmap");
            Files.write(outside, bytes);
            Files.createSymbolicLink(game.resolve("Maps/Alias.melmap"), outside);
            Path large = game.resolve("Mods/Large.melmod");
            try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) {
                file.setLength(9 * 1024 * 1024);
            }

            // A controlled callback checks how the scanner routes recognizable JSON.
            // Production JSON tokenization is provided by Android's JsonReader.
            FileScan scanner = new FileScan(game.toFile(), (input, start) -> {
                if (input[start] != '{') throw new AssertionError("Root offset is incorrect");
                return input[input.length - 1] == '}';
            });
            FileScan.Result result = scanner.run();
            check(noFinding(result, original), "Valid reference is not an actionable issue");
            check(noFinding(result, "Maps/SameSize.melmap"), "Equal lengths alone do not produce a duplicate");
            check(noFinding(result, "Mods/Json.melmod"), "UTF-8 BOM and whitespace are handled before JSON verification");
            check(finding(result, "Saves/BadJson.melsave").issue.equals("invalid"), "Recognized JSON structure failure is reported");
            check(finding(result, duplicate).reference.equals(original), "Exact bytes identify the reference across nested paths");
            check(finding(result, duplicate).issue.equals("duplicate") && finding(result, duplicate).selectable, "Duplicate copy is selectable");
            check(finding(result, broken).issue.equals("invalid") && finding(result, broken).retained, "Truncated known ZIP is flagged and kept as duplicate reference");
            check(finding(result, repeatedBroken).selectable, "Extra malformed ZIP can be isolated separately");
            check(finding(result, "Maps/BadCrc.melmap").issue.equals("invalid"), "Stored ZIP checksum mismatch is detected");
            check(finding(result, empty).issue.equals("empty") && finding(result, empty).selectable, "Supported empty file is actionable");
            check(finding(result, foreign).issue.equals("unsupported") && !finding(result, foreign).selectable, "Unknown format is not marked damaged");
            check(!finding(result, "Mods/Unknown.txt").selectable, "Unsupported extension is not eligible even if empty");
            check(finding(result, "Mods/Large.melmod").issue.equals("limit"), "Oversized JSON is reported separately");
            check(finding(result, "Maps/Alias.melmap").issue.equals("unreadable"), "Escaping symlink is an access finding");
            check(noFinding(result, "Worlds/Progress/profile/Data.json") && noFinding(result, "Preferences/Outside.melsave"), "Progress and unrelated folders are excluded");
            check(!Files.exists(quarantine) && Arrays.equals(Files.readAllBytes(game.resolve(duplicate)), bytes), "Scan is read-only");

            ModVault vault = ModVault.forQuarantine(game.toFile(), quarantine.toFile());
            rejects(SecurityException.class, () -> scanner.quarantine(finding(result, broken), vault), "Reference cannot be quarantined");
            rejects(SecurityException.class, () -> scanner.quarantine(finding(result, foreign), vault), "Unchecked item cannot be quarantined");
            scanner.quarantine(finding(result, duplicate), vault);
            check(!Files.exists(game.resolve(duplicate)) && Files.exists(game.resolve(original)), "Only explicitly selected duplicate is moved");
            ModVault reopened = ModVault.forQuarantine(game.toFile(), quarantine.toFile());
            ModVault.Item saved = reopened.stored().items.get(0);
            check(saved.path.equals(duplicate), "A fresh vault can read the original nested path");
            write(game.resolve(duplicate), different);
            rejects(ModVault.Conflict.class, () -> reopened.enable(saved.id), "Different replacement at original path is preserved");
            check(Arrays.equals(Files.readAllBytes(game.resolve(duplicate)), different), "Restore collision keeps existing bytes");
            Files.delete(game.resolve(duplicate));
            reopened.enable(saved.id);
            check(Arrays.equals(Files.readAllBytes(game.resolve(duplicate)), bytes), "Restore returns exact bytes to the original path");
            check(reopened.stored().items.isEmpty(), "Restored item is no longer listed as quarantined");

            FileScan.Finding stale = finding(scanner.run(), duplicate);
            FileTime time = Files.getLastModifiedTime(game.resolve(duplicate));
            Files.write(game.resolve(duplicate), different);
            Files.setLastModifiedTime(game.resolve(duplicate), time);
            rejects(ModVault.Changed.class, () -> scanner.quarantine(stale, reopened), "Matching size and timestamp cannot bypass stale-content check");
            check(Arrays.equals(Files.readAllBytes(game.resolve(duplicate)), different), "Stale selection does not remove replacement");
            Files.write(game.resolve(duplicate), bytes);
            FileScan.Finding missingReference = finding(scanner.run(), duplicate);
            Files.delete(game.resolve(original));
            rejects(ModVault.Changed.class, () -> scanner.quarantine(missingReference, reopened), "Disappeared reference blocks duplicate quarantine");
            check(Arrays.equals(Files.readAllBytes(game.resolve(duplicate)), bytes), "Last matching copy remains in place");

            scanner.quarantine(finding(scanner.run(), empty), reopened);
            ModVault.Item emptyCopy = reopened.stored().items.get(0);
            Path record = quarantine.resolve(emptyCopy.id).resolve("record.properties");
            Properties prepared = new Properties();
            try (InputStream input = Files.newInputStream(record)) { prepared.load(input); }
            prepared.setProperty("state", "prepared");
            try (OutputStream output = Files.newOutputStream(record)) { prepared.store(output, "interrupted after removal"); }
            check(reopened.stored().items.get(0).state.equals("recovery"), "Prepared journal exposes recovery after interruption");
            reopened.enable(emptyCopy.id);
            check(Files.isRegularFile(game.resolve(empty)) && Files.size(game.resolve(empty)) == 0, "Empty world can be restored exactly");
            ModVault modVault = new ModVault(game.toFile(), temp.resolve("DisabledMods").toFile());
            rejects(SecurityException.class, () -> modVault.disable(duplicate, bytes.length, 0), "Ordinary mod vault still rejects maps");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(temp)) {
                files.sorted(Comparator.reverseOrder()).forEach(file -> {
                    try { Files.delete(file); } catch (IOException error) { throw new UncheckedIOException(error); }
                });
            }
        }
        System.out.println("File scan checks: " + checks + " passed");
    }
}
