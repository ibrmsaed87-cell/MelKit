import com.spinel.melkit.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ModProfilesCheck {
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
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }
    private static final String CAR = "Mods/cars/سيارة.melmod", WAR = "Mods/tank.melmod", CORE = "Mods/base.melmod";
    static class Fixture implements AutoCloseable {
        final Path temporary, root, vaultPath, profilesPath;
        final ModVault vault;
        final ModProfiles profiles;
        final Map<String, byte[]> original = new LinkedHashMap<>();
        Fixture() throws Exception {
            temporary = Files.createTempDirectory("melkit-profiles-check-"); root = temporary.resolve("game");
            vaultPath = temporary.resolve("Download/MelKit/DisabledMods");
            profilesPath = temporary.resolve("Download/MelKit/ModProfiles");
            for (String path : Arrays.asList(CAR, WAR, CORE)) {
                Path file = root.resolve(path); Files.createDirectories(file.getParent());
                byte[] data = ("content-original:" + path).getBytes("UTF-8"); Files.write(file, data); original.put(path, data);
            }
            vault = new ModVault(root.toFile(), vaultPath.toFile());
            profiles = new ModProfiles(vault, profilesPath.toFile());
            profiles.saveProfile("core", "", set(CORE));
        }
        String group(String name, String... members) throws Exception { return profiles.saveProfile("", name, set(members)); }
        Set<String> active() throws Exception {
            Set<String> result = new HashSet<>();
            for (ModVault.Item item : vault.list().items) if (item.id.isEmpty()) result.add(item.path);
            return result;
        }
        void bytes(String path) throws Exception { ok(Arrays.equals(Files.readAllBytes(root.resolve(path)), original.get(path)), "Exact original bytes: " + path); }
        @Override public void close() throws Exception {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException error) { throw new RuntimeException(error); } });
            }
        }
    }
    static class ProcessStopped extends Error {}
    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            String cars = f.group("السيارات", CAR), war = f.group("الحروب", WAR);
            ok(f.active().equals(set(CAR, WAR, CORE)), "Saving groups and core mods does not move files");
            f.profiles.apply(cars);
            ok(f.active().equals(set(CAR, CORE)), "Only selected and core mods are active");
            ok(f.profiles.state().activeId.equals(cars), "Exactly one current group is marked active");
            ok(f.profiles.state().profiles.get(0).id.equals(cars), "Current group is listed first");
            ok(f.profiles.state().previousName.isEmpty() && f.profiles.state().targetName.equals("السيارات"),
                    "First activation describes an unnamed prior state without inventing a source group");
            f.profiles.apply(war);
            ok(f.active().equals(set(WAR, CORE)), "Switching groups replaces membership and preserves core");
            ModProfiles.State switchState = f.profiles.state();
            ok(switchState.previousName.equals("السيارات") && switchState.targetName.equals("الحروب") && switchState.switchedAt > 0,
                    "Switch summary captures both names and an actual timestamp");
            f.bytes(WAR); f.bytes(CORE);
            ModProfiles restarted = new ModProfiles(new ModVault(f.root.toFile(), f.vaultPath.toFile()), f.profilesPath.toFile());
            ok(restarted.state().activeId.equals(war) && restarted.state().core.equals(set(CORE)), "Groups, core and selected state survive restart");
            ok(restarted.state().previousName.equals(switchState.previousName) && restarted.state().targetName.equals(switchState.targetName)
                    && restarted.state().switchedAt == switchState.switchedAt, "Switch summary survives restart without replacing its timestamp");
            f.profiles.saveProfile(cars, "Renamed cars", set(CAR)); f.profiles.saveProfile(war, "Renamed war", set(WAR));
            ok(restarted.state().previousName.equals("السيارات") && restarted.state().targetName.equals("الحروب"),
                    "Later name edits do not rewrite the historical switch summary");
            restarted.undo();
            ok(f.active().equals(set(CAR, CORE)), "Undo restores exact prior membership");
            f.bytes(CAR); f.bytes(CORE);
            ok(restarted.state().activeId.equals(cars) && !restarted.state().undo, "Undo restores previous group and is one-level");
            ok(restarted.state().switchedAt == switchState.switchedAt, "Undo preserves the time of the original switch");
            rejects(ModVault.Changed.class, restarted::undo, "Repeated undo cannot replay an old switch");
            f.profiles.saveProfile(cars, "سيارات جديدة", set(CAR));
            ok(f.active().equals(set(CAR, CORE)), "Editing a group is metadata only");
            f.profiles.delete(cars);
            ok(f.active().equals(set(CAR, CORE)) && f.profiles.state().activeId.isEmpty(), "Deleting a selected definition preserves game files");
            String empty = f.group("الأساسية فقط"); f.profiles.apply(empty);
            ok(f.active().equals(set(CORE)), "Empty group keeps core mods active");
            f.profiles.undo(); ok(f.active().equals(set(CAR, CORE)), "Undo from empty group returns previous files");
            String missing = f.group("Missing", "Mods/missing.melmod");
            rejects(ModProfiles.MissingMod.class, () -> f.profiles.apply(missing), "Unavailable target is rejected before changes");
            ok(f.active().equals(set(CAR, CORE)), "Missing selection preserves current files");
            rejects(SecurityException.class, () -> f.group("bad", "Mods/../Maps/a.melmod"), "Stored membership cannot escape mod paths");
            Path linked = f.temporary.resolve("linked"); Files.createSymbolicLink(linked, f.profilesPath);
            rejects(SecurityException.class, () -> new ModProfiles(f.vault, linked.toFile()), "Profile storage symlink is rejected");
        }
        try (Fixture f = new Fixture()) {
            String cars = f.group("Cars", CAR), war = f.group("War", WAR);
            f.profiles.apply(cars); f.profiles.apply(war);
            File journal = f.profilesPath.resolve("switch.properties").toFile();
            Properties legacy = new Properties();
            try (InputStream input = new FileInputStream(journal)) { legacy.load(input); }
            legacy.remove("previousName"); legacy.remove("targetName"); legacy.remove("switchedAt");
            try (OutputStream output = new FileOutputStream(journal)) { legacy.store(output, "Old MelKit journal"); }
            ModProfiles.State state = f.profiles.state();
            ok(state.previousName.equals("Cars") && state.targetName.equals("War") && state.switchedAt == 0,
                    "Legacy journals use available group names and do not invent historical time");
            legacy.setProperty("switchedAt", "invalid timestamp"); legacy.setProperty("targetName", "bad\nname");
            try (OutputStream output = new FileOutputStream(journal)) { legacy.store(output, "Damaged optional display fields"); }
            ok(f.profiles.state().switchedAt == 0 && f.profiles.state().targetName.isEmpty(),
                    "Invalid optional display fields do not block reading recovery state");
            f.profiles.undo();
            ok(f.active().equals(set(CAR, CORE)), "Undo remains available for a legacy journal with invalid optional metadata");
        }
        try (Fixture f = new Fixture()) {
            String cars = f.group("Cars", CAR);
            f.profiles.apply(cars);
            Files.write(f.root.resolve(CAR), new byte[]{4,5,6});
            rejects(ModVault.Changed.class, f.profiles::undo, "Undo refuses externally modified current files");
            ok(Arrays.equals(Files.readAllBytes(f.root.resolve(CAR)), new byte[]{4,5,6}), "Stale undo preserves modified content");
            ok(!Files.exists(f.root.resolve(WAR)), "Stale undo does not enable other files partially");
        }
        try (Fixture f = new Fixture()) {
            String cars = f.group("Cars", CAR); boolean[] failed = {false};
            ModProfiles flaky = new ModProfiles(f.vault, f.profilesPath.toFile(), (done, total) -> {
                if (done == 1 && !failed[0]) { failed[0] = true; throw new IOException("Connection interrupted"); }
            });
            rejects(ModProfiles.RolledBack.class, () -> flaky.apply(cars), "An interrupted switch rolls back when its copies are available");
            ok(f.active().equals(set(CAR, WAR, CORE)), "Automatic rollback restores complete original membership");
            for (String path : f.original.keySet()) f.bytes(path);
            ok(!f.profiles.state().recovery && !f.profiles.state().undo, "Successful rollback clears pending state");
        }
        try (Fixture f = new Fixture()) {
            String cars = f.group("Cars", CAR);
            ModProfiles stopped = new ModProfiles(f.vault, f.profilesPath.toFile(), (done, total) -> {
                if (done == 1) throw new ProcessStopped();
            });
            rejects(ProcessStopped.class, () -> stopped.apply(cars), "Process death is simulated after one completed move");
            ok(f.profiles.state().recovery && f.profiles.state().undo, "A fresh manager exposes recoverable pending switch");
            ok(f.profiles.state().targetName.equals("Cars") && f.profiles.state().switchedAt > 0,
                    "Interrupted switches retain their summary for recovery");
            rejects(ModProfiles.NeedsRecovery.class, () -> f.profiles.apply(cars), "New switch is blocked during recovery");
            rejects(ModProfiles.NeedsRecovery.class, f.profiles::requireIdle, "Individual toggle is blocked during recovery");
            rejects(ModProfiles.NeedsRecovery.class, () -> f.group("Other", WAR), "Metadata changes are blocked during recovery");
            f.profiles.undo();
            ok(f.active().equals(set(CAR, WAR, CORE)) && !f.profiles.state().recovery, "Restart recovery restores the saved starting state");
            for (String path : f.original.keySet()) f.bytes(path);
        }
        try (Fixture f = new Fixture()) {
            String cars = f.group("Cars", CAR); boolean[] created = {false};
            ModProfiles collision = new ModProfiles(f.vault, f.profilesPath.toFile(), (done, total) -> {
                if (done == 1 && !created[0]) { created[0] = true; Files.write(f.root.resolve(WAR), new byte[]{99}); }
            });
            rejects(ModProfiles.NeedsRecovery.class, () -> collision.apply(cars), "Late external collision leaves pending recovery");
            ok(Arrays.equals(Files.readAllBytes(f.root.resolve(WAR)), new byte[]{99}), "External collision bytes are never overwritten");
            rejects(ModVault.Changed.class, f.profiles::undo, "Recovery refuses a conflicting external file");
            Files.delete(f.root.resolve(WAR)); f.profiles.undo();
            ok(f.active().equals(set(CAR, WAR, CORE)), "Recovery succeeds once a test collision is removed"); f.bytes(WAR);
        }
        try (Fixture f = new Fixture()) {
            Path file = f.root.resolve(WAR);
            f.vault.disable(WAR, file.toFile().length(), file.toFile().lastModified());
            String war = f.group("War", WAR);
            ModVault full = new ModVault(f.root.toFile(), f.vaultPath.toFile(), dir -> 0);
            ModProfiles noSpace = new ModProfiles(full, f.profilesPath.toFile());
            rejects(ModProfiles.RolledBack.class, () -> noSpace.apply(war), "Space failure stops and preserves the original state");
            ok(f.active().equals(set(CAR, CORE)), "No-space failure leaves active files untouched"); f.bytes(CAR); f.bytes(CORE);
        }
        try (Fixture f = new Fixture()) {
            Path file = f.root.resolve(WAR);
            f.vault.disable(WAR, file.toFile().length(), file.toFile().lastModified());
            Files.write(file, new byte[]{7}); f.vault.disable(WAR, 1, file.toFile().lastModified());
            String war = f.group("War", WAR);
            rejects(ModVault.Conflict.class, () -> f.profiles.apply(war), "Ambiguous inactive versions require explicit individual selection");
            ok(f.active().equals(set(CAR, CORE)), "Version ambiguity does not move any source files");
            String chosen = f.vault.list().items.stream().filter(i -> i.path.equals(WAR) && i.bytes == 1).findFirst().get().id;
            f.vault.enable(chosen); f.profiles.apply(war);
            ok(f.active().equals(set(WAR, CORE)), "An explicitly activated version resolves profile ambiguity");
            ok(Arrays.equals(Files.readAllBytes(file), new byte[]{7}), "Older vault versions never replace the chosen active bytes");
        }
        System.out.println("Mod profile checks: " + checks + " passed");
    }
}
