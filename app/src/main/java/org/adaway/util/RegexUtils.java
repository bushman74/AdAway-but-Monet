/*
 * Copyright (C) 2011-2012 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of AdAway.
 *
 * AdAway is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AdAway is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AdAway.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.adaway.util;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

public class RegexUtils {
    private static final Pattern WILDCARD_PATTERN = Pattern.compile("[*?]");
    /**
     * The longest valid host name, without its trailing dot.
     */
    private static final int MAX_LENGTH = 253;
    /**
     * The most labels a valid host name has.
     */
    private static final int MAX_LABELS = 127;
    /**
     * The longest valid label.
     */
    private static final int MAX_LABEL_LENGTH = 63;

    /**
     * Check whether a hostname is valid.
     * <p>
     * The rules are those of {@link InternetDomainName#isValid(String)}, which is called for the
     * names holding non-ASCII characters. The other names, nearly all of them, are checked here
     * in a single pass: Guava builds a list of labels and several copies of the name to check
     * one, which made validation most of the cost of parsing a source.
     *
     * @param hostname The hostname to validate.
     * @return return {@code true} if hostname is valid, {@code false} otherwise.
     */
    public static boolean isValidHostname(String hostname) {
        int length = hostname.length();
        for (int index = 0; index < length; index++) {
            if (hostname.charAt(index) >= 0x80) {
                return InternetDomainName.isValid(hostname);
            }
        }
        // A single trailing dot is allowed.
        if (length > 0 && hostname.charAt(length - 1) == '.') {
            length--;
        }
        if (length > MAX_LENGTH) {
            return false;
        }
        int labels = 0;
        int labelStart = 0;
        for (int index = 0; index <= length; index++) {
            if (index == length || hostname.charAt(index) == '.') {
                int labelLength = index - labelStart;
                if (labelLength < 1 || labelLength > MAX_LABEL_LENGTH) {
                    return false;
                }
                // No label starts or ends with a dash or an underscore.
                char first = hostname.charAt(labelStart);
                char last = hostname.charAt(index - 1);
                if (first == '-' || first == '_' || last == '-' || last == '_') {
                    return false;
                }
                // The last label does not start with a digit, which tells names from addresses.
                if (index == length && first >= '0' && first <= '9') {
                    return false;
                }
                labels++;
                labelStart = index + 1;
            } else if (!isLabelCharacter(hostname.charAt(index))) {
                return false;
            }
        }
        return labels <= MAX_LABELS;
    }

    private static boolean isLabelCharacter(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '_';
    }

    /**
     * Check whether a wildcard hostname is valid.
     * Wildcard hostname is an hostname with one of the following wildcard:
     * <ul>
     * <li>{@code *} for any character sequence,</li>
     * <li>{@code ?} for any character</li>
     * </ul>
     * <p/>
     * Wildcard validation is quite tricky, because wildcards can be placed anywhere and can match with
     * anything. To make sure we don't dismiss certain valid wildcard host names, we trim wildcards
     * or replace them with an alphanumeric character for further validation.<br/>
     * We only reject whitelist host names which cannot match against valid host names under any circumstances.
     *
     * @param hostname The wildcard hostname to validate.
     * @return return {@code true} if wildcard hostname is valid, {@code false} otherwise.
     */
    public static boolean isValidWildcardHostname(String hostname) {
        // Without wildcard, both names below are the name itself.
        if (hostname.indexOf('*') == -1 && hostname.indexOf('?') == -1) {
            return isValidHostname(hostname);
        }
        // Clear wildcards from host name then validate it
        Matcher matcher = WILDCARD_PATTERN.matcher(hostname);
        String clearedHostname = matcher.replaceAll("");
        // Replace wildcards from host name by an alphanumeric character
        String replacedHostname = matcher.replaceAll("a");
        // Check if any hostname is valid
        return isValidHostname(clearedHostname) || isValidHostname(replacedHostname);
    }

    /**
     * Check if an IP address is valid.
     *
     * @param ip The IP to validate.
     * @return {@code true} if the IP is valid, {@code false} otherwise.
     */
    public static boolean isValidIP(String ip) {
        try {
            InetAddresses.forString(ip);
            return true;
        } catch (IllegalArgumentException exception) {
            Timber.d(exception, "Invalid IP address: %s.", ip);
            return false;
        }
    }

    /*
     * Transforms String with * and ? characters to regex String, convert "example*.*" to regex
     * "^example.*\\..*$", from http://www.rgagnon.com/javadetails/java-0515.html
     */
    public static String wildcardToRegex(String wildcard) {
        StringBuilder regex = new StringBuilder(wildcard.length());
        regex.append('^');
        for (int i = 0, is = wildcard.length(); i < is; i++) {
            char c = wildcard.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append(".");
                    break;
                // escape special regex-characters
                case '(':
                case ')':
                case '[':
                case ']':
                case '$':
                case '^':
                case '.':
                case '{':
                case '}':
                case '|':
                case '\\':
                    regex.append("\\");
                    regex.append(c);
                    break;
                default:
                    regex.append(c);
                    break;
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
