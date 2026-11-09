import com.spinel.melkit.ads.AdPolicy;
import java.util.ArrayList;
import java.util.List;

public final class AdPolicyCheck {
    private static int checks;
    private static final long START = 1_000_000L;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static AdPolicy empty() { return new AdPolicy(0, 0, 0, List.of()); }
    private static void twoTasks(AdPolicy p) { p.completedTask(); p.completedTask(); }

    public static void main(String[] args) {
        AdPolicy p = empty();
        check(!p.interstitialEligible(START), "Navigation and elapsed time alone are not opportunities");
        p.normalForeground();
        p.completedTask();
        check(!p.interstitialEligible(START), "First successful task stays free");
        p.completedTask();
        check(p.interstitialEligible(START), "Two completed tasks can reach a break");
        check(p.interstitialEligible(START + 100), "No-fill does not consume an impression or task credit");
        check(p.getInterstitials().isEmpty(), "Eligibility checks never count as impressions");
        p.shown(false, START);
        check(!p.interstitialEligible(START), "An actual impression consumes task credit");
        p.closed(START + 20_000);
        twoTasks(p);
        check(!p.interstitialEligible(START + 109_999), "Cooldown starts after close, not at ad load or show");
        check(p.interstitialEligible(START + 110_000), "90 seconds after close allows the next task break");
        p.shown(false, START + 110_000); p.closed(START + 120_000); twoTasks(p);
        check(p.interstitialEligible(START + 210_000), "Third exposure is possible with tasks and cooldown");
        p.shown(false, START + 210_000); p.closed(START + 220_000); twoTasks(p);
        check(!p.interstitialEligible(START + 310_000), "Three interstitials exhaust a rolling ten-minute window");
        check(!p.interstitialEligible(START + 599_999), "Window does not reopen early");
        check(p.interstitialEligible(START + 600_000), "Oldest exposure expires exactly at ten minutes");
        check(p.getInterstitials().size() == 2, "Only expired exposures are removed");
        List<Long> restored = p.getInterstitials();
        restored.clear();
        check(p.getInterstitials().size() == 2, "Snapshot caller cannot change internal rate limits");

        p = empty();
        p.normalForeground();
        check(!p.appOpenEligible(START), "First launch has no app-open ad");
        p.normalForeground();
        check(!p.appOpenEligible(START), "Second normal launch has no app-open ad");
        p.normalForeground();
        check(p.appOpenEligible(START), "Third normal launch can use a preloaded app-open ad");
        p.shown(true, START); p.closed(START + 30_000);
        twoTasks(p);
        check(!p.interstitialEligible(START + 119_999), "App-open and interstitial share the same cooldown");
        check(p.interstitialEligible(START + 120_000), "A later natural break is usable after app-open");
        check(!p.appOpenEligible(START + 599_999), "App-open capped at one per ten minutes");
        check(p.appOpenEligible(START + 600_000), "App-open cap reopens at its exact boundary");
        p.shown(false, START + 600_000); p.closed(START + 620_000);
        check(!p.appOpenEligible(START + 709_999), "A recent interstitial blocks app-open too");
        check(p.appOpenEligible(START + 710_000), "Shared cooldown is symmetric");

        p = new AdPolicy(8, START, START - 500_000, List.of(START - 10_000, START - 20_000, START - 30_000));
        twoTasks(p);
        check(!p.interstitialEligible(START + 90_000), "Persisted cap survives process restart");
        check(!p.appOpenEligible(START + 90_000), "Persisted app-open cap survives process restart");
        check(p.appOpenEligible(START + 100_000), "Persisted app-open boundary remains correct");
        check(!p.interstitialEligible(START - 1), "Clock rollback never bypasses shared cooldown");
        check(p.getInterstitials().size() == 3, "Clock rollback preserves impression timestamps");
        check(!p.appOpenEligible(START - 1), "Clock rollback cannot bypass app-open cooldown");
        p.normalForeground(); p.completedTask();
        check(!p.interstitialEligible(START + 1_000_000), "A new normal visit starts with one free task");
        check(p.getNormalOpens() == 9, "Only explicit normal visits increment the launch count");

        p = new AdPolicy(3, 0, START + 1_000, new ArrayList<>(List.of(START + 2_000, START + 3_000, START + 4_000)));
        twoTasks(p);
        check(!p.appOpenEligible(START), "Future app-open timestamp fails closed");
        check(!p.interstitialEligible(START), "Future interstitial timestamps remain capped");
        check(!empty().appOpenEligible(0), "Zero clock value cannot create an opportunity");
        System.out.println("Ad policy checks passed: " + checks);
    }
}
