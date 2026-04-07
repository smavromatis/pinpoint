package com.pinpoint.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public class TrafficUtils {

    private static final Pattern IMAGE_EXT = Pattern.compile(".*\\.(png|jpg|jpeg|gif|svg|ico|webp|bmp)$");
    private static final Pattern FONT_EXT = Pattern.compile(".*\\.(woff|woff2|ttf|eot)$");
    private static final Pattern ENDPOINT_EXT = Pattern.compile(".*\\.(html|htm|php|asp|aspx|jsp|do|action|json|xml)$");

    public static String[] parseHostAndPath(String url) {
        String host = "-";
        String path = "/";
        try {
            URI u = new URI(url);
            host = u.getHost() != null ? u.getHost() : "-";
            path = u.getPath() != null && !u.getPath().isEmpty() ? u.getPath() : "/";
        } catch (URISyntaxException ignored) {
        }
        return new String[]{host, path};
    }

    public static String getFileType(String path) {
        if (path == null) return "Endpoints";

        String lower = path.toLowerCase();
        int queryIdx = lower.indexOf('?');
        if (queryIdx > 0) lower = lower.substring(0, queryIdx);

        if (lower.endsWith(".js")) return "JavaScript";
        if (lower.endsWith(".css")) return "CSS";
        if (IMAGE_EXT.matcher(lower).matches()) return "Images";
        if (FONT_EXT.matcher(lower).matches()) return "Fonts";
        if (ENDPOINT_EXT.matcher(lower).matches() || !lower.contains(".")) return "Endpoints";
        return "Other";
    }
}
