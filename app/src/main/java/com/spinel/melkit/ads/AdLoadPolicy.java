package com.spinel.melkit.ads;

/** Retry timing for loading only. This class cannot create a display opportunity. */
public final class AdLoadPolicy {
    private static final long FIRST_RETRY_MS = 60_000L;
    private static final long MAX_RETRY_MS = 900_000L;
    private int failures;
    private long retryAt;

    public boolean canLoad(long now) { return now >= retryAt; }

    public long retryDelay(long now) { return Math.max(0, retryAt - now); }

    public void loaded() {
        failures = 0;
        retryAt = 0;
    }

    public void failed(long now) {
        failures = Math.min(5, failures + 1);
        long delay = Math.min(MAX_RETRY_MS, FIRST_RETRY_MS * (1L << (failures - 1)));
        retryAt = now + delay;
    }
}
