package org.adaway.model.source;

import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import org.adaway.util.RegexUtils;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.adaway.model.source.SourceLoader.splitHostsLine;
import static org.junit.Assert.*;

public class SourceLoaderTest {

    // Test data comes from Guava InternetDomainName unit test
    // https://github.com/google/guava/blob/master/android/guava-tests/test/com/google/common/net/InternetDomainNameTest.java
    private static final String ALMOST_TOO_MANY_LEVELS = "a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a";
    private static final String ALMOST_TOO_LONG = "aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.aaaaa.a1234567890.c";
    private static final Set<String> VALID_NAMES = new HashSet<>();
    private static final Set<String> INVALID_NAMES = new HashSet<>();
    private static final Set<String> VALID_WILDCARD_NAMES = new HashSet<>();
    private static final Set<String> INVALID_WILDCARD_NAMES = new HashSet<>();

    static {
        VALID_NAMES.add("foo.com");
        VALID_NAMES.add("f-_-o.cOM");
        VALID_NAMES.add("f--1.com");
        VALID_NAMES.add("f11-1.com");
        VALID_NAMES.add("www");
        VALID_NAMES.add("abc.a23");
        VALID_NAMES.add("biz.com.ua");
        VALID_NAMES.add("x");
        VALID_NAMES.add("fOo");
        VALID_NAMES.add("f--o");
        VALID_NAMES.add("f_a");
        VALID_NAMES.add(ALMOST_TOO_MANY_LEVELS);
        VALID_NAMES.add(ALMOST_TOO_LONG);

        INVALID_NAMES.add("");
        INVALID_NAMES.add(" ");
        INVALID_NAMES.add("127.0.0.1");
        INVALID_NAMES.add("::1");
        INVALID_NAMES.add("13");
        INVALID_NAMES.add("abc.12c");
        INVALID_NAMES.add("foo-.com");
        INVALID_NAMES.add("_bar.quux");
        INVALID_NAMES.add("foo+bar.com");
        INVALID_NAMES.add("foo!bar.com");
        INVALID_NAMES.add(".foo.com");
        INVALID_NAMES.add("..bar.com");
        INVALID_NAMES.add("baz..com");
        INVALID_NAMES.add("..quiffle.com");
        INVALID_NAMES.add("fleeb.com..");
        INVALID_NAMES.add(".");
        INVALID_NAMES.add("..");
        INVALID_NAMES.add("...");
        INVALID_NAMES.add("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.com");
        INVALID_NAMES.add(ALMOST_TOO_MANY_LEVELS + ".com");
        INVALID_NAMES.add(ALMOST_TOO_LONG + ".d");

        // The following are generally valid if the wildcards match at least against a single [a-zA-Z],
        // or even [a-zA-Z0-9] in some circumstances.
        // However, keep in mind that they can also match against invalid host names, like when matching a
        // single [-_.] or empty string. But, because this is a white list, this is not an issue for us.
        VALID_WILDCARD_NAMES.add("*.example.com");
        VALID_WILDCARD_NAMES.add("?.example.com");
        VALID_WILDCARD_NAMES.add("*-example.com");
        VALID_WILDCARD_NAMES.add("?-example.com");
        VALID_WILDCARD_NAMES.add("*_example.com");
        VALID_WILDCARD_NAMES.add("?_example.com");
        VALID_WILDCARD_NAMES.add("example.*");
        VALID_WILDCARD_NAMES.add("example.?");
        VALID_WILDCARD_NAMES.add("example-*");
        VALID_WILDCARD_NAMES.add("example-?");
        VALID_WILDCARD_NAMES.add("example_*");
        VALID_WILDCARD_NAMES.add("example_?");
        VALID_WILDCARD_NAMES.add("sub.*.example.com");
        VALID_WILDCARD_NAMES.add("sub.?.example.com");
        VALID_WILDCARD_NAMES.add("f--?.com");
        VALID_WILDCARD_NAMES.add("*");
        VALID_WILDCARD_NAMES.add("?");

        // The following cannot be valid in any circumstance, because they can never match against a valid host name.
        INVALID_WILDCARD_NAMES.add("abc.12*");
        INVALID_WILDCARD_NAMES.add("abc.12?");
        INVALID_WILDCARD_NAMES.add("foo*-.com");
        INVALID_WILDCARD_NAMES.add("foo?-.com");
        INVALID_WILDCARD_NAMES.add("_*bar.quux");
        INVALID_WILDCARD_NAMES.add("_?bar.quux");
        INVALID_WILDCARD_NAMES.add("foo?+*bar.com");
        INVALID_WILDCARD_NAMES.add("foo?!*bar.com");
        INVALID_WILDCARD_NAMES.add(".*foo.com");
        INVALID_WILDCARD_NAMES.add(".?foo.com");
        INVALID_WILDCARD_NAMES.add("..*bar.com");
        INVALID_WILDCARD_NAMES.add("..?bar.com");
        INVALID_WILDCARD_NAMES.add("*..bar.com");
        INVALID_WILDCARD_NAMES.add("?..bar.com");
        INVALID_WILDCARD_NAMES.add("baz*..com");
        INVALID_WILDCARD_NAMES.add("baz?..com");
        INVALID_WILDCARD_NAMES.add("fleeb.com*..");
        INVALID_WILDCARD_NAMES.add("fleeb.com?..");
        INVALID_WILDCARD_NAMES.add("fleeb.com..*");
        INVALID_WILDCARD_NAMES.add("fleeb.com..?");
    }

