package com.spinel.melkit.storage;

import java.io.*;
import java.util.*;

/** One selected profile, persistent core mods and a recoverable one-level switch journal. */
public final class ModProfiles {
    private static final int MAX_ITEMS = 5000, MAX_PROFILES = 50, MAX_BYTES = 8 * 1024 * 1024;
    private final ModVault vault;
    private final File directory;
    public static class NeedsRecovery extends IOException {}
    public static class MissingMod extends IOException {}
    public static class RolledBack extends IOException {}
    public static class Limit extends IOException {}
    public interface Progress { void update(int done, int total) throws Exception; }
    private final Progress progress;

    public static final class Profile {
        public final String id, name;
        public final Set<String> paths;
        Profile(String id, String name, Set<String> paths) { this.id = id; this.name = name; this.paths = paths; }
    }
    public static final class State {
        public final List<Profile> profiles;
        public final Set<String> core;
        public final String activeId;
        public final boolean undo, recovery;
        public final int warnings;
        State(List<Profile> profiles, Set<String> core, String activeId, boolean undo, boolean recovery, int warnings) {
            this.profiles = profiles; this.core = core; this.activeId = activeId; this.undo = undo; this.recovery = recovery; this.warnings = warnings;
        }
    }
    private static final class Config {
        final Map<String, Profile> profiles = new LinkedHashMap<>();
        Set<String> core = new LinkedHashSet<>();
        String selected = "";
    }
    private static final class Switch {
        String phase, previous, target;
        Map<String, String> before, after;
        Set<String> core;
    }

    public ModProfiles(ModVault vault, File directory) throws IOException { this(vault, directory, (done, total) -> {}); }
    public ModProfiles(ModVault vault, File directory, Progress progress) throws IOException {
        this.vault = vault; this.directory = directory.getAbsoluteFile(); this.progress = progress;
        safe("profiles.properties");
    }

