import com.spinel.melkit.storage.ContentPaths;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Host-JVM security checks: no Android SDK or signing secret is needed. */
public final class ContentPathsCheck {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("melkit-content-paths-");
        try {
            File root = Files.createDirectories(temp.resolve("game/files")).toFile();
            Files.createDirectories(root.toPath().resolve("Mods"));
            Files.createDirectories(root.toPath().resolve("Saves"));
            Files.createDirectories(root.toPath().resolve("Maps"));
            Files.createDirectories(root.toPath().resolve("Worlds"));
            Files.createDirectories(root.toPath().resolve("Preferences"));
            ContentPaths paths = new ContentPaths(root);
            expect(paths.resolve("", true).equals(root.getCanonicalFile()));
            expect(paths.resolve("Mods/a.melmod", false).getParentFile().equals(new File(root, "Mods")));
            expect(paths.resolve("Saves/مبنى تجريبي.melsave", false).getName().equals("مبنى تجريبي.melsave"));
            expect(paths.resolve("Maps/a.melmap", false).getName().equals("a.melmap"));
            expect(paths.resolve("Worlds/example", false).getName().equals("example"));
            expect(ContentPaths.isContentFolder("mods"));
            expect(!ContentPaths.isContentFolder("ModsBackup"));
            for (String invalid : new String[] {null, "", "/etc/passwd", "../files/Mods/a", "Mods/../Saves/a",
                    "Mods/./a", "Mods//a", "Mods/", "Mods\\a", "Mods/a\0b", "Preferences/config",
                    "ModsBackup/file", "Android/data/another.app", "Mods/" + "x".repeat(4097)}) {
                rejected(paths, invalid);
            }
            Path outside = Files.createDirectories(temp.resolve("outside"));
            Files.createSymbolicLink(root.toPath().resolve("Mods/escape"), outside);
            rejected(paths, "Mods/escape/private");
            Files.createSymbolicLink(root.toPath().resolve("Mods/preferences"), root.toPath().resolve("Preferences"));
            rejected(paths, "Mods/preferences/config");
            Files.createDirectories(temp.resolve("game/files-sibling"));
            Files.createSymbolicLink(root.toPath().resolve("Mods/sibling"), temp.resolve("game/files-sibling"));
            rejected(paths, "Mods/sibling/private");
            System.out.println("Content path checks passed: " + checks);
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    private static void rejected(ContentPaths paths, String value) throws Exception {
        try { paths.resolve(value, false); }
        catch (SecurityException expected) { checks++; return; }
        throw new AssertionError("An invalid path was accepted");
    }

    private static void expect(boolean value) {
        if (!value) throw new AssertionError("Unexpected path resolution");
        checks++;
    }
}
