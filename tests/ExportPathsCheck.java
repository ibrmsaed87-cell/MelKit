import com.spinel.melkit.storage.ExportPaths;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises real directory creation and collision handling without Android or game writes. */
public final class ExportPathsCheck {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("melkit-export-paths-");
        try {
            File downloads = Files.createDirectory(temp.resolve("Download")).toFile();
            expect(ExportPaths.relativeDirectory("Maps/test.melmap").equals("MelKit/Maps/"));
            expect(ExportPaths.relativeDirectory("Mods/group/test.melmod").equals("MelKit/Mods/group/"));
            expect(ExportPaths.fileName("Saves/مبنى تجريبي.melsave").equals("مبنى تجريبي.melsave"));
            for (String invalid : new String[] {null, "", "Mods", "/Maps/a", "../Mods/a", "Mods/../a",
                    "Maps/./a", "Mods//a", "Saves/", "Mods/a\\b", "Maps/a\0b", "Preferences/a",
                    "Maps/" + "a".repeat(4097)}) {
                rejected(() -> ExportPaths.relativeDirectory(invalid));
            }
            File first = ExportPaths.reserveLegacy(downloads, "Maps/خريطة.melmap");
            expect(first.toPath().equals(downloads.toPath().resolve("MelKit/Maps/خريطة.melmap")));
            Files.writeString(first.toPath(), "original bytes");
            File second = ExportPaths.reserveLegacy(downloads, "Maps/خريطة.melmap");
            expect(second.getName().equals("خريطة (1).melmap"));
            expect(Files.readString(first.toPath()).equals("original bytes"));
            expect(ExportPaths.reserveLegacy(downloads, "Maps/خريطة.melmap").getName().equals("خريطة (2).melmap"));
            File nested = ExportPaths.reserveLegacy(downloads, "Mods/group/a.melmod");
            expect(nested.toPath().equals(downloads.toPath().resolve("MelKit/Mods/group/a.melmod")));
            ExportPaths.reserveLegacy(downloads, "Saves/no_extension");
            expect(ExportPaths.reserveLegacy(downloads, "Saves/no_extension").getName().equals("no_extension (1)"));
            File blocked = new File(downloads, "MelKit/Worlds");
            Files.writeString(blocked.toPath(), "not a folder");
            try { ExportPaths.reserveLegacy(downloads, "Worlds/a"); throw new AssertionError("Accepted a non-directory"); }
            catch (java.io.IOException expected) { checks++; }
            Path outside = Files.createDirectory(temp.resolve("outside"));
            Files.createSymbolicLink(downloads.toPath().resolve("MelKit/Mods/escape"), outside);
            rejected(() -> ExportPaths.reserveLegacy(downloads, "Mods/escape/a.melmod"));
            Path linkedDownloads = Files.createDirectory(temp.resolve("linked-downloads"));
            Files.createSymbolicLink(linkedDownloads.resolve("MelKit"), outside);
            rejected(() -> ExportPaths.reserveLegacy(linkedDownloads.toFile(), "Maps/a.melmap"));
            try (var files = Files.list(outside)) { expect(files.findAny().isEmpty()); }
            System.out.println("Export path checks passed: " + checks);
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    private interface Action { void run() throws Exception; }
    private static void rejected(Action action) throws Exception {
        try { action.run(); }
        catch (SecurityException expected) { checks++; return; }
        throw new AssertionError("An invalid export path was accepted");
    }
    private static void expect(boolean value) {
        if (!value) throw new AssertionError("Unexpected export behavior");
        checks++;
    }
}
