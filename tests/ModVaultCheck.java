import com.spinel.melkit.storage.ModVault;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ModVaultCheck {
    private static int checks;
    private static void ok(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    interface Action { void run() throws Exception; }
    private static void rejects(Class<? extends Throwable> type, Action action, String label) throws Exception {
        try { action.run(); } catch (Throwable error) {
            if (type.isInstance(error)) { checks++; return; }
            throw new AssertionError(label, error);
        }
        throw new AssertionError(label);
    }
    private static void state(Path journal, String value) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(journal)) { p.load(in); }
        p.setProperty("state", value);
        try (OutputStream out = Files.newOutputStream(journal)) { p.store(out, "Simulate process interruption"); }
    }
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("melkit-vault-check-");
        try {
            Path game = Files.createDirectories(temporary.resolve("game/Mods/cars"));
            Path root = temporary.resolve("game");
            Path vault = temporary.resolve("Download/MelKit/DisabledMods");
            Path source = game.resolve("سيارة (1).melmod");
            byte[] original = new byte[150123]; new Random(7).nextBytes(original);
            Files.write(source, original);
            Files.write(game.resolve("readme.txt"), new byte[]{9});
            ModVault manager = new ModVault(root.toFile(), vault.toFile());
            rejects(SecurityException.class, () -> new ModVault(root.toFile(), root.toFile()), "Game folder cannot be the vault");
            ok(manager.list().items.size() == 1, "Only mod files are listed, including nested Arabic names");
            String relative = "Mods/cars/سيارة (1).melmod";
            long modified = source.toFile().lastModified();
            rejects(ModVault.Changed.class, () -> manager.disable(relative, 1, modified), "Stale UI is rejected");
            ok(Arrays.equals(Files.readAllBytes(source), original), "Stale request preserves original bytes");
            ModVault full = new ModVault(root.toFile(), vault.toFile(), directory -> 0);
            rejects(ModVault.NoSpace.class, () -> full.disable(relative, original.length, modified), "No space stops before source removal");
            ok(Files.exists(source), "Original remains on space failure");
            String id = manager.disable(relative, original.length, modified);
            Path payload = vault.resolve(id).resolve("content.melmod");
            Path journal = vault.resolve(id).resolve("record.properties");
            ok(!Files.exists(source), "Disabled mod is absent from game");
            ok(Arrays.equals(Files.readAllBytes(payload), original), "Vault bytes match the original");
            ok(manager.list().items.size() == 1 && manager.list().items.get(0).state.equals("disabled"), "Disabled mod remains visible");
            ModVault restarted = new ModVault(root.toFile(), vault.toFile());
            restarted.enable(id);
            ok(Arrays.equals(Files.readAllBytes(source), original), "Fresh process returns exact bytes to original nested path");
            ok(manager.list().items.size() == 1 && manager.list().items.get(0).state.equals("active"), "Enabled record does not duplicate active mod");
            restarted.enable(id);
            ok(Arrays.equals(Files.readAllBytes(source), original), "Repeated enable is idempotent");
            String again = manager.disable(relative, original.length, source.toFile().lastModified());
            ok(again.equals(id) && vault.toFile().list().length == 1, "Unchanged repeated toggle reuses safety copy");
            Files.write(source, new byte[]{1,2,3});
            rejects(ModVault.Conflict.class, () -> manager.enable(id), "Different existing game file is never overwritten");
            ok(Arrays.equals(Files.readAllBytes(source), new byte[]{1,2,3}), "Collision preserves existing file");
            ok(Arrays.equals(Files.readAllBytes(payload), original), "Collision preserves vault copy");
            Files.delete(source);
            Files.write(payload, new byte[]{1});
            rejects(ModVault.Changed.class, () -> manager.enable(id), "Changed vault copy is rejected");
            ok(!Files.exists(source), "Invalid vault copy is never published into game");
            Files.write(payload, original);
            state(journal, "prepared");
            ok(manager.list().items.get(0).state.equals("recovery"), "Crash after source removal exposes recovery action");
            manager.enable(id);
            ok(Arrays.equals(Files.readAllBytes(source), original), "Prepared transaction is recoverable after restart");
            state(journal, "prepared");
            ok(manager.list().items.size() == 2, "Crash before source removal shows active mod and recovery copy");
            manager.enable(id);
            ok(manager.list().items.size() == 1, "Recovery of an existing identical source completes without replacement");
            rejects(SecurityException.class, () -> manager.disable("Mods/../Maps/a.melmod", 0, 0), "Traversal rejected");
            rejects(SecurityException.class, () -> manager.disable("Maps/a.melmap", 0, 0), "Other content types rejected");
            rejects(SecurityException.class, () -> manager.enable("../../outside"), "Untrusted vault identifier rejected");
            Path outside = temporary.resolve("outside.melmod"); Files.write(outside, new byte[]{8});
            Path link = game.resolve("linked.melmod"); Files.createSymbolicLink(link, outside);
            rejects(SecurityException.class, () -> manager.disable("Mods/cars/linked.melmod", 1, link.toFile().lastModified()), "Symlink cannot remove external file");
            ok(Files.exists(outside), "External file remains untouched"); Files.delete(link);
            Path linkedVault = temporary.resolve("linked-vault"); Files.createSymbolicLink(linkedVault, vault);
            rejects(SecurityException.class, () -> new ModVault(root.toFile(), linkedVault.toFile()), "Vault symlink rejected");
            Files.createDirectories(vault.resolve("unfinished"));
            ok(manager.list().warnings == 1 && manager.list().items.size() == 1, "Unreadable journal warns without hiding valid mods");
            ModVault enableFull = new ModVault(root.toFile(), vault.toFile(), directory -> 0);
            manager.disable(relative, original.length, source.toFile().lastModified());
            rejects(ModVault.NoSpace.class, () -> enableFull.enable(id), "Restore space failure retains vault copy");
            ok(Arrays.equals(Files.readAllBytes(payload), original) && !Files.exists(source), "No-space restore does not lose vault bytes");
            Files.delete(payload);
            ok(manager.list().warnings == 2, "Missing payload is reported without claiming corruption");
            ok(Files.exists(game.resolve("readme.txt")), "Non-mod files are unchanged");
            System.out.println("Mod vault checks: " + checks + " passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException e) { throw new RuntimeException(e); } });
            }
        }
    }
}
