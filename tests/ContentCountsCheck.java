import com.spinel.melkit.storage.ContentCounts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class ContentCountsCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("melkit-counts-");
        try {
            Path root = Files.createDirectory(temp.resolve("game"));
            expectCounts(root, 0, 0, 0);
            Path mods = Files.createDirectories(root.resolve("Mods/حزمة"));
            for (int i = 0; i < 53; i++) Files.write(mods.resolve(i + ".melmod"), new byte[] {1});
            Files.write(mods.resolve("upper.MELMOD"), new byte[] {1});
            Files.write(mods.resolve("cover.png"), new byte[] {1});
            Files.write(mods.resolve("wrong.melsave"), new byte[] {1});
            Files.createDirectories(root.resolve("Maps/nested"));
            Files.write(root.resolve("Maps/nested/map.melmap"), new byte[] {1});
            Files.createDirectories(root.resolve("Saves"));
            Files.write(root.resolve("Saves/empty.melsave"), new byte[0]);
            Files.createDirectories(root.resolve("Worlds/progress"));
            Files.write(root.resolve("Worlds/progress/excluded.melsave"), new byte[] {1});
            Files.write(root.resolve("Worlds/progress/a.melworld"), new byte[] {1});
            Files.write(root.resolve("Worlds/second.MELWORLD"), new byte[] {1});
            var all = ContentCounts.read(root.toFile());
            expect(all.worlds == 2); expect(all.maps == 1); expect(all.saves == 1);
            expectCounts(root, 54, 1, 1);
            Files.delete(mods.resolve("0.melmod"));
            expectCounts(root, 53, 1, 1);
            Path disabled = Files.createDirectory(temp.resolve("DisabledMods"));
            Files.write(disabled.resolve("outside.melmod"), new byte[] {1});
            Files.createSymbolicLink(mods.resolve("escape"), disabled);
            rejected(SecurityException.class, () -> ContentCounts.read(root.toFile()));
            Files.delete(mods.resolve("escape"));
            Files.createSymbolicLink(mods.resolve("loop"), root.resolve("Mods"));
            rejected(SecurityException.class, () -> ContentCounts.read(root.toFile()));
            Files.delete(mods.resolve("loop"));
            expectCounts(root, 53, 1, 1);
            rejected(IOException.class, () -> ContentCounts.read(temp.resolve("missing").toFile()));
            Path invalid = Files.createDirectory(temp.resolve("invalid"));
            Files.write(invalid.resolve("Maps"), new byte[] {1});
            rejected(IOException.class, () -> ContentCounts.read(invalid.toFile()));
            Path deep = Files.createDirectories(root.resolve("Maps/deep"));
            for (int i = 0; i < 65; i++) deep = Files.createDirectory(deep.resolve("child"));
            rejected(IOException.class, () -> ContentCounts.read(root.toFile()));
            System.out.println("Content count checks passed: " + checks);
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
    private static void expectCounts(Path root, int mods, int maps, int saves) throws Exception {
        var result = ContentCounts.read(root.toFile());
        expect(result.mods == mods); expect(result.maps == maps); expect(result.saves == saves);
    }
    private interface Action { void run() throws Exception; }
    private static void rejected(Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); } catch (Exception error) {
            if (type.isInstance(error)) { checks++; return; }
            throw error;
        }
        throw new AssertionError("Expected rejection: " + type.getSimpleName());
    }
    private static void expect(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
}
