package com.spinel.melkit.storage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Bounded reads of embedded PNG previews. Never extracts archive paths to disk. */
public final class PreviewImages {
    // The verified world preview is 320,569 bytes; stay below Binder's transaction limit.
    public static final int MAX_IMAGE_BYTES = 512 * 1024;
    public static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private PreviewImages() {}

    public static byte[] validated(byte[] bytes) {
        if (bytes == null || bytes.length < 33 || bytes.length > MAX_IMAGE_BYTES) return new byte[0];
        for (int i = 0; i < PNG.length; i++) if (bytes[i] != PNG[i]) return new byte[0];
        if (integer(bytes, 8) != 13 || bytes[12] != 'I' || bytes[13] != 'H'
                || bytes[14] != 'D' || bytes[15] != 'R') return new byte[0];
        int width = integer(bytes, 16);
        int height = integer(bytes, 20);
        if (width < 1 || height < 1 || width > 4096 || height > 4096) return new byte[0];
        return bytes;
    }

    private static int integer(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    public static byte[] readMap(File file) throws IOException {
        return readArchive(file, "MapPreview");
    }

    public static byte[] readWorld(File file) throws IOException {
        return readArchive(file, "WorldPreviewImage");
    }

    public static byte[] readStream(InputStream source, boolean world) throws IOException {
        String name = world ? "WorldPreviewImage" : "MapPreview";
        try (ZipInputStream zip = new ZipInputStream(limited(source, MAX_ARCHIVE_BYTES))) {
            byte[] buffer = new byte[8192];
            long scanned = 0;
            int entries = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 4096) return new byte[0];
                boolean preview = entry.getName().equals(name) && !entry.isDirectory();
                if (preview && entry.getSize() > MAX_IMAGE_BYTES) return new byte[0];
                ByteArrayOutputStream output = preview ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    scanned += count;
                    if (scanned > MAX_JSON_BYTES) return new byte[0];
                    if (output != null) {
                        if (output.size() + count > MAX_IMAGE_BYTES) return new byte[0];
                        output.write(buffer, 0, count);
                    }
                }
                if (output != null) return validated(output.toByteArray());
            }
            return new byte[0];
        }
    }

    private static byte[] readArchive(File file, String previewName) throws IOException {
        if (file.length() > MAX_ARCHIVE_BYTES) return new byte[0];
        try (ZipFile archive = new ZipFile(file)) {
            if (archive.size() > 4096) return new byte[0];
            ZipEntry entry = archive.getEntry(previewName);
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_IMAGE_BYTES) return new byte[0];
            try (InputStream input = limited(archive.getInputStream(entry), MAX_IMAGE_BYTES)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                byte[] bytes = output.toByteArray();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc()) return new byte[0];
                return validated(bytes);
            }
        }
    }

    public static InputStream limited(InputStream input, long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative read limit");
        return new FilterInputStream(input) {
            private long remaining = limit;
            @Override public int read() throws IOException {
                int value = in.read();
                if (value >= 0 && --remaining < 0) throw new IOException("Preview read limit exceeded");
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
                if (count > 0 && (remaining -= count) < 0) throw new IOException("Preview read limit exceeded");
                return count;
            }
            @Override public long skip(long count) throws IOException {
                long skipped = in.skip(Math.min(Math.max(count, 0), remaining));
                remaining -= skipped;
                return skipped;
            }
            @Override public boolean markSupported() { return false; }
            @Override public void reset() throws IOException { throw new IOException("No preview reset"); }
        };
    }
}
