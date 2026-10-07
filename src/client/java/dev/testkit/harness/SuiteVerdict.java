package dev.testkit.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What failed in this run, so one failure does not end the whole suite.
 *
 * <p>Fabric's own test loop rethrows the first failure, which takes the client down with it: every test class
 * registered after the failing one never runs, and finding out what else is broken means relaunching the client
 * and the anticheat server once per failure. {@code MixinClientGameTestRunner} catches each test's failure,
 * records it here, puts the client back on the title screen and carries on. The run still fails at the end,
 * listing every failure at once.
 *
 * <p>The failed scenario names are written to {@link #failedFile()}, and {@code -Pfailed} reads them back as the
 * scenario filter, so the next run is only what failed.
 *
 * <p>It also catches a scenario that <b>started and never finished</b> — got past its filter, never threw, and
 * never reached its verdict. That is the worst failure a suite like this can have: a scenario that did nothing,
 * reported as a green build. Every name {@code Scenario.run} starts must either finish or fail.
 */
public final class SuiteVerdict {

    private static final List<String> failures = new ArrayList<>();
    private static final Set<String> failedNames = new LinkedHashSet<>();
    private static final Set<String> expected = new LinkedHashSet<>();
    private static final Set<String> finished = new LinkedHashSet<>();

    /** The scenario that last got past the filter, so a failure is filed under its name, not its class. */
    private static volatile String current;

    private SuiteVerdict() {
    }

    /** Where the failed names go. Set by Gradle to a file in {@code build/}; relative to the run dir otherwise. */
    public static Path failedFile() {
        return Path.of(System.getProperty("testkit.failedFile", "gametest-failed.txt"));
    }

    /** A scenario got past the filter. Failures from here on are filed under its name. */
    public static void started(String name) {
        current = name;
    }

    /** A scenario is about to run a body, and must reach {@link #finished} or fail. */
    public static void expect(String name) {
        current = name;
        expected.add(name);
    }

    /** A scenario reached its verdict. */
    public static void finished(String name) {
        finished.add(name);
    }

    /** Called before each test class, so a failure is never pinned on the previous class's scenario. */
    public static void beginTest() {
        current = null;
    }

    /** Record a test's failure. {@code test} is the test class, used when no scenario name ran. */
    public static void fail(String test, Throwable failure) {
        String name = current != null ? current : test;
        failedNames.add(name);
        String why = String.valueOf(failure.getMessage()).lines().findFirst().orElse(failure.toString());
        failures.add(name + " (" + test + "): " + failure.getClass().getSimpleName() + ": " + why);
        System.out.println("[suite] FAILED " + name + " — " + why + " — carrying on with the rest");
        failure.printStackTrace(System.out);
    }

    public static boolean anyFailed() {
        return !failures.isEmpty();
    }

    /** Fail every scenario that started and never finished, write (or clear) the failed-names file, summarise. */
    public static String finish() {
        for (String name : expected) {
            if (!finished.contains(name) && !failedNames.contains(name)) {
                failedNames.add(name);
                failures.add(name + ": started and never finished — it threw nothing and reached no verdict, "
                        + "so it tested nothing");
                System.out.println("[suite] FAILED " + name + " — started and never finished");
            }
        }
        Path file = failedFile();
        try {
            if (failedNames.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, String.join(",", failedNames), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.out.println("[suite] could not write " + file.toAbsolutePath() + ": " + e);
        }
        if (failures.isEmpty()) {
            return "[suite] " + finished.size() + " scenario(s) reached a verdict, none failed";
        }
        StringBuilder out = new StringBuilder("[suite] " + failures.size()
                + " failure(s); re-run only these with -Pfailed:");
        for (String line : failures) {
            out.append("\n  ").append(line);
        }
        return out.toString();
    }
}
