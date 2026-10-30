package com.pinpoint.utils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrafficUtils {

    private static final Pattern IMAGE_EXT = Pattern.compile(".*\\.(png|jpg|jpeg|gif|svg|ico|webp|bmp)$");
    private static final Pattern FONT_EXT = Pattern.compile(".*\\.(woff|woff2|ttf|eot)$");
    private static final Pattern ENDPOINT_EXT = Pattern.compile(".*\\.(html|htm|php|asp|aspx|jsp|do|action|json|xml)$");

    /**
     * Host and (decoded) path of a URL. Parsed by hand rather than with java.net.URI, which rejects
     * characters browsers leave unescaped in real traffic, e.g. {@code ?filter={"q":"x"}} or
     * {@code ?q=a|b}; that would collapse every such request into one "-" node.
     */
    public static String[] parseHostAndPath(String url) {
        String[] invalid = {"-", "/"};
        if (url == null) return invalid;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) return invalid;

        String rest = url.substring(schemeEnd + 3);
        int cut = indexOfAny(rest, '?', '#');
        if (cut >= 0) rest = rest.substring(0, cut);

        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String rawPath = slash >= 0 ? rest.substring(slash) : "/";

        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        String host;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            host = end > 0 ? authority.substring(0, end + 1) : authority;   // IPv6 literal
        } else {
            int colon = authority.indexOf(':');
            host = colon >= 0 ? authority.substring(0, colon) : authority;
        }
        if (host.isEmpty() || host.chars().anyMatch(Character::isWhitespace)) return invalid;

        return new String[]{host, decodePath(rawPath)};
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a), ib = s.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }

    /** Percent-decodes a path (like URI.getPath()); a literal '+' stays '+', invalid encoding is kept as-is. */
    private static String decodePath(String rawPath) {
        if (rawPath.isEmpty()) return "/";
        if (rawPath.indexOf('%') < 0) return rawPath;
        try {
            return URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return rawPath;
        }
    }

    private static final Pattern ID_SEGMENT = Pattern.compile(
            "\\d+"                                                          // 12345
            + "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" // UUID
            + "|[0-9a-fA-F]{12,}"                                           // hex hashes / object ids
            + "|(?=[A-Za-z0-9_-]*\\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{20,}"); // long random tokens
    // Build hash before the extension: main.a1b2c3d4.js, chunk-3f9a8b7c.css
    private static final Pattern FILE_HASH = Pattern.compile("[.-][0-9a-fA-F]{6,}(?=\\.[A-Za-z0-9]+$)");
    private static final String ELLIPSIS = "…";

    // Ids longer than this are shortened in labels, keeping both ends so they stay distinguishable
    private static final int MAX_ID_LABEL_LENGTH = 12;
    // A hashed file name segment (e.g. a1b2c3d4e5) longer than this is shortened the same way
    private static final Pattern LONG_FILE_HASH = Pattern.compile("([.-])([0-9a-fA-F]{9,})(?=\\.[A-Za-z0-9]+$)");

    /**
     * Normalizes one path segment for grouping (flow keys): ids become ":id", build hashes are
     * stripped from file names. Never used for node labels, which must keep ids distinguishable.
     */
    public static String normalizeSegment(String segment) {
        if (ID_SEGMENT.matcher(segment).matches()) return ":id";
        return FILE_HASH.matcher(segment).replaceFirst("");
    }

    public static boolean isIdSegment(String segment) {
        return ID_SEGMENT.matcher(segment).matches();
    }

    /**
     * A path segment as shown on a node: the real value, so /users/1 and /users/2 never look alike.
     * Long ids keep their start and end (550e8400…0000); long build hashes are shortened likewise.
     */
    public static String displaySegment(String segment) {
        if (isIdSegment(segment)) {
            if (segment.length() <= MAX_ID_LABEL_LENGTH) return segment;
            return segment.substring(0, 4) + ELLIPSIS + segment.substring(segment.length() - 4);
        }
        Matcher m = LONG_FILE_HASH.matcher(segment);
        if (m.find()) {
            String hash = m.group(2);
            return segment.substring(0, m.start(2)) + hash.substring(0, 4) + ELLIPSIS + hash.substring(hash.length() - 2)
                    + segment.substring(m.end(2));
        }
        return segment;
    }

    /**
     * A readable label for an endpoint that fits {@code maxWidth}: the last segment plus as many
     * parent segments as fit (an id always keeps its parent, e.g. "users/8812"). Segments that
     * don't fit are replaced by a leading "…/"; a single segment too long is shortened in the middle.
     */
    public static String endpointLabel(String path, ToIntFunction<String> width, int maxWidth) {
        List<String> segments = new ArrayList<>();
        if (path != null) {
            for (String s : path.split("/")) {
                if (!s.isEmpty()) segments.add(s);
            }
        }
        if (segments.isEmpty()) return "/";

        int last = segments.size() - 1;
        int first = last;
        // An id alone says nothing: always pull in the segment that names it
        if (isIdSegment(segments.get(last)) && last > 0) first = last - 1;
        segments.replaceAll(TrafficUtils::displaySegment);

        String label = String.join("/", segments.subList(first, last + 1));
        if (width.applyAsInt(prefixed(label, first)) > maxWidth) {
            return ellipsizeMiddle(prefixed(label, first), width, maxWidth);
        }
        while (first > 0) {
            String candidate = segments.get(first - 1) + "/" + label;
            if (width.applyAsInt(prefixed(candidate, first - 1)) > maxWidth) break;
            label = candidate;
            first--;
        }
        return prefixed(label, first);
    }

    private static String prefixed(String label, int first) {
        return first == 0 ? "/" + label : ELLIPSIS + "/" + label;
    }

    /** Shortens text in the middle, keeping its start and end (and so a file extension). */
    private static String ellipsizeMiddle(String text, ToIntFunction<String> width, int maxWidth) {
        int keep = text.length() - 1;
        while (keep > 1) {
            int head = (keep + 1) / 2;
            int tail = keep - head;
            String candidate = text.substring(0, head) + ELLIPSIS + text.substring(text.length() - tail);
            if (width.applyAsInt(candidate) <= maxWidth) return candidate;
            keep--;
        }
        return ELLIPSIS;
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