    @Test
    public void testHostParser() {
        assertNull(splitHostsLine("# [mocean.mobi]"));
        assertArrayEquals(
                new String[]{"127.0.0.1", "www.domain.com"},
                splitHostsLine("127.0.0.1 www.domain.com ## some comments #")
        );
        assertArrayEquals(
                new String[]{"127.0.0.1", "ad.domain.net"},
                splitHostsLine("127.0.0.1 ad.domain.net ## some comments")
        );
        assertArrayEquals(
                new String[]{"0.0.0.0", "tabs.example.com"},
                splitHostsLine(" \t0.0.0.0\t\ttabs.example.com\t")
        );
        assertArrayEquals(
                new String[]{"::1", "ipv6.example.com"},
                splitHostsLine("::1 ipv6.example.com#comment")
        );
        assertNull(splitHostsLine("0.0.0.0"));
        assertNull(splitHostsLine("0.0.0.0 "));
        assertNull(splitHostsLine("0.0.0.0 # no host"));
        assertNull(splitHostsLine("0.0.0.0#example.com"));
        assertNull(splitHostsLine(""));
    }

    @Test
    public void readsCommentsInBlockedLists() {
        SourceLoader loader = loader(false, false);
        assertHost(loader.parseLine("0.0.0.0 example.com"), ListType.BLOCKED, "example.com", null);
        assertHost(loader.parseLine("0.0.0.0 example.com # note"), ListType.BLOCKED, "example.com", null);
        assertHost(loader.parseLine("0.0.0.0 example.com#note"), ListType.BLOCKED, "example.com", null);
        assertHost(loader.parseLine("127.0.0.1\texample.com\t# note # more"), ListType.BLOCKED, "example.com", null);
        assertNull(loader.parseLine("# 0.0.0.0 example.com"));
        assertNull(loader.parseLine("  # 0.0.0.0 example.com"));
        assertNull(loader.parseLine("0.0.0.0 # example.com"));
        assertNull(loader.parseLine("0.0.0.0#example.com"));
    }

    @Test
    public void readsCommentsInRedirectedLists() {
        SourceLoader loader = loader(false, true);
        assertHost(loader.parseLine("10.0.0.1 example.com # note"), ListType.REDIRECTED, "example.com", "10.0.0.1");
        assertHost(loader.parseLine("10.0.0.1 example.com#note"), ListType.REDIRECTED, "example.com", "10.0.0.1");
        assertNull(loader.parseLine("# 10.0.0.1 example.com"));
        assertNull(loader.parseLine("10.0.0.1 # example.com"));
    }

    @Test
    public void readsCommentsInAllowLists() {
        SourceLoader loader = loader(true, false);
        assertHost(loader.parseLine("example.com"), ListType.ALLOWED, "example.com", null);
        // A comment ending the line is dropped, so the host before it is kept.
        assertHost(loader.parseLine("example.com # note"), ListType.ALLOWED, "example.com", null);
        assertHost(loader.parseLine("example.com#note"), ListType.ALLOWED, "example.com", null);
        assertHost(loader.parseLine("\t*.example.com\t# wildcard # note"), ListType.ALLOWED, "*.example.com", null);
        // A line starting with one is a comment.
        assertNull(loader.parseLine("# example.com"));
        assertNull(loader.parseLine("   # example.com"));
        assertNull(loader.parseLine("#"));
        assertNull(loader.parseLine(""));
    }

    private static SourceLoader loader(boolean allowEnabled, boolean redirectEnabled) {
        HostsSource source = new HostsSource();
        source.setId(3);
        source.setAllowEnabled(allowEnabled);
        source.setRedirectEnabled(redirectEnabled);
        return new SourceLoader(source);
    }

    private static void assertHost(HostListItem item, ListType type, String host, String redirection) {
        assertNotNull("The line must list a host.", item);
        assertEquals(type, item.getType());
        assertEquals(host, item.getHost());
        assertEquals(redirection, item.getRedirection());
        assertEquals(3, item.getSourceId());
        assertTrue(item.isEnabled());
    }

    @Test
    public void isValidHostname() {
        for (String validName : VALID_NAMES) {
            assertTrue(
                    "The hostname '" + validName + "' should be valid.",
                    RegexUtils.isValidHostname(validName)
            );
        }
    }

    @Test
    public void isValidWhitelistHostname() {
        for (String validName : VALID_NAMES) {
            assertTrue(
                    "The hostname '" + validName + "' should be a valid white list host name.",
                    RegexUtils.isValidWildcardHostname(validName)
            );
        }
        for (String wildcardName : VALID_WILDCARD_NAMES) {
            assertTrue(
                    "The wildcard hostname '" + wildcardName + "' should be valid.",
                    RegexUtils.isValidWildcardHostname(wildcardName)
            );
        }
    }

    @Test
    public void isInvalidHostname() {
        for (String invalidName : INVALID_NAMES) {
            assertFalse(
                    "The hostname '" + invalidName + "' should not be valid.",
                    RegexUtils.isValidHostname(invalidName)
            );
        }
    }

    @Test
    public void isInvalidWhitelistHostname() {
        for (String invalidName : INVALID_NAMES) {
            assertFalse(
                    "The hostname '" + invalidName + "' should not be valid.",
                    RegexUtils.isValidWildcardHostname(invalidName)
            );
        }
        for (String invalidWildcardName : INVALID_WILDCARD_NAMES) {
            assertFalse(
                    "The wildcard hostname '" + invalidWildcardName + "' should not be valid.",
                    RegexUtils.isValidWildcardHostname(invalidWildcardName)
            );
        }
    }
}
