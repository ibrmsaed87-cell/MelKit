package com.spinel.melkit.storage;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Verified mod moves with a durable journal in shared storage. Never overwrites game files. */
public final class ModVault {
    private static final int MAX_ITEMS = 5000;
    private final File gameRoot, vaultRoot;
    public interface SpaceProbe { long available(File directory); }
    private final SpaceProbe space;
    public static class Conflict extends IOException {}
    public static class Changed extends IOException {}
    public static class NoSpace extends IOException {}
    public static class Incomplete extends IOException {}

    public static final class Item {
        public final String id, path, state;
        public final long bytes, modified;
        Item(String id, String path, String state, long bytes, long modified) {
            this.id = id; this.path = path; this.state = state;
            this.bytes = bytes; this.modified = modified;
        }
    }
    public static final class Snapshot {
        public final List<Item> items = new ArrayList<>();
        public int warnings;
    }
    private static final class Record {
        String id, path, hash, state;
        long bytes;
    }

    public ModVault(File gameRoot, File vaultRoot) throws IOException {
        this(gameRoot, vaultRoot, File::getUsableSpace);
    }

    public ModVault(File gameRoot, File vaultRoot, SpaceProbe space) throws IOException {
        this.gameRoot = gameRoot.getCanonicalFile();
        this.vaultRoot = vaultRoot.getAbsoluteFile();
        this.space = space;
        if (!this.vaultRoot.equals(this.vaultRoot.getCanonicalFile())) throw new SecurityException("Vault symlink");
        String game = this.gameRoot.getPath() + File.separator;
        if (this.vaultRoot.equals(this.gameRoot) || this.vaultRoot.getPath().startsWith(game)) throw new SecurityException("Vault inside game");
    }

    private File mod(String path) throws IOException {
        if (path == null || !path.toLowerCase(Locale.ROOT).startsWith("mods/")
                || !path.toLowerCase(Locale.ROOT).endsWith(".melmod")) throw new SecurityException("Not a mod");
        File file = new ContentPaths(gameRoot).resolve(path, false);
        if (!file.equals(new File(gameRoot, path).getAbsoluteFile())) throw new SecurityException("Mod symlink");
        return file;
    }

    private void checkVault() throws IOException {
        if (!vaultRoot.equals(vaultRoot.getCanonicalFile())) throw new SecurityException("Vault symlink");
        if (vaultRoot.exists() && !vaultRoot.isDirectory()) throw new IOException("Vault is not a directory");
    }

