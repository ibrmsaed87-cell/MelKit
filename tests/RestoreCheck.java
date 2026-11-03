import com.spinel.melkit.storage.NewFileRestore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

public final class RestoreCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("melkit-restore-");
        try {
            Path root = Files.createDirectory(temp.resolve("game"));
            byte[] bytes = "verified saved copy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha = sha(bytes);
            for (String path : new String[] {"Maps/خريطة.melmap", "Mods/test.melmod", "Saves/test.melsave", "Worlds/test.melworld"}) {
                NewFileRestore.restore(root.toFile(), path, new ByteArrayInputStream(bytes), bytes.length, sha);
                expect(Arrays.equals(Files.readAllBytes(root.resolve(path)), bytes));
            }
            rejected(NewFileRestore.AlreadyExists.class, () -> NewFileRestore.restore(root.toFile(), "Maps/خريطة.melmap",
                new ByteArrayInputStream(new byte[] {1}), 1, sha(new byte[] {1})));
            expect(Arrays.equals(Files.readAllBytes(root.resolve("Maps/خريطة.melmap")), bytes));
            rejected(NewFileRestore.SourceChanged.class, () -> NewFileRestore.restore(root.toFile(), "Maps/bad.melmap",
                new ByteArrayInputStream(bytes), bytes.length, "0".repeat(64)));
            expect(!Files.exists(root.resolve("Maps/bad.melmap")));
            rejected(NewFileRestore.SourceChanged.class, () -> NewFileRestore.restore(root.toFile(), "Maps/short.melmap",
                new ByteArrayInputStream(bytes), bytes.length + 1, sha));
            rejected(NewFileRestore.SourceChanged.class, () -> NewFileRestore.restore(root.toFile(), "Maps/long.melmap",
                new ByteArrayInputStream(bytes), bytes.length - 1, sha));
            rejected(IOException.class, () -> NewFileRestore.restore(root.toFile(), "Maps/io.melmap", new InputStream() {
                @Override public int read() throws IOException { throw new IOException("interrupted source"); }
            }, bytes.length, sha));
            expect(!Files.exists(root.resolve("Maps/io.melmap")));
            for (String path : new String[] {"Maps/../escape.melmap", "/Maps/out.melmap", "Preferences/test.melmod",
                    "Maps/config.json", "Mods/wrong.melmap", "Maps//bad.melmap"}) {
                rejected(SecurityException.class, () -> NewFileRestore.restore(root.toFile(), path,
                    new ByteArrayInputStream(bytes), bytes.length, sha));
            }
            Path outside = Files.createDirectory(temp.resolve("outside"));
            Files.createSymbolicLink(root.resolve("Maps/escape"), outside);
            rejected(SecurityException.class, () -> NewFileRestore.restore(root.toFile(), "Maps/escape/a.melmap",
                new ByteArrayInputStream(bytes), bytes.length, sha));
            rejected(NewFileRestore.AlreadyExists.class, () -> NewFileRestore.restore(root.toFile(), "Maps/race.melmap",
                new ByteArrayInputStream(bytes) {
                    private boolean created;
                    @Override public synchronized int read(byte[] b, int off, int len) {
                        if (!created) {
                            created = true;
                            try { Files.writeString(root.resolve("Maps/race.melmap"), "another file"); }
                            catch (IOException e) { throw new AssertionError(e); }
                        }
                        return super.read(b, off, len);
                    }
                }, bytes.length, sha));
            expect(Files.readString(root.resolve("Maps/race.melmap")).equals("another file"));
            try (var walk = Files.walk(root)) {
                expect(walk.noneMatch(p -> p.getFileName().toString().startsWith(".melkit-restore-")));
            }
            if (args.length == 1) {
                byte[] sample = Files.readAllBytes(Path.of(args[0]));
                NewFileRestore.restore(root.toFile(), "Maps/real-copy.melmap", new ByteArrayInputStream(sample), sample.length, sha(sample));
                expect(Arrays.equals(Files.readAllBytes(root.resolve("Maps/real-copy.melmap")), sample));
                expect(Arrays.equals(Files.readAllBytes(Path.of(args[0])), sample));
            }
            System.out.println("New-file restore checks passed: " + checks);
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
    private interface Action { void run() throws Exception; }
    private static void rejected(Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); } catch (Exception error) {
            if (type.isInstance(error)) { checks++; return; }
            throw error;
        }
        throw new AssertionError("Expected restore rejection: " + type.getSimpleName());
    }
    private static void expect(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
