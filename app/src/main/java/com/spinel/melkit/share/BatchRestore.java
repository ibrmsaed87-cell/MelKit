package com.spinel.melkit.share;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sequential restore plan. File writes still go through the existing verified restorer. */
public final class BatchRestore {
    public enum Outcome { WAITING, RUNNING, RESTORED, SKIPPED, FAILED, NOT_ATTEMPTED }

    public static final class Entry {
        public final String path, status;
        public final Outcome outcome;
        private Entry(String path, String status, Outcome outcome) {
            this.path = path; this.status = status; this.outcome = outcome;
        }
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final int total, completed, restored, skipped, failed, notAttempted;
        public final String current, stopReason;
        public final boolean finished, stopRequested;
        private Snapshot(List<Entry> entries, String current, boolean finished, boolean stopRequested, String stopReason) {
            this.entries = Collections.unmodifiableList(entries); this.total = entries.size();
            this.current = current; this.finished = finished; this.stopRequested = stopRequested; this.stopReason = stopReason;
            int restored = 0, skipped = 0, failed = 0, notAttempted = 0;
            for (Entry entry : entries) {
                if (entry.outcome == Outcome.RESTORED) restored++;
                if (entry.outcome == Outcome.SKIPPED) skipped++;
                if (entry.outcome == Outcome.FAILED) failed++;
                if (entry.outcome == Outcome.NOT_ATTEMPTED) notAttempted++;
            }
            this.restored = restored; this.skipped = skipped; this.failed = failed; this.notAttempted = notAttempted;
            this.completed = restored + skipped + failed;
        }
    }

    private final List<String> paths;
    private final Map<String, String> results = new LinkedHashMap<>();
    private int index;
    private String current = "", stopReason = "";
    private boolean stopRequested, finished;

    public BatchRestore(Collection<String> selection) {
        if (selection == null || selection.isEmpty() || selection.size() > ShareWire.MAX_FILES) {
            throw new IllegalArgumentException("Invalid restore selection");
        }
        Set<String> unique = new HashSet<>();
        paths = new ArrayList<>();
        for (String path : selection) {
            if (!ShareWire.validPath(path) || !unique.add(ShareWire.pathKey(path))) {
                throw new IllegalArgumentException("Invalid or duplicate restore path");
            }
            paths.add(path);
        }
    }

    /** Starts at most one file; call complete only after the restorer returns its result. */
    public synchronized String next() {
        if (!current.isEmpty()) throw new IllegalStateException("A restore is already in progress");
        if (finished || stopRequested || index == paths.size()) {
            finished = true;
            return null;
        }
        current = paths.get(index++);
        return current;
    }

    public synchronized void complete(String status) {
        if (current.isEmpty()) throw new IllegalStateException("No restore in progress");
        String result = status == null || status.isEmpty() ? "failed" : status;
        results.put(current, result);
        current = "";
        // Do not continue after a lost connection, another operation, pending mod recovery,
        // or a file operation whose cleanup needs checking. Other per-file failures continue.
        if (result.equals("connect") || result.equals("busy") || result.equals("recovery")
                || result.equals("partial") || result.equals("unconfirmed")) requestStop(result);
        if (index == paths.size() || stopRequested) finished = true;
    }

    /** Stops between files. It never interrupts the verified write of the current file. */
    public synchronized void requestStop() { requestStop("cancelled"); }

    private void requestStop(String reason) {
        if (finished) return;
        stopRequested = true;
        if (stopReason.isEmpty()) stopReason = reason;
        if (current.isEmpty()) finished = true;
    }

    public synchronized Snapshot snapshot() {
        List<Entry> entries = new ArrayList<>();
        for (String path : paths) {
            String status = results.get(path);
            Outcome outcome;
            if (status != null) {
                outcome = status.equals("ok") ? Outcome.RESTORED : status.equals("exists") ? Outcome.SKIPPED : Outcome.FAILED;
            } else if (path.equals(current)) {
                outcome = Outcome.RUNNING; status = "running";
            } else {
                outcome = finished ? Outcome.NOT_ATTEMPTED : Outcome.WAITING;
                status = finished ? "not_attempted" : "waiting";
            }
            entries.add(new Entry(path, status, outcome));
        }
        return new Snapshot(entries, current, finished, stopRequested, stopReason);
    }
}
