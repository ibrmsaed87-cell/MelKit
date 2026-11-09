import com.spinel.melkit.storage.NewFileRestore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;

public final class RestoreCopyCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("melkit-restore-copy-");
        try {
            Path root = Files.createDirectory(temp.resolve("game"));
            byte[] source = "saved content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] original = "existing game file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha = hash(source);
            for (String path : new String[] {"Maps/nested/خريطة.melmap", "Mods/Blue Android.melmod",
                    "Saves/12 cylinder engine.melsave", "Worlds/world.melworld"}) {
                Files.createDirectories(root.resolve(path).getParent());
                Files.write(root.resolve(path), original);
                String first = NewFileRestore.restoreAsCopy(root.toFile(), path, new ByteArrayInputStream(source), source.length, sha);
                expect(first.endsWith(" (1)" + path.substring(path.lastIndexOf('.'))));
                expect(Arrays.equals(Files.readAllBytes(root.resolve(first)), source));
                String second = NewFileRestore.restoreAsCopy(root.toFile(), path, new ByteArrayInputStream(source), source.length, sha);
                expect(second.endsWith(" (2)" + path.substring(path.lastIndexOf('.'))));
                expect(Arrays.equals(Files.readAllBytes(root.resolve(second)), source));
                expect(Arrays.equals(Files.readAllBytes(root.resolve(path)), original));
                expect(Arrays.equals(Files.readAllBytes(root.resolve(first)), source));
            }
            String fresh = NewFileRestore.restoreAsCopy(root.toFile(), "Saves/fresh.melsave",
                new ByteArrayInputStream(source), source.length, sha);
            expect(fresh.equals("Saves/fresh (1).melsave"));
            expect(!Files.exists(root.resolve("Saves/fresh.melsave")));
            String raced = NewFileRestore.restoreAsCopy(root.toFile(), "Maps/race.melmap", new ByteArrayInputStream(source) {
                private boolean created;
                @Override public synchronized int read(byte[] b, int off, int len) {
                    if (!created) {
                        created = true;
                        try { Files.write(root.resolve("Maps/race (1).melmap"), original); }
                        catch (Exception error) { throw new AssertionError(error); }
                    }
                    return super.read(b, off, len);
                }
            }, source.length, sha);
            expect(raced.equals("Maps/race (2).melmap"));
            expect(Arrays.equals(Files.readAllBytes(root.resolve("Maps/race (1).melmap")), original));
            expect(Arrays.equals(Files.readAllBytes(root.resolve(raced)), source));
            rejected(NewFileRestore.SourceChanged.class, () -> NewFileRestore.restoreAsCopy(root.toFile(), "Saves/bad.melsave",
                new ByteArrayInputStream(source), source.length, "0".repeat(64)));
            expect(!Files.exists(root.resolve("Saves/bad (1).melsave")));
            rejected(NewFileRestore.SourceChanged.class, () -> NewFileRestore.restoreAsCopy(root.toFile(), "Saves/short.melsave",
                new ByteArrayInputStream(source), source.length + 1, sha));
            rejected(SecurityException.class, () -> NewFileRestore.restoreAsCopy(root.toFile(), "Saves/../bad.melsave",
                new ByteArrayInputStream(source), source.length, sha));
            Path outside = Files.createDirectory(temp.resolve("outside"));
            Files.createSymbolicLink(root.resolve("Saves/escape"), outside);
            rejected(SecurityException.class, () -> NewFileRestore.restoreAsCopy(root.toFile(), "Saves/escape/bad.melsave",
                new ByteArrayInputStream(source), source.length, sha));
            try (var walk = Files.walk(root)) {
                expect(walk.noneMatch(p -> p.getFileName().toString().startsWith(".melkit-restore-")));
            }
            System.out.println("Restore-copy checks passed: " + checks);
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
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
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
