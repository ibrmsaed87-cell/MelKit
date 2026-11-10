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
    private static void actions(AdPolicy p) { p.userAction(); p.userAction(); }
    private static void task(AdPolicy p) { actions(p); p.completedTask(); }

    public static void main(String[] args) {
        AdPolicy p = empty();
        check(!p.interstitialEligible(START), "Navigation and elapsed time alone are not opportunities");
        p.normalForeground();
        actions(p);
        check(!p.interstitialEligible(START), "Navigation alone cannot create an ad break");
        p.completedTask();
        check(p.interstitialEligible(START), "First successful task with two interactions can reach a break");
        check(p.interstitialEligible(START + 100), "No-fill does not consume an impression or task credit");
        check(p.getInterstitials().isEmpty(), "Eligibility checks never count as impressions");
        p.shown(false, START);
        check(!p.interstitialEligible(START), "An actual impression consumes task credit");
        p.closed(START + 20_000);
        task(p);
        check(!p.interstitialEligible(START + 69_999), "Cooldown starts after close, not at ad load or show");
        check(p.interstitialEligible(START + 70_000), "50 seconds after close allows the next task break");
        p.shown(false, START + 70_000); p.closed(START + 80_000); task(p);
        check(p.interstitialEligible(START + 130_000), "Third exposure is possible with tasks and cooldown");
        p.shown(false, START + 130_000); p.closed(START + 140_000); task(p);
        check(!p.interstitialEligible(START + 190_000), "Three interstitials exhaust a rolling five-minute window");
        check(!p.interstitialEligible(START + 299_999), "Window does not reopen early");
        check(p.interstitialEligible(START + 300_000), "Oldest exposure expires exactly at five minutes");
        check(p.getInterstitials().size() == 2, "Only expired exposures are removed");
        List<Long> restored = p.getInterstitials();
        restored.clear();
        check(p.getInterstitials().size() == 2, "Snapshot caller cannot change internal rate limits");

        p = empty(); p.normalForeground(); p.completedTask();
        check(!p.interstitialEligible(START), "Task callbacks cannot fabricate user actions");
        p.userAction();
        check(!p.interstitialEligible(START), "A single action cannot be followed by an interstitial");
        p.userAction();
        check(p.interstitialEligible(START), "One task with two observed interactions is enough");
        p.shown(false, START); p.closed(START); p.completedTask(); p.userAction();
        check(!p.interstitialEligible(START + 50_000), "One-click repeats stay blocked after the cooldown");
        p.userAction();
        check(p.interstitialEligible(START + 50_000), "A second interaction releases the single-task opportunity");

        p = empty();
        p.normalForeground();
        check(!p.appOpenEligible(START), "First launch has no app-open ad");
        p.normalForeground();
        check(!p.appOpenEligible(START), "Second normal launch has no app-open ad");
        p.normalForeground();
        check(p.appOpenEligible(START), "Third normal launch can use a preloaded app-open ad");
        p.shown(true, START); p.closed(START + 30_000);
        task(p);
        check(!p.interstitialEligible(START + 79_999), "App-open and interstitial share the same cooldown");
        check(p.interstitialEligible(START + 80_000), "A later natural break is usable after app-open");
        check(!p.appOpenEligible(START + 300_000), "Interstitial window change does not shorten app-open's cap");
        check(!p.appOpenEligible(START + 599_999), "App-open capped at one per ten minutes");
        check(p.appOpenEligible(START + 600_000), "App-open cap reopens at its exact boundary");
        p.shown(false, START + 600_000); p.closed(START + 620_000);
        check(!p.appOpenEligible(START + 669_999), "A recent interstitial blocks app-open too");
        check(p.appOpenEligible(START + 670_000), "Shared cooldown is symmetric");

        p = new AdPolicy(8, START, START - 500_000, List.of(START - 10_000, START - 20_000, START - 30_000));
        task(p);
        check(!p.interstitialEligible(START + 50_000), "Persisted cap survives process restart");
        check(!p.appOpenEligible(START + 50_000), "Persisted app-open cap survives process restart");
        check(p.appOpenEligible(START + 100_000), "Persisted app-open boundary remains correct");
        check(!p.interstitialEligible(START - 1), "Clock rollback never bypasses shared cooldown");
        check(p.getInterstitials().size() == 3, "Clock rollback preserves impression timestamps");
        check(!p.appOpenEligible(START - 1), "Clock rollback cannot bypass app-open cooldown");
        p.normalForeground(); p.completedTask();
        check(!p.interstitialEligible(START + 1_000_000), "A new normal visit cannot reuse old interactions");
        actions(p);
        check(p.interstitialEligible(START + 1_000_000), "New visit's first task can show after two interactions");
        check(p.getNormalOpens() == 9, "Only explicit normal visits increment the launch count");

        p = new AdPolicy(3, 0, START + 1_000, new ArrayList<>(List.of(START + 2_000, START + 3_000, START + 4_000)));
        task(p);
        check(!p.appOpenEligible(START), "Future app-open timestamp fails closed");
        check(!p.interstitialEligible(START), "Future interstitial timestamps remain capped");
        check(!empty().appOpenEligible(0), "Zero clock value cannot create an opportunity");

        p = empty(); p.normalForeground(); task(p); p.shown(false, START);
        AdPolicy restarted = new AdPolicy(3, p.getLastClosed(), p.getLastAppOpen(), p.getInterstitials());
        task(restarted);
        check(!restarted.interstitialEligible(START + 49_999), "Process death before dismissal retains the minimum gap");
        check(!restarted.appOpenEligible(START + 49_999), "A restart cannot stack app-open on the prior interstitial");
        check(restarted.interstitialEligible(START + 50_000), "An absent dismissal cannot block ads forever");
        restarted.closed(START - 1);
        check(!restarted.interstitialEligible(START + 49_999), "Closing after clock rollback cannot shorten the gap");
        restarted.closed(START + 30_000);
        check(!restarted.interstitialEligible(START + 79_999), "A real dismissal extends the fallback gap");
        check(restarted.interstitialEligible(START + 80_000), "Normal dismissal still controls the next opportunity");
        System.out.println("Ad policy checks passed: " + checks);
    }
}
