package com.spinel.melkit.share;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** MelKit ZIP bundles preserve game paths and verify all entries before import. */
public final class ContentBundle {
    private static final String MANIFEST = "MelKit.bundle";
    private static final String VERSION = "MELKIT-BUNDLE-1";
    public static final long MAX_ARCHIVE = ShareWire.MAX_TOTAL + 64L * 1024 * 1024;

    private ContentBundle() {}

    public interface Open { InputStream input() throws Exception; }
    public interface Verify { void source() throws Exception; }

    public static final class Source {
        private final String path;
        private final long size;
        private final Open open;
        private final Verify verify;

        public Source(String path, long size, Open open, Verify verify) {
            this.path = path;
            this.size = size;
            this.open = open;
            this.verify = verify;
        }
    }

    private static final class Entry {
        private final String path;
        private final long size;
        private final byte[] hash;

        private Entry(String path, long size, byte[] hash) {
            this.path = path;
            this.size = size;
            this.hash = hash;
        }
    }

    public static void write(File archive, List<Source> sources) throws Exception {
        require(!sources.isEmpty() && sources.size() <= ShareWire.MAX_FILES, "INVALID_FILE");
        List<Entry> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (Source source : sources) {
                long limit = Math.min(ShareWire.MAX_FILE, ShareWire.MAX_TOTAL - total);
                require(ShareWire.validPath(source.path) && paths.add(ShareWire.pathKey(source.path)) &&
                        (source.size == -1 || source.size > 0 && source.size <= limit), "INVALID_FILE");
                zip.putNextEntry(new ZipEntry(source.path));
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size;
                try (InputStream input = source.open.input()) {
                    size = copy(input, zip, limit, digest);
                }
                require(size > 0 && (source.size == -1 || size == source.size), "FILE_CHANGED");
                source.verify.source();
                zip.closeEntry();
                total += size;
                entries.add(new Entry(source.path, size, digest.digest()));
            }
            zip.putNextEntry(new ZipEntry(MANIFEST));
            DataOutputStream index = new DataOutputStream(zip);
            index.writeUTF(VERSION);
            index.writeInt(entries.size());
            for (Entry entry : entries) {
                index.writeUTF(entry.path);
                index.writeLong(entry.size);
                index.write(entry.hash);
            }
            zip.closeEntry();
        }
        read(archive, null);
    }

    public static int unpack(File archive, File directory) throws Exception {
        require(directory.mkdir(), "SAVE_FAILED");
        boolean complete = false;
        try {
            int count = read(archive, directory);
            complete = true;
            return count;
        } finally {
            if (!complete) ShareWire.deleteTree(directory);
        }
    }

    private static int read(File archive, File directory) throws Exception {
        require(archive.length() <= MAX_ARCHIVE, "INVALID_BUNDLE");
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry index = zip.getEntry(MANIFEST);
            require(index != null && index.getSize() > 0 && index.getSize() <= 2 * 1024 * 1024,
                    "INVALID_BUNDLE");
            List<Entry> entries = new ArrayList<>();
            Set<String> paths = new HashSet<>();
            long total = 0;
            try (DataInputStream manifest = new DataInputStream(zip.getInputStream(index))) {
                require(VERSION.equals(manifest.readUTF()), "INVALID_BUNDLE");
                int count = manifest.readInt();
                require(count > 0 && count <= ShareWire.MAX_FILES && zip.size() == count + 1, "INVALID_BUNDLE");
                for (int i = 0; i < count; i++) {
                    String path = manifest.readUTF();
                    long size = manifest.readLong();
                    byte[] hash = new byte[32];
                    manifest.readFully(hash);
                    require(ShareWire.validPath(path) && paths.add(ShareWire.pathKey(path)) &&
                            size > 0 && size <= ShareWire.MAX_FILE, "INVALID_BUNDLE");
                    ZipEntry file = zip.getEntry(path);
                    require(file != null && !file.isDirectory() && file.getSize() == size, "INVALID_BUNDLE");
                    total += size;
                    entries.add(new Entry(path, size, hash));
                }
                require(manifest.read() == -1 && total <= ShareWire.MAX_TOTAL, "INVALID_BUNDLE");
            }
            if (directory != null) require(directory.getUsableSpace() >= total, "SPACE");
            for (Entry entry : entries) {
                try (InputStream input = zip.getInputStream(zip.getEntry(entry.path))) {
                    if (directory == null) {
                        verify(input, null, entry);
                    } else {
                        File file = new File(directory, entry.path);
                        File parent = file.getParentFile();
                        require((parent.isDirectory() || parent.mkdirs()) && file.createNewFile(), "INVALID_BUNDLE");
                        try (FileOutputStream output = new FileOutputStream(file)) {
                            verify(input, output, entry);
                            output.getFD().sync();
                        }
                    }
                }
            }
            return entries.size();
        }
    }

    private static void verify(InputStream input, OutputStream output, Entry entry) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = copy(input, output, entry.size, digest);
        require(count == entry.size && MessageDigest.isEqual(entry.hash, digest.digest()), "CHECKSUM");
    }

    static long copy(InputStream input, OutputStream output, long limit, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[65536];
        long size = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CANCELLED");
            size += count;
            require(size <= limit, "LIMIT");
            if (digest != null) digest.update(buffer, 0, count);
            if (output != null) output.write(buffer, 0, count);
        }
        return size;
    }

    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }
}
