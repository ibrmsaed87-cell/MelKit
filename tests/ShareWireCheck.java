import com.spinel.melkit.share.ShareWire;
import com.spinel.melkit.share.ContentBundle;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ShareWireCheck {
    private static final String KEY = "12".repeat(32);
    private static int checks;

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static ShareWire.Entry stage(Path dir, String path, byte[] bytes) throws Exception {
        return ShareWire.stage(Files.createTempFile(dir, "send-", ".part").toFile(), path,
                new ByteArrayInputStream(bytes), bytes.length, ShareWire.MAX_FILE, new ShareWire.Control());
    }

    private static FutureTask<Void> sender(ServerSocket server, String id, List<ShareWire.Entry> entries,
            ShareWire.Control control, List<Long> progress) {
        FutureTask<Void> work = new FutureTask<>(() -> {
            ShareWire.send(server, id, KEY, entries, control, item -> progress.add(item.bytes), () -> {});
            return null;
        });
        Thread thread = new Thread(work, "share-check-sender");
        thread.setDaemon(true);
        thread.start();
        return work;
    }

    public static void main(String[] args) throws Exception {
        check(ShareWire.validPath("Worlds/Melones/عالم.melworld"), "Nested Arabic path");
        check(!ShareWire.validPath("Mods/../outside.melmod"), "Reject traversal");
        check(!ShareWire.validPath("Mods\\outside.melmod"), "Reject backslash");
        check(!ShareWire.validPath("Mods//outside.melmod"), "Reject empty segment");
        check(!ShareWire.validPath("Maps/wrong.melmod"), "Match extension to category");
        check(!ShareWire.validPath("Worlds/world.mcworld"), "Reject incompatible game files");
        check(ShareWire.pathKey("Mods/É.MELMOD").equals(ShareWire.pathKey("mods/e\u0301.melmod")), "Normalize duplicate paths");
        check(!ShareWire.validBatchId("../bad"), "Reject invalid session name");
        Path root = Files.createTempDirectory("melkit-share-check-");
        try {
            byte[] map = new byte[90_000], mod = new byte[80_000];
            new Random(5).nextBytes(map);
            new Random(7).nextBytes(mod);
            var entries = List.of(stage(root, "Maps/one.melmap", map), stage(root, "Mods/nested/اثنان.melmod", mod));
            File history = root.resolve("received").toFile();
            String id = UUID.randomUUID().toString();
            List<Long> sent = new ArrayList<>(), received = new ArrayList<>();
            try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                 ShareWire.Control control = new ShareWire.Control()) {
                var work = sender(server, id, entries, control, sent);
                try (Socket foreign = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                    foreign.setSoTimeout(2000);
                    DataOutputStream output = new DataOutputStream(foreign.getOutputStream());
                    output.writeUTF("MELKIT-SHARE-1");
                    output.writeUTF(id);
                    output.writeUTF("ab".repeat(32));
                    output.flush();
                    check(foreign.getInputStream().read() == -1, "Unauthorized peer receives no metadata");
                }
                try (Socket peer = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                    List<ShareWire.Entry> batch = ShareWire.receive(peer, id, KEY, history, new ShareWire.Control(),
                            item -> received.add(item.bytes), () -> {});
                    check(batch.size() == 2, "Two categories published in one batch");
                    check(Arrays.equals(Files.readAllBytes(batch.get(0).file.toPath()), map), "Map bytes preserved");
                    check(Arrays.equals(Files.readAllBytes(batch.get(1).file.toPath()), mod), "Nested mod bytes preserved");
                }
                work.get(4, TimeUnit.SECONDS);
            }
            check(sent.get(sent.size() - 1) == map.length + mod.length && received.get(received.size() - 1) == map.length + mod.length,
                    "Both phones report full bytes after receipt");
            check(!new File(history, "." + id + ".part").exists(), "No partial directory after success");
            try {
                ShareWire.receive(new Socket(), id, KEY, history, new ShareWire.Control(), item -> {}, () -> {});
                throw new AssertionError("Session replay accepted");
            } catch (IOException expected) {
                check(expected.getMessage().equals("SESSION_EXISTS"), "Existing received batch is preserved");
            }
            String second = UUID.randomUUID().toString();
            try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                 ShareWire.Control control = new ShareWire.Control();
                 Socket peer = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                var work = sender(server, second, List.of(entries.get(1)), control, new ArrayList<>());
                var batch = ShareWire.receive(peer, second, KEY, history, new ShareWire.Control(), item -> {}, () -> {});
                check(batch.size() == 1 && batch.get(0).file.getPath().contains(second), "Next batch has its own history");
                work.get(4, TimeUnit.SECONDS);
            }
            check(new File(history, id).isDirectory() && new File(history, second).isDirectory(),
                    "Consecutive successful sessions retain both batches");
            String next = UUID.randomUUID().toString();
            Files.write(entries.get(1).file.toPath(), new byte[mod.length]);
            try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                 ShareWire.Control control = new ShareWire.Control();
                 Socket peer = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                var work = sender(server, next, entries, control, new ArrayList<>());
                try {
                    ShareWire.receive(peer, next, KEY, history, new ShareWire.Control(), item -> {}, () -> {});
                    throw new AssertionError("Wrong checksum accepted");
                } catch (IOException expected) {
                    check(expected.getMessage().equals("CHECKSUM"), "Second file checksum failure");
                }
                try {
                    work.get(4, TimeUnit.SECONDS);
                    throw new AssertionError("Sender completed without committed receipt");
                } catch (ExecutionException expected) { checks++; }
            }
            check(!new File(history, next).exists() && !new File(history, "." + next + ".part").exists(),
                    "Failed batch rolls back even after a valid first file");
            check(new File(history, id).isDirectory(), "Failed next session preserves prior history");
            String cancelled = UUID.randomUUID().toString();
            try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                 ShareWire.Control control = new ShareWire.Control();
                 ShareWire.Control receiveControl = new ShareWire.Control();
                 Socket peer = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                var work = sender(server, cancelled, List.of(entries.get(0)), control, new ArrayList<>());
                try {
                    ShareWire.receive(peer, cancelled, KEY, history, receiveControl, item -> receiveControl.close(), () -> {});
                    throw new AssertionError("Cancellation ignored");
                } catch (InterruptedIOException expected) { checks++; }
                try { work.get(4, TimeUnit.SECONDS); throw new AssertionError("Cancelled batch acknowledged"); }
                catch (ExecutionException expected) { checks++; }
            }
            check(!new File(history, cancelled).exists() && !new File(history, "." + cancelled + ".part").exists(),
                    "Cancellation leaves no visible files or partial batch");

            String archivePath = "Bundles/حزمة.zip";
            check(ShareWire.validBundlePath(archivePath) && !ShareWire.validPath(archivePath),
                    "A bundle travels as a distinct payload, not as a game file");
            check(!ShareWire.validBundlePath("Bundles/../outside.zip") &&
                    !ShareWire.validBundlePath("Bundles/nested/bundle.zip") &&
                    !ShareWire.validBundlePath("Saves/ordinary.zip"), "Reject unexpected ZIP paths");
            Path archive = root.resolve("original.zip");
            ContentBundle.write(archive.toFile(), List.of(
                    new ContentBundle.Source("Maps/one.melmap", map.length, () -> new ByteArrayInputStream(map), () -> {}),
                    new ContentBundle.Source("Mods/nested/اثنان.melmod", mod.length, () -> new ByteArrayInputStream(mod), () -> {})));
            ShareWire.Entry bundle;
            try (InputStream input = Files.newInputStream(archive)) {
                bundle = ShareWire.stage(root.resolve("bundle.part").toFile(), archivePath, input,
                        Files.size(archive), ContentBundle.MAX_ARCHIVE, new ShareWire.Control());
            }
            String bundleId = UUID.randomUUID().toString();
            try {
                ShareWire.send(null, bundleId, KEY, List.of(bundle, entries.get(0)), new ShareWire.Control(),
                        item -> {}, () -> {});
                throw new AssertionError("Mixed archive and game-file transfer accepted");
            } catch (IOException expected) {
                check(expected.getMessage().equals("INVALID_FILE"), "Send the ZIP as one item");
            }
            try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                 ShareWire.Control control = new ShareWire.Control();
                 Socket peer = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                var work = sender(server, bundleId, List.of(bundle), control, new ArrayList<>());
                var batch = ShareWire.receive(peer, bundleId, KEY, history, new ShareWire.Control(), item -> {}, () -> {});
                check(batch.size() == 1 && batch.get(0).path.equals(archivePath), "Receive one ZIP with its name");
                check(Files.mismatch(archive, batch.get(0).file.toPath()) == -1, "Received ZIP is byte-identical");
                Path restored = root.resolve("bundle-contents");
                check(ContentBundle.unpack(batch.get(0).file, restored.toFile()) == 2, "Received bundle opens inside MelKit");
                check(Arrays.equals(Files.readAllBytes(restored.resolve("Mods/nested/اثنان.melmod")), mod),
                        "Received contents preserve paths and file bytes");
                work.get(4, TimeUnit.SECONDS);
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("Share wire checks: " + checks + " passed");
    }
}
