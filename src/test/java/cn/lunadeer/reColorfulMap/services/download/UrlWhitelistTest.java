package cn.lunadeer.reColorfulMap.services.download;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UrlWhitelistTest {
    @Test
    void requiresExactHostnameAndRejectsCredentialsEvenWhenUnrestricted() {
        UrlWhitelist policy = UrlWhitelist.compile(List.of("images.example.org"));
        assertTrue(policy.allows(URI.create("https://IMAGES.EXAMPLE.ORG./picture.png")));
        assertTrue(policy.allows(URI.create("http://images.example.org/picture.png")));
        assertFalse(policy.allows(URI.create("https://images.example.org.evil.invalid/picture.png")));
        assertFalse(policy.allows(URI.create("https://child.images.example.org/picture.png")));
        assertThrows(IllegalArgumentException.class,
                () -> UrlWhitelist.compile(List.of()).allows(URI.create("http://allowed.example@evil.invalid/a")));
    }

    @Test
    void explicitSchemesAndPortsArePartOfOrigin() {
        UrlWhitelist secure = UrlWhitelist.compile(List.of("https://images.example.org"));
        assertTrue(secure.allows(URI.create("https://images.example.org:443/a")));
        assertFalse(secure.allows(URI.create("http://images.example.org/a")));
        assertFalse(secure.allows(URI.create("https://images.example.org:8443/a")));
        UrlWhitelist port = UrlWhitelist.compile(List.of("127.0.0.1:8080"));
        assertTrue(port.allows(URI.create("http://127.0.0.1:8080/a")));
        assertTrue(port.allows(URI.create("https://127.0.0.1:8080/a")));
        assertFalse(port.allows(URI.create("http://127.0.0.1:8081/a")));
    }

    @Test
    void matchesPathSegmentsAndKeepsSignedQueriesUntouched() {
        UrlWhitelist policy = UrlWhitelist.compile(List.of("https://images.example.org/pictures/"));
        assertTrue(policy.allows(URI.create("https://images.example.org/pictures")));
        assertTrue(policy.allows(URI.create("https://images.example.org/pictures/a%20b.png?signature=a%2Fb%3D")));
        assertFalse(policy.allows(URI.create("https://images.example.org/pictures-evil/a.png")));
        assertFalse(policy.allows(URI.create("https://images.example.org/Pictures/a.png")));
        String signed = "HTTPS://IMAGES.EXAMPLE.ORG/pictures/a%20b.png?signature=a%2Fb%3D";
        assertEquals("https://images.example.org/pictures/a%20b.png?signature=a%2Fb%3D",
                UrlWhitelist.normalize(signed).toString());
    }

    @Test
    void preservesEmptyWhitelistDefaultsAndSupportsIpv6() {
        assertTrue(UrlWhitelist.compile(List.of()).allows(URI.create("http://127.0.0.1:9999/a")));
        assertTrue(UrlWhitelist.compile(List.of("  ")).allows(URI.create("https://any.example/a")));
        assertTrue(UrlWhitelist.compile(List.of("[::1]:8080/images"))
                .allows(URI.create("http://[::1]:8080/images/a")));
    }

    @Test
    void rejectsAmbiguousPathsAndInvalidWhitelistRules() {
        for (String path : List.of("/pictures/../private", "/pictures/%2e%2e/private",
                "/pictures%2f..%2fprivate", "/pictures/%252e%252e/private", "/pictures/%5cprivate")) {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlWhitelist.normalize("https://images.example.org" + path), path);
        }
        for (String rule : List.of("ftp://images.example.org", "https://user@images.example.org",
                "https://images.example.org:0", "https://images.example.org:65536",
                "https://images.example.org/a?q=1", "https://images.example.org/#fragment", "not a host")) {
            assertThrows(IllegalArgumentException.class, () -> UrlWhitelist.compile(List.of(rule)), rule);
        }
    }
}
