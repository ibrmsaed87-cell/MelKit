import com.spinel.melkit.share.BatchRestore;
import com.spinel.melkit.share.ShareWire;
import com.spinel.melkit.storage.NewFileRestore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BatchRestoreCheck {
    private static int checks;
    private static final List<String> PATHS = Arrays.asList("Maps/خريطة.melmap", "Mods/Blue Android.melmod", "Saves/test.melsave");
    private interface Action { void run() throws Exception; }
    private static void ok(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private static void rejects(Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); } catch (Exception error) {
            if (type.isInstance(error)) { checks++; return; }
            throw error;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        rejects(IllegalArgumentException.class, () -> new BatchRestore(Collections.emptyList()));
        rejects(IllegalArgumentException.class, () -> new BatchRestore(null));
        for (String bad : Arrays.asList("Mods/../bad.melmod", "Mods/file.zip", "Preferences/private.melmod")) {
            rejects(IllegalArgumentException.class, () -> new BatchRestore(Arrays.asList(PATHS.get(0), bad)));
        }
        rejects(IllegalArgumentException.class, () -> new BatchRestore(Arrays.asList("Mods/a.melmod", "Mods/A.melmod")));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= ShareWire.MAX_FILES; i++) tooMany.add("Mods/" + i + ".melmod");
        rejects(IllegalArgumentException.class, () -> new BatchRestore(tooMany));
        tooMany.remove(tooMany.size() - 1);
        ok(new BatchRestore(tooMany).snapshot().total == ShareWire.MAX_FILES, "Full allowed bundle fits the plan");

        List<String> selection = new ArrayList<>(PATHS);
        BatchRestore plan = new BatchRestore(selection);
        selection.clear();
        BatchRestore.Snapshot before = plan.snapshot();
        ok(before.total == 3 && before.completed == 0 && before.notAttempted == 0 && !before.finished,
                "The plan captures its selection and never claims pending files failed");
        rejects(UnsupportedOperationException.class, () -> before.entries.clear());
        rejects(IllegalStateException.class, () -> plan.complete("ok"));
        ok(plan.next().equals(PATHS.get(0)), "Restores in the captured order");
        ok(plan.snapshot().completed == 0 && plan.snapshot().entries.get(0).outcome == BatchRestore.Outcome.RUNNING,
                "A running file is not counted as restored");
        rejects(IllegalStateException.class, plan::next);
        plan.complete("ok");
        ok(before.completed == 0 && plan.snapshot().restored == 1, "Progress snapshots are immutable");
        plan.next(); plan.complete("exists");
        plan.next(); plan.complete("changed");
        BatchRestore.Snapshot mixed = plan.snapshot();
        ok(mixed.finished && mixed.completed == 3 && mixed.restored == 1 && mixed.skipped == 1 && mixed.failed == 1,
                "Only confirmed ok and exists responses count as restored or skipped");
        ok(mixed.notAttempted == 0 && plan.next() == null, "Completed plans cannot replay a restore");
        rejects(IllegalStateException.class, () -> plan.complete("ok"));
        plan.requestStop();
        ok(!plan.snapshot().stopRequested, "Stopping a finished plan does not rewrite its result");

        for (String fatal : Arrays.asList("connect", "busy", "recovery", "partial", "unconfirmed")) {
            BatchRestore stopped = new BatchRestore(PATHS);
            stopped.next(); stopped.complete(fatal);
            BatchRestore.Snapshot state = stopped.snapshot();
            ok(state.finished && state.failed == 1 && state.completed == 1 && state.notAttempted == 2
                    && state.stopReason.equals(fatal) && stopped.next() == null,
                    "Stops after " + fatal + " without claiming the remaining files failed");
        }
        BatchRestore cancelEarly = new BatchRestore(PATHS);
        cancelEarly.requestStop();
        ok(cancelEarly.next() == null && cancelEarly.snapshot().notAttempted == 3 && cancelEarly.snapshot().failed == 0,
                "Stop before the first file does not perform or fail a restore");
        BatchRestore cancelled = new BatchRestore(PATHS);
        cancelled.next(); cancelled.requestStop();
        ok(!cancelled.snapshot().finished && !cancelled.snapshot().current.isEmpty(),
                "A stop request lets the current verified file finish");
        cancelled.complete("ok");
        ok(cancelled.next() == null && cancelled.snapshot().restored == 1 && cancelled.snapshot().notAttempted == 2,
                "Stops between files and retains the completed result");
        BatchRestore disconnected = new BatchRestore(PATHS);
        disconnected.next(); disconnected.complete("ok");
        disconnected.next(); disconnected.complete("connect");
        ok(disconnected.snapshot().restored == 1 && disconnected.snapshot().failed == 1
                && disconnected.snapshot().notAttempted == 1, "Connection loss keeps earlier successes and leaves later files unattempted");
        BatchRestore failures = new BatchRestore(PATHS);
        failures.next(); failures.complete("failed");
        ok(failures.next().equals(PATHS.get(1)), "A generic per-file failure permits the next selected file");
        failures.complete("ok"); failures.next(); failures.complete(null);
        ok(failures.snapshot().finished && failures.snapshot().failed == 2 && failures.snapshot().restored == 1,
                "Unknown results are never counted as success");

        // Exercise the same plan with the production single-file restorer and real files.
        Path temporary = Files.createTempDirectory("melkit-batch-restore-");
        try {
            Path game = Files.createDirectory(temporary.resolve("game"));
            Files.createDirectories(game.resolve("Maps"));
            byte[] original = "keep existing map".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(game.resolve(PATHS.get(0)), original);
            byte[] contents = "verified bundle member".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            BatchRestore integrated = new BatchRestore(PATHS);
            String path;
            while ((path = integrated.next()) != null) {
                String status;
                try {
                    String hash = path.equals(PATHS.get(1)) ? "0".repeat(64) : sha(contents);
                    NewFileRestore.restore(game.toFile(), path, new ByteArrayInputStream(contents), contents.length, hash);
                    status = "ok";
                } catch (NewFileRestore.AlreadyExists existing) { status = "exists";
                } catch (NewFileRestore.SourceChanged changed) { status = "changed"; }
                integrated.complete(status);
            }
            BatchRestore.Snapshot state = integrated.snapshot();
            ok(state.restored == 1 && state.skipped == 1 && state.failed == 1 && state.finished,
                    "A per-file verification failure does not prevent the later selected save from restoring");
            ok(Arrays.equals(Files.readAllBytes(game.resolve(PATHS.get(0))), original), "Conflicting original bytes are preserved");
            ok(!Files.exists(game.resolve(PATHS.get(1))), "Failed verification publishes no mod");
            ok(Arrays.equals(Files.readAllBytes(game.resolve(PATHS.get(2))), contents), "Later selected save restores byte for byte");
            try (var files = Files.walk(game)) {
                ok(files.noneMatch(p -> p.getFileName().toString().startsWith(".melkit-restore-")),
                        "No staging files remain after mixed outcomes");
            }
            BatchRestore retry = new BatchRestore(Arrays.asList(PATHS.get(0), PATHS.get(2)));
            while ((path = retry.next()) != null) {
                try {
                    NewFileRestore.restore(game.toFile(), path, new ByteArrayInputStream(contents), contents.length, sha(contents));
                    retry.complete("ok");
                } catch (NewFileRestore.AlreadyExists existing) { retry.complete("exists"); }
            }
            ok(retry.snapshot().skipped == 2 && retry.snapshot().restored == 0, "Repeated restore only skips existing destinations");
            BatchRestore stopDuringWrite = new BatchRestore(Arrays.asList("Mods/stop.melmod", "Saves/not-started.melsave"));
            String current = stopDuringWrite.next();
            NewFileRestore.restore(game.toFile(), current, new ByteArrayInputStream(contents) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    stopDuringWrite.requestStop();
                    return super.read(b, off, len);
                }
            }, contents.length, sha(contents));
            stopDuringWrite.complete("ok");
            ok(Arrays.equals(Files.readAllBytes(game.resolve(current)), contents), "Stop during a write preserves the complete verified file");
            ok(stopDuringWrite.next() == null && !Files.exists(game.resolve("Saves/not-started.melsave")),
                    "Stop does not begin the next game file");
            ok(stopDuringWrite.snapshot().restored == 1 && stopDuringWrite.snapshot().notAttempted == 1,
                    "Stopped summary matches the actual published files");
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Batch restore checks: " + checks + " passed");
    }
}
