package com.spinel.melkit.storage;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Checks known game containers and keeps the evidence needed for an explicit quarantine. */
public final class FileScan {
    private static final int MAX_FILES = 5000;
    private static final int MAX_NODES = 10000;
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final long MAX_ZIP_BYTES = 64L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
    private static final long MAX_HASH_BYTES = 512L * 1024 * 1024;
    private final File root;
    private final ContentPaths paths;
    private final JsonCheck jsonCheck;

    public interface JsonCheck { boolean valid(byte[] bytes, int start); }

    public static final class Finding {
        public final String path, issue, reference;
        public final long bytes;
        public final boolean selectable, retained;
        private final long modified;
        private final String hash;

        private Finding(Candidate item) {
            path = item.path;
            issue = item.issue.equals("ok") ? "duplicate" : item.issue;
            reference = item.reference;
            bytes = item.bytes;
            modified = item.modified;
            hash = item.hash;
            retained = item.retained;
            selectable = !retained && hash != null && (issue.equals("empty") || issue.equals("invalid") || !reference.isEmpty());
        }
    }

    public static final class Result {
        public final List<Finding> findings = new ArrayList<>();
        public int files;
        public boolean partial;
    }

    private static final class Candidate {
        final String path;
        final long bytes, modified;
        String issue, hash, reference = "";
        boolean retained;

        Candidate(String path, long bytes, long modified, String issue) {
            this.path = path;
            this.bytes = bytes;
            this.modified = modified;
            this.issue = issue;
        }

        String sizeKey() { return path.substring(path.lastIndexOf('.')).toLowerCase(Locale.ROOT) + ":" + bytes; }
    }

    public FileScan(File gameRoot, JsonCheck jsonCheck) throws IOException {
        root = gameRoot.getCanonicalFile();
        paths = new ContentPaths(root);
        this.jsonCheck = jsonCheck;
    }

    private File scoped(String path) throws IOException {
        File file = paths.resolve(path, false);
        if (!file.equals(new File(root, path).getAbsoluteFile())) throw new SecurityException("Content symlink");
        return file;
    }

    public Result run() throws Exception {
        if (!root.isDirectory()) throw new FileNotFoundException("Game folder unavailable");
        File[] folders = root.listFiles(file -> ContentPaths.isContentFolder(file.getName()));
        if (folders == null) throw new IOException("Cannot list game folders");
        Result result = new Result();
        List<Candidate> candidates = new ArrayList<>();
        int[] nodes = {0};
        Arrays.sort(folders, Comparator.comparing(File::getName));
        for (File folder : folders) collect(folder.getName(), 0, nodes, candidates, result);
        candidates.sort(Comparator.comparing(item -> item.path));
        Map<String, Integer> sizes = new HashMap<>();
        for (Candidate item : candidates) if (item.issue == null && item.bytes > 0) {
            sizes.merge(item.sizeKey(), 1, Integer::sum);
        }
        Map<String, Candidate> originals = new HashMap<>();
        for (Candidate item : candidates) {
            if (item.issue == null) inspect(item, sizes);
            if (item.hash == null || item.bytes == 0) continue;
            String key = item.sizeKey() + ":" + item.hash;
            Candidate original = originals.putIfAbsent(key, item);
            if (original != null) {
                original.retained = true;
                item.reference = original.path;
            }
        }
        for (Candidate item : candidates) {
            if (!item.issue.equals("ok") || !item.reference.isEmpty()) result.findings.add(new Finding(item));
        }
        return result;
    }

