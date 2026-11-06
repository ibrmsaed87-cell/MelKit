package com.spinel.melkit.share;

import com.spinel.melkit.storage.NewFileRestore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Batch protocol adapted from MCBK. A receipt follows publication of the verified batch. */
public final class ShareWire {
    public static final int MAX_FILES = 100;
    public static final long MAX_FILE = 2L * 1024 * 1024 * 1024;
    public static final long MAX_TOTAL = 8L * 1024 * 1024 * 1024;
    private static final String MAGIC = "MELKIT-SHARE-1";
    private static final long HEADROOM = 16L * 1024 * 1024;

    private ShareWire() {}

    public static final class Entry {
        public final File file;
        public final String path, hash;
        public final long size;
        private Entry(File file, String path, String hash, long size) {
            this.file = file;
            this.path = path;
            this.hash = hash;
            this.size = size;
        }
    }

    public static final class Progress {
        public final String path;
        public final int index, count;
        public final long bytes, total;
        Progress(String path, int index, int count, long bytes, long total) {
            this.path = path;
            this.index = index;
            this.count = count;
            this.bytes = bytes;
            this.total = total;
        }
    }

    public static final class Control implements Closeable {
        private final List<Closeable> tracked = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        public volatile long lastActivity = System.nanoTime();
        public void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CANCELLED");
        }
        public void touch() throws InterruptedIOException {
            check();
            lastActivity = System.nanoTime();
        }
        public <T extends Closeable> T track(T value) throws IOException {
            tracked.add(value);
            if (cancelled) {
                untrack(value);
                value.close();
                check();
            }
            return value;
        }
        public void untrack(Closeable value) { tracked.remove(value); }
        @Override public void close() {
            cancelled = true;
            for (Closeable value : tracked) {
                try { value.close(); } catch (IOException ignored) {}
            }
            tracked.clear();
        }
    }

    public static boolean validPath(String path) {
        if (path == null || !NewFileRestore.supported(path) || path.length() > 4096 || path.indexOf('\\') >= 0) return false;
        String[] segments = path.split("/", -1);
        if (segments.length > 33) return false;
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
                    !segment.equals(segment.trim()) || segment.getBytes(StandardCharsets.UTF_8).length > 255 ||
                    segment.chars().anyMatch(c -> Character.isISOControl(c) || c == ':')) return false;
        }
        return true;
    }

    public static String pathKey(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    public static boolean validBatchId(String id) {
        return id != null && id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    }

    private static boolean validKey(String key) { return key != null && key.matches("[a-f0-9]{64}"); }

    public static Entry stage(File file, String path, InputStream source, long expected, long limit, Control control)
            throws Exception {
        require(validPath(path) && limit > 0 && limit <= MAX_FILE && expected <= limit, "INVALID_FILE");
        require(file.getParentFile().getUsableSpace() >= HEADROOM + Math.max(0, expected), "SPACE");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        long size = 0;
        try (FileOutputStream output = new FileOutputStream(file)) {
            for (int count; (count = source.read(buffer)) != -1;) {
                control.touch();
                size += count;
                require(size <= limit, "INVALID_FILE");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            require(size > 0 && (expected < 0 || size == expected), "FILE_CHANGED");
            output.getFD().sync();
        }
        return new Entry(file, path, hex(digest.digest()), size);
    }

    public static void send(ServerSocket server, String id, String key, List<Entry> files, Control control,
            Consumer<Progress> progress, Runnable verifying) throws Exception {
        require(validBatchId(id) && validKey(key) && files.size() > 0 && files.size() <= MAX_FILES, "INVALID_FILE");
        long total = 0;
        Set<String> paths = new HashSet<>();
        for (Entry entry : files) {
            require(validPath(entry.path) && paths.add(pathKey(entry.path)) && entry.size > 0 &&
                    entry.size <= MAX_FILE && validKey(entry.hash), "INVALID_FILE");
            total += entry.size;
        }
        require(total <= MAX_TOTAL, "INVALID_FILE");
        server.setSoTimeout(1000);
        long deadline = System.nanoTime() + 600_000_000_000L;
        while (System.nanoTime() < deadline) {
            control.check();
            Socket peer;
            try { peer = server.accept(); } catch (SocketTimeoutException ignored) { continue; }
            try (Socket socket = control.track(peer)) {
                socket.setSoTimeout(10000);
                socket.setTcpNoDelay(true);
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                try {
                    if (!MAGIC.equals(input.readUTF()) || !id.equals(input.readUTF()) ||
                            !equal(key, input.readUTF())) continue;
                } catch (IOException ignored) { continue; }
                socket.setSoTimeout(45000);
                control.touch();
                output.writeUTF(MAGIC);
                output.writeInt(files.size());
                output.writeLong(total);
                for (Entry entry : files) {
                    output.writeUTF(entry.path);
                    output.writeLong(entry.size);
                    output.writeUTF(entry.hash);
                }
                output.flush();
                require("READY".equals(input.readUTF()), "RECEIVER_DECLINED");
                long done = 0;
                byte[] buffer = new byte[65536];
                for (int index = 0; index < files.size(); index++) {
                    Entry entry = files.get(index);
                    try (InputStream source = new BufferedInputStream(new FileInputStream(entry.file))) {
                        for (long remaining = entry.size; remaining > 0;) {
                            control.check();
                            int count = source.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                            require(count > 0, "FILE_CHANGED");
                            output.write(buffer, 0, count);
                            done += count;
                            remaining -= count;
                            control.touch();
                            progress.accept(new Progress(entry.path, index + 1, files.size(), done, total));
                        }
                        require(source.read() == -1, "FILE_CHANGED");
                    }
                    output.flush();
                    require("VERIFIED".equals(input.readUTF()), "CHECKSUM");
                    control.touch();
                }
                verifying.run();
                require("COMMITTED".equals(input.readUTF()), "RECEIPT_MISSING");
                return;
            } finally { control.untrack(peer); }
        }
        throw new SocketTimeoutException("TIMEOUT");
    }

    public static List<Entry> receive(Socket peer, String id, String key, File directory, Control control,
            Consumer<Progress> progress, Runnable verifying) throws Exception {
        require(validBatchId(id) && validKey(key), "BAD_QR");
        File staging = new File(directory, "." + id + ".part");
        File destination = new File(directory, id);
        boolean created = false, committed = false;
        try (Socket socket = control.track(peer)) {
            require(!destination.exists(), "SESSION_EXISTS");
            require(directory.isDirectory() || directory.mkdirs(), "SAVE_FAILED");
            require(staging.mkdir(), "SESSION_EXISTS");
            created = true;
            socket.setSoTimeout(45000);
            socket.setTcpNoDelay(true);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            output.writeUTF(MAGIC);
            output.writeUTF(id);
            output.writeUTF(key);
            output.flush();
            require(MAGIC.equals(input.readUTF()), "INCOMPATIBLE");
            int count = input.readInt();
            long total = input.readLong(), declared = 0;
            require(count > 0 && count <= MAX_FILES && total > 0 && total <= MAX_TOTAL, "INVALID_FILE");
            List<Entry> headers = new ArrayList<>();
            Set<String> paths = new HashSet<>();
            for (int index = 0; index < count; index++) {
                String path = input.readUTF();
                long size = input.readLong();
                String hash = input.readUTF();
                require(validPath(path) && paths.add(pathKey(path)) && size > 0 && size <= MAX_FILE && validKey(hash), "INVALID_FILE");
                headers.add(new Entry(new File(destination, path), path, hash, size));
                declared += size;
            }
            require(declared == total, "INVALID_FILE");
            require(directory.getUsableSpace() >= total + HEADROOM, "SPACE");
            output.writeUTF("READY");
            output.flush();
            byte[] buffer = new byte[65536];
            long done = 0;
            for (int index = 0; index < count; index++) {
                Entry entry = headers.get(index);
                File target = new File(staging, entry.path);
                File parent = target.getParentFile();
                require(parent.isDirectory() || parent.mkdirs(), "SAVE_FAILED");
                require(target.createNewFile(), "INVALID_FILE");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileOutputStream sink = new FileOutputStream(target)) {
                    for (long remaining = entry.size; remaining > 0;) {
                        control.check();
                        int size = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                        if (size <= 0) throw new EOFException("CONNECTION_LOST");
                        sink.write(buffer, 0, size);
                        digest.update(buffer, 0, size);
                        done += size;
                        remaining -= size;
                        control.touch();
                        progress.accept(new Progress(entry.path, index + 1, count, done, total));
                    }
                    sink.getFD().sync();
                }
                verifying.run();
                require(equal(entry.hash, hex(digest.digest())), "CHECKSUM");
                output.writeUTF("VERIFIED");
                output.flush();
                control.touch();
            }
            control.check();
            require(!destination.exists() && staging.renameTo(destination), "SAVE_FAILED");
            committed = true;
            try {
                output.writeUTF("COMMITTED");
                output.flush();
            } catch (IOException ignored) {
                // The verified batch is already available even if the receipt cannot reach the sender.
            }
            return headers;
        } finally {
            if (created && !committed) deleteTree(staging);
            control.untrack(peer);
        }
    }

    static void deleteTree(File root) {
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        root.delete();
    }

    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >>> 4) & 15, 16));
            out.append(Character.forDigit(value & 15, 16));
        }
        return out.toString();
    }

    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }
}