    private File folder(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new SecurityException("Invalid vault identifier");
        }
        checkVault();
        File directory = new File(vaultRoot, id);
        if (!directory.equals(directory.getCanonicalFile())) throw new SecurityException("Vault entry symlink");
        return directory;
    }

    private File child(String id, String name) throws IOException {
        File file = new File(folder(id), name);
        if (!file.equals(file.getCanonicalFile())) throw new SecurityException("Vault file symlink");
        return file;
    }

    private Record read(String id) throws IOException {
        File journal = child(id, "record.properties");
        if (!journal.isFile() || journal.length() > 16384) throw new IOException("Unreadable journal");
        Properties p = new Properties();
        try (InputStream input = new FileInputStream(journal)) { p.load(input); }
        Record r = new Record();
        r.id = id; r.path = p.getProperty("path"); r.hash = p.getProperty("sha256"); r.state = p.getProperty("state");
        try { r.bytes = Long.parseLong(p.getProperty("bytes", "-1")); }
        catch (NumberFormatException error) { throw new IOException("Invalid journal", error); }
        mod(r.path);
        if (r.bytes < 0 || r.hash == null || !r.hash.matches("[a-f0-9]{64}")
                || !Arrays.asList("copying", "prepared", "disabled", "enabled").contains(r.state)) {
            throw new IOException("Invalid journal");
        }
        return r;
    }

    private void write(Record r, String state) throws IOException {
        File directory = folder(r.id);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create vault entry");
        Properties p = new Properties();
        p.setProperty("path", r.path); p.setProperty("sha256", r.hash);
        p.setProperty("bytes", Long.toString(r.bytes)); p.setProperty("state", state);
        File temporary = File.createTempFile(".journal-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { p.store(output, "MelKit mod vault"); output.getFD().sync(); }
            if (!temporary.renameTo(child(r.id, "record.properties"))) throw new IOException("Cannot publish journal");
            r.state = state;
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    public Snapshot list() throws Exception {
        if (!gameRoot.isDirectory()) throw new IOException("Game folder unavailable");
        Snapshot snapshot = new Snapshot();
        File mods = new File(gameRoot, "Mods");
        if (mods.exists()) active(mods, "Mods", snapshot.items, 0);
        checkVault();
        if (vaultRoot.exists()) {
            File[] directories = vaultRoot.listFiles();
            if (directories == null) throw new IOException("Cannot read vault");
            if (directories.length > MAX_ITEMS) throw new IOException("Too many vault entries");
            for (File directory : directories) {
                try {
                    Record r = read(directory.getName());
                    if ("enabled".equals(r.state)) continue;
                    File content = child(r.id, "content.melmod");
                    if (!content.isFile() || content.length() != r.bytes) { snapshot.warnings++; continue; }
                    snapshot.items.add(new Item(r.id, r.path, "disabled".equals(r.state) && !mod(r.path).exists() ? "disabled" : "recovery",
                            r.bytes, content.lastModified()));
                } catch (Exception error) { snapshot.warnings++; }
            }
        }
        snapshot.items.sort(Comparator.comparing((Item i) -> i.id.isEmpty() ? 0 : 1)
                .thenComparing(i -> i.path.toLowerCase(Locale.ROOT)).thenComparing(i -> i.id));
        return snapshot;
    }

    private void active(File directory, String path, List<Item> items, int depth) throws Exception {
        if (depth > 32 || items.size() >= MAX_ITEMS) throw new IOException("Mod list too large");
        if (!directory.getAbsoluteFile().equals(directory.getCanonicalFile())) throw new SecurityException("Mod folder symlink");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot read mods");
        for (File file : children) {
            String relative = path + "/" + file.getName();
            if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new SecurityException("Mod symlink");
            if (file.isDirectory()) active(file, relative, items, depth + 1);
            else if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".melmod")) {
                mod(relative);
                if (items.size() >= MAX_ITEMS) throw new IOException("Mod list too large");
                items.add(new Item("", relative, "active", file.length(), file.lastModified()));
            }
        }
    }

    public String disable(String path, long expectedBytes, long expectedModified) throws Exception {
        File source = mod(path);
        if (!source.isFile() || source.length() != expectedBytes || source.lastModified() != expectedModified) throw new Changed();
        Record r = new Record();
        r.id = UUID.randomUUID().toString(); r.path = path; r.bytes = expectedBytes; r.hash = hash(source);
        checkVault();
        if (!vaultRoot.isDirectory() && !vaultRoot.mkdirs()) throw new IOException("Cannot create vault");
        // Keep headroom for the journal and a full verified copy. Zero means the query failed.
        if (expectedBytes > Long.MAX_VALUE - 1048576L || space.available(vaultRoot) < expectedBytes + 1048576L) throw new NoSpace();
        // Reuse the verified backup of an unchanged mod previously enabled from this vault.
        File[] old = vaultRoot.listFiles();
        if (old == null || old.length > MAX_ITEMS) throw new IOException("Cannot scan vault");
        for (File directory : old) {
            try {
                Record previous = read(directory.getName());
                if ("enabled".equals(previous.state) && previous.path.equals(path)
                        && previous.bytes == r.bytes && previous.hash.equals(r.hash)) {
                    verify(child(previous.id, "content.melmod"), previous);
                    r = previous; break;
                }
            } catch (Exception ignored) { /* Do not alter unreadable older records. */ }
        }
        if (!"enabled".equals(r.state)) {
            if (old.length >= MAX_ITEMS) throw new IOException("Vault entry limit reached");
            write(r, "copying");
            File temporary = child(r.id, "content.partial");
            if (!temporary.createNewFile()) throw new Conflict();
            try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int count; long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > r.bytes) throw new Changed();
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            verify(temporary, r);
            if (!temporary.renameTo(child(r.id, "content.melmod"))) throw new IOException("Cannot publish vault copy");
        }
        write(r, "prepared"); // A crash after this point leaves a discoverable, recoverable copy.
        verify(child(r.id, "content.melmod"), r);
        source = mod(path);
        if (source.lastModified() != expectedModified) throw new Changed();
        verify(source, r);
        if (!source.delete()) throw new Incomplete();
        if (source.exists()) throw new Incomplete();
        write(r, "disabled");
        return r.id;
    }

    public void enable(String id) throws Exception {
        Record r = read(id);
        File payload = child(id, "content.melmod");
        verify(payload, r);
        File target = mod(r.path);
        if (target.exists()) {
            // Idempotent recovery after a completed copy and an interrupted journal update.
            if (!target.isFile() || target.length() != r.bytes || !hash(target).equals(r.hash)) throw new Conflict();
        } else {
            File space = target.getParentFile();
            while (space != null && !space.exists()) space = space.getParentFile();
            if (space == null || r.bytes > Long.MAX_VALUE - 1048576L || this.space.available(space) < r.bytes + 1048576L) throw new NoSpace();
            try (InputStream input = new FileInputStream(payload)) {
                NewFileRestore.restore(gameRoot, r.path, input, r.bytes, r.hash);
            }
        }
        verify(mod(r.path), r);
        write(r, "enabled");
        // Keep the verified safety copy. Repeated toggles reuse it instead of creating duplicates.
    }

    public File previewFile(String id) throws Exception {
        read(id);
        File file = child(id, "content.melmod");
        if (!file.isFile()) throw new IOException("Vault copy unavailable");
        return file;
    }

    public void validateModPath(String path) throws IOException { mod(path); }

    /** Fingerprints are checked against fresh metadata before a batch touches a file. */
    public String fingerprint(Item item) throws Exception {
        if (!item.id.isEmpty()) {
            Record r = read(item.id);
            if (!r.path.equals(item.path)) throw new Changed();
            verify(child(item.id, "content.melmod"), r);
            return r.hash;
        }
        File file = mod(item.path);
        if (!file.isFile() || file.length() != item.bytes || file.lastModified() != item.modified) throw new Changed();
        String result = hash(file);
        if (file.length() != item.bytes || file.lastModified() != item.modified) throw new Changed();
        return result;
    }

    private static void verify(File file, Record r) throws Exception {
        if (!file.isFile() || file.length() != r.bytes || !hash(file).equals(r.hash)) throw new Changed();
    }
    private static String hash(File file) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : sha.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
