package dev.testkit.gametest;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A real dedicated Minecraft server, with GrimAC on it, run as a <b>separate process</b> for the
 * duration of a scenario.
 *
 * <h2>Why a separate process</h2>
 * The obvious approach — the client gametest API's own {@code createServer()} — runs the server inside
 * the client's JVM, and that JVM is {@code EnvType.CLIENT}. In that environment the PacketEvents build
 * GrimAC bundles installs client-side hooks that are incomplete on 26.1.2:
 * {@code FabricPacketEventsAPIManagerFactory.getClientLazyPlayerManagerHolder()} returns null and the
 * client's own connection dies with <i>Invalid player data</i> the instant it joins. Its pre-launch
 * additionally calls {@code addAll} on the immutable list {@code FabricLoader.getEntrypoints} returns,
 * but only down the client branch. Neither happens in {@code EnvType.SERVER}. So the anticheat gets its
 * own process and the client stays clean — which is also how it runs in production, and therefore what
 * the test should be reproducing anyway.
 *
 * <p>The launch spec (java binary, classpath, main class, working directory) is written by Gradle's
 * {@code writeTestServerLaunch} task from loom's own {@code runTestServer} configuration, so this
 * replays exactly what {@code ./gradlew runTestServer} would do rather than guessing at a dev launch.
 */
public final class TestServer implements AutoCloseable {

    /** The server's whole console, mirrored here: a failed startup explains itself hundreds of lines
     * above wherever a tail happens to begin. */
    private static Path consoleLog = Path.of("testserver-console.log");

    /** Whether this client JVM has started a server yet, so the console log starts fresh once per run. */
    private static boolean startedOnce;

    /** Read from the staged {@code server.properties} on every start, so changing it there is all it takes. */
    private static int port = 25565;

    /** A saved world to start the next server on instead of a generated one, and where to spawn in it. */
    private static Path seedWorld;
    private static int[] seedSpawn;

    /** Long enough for a cold dev-classpath server with an anticheat on it. */
    private static final int START_TIMEOUT_SECONDS = 180;
    private static final int STOP_TIMEOUT_SECONDS = 30;

    private final Process process;
    private final Writer console;
    private final List<String> log = new ArrayList<>();
    private int marked;
    private int syncCounter;

    private TestServer(Process process) {
        this.process = process;
        this.console = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    /**
     * Where the client should connect: the {@code server-port} in the staged {@code server.properties}, 25565
     * unless it says otherwise. Change it there if something else on the machine holds 25565 — a server of
     * your own, or a second checkout running its suite at the same time.
     */
    public static String address() {
        return "localhost:" + port;
    }

    /**
     * Start the <b>next</b> server on a copy of a saved world instead of a generated one — a map you built,
     * or one downloaded from the server your mod is meant for. Consumed by the next {@link #start}, so a
     * scenario sets it after {@code Scenario.skip(name)} has said it runs, right before {@code Scenario.run},
     * and clears it ({@code null, null}) in a {@code finally}: a seed left behind by a scenario that was
     * skipped would otherwise start the scenario after it on the wrong world.
     *
     * <p>The copy takes {@code level.dat}, {@code data/} (which on 26.1.2 holds the world generator settings)
     * and {@code dimensions/} (the region files), and leaves out the session lock, the player data, the legacy
     * folders and the saved entities and points of interest: a scenario spawns what it needs. The server's
     * world is wiped before the copy as always, so the save itself is never touched.
     *
     * @param save  the save folder, or null to clear
     * @param spawn where to put the world spawn once the server is up, or null to keep the save's own — which
     *              is wherever the save happened to be made from, often over nothing, and a player who joins
     *              there falls until the scenario's first teleport
     */
    public static void seedWorldFrom(Path save, int[] spawn) {
        seedWorld = save;
        seedSpawn = spawn;
    }

    /**
     * The save named by {@code -Pworld=<name>}: a folder under {@link #savesDir()}, or an absolute path.
     * Null when no {@code -Pworld} was given, so a scenario can fall back to a default of its own.
     */
    public static Path worldFromProperty() {
        String world = System.getProperty("testkit.world", "");
        if (world.isBlank()) {
            return null;
        }
        Path given = Path.of(world);
        return given.isAbsolute() ? given : savesDir().resolve(world);
    }

    /** Where saved worlds live: {@code run/saves}, next to the staged server's own {@code run/testserver}. */
    public static Path savesDir() {
        String spec = System.getProperty("testkit.testserver.launch");
        if (spec == null) {
            return Path.of("run", "saves");
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of(spec))) {
            props.load(in);
        } catch (IOException e) {
            return Path.of("run", "saves");
        }
        String dir = props.getProperty("dir");
        return dir == null ? Path.of("run", "saves") : Path.of(dir).resolveSibling("saves");
    }