    private File safe(String name) throws IOException {
        if (!directory.equals(directory.getCanonicalFile())) throw new SecurityException("Profile folder symlink");
        File file = new File(directory, name);
        if (!file.equals(file.getCanonicalFile())) throw new SecurityException("Profile file symlink");
        return file;
    }
    private Properties read(String name) throws IOException {
        File file = safe(name);
        Properties p = new Properties();
        if (!file.exists()) return p;
        if (!file.isFile() || file.length() > MAX_BYTES) throw new IOException("Cannot read profiles");
        try (InputStream input = new FileInputStream(file)) { p.load(input); }
        return p;
    }
    private void write(String name, Properties p) throws IOException {
        safe(name);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        p.store(data, "MelKit profiles");
        if (data.size() > MAX_BYTES) throw new Limit();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create profiles folder");
        File temporary = File.createTempFile(".profiles-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { data.writeTo(output); output.getFD().sync(); }
            if (!temporary.renameTo(safe(name))) throw new IOException("Cannot publish profiles");
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private int count(Properties p, String key, int max) throws IOException {
        try {
            int n = Integer.parseInt(p.getProperty(key, "0"));
            if (n < 0 || n > max) throw new Limit();
            return n;
        } catch (NumberFormatException error) { throw new IOException("Invalid profile count", error); }
    }
    private Set<String> paths(Properties p, String prefix) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0, n = count(p, prefix + ".count", MAX_ITEMS); i < n; i++) {
            String path = p.getProperty(prefix + "." + i);
            vault.validateModPath(path); result.add(path);
        }
        return result;
    }
    private void putPaths(Properties p, String prefix, Set<String> values) throws IOException {
        if (values.size() > MAX_ITEMS) throw new Limit();
        p.setProperty(prefix + ".count", Integer.toString(values.size()));
        int i = 0;
        for (String value : new TreeSet<>(values)) { vault.validateModPath(value); p.setProperty(prefix + "." + i++, value); }
    }
    private static void id(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new SecurityException("Invalid profile identifier");
        }
    }
    private Config config() throws IOException {
        Properties p = read("profiles.properties"); Config c = new Config();
        c.core = paths(p, "core"); c.selected = p.getProperty("selected", "");
        if (!c.selected.isEmpty()) id(c.selected);
        for (int i = 0, n = count(p, "profiles.count", MAX_PROFILES); i < n; i++) {
            String prefix = "profile." + i, identifier = p.getProperty(prefix + ".id"); id(identifier);
            String name = p.getProperty(prefix + ".name");
            if (name == null || name.trim().isEmpty() || name.length() > 80) throw new IOException("Invalid profile name");
            c.profiles.put(identifier, new Profile(identifier, name, paths(p, prefix + ".paths")));
        }
        return c;
    }
    private void save(Config c) throws IOException {
        Properties p = new Properties();
        p.setProperty("selected", c.selected); putPaths(p, "core", c.core);
        p.setProperty("profiles.count", Integer.toString(c.profiles.size()));
        int i = 0;
        for (Profile profile : c.profiles.values()) {
            String prefix = "profile." + i++;
            p.setProperty(prefix + ".id", profile.id); p.setProperty(prefix + ".name", profile.name);
            putPaths(p, prefix + ".paths", profile.paths);
        }
        write("profiles.properties", p);
    }
    private Map<String, String> hashes(Properties p, String prefix) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> values = paths(p, prefix);
        for (String path : values) {
            String hash = p.getProperty(prefix + ".hash." + path);
            if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid switch journal");
            result.put(path, hash);
        }
        return result;
    }
    private Switch last() throws IOException {
        Properties p = read("switch.properties");
        if (p.isEmpty()) return null;
        Switch s = new Switch(); s.phase = p.getProperty("phase");
        if (!Arrays.asList("pending", "done", "undo_pending", "undone").contains(s.phase)) throw new IOException("Invalid switch state");
        s.previous = p.getProperty("previous", ""); s.target = p.getProperty("target", "");
        if (!s.previous.isEmpty()) id(s.previous);
        if (!s.target.isEmpty()) id(s.target);
        s.before = hashes(p, "before"); s.after = hashes(p, "after"); s.core = paths(p, "core");
        return s;
    }
    private void save(Switch s, String phase) throws IOException {
        Properties p = new Properties(); p.setProperty("phase", phase);
        p.setProperty("previous", s.previous); p.setProperty("target", s.target); putPaths(p, "core", s.core);
        putPaths(p, "before", s.before.keySet()); putPaths(p, "after", s.after.keySet());
        s.before.forEach((path, hash) -> p.setProperty("before.hash." + path, hash));
        s.after.forEach((path, hash) -> p.setProperty("after.hash." + path, hash));
        write("switch.properties", p); s.phase = phase;
    }
    private static boolean pending(Switch s) { return s != null && (s.phase.equals("pending") || s.phase.equals("undo_pending")); }
    private void editable() throws IOException { if (pending(last())) throw new NeedsRecovery(); }
    public void requireIdle() throws IOException { editable(); }

    public State state() throws Exception {
        Config c = config(); Switch s = last(); ModVault.Snapshot snapshot = vault.list();
        Set<String> active = new HashSet<>();
        for (ModVault.Item item : snapshot.items) if (item.id.isEmpty()) active.add(item.path);
        String activeId = "";
        Profile selected = c.profiles.get(c.selected);
        if (!pending(s) && selected != null) {
            Set<String> desired = new HashSet<>(selected.paths); desired.addAll(c.core);
            if (desired.equals(active)) activeId = selected.id;
        }
        final String current = activeId;
        List<Profile> profiles = new ArrayList<>(c.profiles.values());
        profiles.sort(Comparator.comparing((Profile p) -> !p.id.equals(current)).thenComparing(p -> p.name.toLowerCase(Locale.ROOT)));
        return new State(profiles, c.core, activeId,
                s != null && !s.phase.equals("undone") && s.core.equals(c.core), pending(s), snapshot.warnings);
    }

    public Profile profile(String identifier) throws Exception {
        if (identifier.isEmpty()) return new Profile("", "", new LinkedHashSet<>());
        if (identifier.equals("core")) return new Profile("core", "", config().core);
        id(identifier); Profile result = config().profiles.get(identifier);
        if (result == null) throw new IOException("Profile missing");
        return result;
    }
    public String saveProfile(String identifier, String name, Set<String> selected) throws Exception {
        editable(); Config c = config();
        for (String path : selected) vault.validateModPath(path);
        if (selected.size() > MAX_ITEMS) throw new Limit();
        if (identifier.equals("core")) { c.core = new LinkedHashSet<>(selected); save(c); return "core"; }
        name = name.trim();
        if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid name");
        if (identifier.isEmpty()) {
            if (c.profiles.size() >= MAX_PROFILES) throw new Limit();
            identifier = UUID.randomUUID().toString();
        } else { id(identifier); if (!c.profiles.containsKey(identifier)) throw new IOException("Profile missing"); }
        c.profiles.put(identifier, new Profile(identifier, name, new LinkedHashSet<>(selected))); save(c); return identifier;
    }
    public void delete(String identifier) throws Exception {
        editable(); id(identifier); Config c = config(); c.profiles.remove(identifier);
        if (c.selected.equals(identifier)) c.selected = "";
        save(c); // Metadata only. The active and vaulted files are untouched.
    }

    private Map<String, String> active(ModVault.Snapshot snapshot) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (ModVault.Item item : snapshot.items) if (item.id.isEmpty()) result.put(item.path, vault.fingerprint(item));
        return result;
    }
    private ModVault.Item copy(ModVault.Snapshot snapshot, String path, String expected) throws Exception {
        ModVault.Item result = null; String chosenHash = null;
        for (ModVault.Item item : snapshot.items) if (!item.id.isEmpty() && item.path.equals(path)) {
            String hash;
            try { hash = vault.fingerprint(item); } catch (Exception error) { continue; }
            if (expected != null && !expected.equals(hash)) continue;
            if (chosenHash != null && !chosenHash.equals(hash)) throw new ModVault.Conflict();
            chosenHash = hash; result = item;
        }
        if (result == null) throw new MissingMod();
        return result;
    }
    private Map<String, String> desired(Set<String> paths, ModVault.Snapshot snapshot, Map<String, String> active) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String path : paths) result.put(path, active.containsKey(path) ? active.get(path) : vault.fingerprint(copy(snapshot, path, null)));
        return result;
    }
    private void moveTo(Map<String, String> target, Switch s) throws Exception {
        ModVault.Snapshot snapshot = vault.list(); Map<String, String> current = active(snapshot);
        // A stale undo must not touch a new or externally modified mod.
        for (Map.Entry<String, String> item : current.entrySet()) {
            String hash = item.getValue(), path = item.getKey();
            if (!hash.equals(s.before.get(path)) && !hash.equals(s.after.get(path))) throw new ModVault.Changed();
        }
        for (Map.Entry<String, String> item : target.entrySet()) {
            if (!item.getValue().equals(current.get(item.getKey()))) copy(snapshot, item.getKey(), item.getValue());
        }
        int total = 0, done = 0;
        for (Map.Entry<String, String> item : current.entrySet()) if (!item.getValue().equals(target.get(item.getKey()))) total++;
        for (Map.Entry<String, String> item : target.entrySet()) if (!item.getValue().equals(current.get(item.getKey()))) total++;
        progress.update(0, total);
        for (ModVault.Item item : snapshot.items) if (item.id.isEmpty() && !current.get(item.path).equals(target.get(item.path))) {
            if (!vault.fingerprint(item).equals(current.get(item.path))) throw new ModVault.Changed();
            vault.disable(item.path, item.bytes, item.modified); progress.update(++done, total);
        }
        snapshot = vault.list();
        for (Map.Entry<String, String> item : target.entrySet()) if (!item.getValue().equals(current.get(item.getKey()))) {
            vault.enable(copy(snapshot, item.getKey(), item.getValue()).id); progress.update(++done, total);
        }
        if (!active(vault.list()).equals(target)) throw new ModVault.Changed();
        // Finalize a copy left prepared after an interrupted source removal.
        for (ModVault.Item item : vault.list().items) if (!item.id.isEmpty() && item.state.equals("recovery") && target.containsKey(item.path)) {
            String hash;
            try { hash = vault.fingerprint(item); } catch (Exception unreadable) { continue; }
            if (hash.equals(target.get(item.path))) vault.enable(item.id);
        }
    }

    public void apply(String identifier) throws Exception {
        editable(); id(identifier); Config c = config(); Profile profile = c.profiles.get(identifier);
        if (profile == null) throw new IOException("Profile missing");
        ModVault.Snapshot snapshot = vault.list();
        Switch s = new Switch(); s.previous = c.selected; s.target = identifier; s.core = new LinkedHashSet<>(c.core);
        s.before = active(snapshot);
        Set<String> selected = new LinkedHashSet<>(profile.paths); selected.addAll(c.core);
        s.after = desired(selected, snapshot, s.before);
        save(s, "pending"); // Written before the first file operation, including for an empty group.
        try {
            moveTo(s.after, s); c.selected = identifier; save(c); save(s, "done");
        } catch (Exception error) {
            try {
                moveTo(s.before, s); c.selected = s.previous; save(c); save(s, "undone");
            } catch (Exception rollback) { error.addSuppressed(rollback); throw new NeedsRecovery(); }
            throw new RolledBack();
        }
    }
    public void undo() throws Exception {
        Switch s = last(); Config c = config();
        if (s == null || s.phase.equals("undone") || !s.core.equals(c.core)) throw new ModVault.Changed();
        if (s.phase.equals("done") && !active(vault.list()).equals(s.after)) throw new ModVault.Changed();
        save(s, "undo_pending");
        moveTo(s.before, s); c.selected = s.previous; save(c); save(s, "undone");
    }
}
