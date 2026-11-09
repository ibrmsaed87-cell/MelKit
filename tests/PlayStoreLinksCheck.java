import com.spinel.melkit.push.PlayStoreLinks;
import java.util.Map;

public final class PlayStoreLinksCheck {
    private static int checks;
    private static final String URL = "https://play.google.com/store/apps/details?id=com.spinel.mmix";
    public static void main(String[] args) {
        expect(URL.equals(PlayStoreLinks.normalize(URL)));
        expect(URL.equals(PlayStoreLinks.normalize("HTTPS://PLAY.GOOGLE.COM/store/apps/details?id=com.spinel.mmix")));
        expect(URL.equals(PlayStoreLinks.normalize("https://play.google.com:443/store/apps/details?hl=ar&id=com.spinel.mmix&referrer=test")));
        expect(URL.equals(PlayStoreLinks.normalize("https://play.google.com/store/apps/details?id=com%2Espinel%2Emmix")));
        expect(URL.equals(PlayStoreLinks.forPackage("com.spinel.mmix")));
        expect(URL.equals(PlayStoreLinks.fromData(Map.of("url", URL))));
        expect(URL.equals(PlayStoreLinks.fromData(Map.of("link", URL))));
        expect(URL.equals(PlayStoreLinks.fromData(Map.of("play_url", URL))));
        expect(URL.equals(PlayStoreLinks.fromData(Map.of("package_name", "com.spinel.mmix"))));
        expect(URL.equals(PlayStoreLinks.fromData(Map.of("url", " ", "link", URL))));
        expect(PlayStoreLinks.fromData(Map.of()) == null);
        expect(PlayStoreLinks.fromData(Map.of("url", "https://evil.example", "link", URL)) == null);
        for (String value : new String[] {
                "http://play.google.com/store/apps/details?id=com.spinel.mmix",
                "https://play.google.com.evil.example/store/apps/details?id=com.spinel.mmix",
                "https://evil.example/?url=" + URL,
                "https://play.google.com@evil.example/store/apps/details?id=com.spinel.mmix",
                "https://evil.example@play.google.com/store/apps/details?id=com.spinel.mmix",
                "https://play.google.com:8443/store/apps/details?id=com.spinel.mmix",
                URL + "#fragment", URL + "&id=com.other.app",
                "https://play.google.com/store/apps/details?id=&id=com.spinel.mmix",
                "https://play.google.com/store/apps/details?id=com.spinel.mmix&%69d=com.other.app",
                "https://play.google.com/store/apps/details", "https://play.google.com/store/apps/details?hl=ar",
                "https://play.google.com/store/apps/details?id", "https://play.google.com/store/apps/details?id=",
                "https://play.google.com/store/apps/dev?id=123", "https://play.google.com/other?id=com.spinel.mmix",
                "https://play.google.com/store/apps/details%2F?id=com.spinel.mmix",
                "https://play.google.com/store/apps/details?id=com.spinel.mmix%0A",
                "https://play.google.com/store/apps/details?id=com%252Espinel%252Emmix",
                "https://play.google.com/store/apps/details?id=com/spinel/mmix",
                "https://play.google.com/store/apps/details?id=com..mmix",
                "https://play.google.com/store/apps/details?id=com.1spinel.mmix",
                "https://play.google.com/store/apps/details?id=%XX",
                "javascript:alert(1)", "intent://details?id=com.spinel.mmix", "file:///storage/emulated/0/test",
                "market://details?id=com.spinel.mmix", "//play.google.com/store/apps/details?id=com.spinel.mmix",
                "not a URL", "https://play.google.com/store/apps/details?id=" + "a".repeat(5000)
        }) expect(PlayStoreLinks.normalize(value) == null);
        for (String value : new String[] {"", "com", "com..app", ".com.app", "com.app.", "com/app", "com app", "com.app\n", "a".repeat(256)}) {
            expect(PlayStoreLinks.forPackage(value) == null);
        }
        expect(PlayStoreLinks.normalize(null) == null);
        expect(PlayStoreLinks.forPackage(null) == null);
        System.out.println("Play Store link checks passed: " + checks);
    }
    private static void expect(boolean ok) { if (!ok) throw new AssertionError("Failed check " + (checks + 1)); checks++; }
}