    /**
     * Launch the server and block until it reports itself ready.
     *
     * @throws AssertionError if it never becomes ready — with the tail of its log, because a server
     *                        that failed to start is a mod-loading problem and the reason is in there
     */
    public static TestServer start() {
        String spec = System.getProperty("testkit.testserver.launch");
        if (spec == null) {
            throw new AssertionError("No test server launch spec. Run via Gradle so writeTestServerLaunch "
                    + "has produced one, or pass -Dtestkit.testserver.launch=<file>.");
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of(spec))) {
            props.load(in);
        } catch (IOException e) {
            throw new AssertionError("Could not read the test server launch spec at " + spec, e);
        }

        // Beside the launch spec, i.e. in the project's build directory -- the client's working
        // directory is its own run folder, which is not where anyone goes looking.
        consoleLog = Path.of(spec).resolveSibling("testserver-console.log");
        if (!startedOnce) {
            // One file per run, every scenario's server appended in order. Never truncated, it grew by every
            // run ever made on the machine, and the lines worth reading sat under all of them.
            startedOnce = true;
            try {
                Files.deleteIfExists(consoleLog);
            } catch (IOException ignored) {
                // an old log left in place is only untidy
            }
        }

        List<String> command = new ArrayList<>();
        command.add(props.getProperty("java"));
        command.addAll(split(props.getProperty("jvmArgs")));
        command.add("-cp");
        command.add(props.getProperty("classpath"));
        command.add(props.getProperty("main"));
        command.addAll(split(props.getProperty("args")));
        command.add("nogui");

