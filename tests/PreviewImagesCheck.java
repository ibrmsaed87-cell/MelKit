import com.spinel.melkit.storage.PreviewImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/** Actual ZIP/PNG/stream-limit checks; supplied game samples stay outside the repository. */
public final class PreviewImagesCheck {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("melkit-preview-checks-");
        try {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "PNG", output);
            byte[] png = output.toByteArray();
            expect(Arrays.equals(PreviewImages.validated(png), png));
            expect(PreviewImages.validated(null).length == 0);
            expect(PreviewImages.validated(new byte[0]).length == 0);
            expect(PreviewImages.validated(new byte[PreviewImages.MAX_IMAGE_BYTES + 1]).length == 0);
            byte[] bad = png.clone(); bad[0] = 0;
            expect(PreviewImages.validated(bad).length == 0);
            bad = png.clone(); bad[16] = (byte) 255;
            expect(PreviewImages.validated(bad).length == 0);
            bad = png.clone(); bad[16] = 0; bad[17] = 0; bad[18] = 0; bad[19] = 0;
            expect(PreviewImages.validated(bad).length == 0);
            bad = png.clone(); bad[16] = 0; bad[17] = 0; bad[18] = 16; bad[19] = 1;
            expect(PreviewImages.validated(bad).length == 0);
            try (var input = PreviewImages.limited(new ByteArrayInputStream(png), png.length)) {
                expect(Arrays.equals(input.readAllBytes(), png));
            }
            try (var input = PreviewImages.limited(new ByteArrayInputStream(png), png.length - 1)) {
                ioRejected(input::readAllBytes);
            }
            try (var input = PreviewImages.limited(new ByteArrayInputStream(new byte[] {1, 2}), 1)) {
                expect(input.read() == 1);
                ioRejected(input::read);
            }
            try (var input = PreviewImages.limited(new ByteArrayInputStream(png), png.length)) {
                expect(input.skip(Long.MAX_VALUE) == png.length);
                expect(input.read() == -1);
            }
            Path map = archive(temp.resolve("valid.melmap"), "MapPreview", png, false);
            byte[] before = Files.readAllBytes(map);
            expect(Arrays.equals(PreviewImages.readMap(map.toFile()), png));
            expect(Arrays.equals(Files.readAllBytes(map), before));
            Path missing = archive(temp.resolve("missing.melmap"), "Data", png, false);
            expect(PreviewImages.readMap(missing.toFile()).length == 0);
            Path traversal = archive(temp.resolve("traversal.melmap"), "../MapPreview", png, false);
            expect(PreviewImages.readMap(traversal.toFile()).length == 0);
            Path oversized = archive(temp.resolve("oversized.melmap"), "MapPreview",
                    new byte[PreviewImages.MAX_IMAGE_BYTES + 1], false);
            expect(PreviewImages.readMap(oversized.toFile()).length == 0);
            Path invalid = archive(temp.resolve("bad-image.melmap"), "MapPreview", new byte[] {1, 2, 3}, false);
            expect(PreviewImages.readMap(invalid.toFile()).length == 0);
            Path crcMap = archive(temp.resolve("bad-crc.melmap"), "MapPreview", png, true);
            byte[] zip = Files.readAllBytes(crcMap);
            // The stored PNG starts after the 30-byte local header and the filename.
            zip[30 + "MapPreview".length() + png.length - 1] ^= 1;
            Files.write(crcMap, zip);
            expect(PreviewImages.readMap(crcMap.toFile()).length == 0);
            File huge = temp.resolve("huge.melmap").toFile();
            try (var file = new RandomAccessFile(huge, "rw")) { file.setLength(64L * 1024 * 1024 + 1); }
            expect(PreviewImages.readMap(huge).length == 0);
            Path notZip = temp.resolve("not-zip.melmap"); Files.writeString(notZip, "unknown format");
            ioRejected(() -> PreviewImages.readMap(notZip.toFile()));
            if (args.length == 2) {
                byte[] sampleBefore = Files.readAllBytes(Path.of(args[0]));
                expect(Arrays.equals(PreviewImages.readMap(new File(args[0])), Files.readAllBytes(Path.of(args[1]))));
                expect(Arrays.equals(Files.readAllBytes(Path.of(args[0])), sampleBefore));
            }
            System.out.println("Preview image checks passed: " + checks);
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
            }
        }
    }

    private static Path archive(Path path, String name, byte[] bytes, boolean stored) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            ZipEntry entry = new ZipEntry(name);
            if (stored) {
                CRC32 crc = new CRC32(); crc.update(bytes);
                entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCrc(crc.getValue());
            }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
        }
        return path;
    }
    private interface Action { void run() throws Exception; }
    private static void ioRejected(Action action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected a bounded-read failure");
    }
    private static void expect(boolean value) {
        if (!value) throw new AssertionError("Unexpected preview behavior");
        checks++;
    }
}
