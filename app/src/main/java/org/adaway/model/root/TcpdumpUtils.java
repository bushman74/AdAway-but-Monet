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

package org.adaway.model.root;

import android.content.Context;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.DnsRequest;
import org.adaway.util.PrivateDns;

import com.topjohnwu.superuser.Shell;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static org.adaway.model.root.ShellUtils.mergeAllLines;

import timber.log.Timber;

class TcpdumpUtils {
    private static final String TCPDUMP_EXECUTABLE = "tcpdump";
    private static final String TCPDUMP_LOG = "dns_log.txt";
    /**
     * A directory the privileged shell can both write to and execute from.
     */
    private static final String SHELL_TEMPORARY_DIRECTORY = "/data/local/tmp";
    /**
     * The capture program some systems provide, used when the bundled one does not run.
     */
    private static final String SYSTEM_TCPDUMP = "/system/bin/tcpdump";
    /**
     * The capture arguments, most wanted first. Kept in one place because the running capture is
     * recognised by them.
     *
     * The capture is deliberately not verbose. Asked to be, it prints the packet header and the
     * packet content on two separate lines, leaving the requested host name on a line of its own
     * with no time on it. Without it, each request is one line carrying both.
     *
     * The first set asks for a dated timestamp on every line, so each request can be shown with
     * the time it was made. The second leaves the capture to timestamp lines its own way, and the
     * third drops timestamps altogether; they are only used if a capture program does not
     * understand what comes before, so an unusual one still records the host names.
     */
    private static final String[] CAPTURE_ARGUMENT_VARIANTS = {
            "-i any -p -l -tttt -s 512 'udp dst port 53'",
            "-i any -p -l -s 512 'udp dst port 53'",
            "-i any -p -l -t -s 512 'udp dst port 53'"
    };
    /**
     * Recognises a running capture whichever program is running it.
     * Matched against the whole command line, so it covers both the bundled program and a system
     * one. The first character is bracketed so the shell command carrying the pattern is not
     * reported as a match.
     */
    private static final String CAPTURE_PROCESS_PATTERN = "[t]cpdump.*udp dst port 53";
    private static final String TCPDUMP_HOSTNAME_REGEX = "(?:A\\?|AAAA\\?)\\s(\\S+)\\.\\s";
    /**
     * The delay before checking the capture is still alive, in milliseconds.
     */
    private static final long START_CHECK_DELAY_MS = 500L;
    /**
     * The number of log file lines reported when a capture fails to start.
     */
    private static final int LOG_TAIL_LINES = 10;
    private static final Pattern TCPDUMP_HOSTNAME_PATTERN = Pattern.compile(TCPDUMP_HOSTNAME_REGEX);
    /**
     * Recognises the dated timestamp starting a capture line, ignoring its fraction of a second.
     */
    private static final Pattern TCPDUMP_DATE_TIME_PATTERN =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    /**
     * Recognises the time of day starting a capture line that carries no date.
     */
    private static final Pattern TCPDUMP_TIME_PATTERN =
            Pattern.compile("^(\\d{2}:\\d{2}:\\d{2})");
    private static final DateTimeFormatter TCPDUMP_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Private constructor.
     */
    private TcpdumpUtils() {

    }

    /**
     * Checks if tcpdump is running
     *
     * @return true if tcpdump is running
     */
    static boolean isTcpdumpRunning() {
        return ShellUtils.isProcessRunning(CAPTURE_PROCESS_PATTERN);
    }

    /**
     * Start tcpdump tool.
     *
     * @param context The application context.
     * @return {@code null} when the capture started, otherwise the reason it did not
     */
    static String startTcpdump(Context context) {
        Timber.d("Starting tcpdump...");

        // Root is required to capture packets at all.
        if (!Shell.getShell().isRoot()) {
            return context.getString(R.string.dns_recording_error_no_root);
        }

        File file = getLogFile(context);
        try {
            // Create log file before using it with tcpdump if not exists
            if (!file.exists() && !file.createNewFile()) {
                return context.getString(R.string.dns_recording_error_log_file, file.getAbsolutePath());
            }
        } catch (IOException e) {
            Timber.e(e, "Problem while getting cache directory!");
            return context.getString(R.string.dns_recording_error_log_file, file.getAbsolutePath());
        }

        // Take over from any capture already running: it may have been started by an earlier
        // version of the application, with arguments that record no time, and leaving it alone
        // would look like a capture that started but never timestamps anything.
        stopTcpdump();

        StringBuilder attempts = new StringBuilder();
        for (String candidate : captureCandidates(context)) {
            for (String arguments : CAPTURE_ARGUMENT_VARIANTS) {
                if (startCapture(context, candidate, arguments, file)) {
                    Timber.i("Capturing DNS requests with %s %s.", candidate, arguments);
                    PreferenceHelper.setLastWorkingCapture(context, candidate);
                    return null;
                }
            }
            describeFailedAttempt(context, candidate, file, attempts);
        }
        return context.getString(R.string.dns_recording_error_not_runnable, attempts.toString());
    }