        File dir = new File(props.getProperty("dir"));
        dir.mkdirs();
        wipeWorld(dir);
        Path seed = seedWorld;
        int[] spawn = seedSpawn;
        seedWorld = null;
        seedSpawn = null;
        if (seed != null) {
            copyWorld(seed, new File(dir, "world").toPath());
        }
        enableVerboseToConsole(dir);
        port = readPortAndStayOffline(dir);
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new AssertionError("Could not start the anticheat test server", e);
        }

        TestServer server = new TestServer(process);
        CountDownLatch ready = new CountDownLatch(1);
        Thread pump = new Thread(() -> server.pump(ready), "anticheat-server-log");
        pump.setDaemon(true);
        pump.start();

        // Polled rather than one long await, because a server that dies during startup never signals
        // readiness and the useful information is its own output. Counting down when the stream closes
        // would be worse than useless: it makes a dead server report itself ready, and the client then
        // fails with a bare "connection refused" that says nothing about the real cause.
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
            while (!ready.await(500, TimeUnit.MILLISECONDS)) {
                if (!process.isAlive()) {
                    String tail = server.tail(25);
                    String hint = tail.contains("FAILED TO BIND")
                            ? "Port " + port + " is taken — another server, or another checkout running its "
                                    + "suite. Stop it, or change server-port in "
                                    + new File(dir, "server.properties") + ".\n"
                            : "";
                    throw new AssertionError("Anticheat test server exited with code "
                            + process.exitValue() + " before it was ready:\n" + hint + tail);
                }
                if (System.nanoTime() > deadline) {
                    server.close();
                    throw new AssertionError("Anticheat test server did not start within "
                            + START_TIMEOUT_SECONDS + "s:\n" + server.tail(25));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
            throw new AssertionError("Interrupted waiting for the anticheat test server", e);
        }
        System.out.println("[anticheat] test server ready on " + address());
        if (seed != null) {
            System.out.println("[anticheat] world seeded from " + seed.getFileName());
            if (spawn != null) {
                // Before the client joins: the join puts a new player at the world spawn.
                server.command("setworldspawn " + spawn[0] + " " + spawn[1] + " " + spawn[2]);
                server.sync(30);
            }
        }
        return server;
    }

    /** Copy a saved world into the server's world folder. See {@link #seedWorldFrom} for what is left out. */
    private static void copyWorld(Path save, Path world) {
        if (!Files.isRegularFile(save.resolve("level.dat"))) {
            throw new AssertionError("No level.dat in the saved world at " + save.toAbsolutePath());
        }
        try (var walk = Files.walk(save)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path relative = save.relativize(source);
                if (relative.getNameCount() == 0 || !worthCopying(relative)) {
                    continue;
                }
                Path target = world.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("Could not copy the saved world " + save + " into " + world, e);
        }
    }

    /** Only what the server needs to stand the world up: the level, its data, and the region files. */
    private static boolean worthCopying(Path relative) {
        String first = relative.getName(0).toString();
        if (!(first.equals("level.dat") || first.equals("data") || first.equals("dimensions"))) {
            return false;
        }
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("entities") || name.equals("poi") || name.endsWith(".dat_old")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Read the port from the staged {@code server.properties}, and put {@code online-mode} and
     * {@code enforce-secure-profile} back to false if anything turned them on.
     *
     * <p>The gametest client has no Mojang session, so an online-mode server cannot complete its login: the
     * connection arrives and drops a second later, and the only thing either side logs is the client's
     * {@code Failed to retrieve profile key pair} — which also appears on healthy runs. Every scenario then
     * fails at the connect, with nothing pointing at the file. The server rewrites the file on every start, so
     * one hand edit, or one manual {@code runTestServer} with the setting changed, breaks every run after it.
     * Put back here, where it is cheap, rather than diagnosed later.
     */
    private static int readPortAndStayOffline(File dir) {
        Path file = dir.toPath().resolve("server.properties");
        if (!Files.isRegularFile(file)) {
            return 25565;
        }
        try {
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            String patched = text;
            for (String key : new String[]{"online-mode", "enforce-secure-profile"}) {
                String next = patched.replaceAll("(?m)^" + key + "=true", key + "=false");
                if (!next.equals(patched)) {
                    System.out.println("[anticheat] server.properties had " + key + "=true; set it back to false");
                }
                patched = next;
            }
            if (!patched.equals(text)) {
                Files.writeString(file, patched, StandardCharsets.ISO_8859_1);
            }
            Properties props = new Properties();
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
            return Integer.parseInt(props.getProperty("server-port", "25565").trim());
        } catch (IOException | NumberFormatException e) {
            throw new AssertionError("Could not read " + file, e);
        }
    }

    /**
     * Delete the saved world so every scenario starts on untouched ground.
     *
     * <p>Scenarios each get their own server process but shared one world folder, so whatever the last
     * one built was still standing when the next one arrived. That is how a scaffold run scored
     * "bridged=812, placed=0": the 812 was the previous scenario's arena platform, the module had
     * nothing to bridge over because the ground was already solid, and the barrier walls penned it in.
     * Player data goes too, which is also how a fake player from an earlier run stops coming back.
     */
    private static void wipeWorld(File dir) {
        for (String name : new String[]{"world", "world_nether", "world_the_end"}) {
            File world = new File(dir, name);
            if (world.exists()) {
                deleteTree(world);
            }
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    /**
     * Point GrimAC's verbose output at the server console.
     *
     * <p>Without this the harness cannot see check output at all, and every scenario reports clean — the
     * positive control catches it, but only if someone runs it. The config does not exist until the
     * server has started once, so a fresh clone would otherwise need a throwaway run before any result
     * meant anything. Patching here rather than in the build means it self-heals: whatever state the
     * config is in when a scenario starts, verbose is on by the time the server launches.
     *
     * <p>Console rather than chat because the alternative is granting the test player the alerts
     * permission, and a player with permissions is a player GrimAC treats as EXEMPT.
     */
    private static void enableVerboseToConsole(File dir) {
        File config = new File(dir, "config/GrimAC/config.yml");
        try {
            if (!config.exists()) {
                // First run on this machine: seed the one setting we need. Grim fills in every other key
                // with its defaults on load and rewrites the file.
                config.getParentFile().mkdirs();
                Files.writeString(config.toPath(),
                        "# Seeded by the anticheat test kit; GrimAC fills in the rest on first load."
                                + System.lineSeparator()
                                + "verbose:" + System.lineSeparator()
                                + "    print-to-console: true" + System.lineSeparator(),
                        StandardCharsets.UTF_8);
                return;
            }
            String text = Files.readString(config.toPath(), StandardCharsets.UTF_8);
            String patched = text.replaceAll(
                    "(?m)^(verbose:\\s*\\R(?:\\s*#.*\\R)*\\s*print-to-console:\\s*)false", "$1true");
            if (!patched.equals(text)) {
                Files.writeString(config.toPath(), patched, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new AssertionError("Could not enable anticheat verbose output at " + config, e);
        }
    }

    private void pump(CountDownLatch ready) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (log) {
                    log.add(line);
                }
                // Mirrored to a file as well: the useful part of a failed startup is usually hundreds
                // of lines above wherever the tail happens to start.
                try {
                    Files.writeString(consoleLog, line + System.lineSeparator(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // logging must never be the reason a test fails
                }
                // Vanilla prints this once the server is accepting connections.
                if (line.contains("Done (") || line.contains("For help, type")) {
                    ready.countDown();
                }
            }
        } catch (IOException ignored) {
            // the process died; start() notices via isAlive and reports its tail
        }
    }

    /**
     * Block until the server has processed everything sent so far.
     *
     * <p>Commands are handled in order, so a sentinel that replies proves the queue ahead of it is done.
     * This is the only reliable way to know a map has finished building: waiting a fixed number of ticks
     * is a race that a large fill loses, and losing it means a scenario measures a player standing on
     * whatever the world generated instead of on the map.
     */
    public void sync(int timeoutSeconds) {
        String token = "sync" + (++syncCounter);
        command("testkit ping " + token);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (logged("pong " + token)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Server did not finish its command queue within " + timeoutSeconds
                + "s — the map may be half-built:" + System.lineSeparator() + tail(15));
    }

    /** Run a command on the server console, as the console. */
    public void command(String command) {
        try {
            console.write(command);
            console.write("\n");
            console.flush();
        } catch (IOException e) {
            throw new AssertionError("Could not send '" + command + "' to the test server", e);
        }
    }

    /**
     * Draw a line under the console output so far. {@link #since()} reports only what came after, which
     * is how a scenario measures its own body rather than everything since the server booted.
     */
    public void mark() {
        synchronized (log) {
            marked = log.size();
        }
    }

    /** How many lines since the last {@link #mark()} contain {@code needle}. */
    public int countSince(String needle) {
        int count = 0;
        for (String line : since()) {
            if (line.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    /** Server output since the last {@link #mark()}. */
    public List<String> since() {
        synchronized (log) {
            return new ArrayList<>(log.subList(Math.min(marked, log.size()), log.size()));
        }
    }

    /** The last {@code lines} lines of server output — the first place to look when a scenario breaks. */
    public String tail(int lines) {
        synchronized (log) {
            int from = Math.max(0, log.size() - lines);
            return String.join("\n", log.subList(from, log.size()));
        }
    }

    /** Whether the server printed anything matching {@code needle} — for asserting on its own log. */
    public boolean logged(String needle) {
        synchronized (log) {
            for (String line : log) {
                if (line.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (!process.isAlive()) {
            return;
        }
        try {
            command("stop");
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (AssertionError e) {
            process.destroyForcibly();
        }
    }

    private static List<String> split(String packed) {
        List<String> out = new ArrayList<>();
        if (packed == null || packed.isEmpty()) {
            return out;
        }
        for (String part : packed.split("\u0000")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }
}
