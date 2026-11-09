package com.spinel.melkit.push;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Only app-detail URLs on Google Play can leave the app from a push tap. */
public final class PlayStoreLinks {
    private PlayStoreLinks() {}
    public static final String[] DATA_KEYS = {"url", "link", "play_url", "package_name"};

    public static String fromData(Map<String, String> data) {
        for (String key : DATA_KEYS) {
            String value = data.get(key);
            if (value == null || value.trim().isEmpty()) continue;
            // A supplied invalid link is rejected, not silently replaced by another field.
            return key.equals("package_name") ? forPackage(value) : normalize(value);
        }
        return null;
    }

    public static String forPackage(String packageName) {
        if (packageName == null || packageName.length() > 255
                || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) return null;
        return "https://play.google.com/store/apps/details?id=" + packageName;
    }

    public static String normalize(String value) {
        if (value == null || value.length() > 4096) return null;
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"play.google.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawFragment() != null
                    || !"/store/apps/details".equals(uri.getRawPath()) || uri.getRawQuery() == null) return null;
            String packageName = null;
            for (String part : uri.getRawQuery().split("&", -1)) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name());
                if (!key.equals("id")) continue;
                if (packageName != null || pair.length != 2) return null;
                packageName = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
            }
            return forPackage(packageName);
        } catch (Exception ignored) {
            return null;
        }
    }
}