    private void collect(String path, int depth, int[] nodes, List<Candidate> items, Result result) {
        if (depth > 32 || nodes[0] >= MAX_NODES || result.files >= MAX_FILES) {
            result.partial = true;
            return;
        }
        nodes[0]++;
        if (path.equalsIgnoreCase("Worlds/Progress")) return;
        try {
            File file = scoped(path);
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) throw new IOException("Cannot list content folder");
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    if (nodes[0] >= MAX_NODES || result.files >= MAX_FILES) {
                        result.partial = true;
                        break;
                    }
                    if (child.getName().startsWith(".melkit-restore-") && child.getName().endsWith(".tmp")) continue;
                    collect(path + "/" + child.getName(), depth + 1, nodes, items, result);
                }
            } else if (file.isFile()) {
                result.files++;
                String issue = NewFileRestore.supported(path) ? null : "unsupported";
                items.add(new Candidate(path, file.length(), file.lastModified(), issue));
            } else {
                throw new IOException("Content entry unavailable");
            }
        } catch (IOException | SecurityException error) {
            result.partial = true;
            items.add(new Candidate(path, -1, -1, "unreadable"));
        }
    }

    private void inspect(Candidate item, Map<String, Integer> sizes) {
        try {
            File file = scoped(item.path);
            requireUnchanged(file, item);
            if (item.bytes == 0) {
                item.issue = "empty";
                item.hash = ModVault.hash(file, item.bytes);
            } else if (item.bytes > MAX_HASH_BYTES) {
                item.issue = "limit";
            } else {
                String type = item.path.substring(item.path.lastIndexOf('.')).toLowerCase(Locale.ROOT);
                item.issue = type.equals(".melmap") || type.equals(".melworld") ? inspectZip(file) : inspectJson(file, item.bytes);
                if (item.issue.equals("changed")) throw new ModVault.Changed();
                if (item.issue.equals("invalid") || sizes.getOrDefault(item.sizeKey(), 0) > 1) {
                    item.hash = ModVault.hash(file, item.bytes);
                }
            }
            requireUnchanged(scoped(item.path), item);
        } catch (ModVault.Changed error) {
            item.issue = "changed";
            item.hash = null;
        } catch (Exception error) {
            item.issue = "unreadable";
            item.hash = null;
        }
    }

    private void requireUnchanged(File file, Candidate item) throws ModVault.Changed {
        if (!file.isFile() || file.length() != item.bytes || file.lastModified() != item.modified) throw new ModVault.Changed();
    }

    /** Same conservative container validation used by scanning; never modifies the source. */
    public String inspectContent(String path) throws IOException {
        File file = scoped(path);
        if (!NewFileRestore.supported(path)) return "unsupported";
        if (!file.isFile()) return "unreadable";
        if (file.length() == 0) return "empty";
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".melmod") || lower.endsWith(".melsave")
                ? inspectJson(file, file.length()) : inspectZip(file);
    }

    private String inspectJson(File file, long size) throws IOException {
        if (size > MAX_JSON_BYTES) return "limit";
        byte[] bytes = new byte[(int) size];
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            input.readFully(bytes);
            if (input.read() != -1) return "changed";
        }
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191) start = 3;
        while (start < bytes.length && (bytes[start] == ' ' || bytes[start] == '\t' || bytes[start] == '\r' || bytes[start] == '\n')) start++;
        if (start >= bytes.length || bytes[start] != '{') return "unsupported";
        return jsonCheck.valid(bytes, start) ? "ok" : "invalid";
    }

    private String inspectZip(File file) throws IOException {
        if (file.length() > MAX_ZIP_BYTES) return "limit";
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (file.length() < 4) return "unsupported";
            int signature = input.readInt();
            if (signature != 0x504b0304 && signature != 0x504b0506) return "unsupported";
        }
        try (ZipFile archive = new ZipFile(file)) {
            if (archive.size() > 4096) return "limit";
            byte[] buffer = new byte[65536];
            long expanded = 0;
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) return "unsupported";
                if (entry.getSize() > MAX_EXPANDED_BYTES - expanded) return "limit";
                CRC32 crc = new CRC32();
                long size = 0;
                try (InputStream input = archive.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        expanded += count;
                        size += count;
                        if (expanded > MAX_EXPANDED_BYTES) return "limit";
                        crc.update(buffer, 0, count);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) return "invalid";
            }
            return "ok";
        } catch (ZipException error) {
            String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("unsupported") || message.contains("encrypt") || message.contains("compression method")
                    || message.contains("multi-disk") || message.contains("split") || message.contains("entry name")) return "unsupported";
            return "invalid";
        } catch (EOFException error) {
            return "invalid";
        }
    }

    public void quarantine(Finding finding, ModVault vault) throws Exception {
        if (!finding.selectable) throw new SecurityException("Finding cannot be quarantined");
        if (!finding.reference.isEmpty()) {
            File reference = scoped(finding.reference);
            if (!reference.isFile() || reference.length() != finding.bytes
                    || !ModVault.hash(reference, finding.bytes).equals(finding.hash)) throw new ModVault.Changed();
        }
        vault.disable(finding.path, finding.bytes, finding.modified, finding.hash);
    }
}
