package com.spinel.melkit.ads;

import java.util.ArrayList;
import java.util.List;

/** Presentation limits only. No timer, network request, UI, or file operation lives here. */
public final class AdPolicy {
    public static final long FULL_SCREEN_GAP_MS = 90_000L;
    public static final long WINDOW_MS = 600_000L;
    public static final int MAX_INTERSTITIALS = 3;
    private int completedTasks, normalOpens;
    private long lastClosed, lastAppOpen;
    private final List<Long> interstitials;

    public AdPolicy(int normalOpens, long lastClosed, long lastAppOpen, List<Long> interstitials) {
        this.normalOpens = Math.max(0, normalOpens);
        this.lastClosed = Math.max(0, lastClosed);
        this.lastAppOpen = Math.max(0, lastAppOpen);
        this.interstitials = new ArrayList<>(interstitials);
    }

    /** Only genuine foreground visits count; external activities/ads and rotations do not. */
    public void normalForeground() {
        if (normalOpens < Integer.MAX_VALUE) normalOpens++;
        completedTasks = 0;
    }

    /** Call once per verified successful task, including an entire batch as one task. */
    public void completedTask() {
        if (completedTasks < Integer.MAX_VALUE) completedTasks++;
    }

    public boolean interstitialEligible(long now) {
        prune(now);
        return completedTasks >= 2 && gapPassed(now) && interstitials.size() < MAX_INTERSTITIALS;
    }

    public boolean appOpenEligible(long now) {
        return normalOpens >= 3 && gapPassed(now) &&
                (lastAppOpen == 0 || (now >= lastAppOpen && now - lastAppOpen >= WINDOW_MS));
    }

    /** Count an exposure only after the SDK confirms that it actually appeared. */
    public void shown(boolean appOpen, long now) {
        if (appOpen) lastAppOpen = now;
        else { prune(now); interstitials.add(now); }
        // Persist a minimum gap even if the process dies before dismissal.
        lastClosed = Math.max(lastClosed, now);
        completedTasks = 0;
    }

    public void closed(long now) { lastClosed = Math.max(lastClosed, now); }

    private boolean gapPassed(long now) {
        return now > 0 && (lastClosed == 0 || (now >= lastClosed && now - lastClosed >= FULL_SCREEN_GAP_MS));
    }

    private void prune(long now) {
        // A backwards clock must not bypass the cap by deleting future timestamps.
        interstitials.removeIf(time -> time <= 0 || (now >= time && now - time >= WINDOW_MS));
    }

    public int getNormalOpens() { return normalOpens; }
    public long getLastClosed() { return lastClosed; }
    public long getLastAppOpen() { return lastAppOpen; }
    public List<Long> getInterstitials() { return new ArrayList<>(interstitials); }
}