    /**
     * The capture programs to try, in order of preference.
     *
     * The one shipped with the application is preferred. It is also tried from the shell's own
     * temporary directory, in case the shell may not execute a file owned by the application, and
     * finally the system provides one on some devices.
     */
    private static List<String> captureCandidates(Context context) {
        List<String> candidates = new ArrayList<>();
        // Whatever worked last time is tried first, so a device that needs a fallback does not run
        // through the failing candidates on every start.
        String remembered = PreferenceHelper.getLastWorkingCapture(context);
        if (remembered != null) {
            candidates.add(remembered);
        }
        String bundled = new File(
                context.getApplicationInfo().nativeLibraryDir,
                ShellUtils.getExecutableName(TCPDUMP_EXECUTABLE)).getAbsolutePath();
        if (new File(bundled).exists()) {
            addCandidate(candidates, bundled);
            // Keep the same file name so a running capture is still recognised.
            String copy = SHELL_TEMPORARY_DIRECTORY + File.separator
                    + ShellUtils.getExecutableName(TCPDUMP_EXECUTABLE);
            if (Shell.cmd("cp -f " + bundled + " " + copy + " && chmod 700 " + copy).exec().isSuccess()) {
                addCandidate(candidates, copy);
            }
        }
        addCandidate(candidates, SYSTEM_TCPDUMP);
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String candidate) {
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    /**
     * Start a capture and report whether it is still running shortly afterwards.
     *
     * The shell reports success as soon as it backgrounds the command, so only the liveness check
     * tells whether the program actually ran.
     */
    private static boolean startCapture(
            Context context, String executable, String arguments, File logFile) {
        String command = libraryPathPrefix(context, executable)
                + executable + " " + arguments + " >> " + logFile + " 2>&1 &";
        Shell.cmd(command).exec();
        try {
            Thread.sleep(START_CHECK_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return isTcpdumpRunning();
    }

    /**
     * Record why a capture program did not work, running it once more synchronously to read the
     * exit status the backgrounded command could not report.
     */
    private static void describeFailedAttempt(
            Context context, String executable, File logFile, StringBuilder attempts) {
        if (attempts.length() > 0) {
            attempts.append("; ");
        }
        Shell.Result probe = Shell.cmd(
                libraryPathPrefix(context, executable) + executable + " --version").exec();
        attempts.append(executable).append(" -> ").append(describeExit(probe.getCode()));
        String error = ShellUtils.mergeAllLines(probe.getErr()).trim();
        if (!error.isEmpty()) {
            attempts.append(": ").append(error);
        } else {
            String output = readLogFileTail(context);
            if (!output.isEmpty()) {
                attempts.append(": ").append(output);
            }
        }
    }

    /**
     * The library path a capture program needs.
     *
     * Only the programs shipped with the application link against its libraries. Pointing a system
     * program at them would have it load libraries it was not built against.
     */
    private static String libraryPathPrefix(Context context, String executable) {
        if (SYSTEM_TCPDUMP.equals(executable)) {
            return "";
        }
        return "LD_LIBRARY_PATH=" + context.getApplicationInfo().nativeLibraryDir + " ";
    }

    /**
     * Describe a shell exit status, naming the signal when the program was killed by one.
     * A program that crashes on its first instruction is reported as such rather than as an
     * opaque number.
     */
    private static String describeExit(int code) {
        if (code <= 128) {
            return "exit code " + code;
        }
        String signal;
        switch (code - 128) {
            case 4:
                signal = "SIGILL, illegal instruction: the program does not match this processor";
                break;
            case 6:
                signal = "SIGABRT, aborted";
                break;
            case 7:
                signal = "SIGBUS, bad memory access";
                break;
            case 9:
                signal = "SIGKILL, killed";
                break;
            case 11:
                signal = "SIGSEGV, invalid memory access";
                break;
            default:
                signal = "signal " + (code - 128);
                break;
        }
        return "exit code " + code + " (" + signal + ")";
    }

    /**
     * Read the end of the log file, to report why a capture did not start.
     *
     * @param context The application context.
     * @return The last lines of the log file, or an empty string if it could not be read.
     */
    private static String readLogFileTail(Context context) {
        try {
            List<String> lines = Files.readAllLines(getLogFile(context).toPath());
            int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Tell whether encrypted DNS hides the requested host names from the capture.
     *
     * The capture reads plain DNS on port 53. With Private DNS the requests leave the device over
     * TLS, so the packets carry no readable host name and the log stays empty however well the
     * capture runs.
     *
     * @param context The application context.
     * @return The warning to report, or {@code null} when plain DNS is in use.
     */
    static String getEncryptedDnsWarning(Context context) {
        String serverName = PrivateDns.getActiveServerName(context);
        if (serverName == null) {
            return null;
        }
        return context.getString(R.string.dns_recording_warning_private_dns, serverName);
    }

    /**
     * Stop tcpdump.
     */
    static void stopTcpdump() {
        ShellUtils.killProcesses(CAPTURE_PROCESS_PATTERN);
    }

    /**
     * Check if tcpdump binary in bundled in the system.
     */
    static void checkSystemTcpdump() {
        try {
            Shell.Result result = Shell.cmd("tcpdump --version").exec();
            int exitCode = result.getCode();
            String output = mergeAllLines(result.getOut());
            String msg = "Tcpdump " + (
                            exitCode == 0 ?
                                    "present" :
                                    "missing (" + exitCode + ")"
                    ) + "\n" + output;
            Timber.i(msg);
        } catch (Exception exception) {
            Timber.w(exception, "Failed to check system tcpdump binary.");
        }
    }

    /**
     * Get the tcpdump log file.
     *
     * @param context The application context.
     * @return The tcpdump log file.
     */
    static File getLogFile(Context context) {
        return new File(context.getCacheDir(), TCPDUMP_LOG);
    }

    /**
     * Get the recorded requests.
     *
     * A host is reported once, with the time it was last requested. Lines written by a capture
     * that does not timestamp them are reported without a time rather than dropped.
     *
     * @param context The application context.
     * @return The requests read from the tcpdump log file.
     */
    static List<DnsRequest> getRequests(Context context) {
        Path logPath = getLogFile(context).toPath();
        // Check if the log file exists
        if (!Files.exists(logPath)) {
            return emptyList();
        }
        try (Stream<String> lines = Files.lines(logPath)) {
            return parseRequests(lines);
        } catch (IOException | UncheckedIOException exception) {
            Timber.e(exception, "Failed to read the DNS request log.");
            return emptyList();
        }
    }

    /**
     * Read the requests out of capture output.
     *
     * @param lines The lines written by the capture.
     * @return One request per host, in the order the host first appeared, carrying the time of its
     * last request.
     */
    static List<DnsRequest> parseRequests(Stream<String> lines) {
        Map<String, Instant> lastSeenByHost = new LinkedHashMap<>();
        // A request belongs to the last time read, not necessarily to the line naming it: asked to
        // be verbose, a capture puts the packet header, which carries the time, on the line before
        // the one carrying the host name.
        Instant time = null;
        for (Iterator<String> iterator = lines.iterator(); iterator.hasNext(); ) {
            String line = iterator.next();
            Instant lineTime = getTcpdumpTime(line);
            if (lineTime != null) {
                time = lineTime;
            }
            String host = getTcpdumpHostname(line);
            if (host != null) {
                lastSeenByHost.put(host, time);
            }
        }
        return lastSeenByHost.entrySet().stream()
                .map(entry -> new DnsRequest(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Delete log file of tcpdump.
     *
     * @param context The application context.
     */
    static boolean clearLogFile(Context context) {
        // Get the log file
        File file = getLogFile(context);
        // Check if file exists
        if (!file.exists()) {
            return true;
        }
        // Truncate the file content
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            // Only truncate the file
            outputStream.close();   // Useless but help lint
        } catch (IOException exception) {
            Timber.e(exception, "Error while truncating the tcpdump file!");
            // Return failed to clear the log file
            return false;
        }
        // Return successfully clear the log file
        return true;
    }

    /**
     * Gets hostname out of tcpdump log line.
     *
     * @param input One line from dns log.
     * @return A hostname or {code null} if no DNS query in the input.
     */
    private static String getTcpdumpHostname(String input) {
        Matcher tcpdumpHostnameMatcher = TCPDUMP_HOSTNAME_PATTERN.matcher(input);
        if (tcpdumpHostnameMatcher.find()) {
            return tcpdumpHostnameMatcher.group(1);
        } else {
            return null;
        }
    }

    /**
     * Gets the time a tcpdump log line was written.
     *
     * The capture prints the time in the local time zone. A line without one comes from a capture
     * started without timestamps, either an older one or a program that did not support them.
     *
     * @param input One line from the dns log.
     * @return The time of the line, or {@code null} if it carries none.
     */
    private static Instant getTcpdumpTime(String input) {
        try {
            Matcher dated = TCPDUMP_DATE_TIME_PATTERN.matcher(input);
            if (dated.find()) {
                return LocalDateTime.parse(dated.group(1), TCPDUMP_DATE_TIME_FORMATTER)
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
            }
            Matcher timeOnly = TCPDUMP_TIME_PATTERN.matcher(input);
            if (timeOnly.find()) {
                return atMostRecentOccurrence(LocalTime.parse(timeOnly.group(1)));
            }
        } catch (DateTimeParseException exception) {
            Timber.d("Unreadable timestamp in the DNS request log: %s.", input);
        }
        return null;
    }

    /**
     * Place a time of day on the day it last occurred.
     *
     * A capture that timestamps its lines with the time alone leaves the date out. Today is the
     * answer for all but the lines written before midnight and read after it, which would
     * otherwise be dated in the future.
     */
    private static Instant atMostRecentOccurrence(LocalTime time) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime today = time.atDate(LocalDate.now(zone)).atZone(zone);
        return today.isAfter(ZonedDateTime.now(zone))
                ? today.minusDays(1).toInstant()
                : today.toInstant();
    }
}
