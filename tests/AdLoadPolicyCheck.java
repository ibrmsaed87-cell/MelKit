import com.spinel.melkit.ads.AdLoadPolicy;

public final class AdLoadPolicyCheck {
    private static int checks;
    private static final long START = 1_000_000L;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        AdLoadPolicy p = new AdLoadPolicy();
        check(p.canLoad(START), "First preload does not wait");
        p.loaded();
        check(p.canLoad(START + 1), "A consumed successful ad can be replaced immediately");
        p.failed(START);
        check(!p.canLoad(START + 59_999), "A failed request cannot retry before one minute");
        check(p.retryDelay(START + 20_000) == 40_000, "Retry retains the remaining wait");
        check(p.canLoad(START + 60_000), "A retry is possible at the exact boundary");
        p.failed(START + 60_000);
        check(p.retryDelay(START + 60_000) == 120_000, "Repeated failure doubles the delay");
        p.failed(START + 180_000);
        check(p.retryDelay(START + 180_000) == 240_000, "Third failure backs off to four minutes");
        p.failed(START + 420_000);
        check(p.retryDelay(START + 420_000) == 480_000, "Fourth failure backs off to eight minutes");
        p.failed(START + 900_000);
        check(p.retryDelay(START + 900_000) == 900_000, "Retry wait is capped at fifteen minutes");
        for (int i = 0; i < 100; i++) p.failed(START + 900_000);
        check(p.retryDelay(START + 900_000) == 900_000, "Many failures cannot overflow the backoff");
        p.loaded();
        check(p.canLoad(START + 900_001), "Success clears old retry deadlines for replenishment");
        p.failed(START + 900_002);
        check(p.retryDelay(START + 900_002) == 60_000, "Success also resets the failure count");
        check(p.retryDelay(START + 960_003) == 0, "An overdue retry has no negative wait");
        AdLoadPolicy otherFormat = new AdLoadPolicy();
        check(otherFormat.canLoad(START), "Failure in one format does not block the other");
        System.out.println("Ad load policy checks passed: " + checks);
    }
}
