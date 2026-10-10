package cn.lunadeer.reColorfulMap.services.download;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable HTTP origin and path rules; string prefixes are never host boundaries. */
public final class UrlWhitelist {
    private final boolean unrestricted;
    private final List<Rule> rules;

    private record Rule(String scheme, String host, int port, String path) {
        boolean matches(URI target) {
            return (scheme == null || scheme.equals(target.getScheme()))
                    && host.equals(target.getHost())
                    && (port == -1 ? defaultPort(target.getScheme()) : port) == effectivePort(target)
                    && (path.equals("/") || target.getPath().equals(path)
                    || target.getPath().startsWith(path + "/"));
        }
    }

    private UrlWhitelist(boolean unrestricted, List<Rule> rules) {
        this.unrestricted = unrestricted;
        this.rules = List.copyOf(rules);
    }

    /** Also used by configuration validation, before a new configuration is published. */
    public static UrlWhitelist compile(List<String> addresses) {
        Objects.requireNonNull(addresses, "addresses");
        boolean unrestricted = addresses.isEmpty();
        List<Rule> rules = new ArrayList<>();
        for (String address : addresses) {
            if (address == null) {
                throw new IllegalArgumentException("Image URL whitelist entries must be strings");
            }
            String value = address.trim();
            if (value.isEmpty()) {
                unrestricted = true; // Preserve the original empty-string default.
                continue;
            }
            boolean explicitScheme = value.contains("://");
            URI target = normalize(explicitScheme ? value : "https://" + value);
            if (target.getRawQuery() != null || target.getRawFragment() != null) {
                throw new IllegalArgumentException("Image URL whitelist rules cannot contain a query or fragment: " + value);
            }
            String path = target.getPath();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            rules.add(new Rule(explicitScheme ? target.getScheme() : null, target.getHost(), target.getPort(), path));
        }
        return new UrlWhitelist(unrestricted, rules);
    }

    public boolean allows(URI target) {
        URI normalized = normalize(target.toString());
        return unrestricted || rules.stream().anyMatch(rule -> rule.matches(normalized));
    }

    /** Reject ambiguous URL forms even when the whitelist is unrestricted. */
    public static URI normalize(String address) {
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("Expected an HTTP or HTTPS image URL");
            }
            if (uri.getRawUserInfo() != null || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid image URL host, credentials or port");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            String rawPath = uri.getRawPath();
            String path = uri.getPath();
            if (rawPath == null || rawPath.isEmpty()) {
                rawPath = "/";
                path = "/";
            }
            String lower = rawPath.toLowerCase(Locale.ROOT);
            if (path.indexOf('\\') >= 0 || lower.contains("%2f") || lower.contains("%5c")
                    || lower.contains("%25")) {
                throw new IllegalArgumentException("Ambiguous escaping in image URL path");
            }
            for (String segment : path.split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("Image URL paths cannot contain dot segments");
                }
            }
            // Keep the original raw path/query, including signatures and escaped spaces.
            String authority = host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            return new URI(scheme + "://" + authority + rawPath
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment()));
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid image URL", exception);
        }
    }

    private static int defaultPort(String scheme) {
        return scheme.equals("https") ? 443 : 80;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
    }
}
