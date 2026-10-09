package org.adaway.model.source;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.google.common.net.InternetDomainName;

import org.adaway.util.RegexUtils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the hand written parsing gives exactly the results of what it replaced: the regular
 * expression that split hosts lines, extended to the other host names of a line, and Guava's host
 * name validation.
 * <p>
 * Besides the edge cases listed, it compares both on hundreds of thousands of generated inputs.
 * The generator is seeded, so a failure always reproduces.
 */
public class HostsParsingEquivalenceTest {
    /**
     * The expression hosts lines used to be split with.
     */
    private static final Pattern HOSTS_PARSER = Pattern.compile("^\\s*([^#\\s]+)\\s+([^#\\s]+).*$");
    private static final Pattern WILDCARD = Pattern.compile("[*?]");
    private static final Pattern BLANKS = Pattern.compile("\\s+");
    /**
     * The characters lines are generated from: every blank of the expression, the comment sign,
     * line terminators the expression treats specially, and blanks it does not know.
     */
    private static final char[] LINE_CHARACTERS = {
            ' ', '\t', '\u000B', '\f', '\r', '\n', '#', '#', '.', ':', 'a', 'b', '0', '1',
            '\u0085', '\u2028', '\u2029', '\u00A0', '\u3000', '\u00E9'
    };
    /**
     * The characters host names are generated from: every kind the validation tells apart.
     */
    private static final char[] NAME_CHARACTERS = {
            'a', 'z', 'A', 'Z', 'm', '0', '9', '5', '-', '_', '.', '.', '.', '*', '?', ' ', '+',
            '/', ':', '@', '`', '{', '[', '\u007F', '\u00E9', '\u3002', '\uFF0E', '\uFF61'
    };

    @Test
    public void splitsLinesAsTheExpression() {
        List<String> lines = new ArrayList<>();
        String[] edgeCases = {
                "", " ", "#", "a", "a b", " a b ", "a  b c", "a\tb", "a\u000Bb", "a\fb", "a\rb",
                "a\nb", "a b\n", "a b\r", "a b\r\n", "a b \n", "a b\u0085", "a b \u0085",
                "a\u0085b c", "a b\u2028c", "a b \u2029", "a#b c", "a #b", "a b#c", "a b #c\n",
                "#a b", " #a b", "a\u00A0b", "a \u00A0b", "\u3000a b", "a b c d e",
                "0.0.0.0 example.com", "127.0.0.1\texample.com # comment",
                "::1 example.com", "0.0.0.0 example.com#", "0.0.0.0 #example.com"
        };
        for (String line : edgeCases) {
            lines.add(line);
        }
        Random random = new Random(20261009L);
        for (int i = 0; i < 300_000; i++) {
            lines.add(randomString(random, LINE_CHARACTERS, random.nextInt(14)));
        }
        for (String line : lines) {
            assertArrayEquals("Line " + escape(line), splitAsTheExpression(line), SourceLoader.splitHostsLine(line));
        }
    }

    @Test
    public void validatesHostNamesAsGuava() {
        List<String> names = new ArrayList<>();
        String[] edgeCases = {
                "", ".", "..", "a", "a.", "a..", ".a", "a.b", "a.b.", "a-.b", "-a.b", "a_.b",
                "_a.b", "a.-b", "a.b-", "1.2", "a.1", "a.1b", "1a.b", "a.b1", "a.\u00E9",
                "\u00E9.a", "a\u3002b", "a\uFF0Eb", "a\uFF61b", "a.b\u3002", "*.a", "a?.b",
                "a b", "A.B", "a.B-c"
        };
        for (String name : edgeCases) {
            names.add(name);
        }
        Random random = new Random(1553L);
        // Names near every limit: total length, label length and label count.
        for (int length = 245; length <= 260; length++) {
            names.add(repeatedLabels(length, 1));
            names.add(repeatedLabels(length, 5));
            names.add(repeatedLabels(length, 62));
            names.add(repeatedLabels(length, 63));
            names.add(repeatedLabels(length, 64));
            names.add(repeatedLabels(length, 5) + ".");
            names.add(repeatedLabels(length, 5) + "..");
        }
        for (int labels = 120; labels <= 130; labels++) {
            StringBuilder name = new StringBuilder("a");
            for (int i = 1; i < labels; i++) {
                name.append(".a");
            }
            names.add(name.toString());
            names.add(name + ".");
        }
        for (int i = 0; i < 150_000; i++) {
            names.add(randomString(random, NAME_CHARACTERS, random.nextInt(20)));
        }
        // Valid looking names, with the occasional invalid character.
        for (int i = 0; i < 50_000; i++) {
            names.add(randomLabels(random));
        }
        for (String name : names) {
            assertEquals(
                    "Host name " + escape(name),
                    InternetDomainName.isValid(name),
                    RegexUtils.isValidHostname(name)
            );
            assertEquals(
                    "Wildcard host name " + escape(name),
                    isValidWildcardHostnameAsBefore(name),
                    RegexUtils.isValidWildcardHostname(name)
            );
        }
    }

    /**
     * Split a line as the expression did, then add the other host names it ignored: those
     * separated by blanks after the first, up to a comment.
     */
    private static String[] splitAsTheExpression(String line) {
        Matcher matcher = HOSTS_PARSER.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        fields.add(matcher.group(1));
        fields.add(matcher.group(2));
        String rest = line.substring(matcher.end(2));
        int comment = rest.indexOf('#');
        if (comment != -1) {
            rest = rest.substring(0, comment);
        }
        for (String name : BLANKS.split(rest)) {
            if (!name.isEmpty()) {
                fields.add(name);
            }
        }
        return fields.toArray(new String[0]);
    }

    /**
     * How wildcard host names used to be validated.
     */
    private static boolean isValidWildcardHostnameAsBefore(String hostname) {
        Matcher matcher = WILDCARD.matcher(hostname);
        String clearedHostname = matcher.replaceAll("");
        String replacedHostname = matcher.replaceAll("a");
        return InternetDomainName.isValid(clearedHostname) || InternetDomainName.isValid(replacedHostname);
    }

    private static String repeatedLabels(int length, int labelLength) {
        StringBuilder name = new StringBuilder();
        while (name.length() < length) {
            if (name.length() > 0) {
                name.append('.');
            }
            for (int i = 0; i < labelLength && name.length() < length; i++) {
                name.append('a');
            }
        }
        return name.toString();
    }

    private static String randomLabels(Random random) {
        int labels = 1 + random.nextInt(6);
        StringBuilder name = new StringBuilder();
        for (int label = 0; label < labels; label++) {
            if (label > 0) {
                name.append('.');
            }
            int length = 1 + random.nextInt(random.nextInt(10) == 0 ? 70 : 12);
            for (int i = 0; i < length; i++) {
                int pick = random.nextInt(100);
                if (pick < 70) {
                    name.append((char) ('a' + random.nextInt(26)));
                } else if (pick < 85) {
                    name.append((char) ('0' + random.nextInt(10)));
                } else if (pick < 93) {
                    name.append(random.nextBoolean() ? '-' : '_');
                } else if (pick < 98) {
                    name.append((char) ('A' + random.nextInt(26)));
                } else {
                    name.append(NAME_CHARACTERS[random.nextInt(NAME_CHARACTERS.length)]);
                }
            }
        }
        if (random.nextInt(20) == 0) {
            name.append('.');
        }
        return name.toString();
    }

    private static String randomString(Random random, char[] characters, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = characters[random.nextInt(characters.length)];
        }
        return new String(chars);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c >= 0x20 && c < 0x7F) {
                escaped.append(c);
            } else {
                escaped.append(String.format("\\u%04X", (int) c));
            }
        }
        return escaped.append('"').toString();
    }
}
