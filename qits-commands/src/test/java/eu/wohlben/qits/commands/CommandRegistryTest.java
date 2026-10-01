package eu.wohlben.qits.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry against real processes in a real terminal. This is the test that the host's
 * equivalent could not have: there, every spawn was a {@code docker exec} and the suite could only
 * assert on the argv it would have run. Here the process is a local child, so the whole path —
 * spawn, terminal, capture, broadcast, exit, group termination — runs for real without docker.
 */
@EnabledOnOs(OS.LINUX)
class CommandRegistryTest {

  /** Collects everything broadcast to an attached client. */
  private static final class RecordingSink implements CommandOutputSink {
    private final StringBuilder received = new StringBuilder();
    private volatile boolean open = true;

    @Override
    public synchronized void write(String data) {
      received.append(data);
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    synchronized String text() {
      return received.toString();
    }
  }

  /** Captures framed log lines the way {@code CommandLogService} would. */
  private record CapturedLine(long sequence, LogChannel channel, String content) {}

  private static final class RecordingLog implements CommandLogWriter {
    private final List<CapturedLine> lines = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void append(
        String commandId, long sequence, LogChannel channel, String content, Instant timestamp) {
      lines.add(new CapturedLine(sequence, channel, content));
    }

    List<String> contentOn(LogChannel channel) {
      synchronized (lines) {
        return lines.stream()
            .filter(line -> line.channel() == channel)
            .map(CapturedLine::content)
            .toList();
      }
    }
  }

  @Test
  void runsAScriptAndReportsItsExitCode(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    AtomicInteger code = new AtomicInteger(Integer.MIN_VALUE);
    RecordingSink sink = new RecordingSink();

    registry.spawn(
        "c1",
        "echo marker-one; exit 7",
        Map.of(),
        (id, exitCode, manual) -> {
          code.set(exitCode);
          exited.countDown();
        },
        null,
        sink);

    assertTrue(exited.await(30, TimeUnit.SECONDS), "the command should have exited");
    assertEquals(7, code.get());
    assertTrue(sink.text().contains("marker-one"), "expected output, got: " + sink.text());
  }

  @Test
  void theScriptRunsInTheWorkspaceRoot(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("sentinel.txt"), "here");
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingSink sink = new RecordingSink();

    registry.spawn("c2", "cat sentinel.txt", Map.of(), (id, c, m) -> exited.countDown(), null, sink);

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertTrue(sink.text().contains("here"), "expected the file's content, got: " + sink.text());
  }

  @Test
  void theLaunchEnvironmentReachesTheProcess(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingSink sink = new RecordingSink();

    registry.spawn(
        "c3",
        "echo \"[$QITS_TEST_VALUE]\"",
        Map.of("QITS_TEST_VALUE", "passed-through"),
        (id, c, m) -> exited.countDown(),
        null,
        sink);

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertTrue(sink.text().contains("[passed-through]"), "got: " + sink.text());
  }

  @Test
  void theProcessSeesAControllingTerminal(@TempDir Path workspace) throws Exception {
    // The reason for --ctty: without a controlling terminal `test -t 1` fails and every
    // full-screen TUI — which is what a coding agent in TERMINAL mode is — drops to line mode.
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingSink sink = new RecordingSink();

    registry.spawn(
        "c4",
        "test -t 1 && echo IS-TTY || echo NOT-TTY",
        Map.of(),
        (id, c, m) -> exited.countDown(),
        null,
        sink);

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertTrue(sink.text().contains("IS-TTY"), "expected a tty, got: " + sink.text());
  }

  @Test
  void keystrokesReachTheProcessAndAreCapturedOnStdin(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingSink sink = new RecordingSink();
    RecordingLog log = new RecordingLog();

    registry.spawn(
        "c5", "read -r answer; echo \"got:$answer\"", Map.of(), (id, c, m) -> exited.countDown(), log, sink);

    // Give the shell a moment to reach the read before typing at it.
    waitUntil(() -> registry.isRunning("c5"), 10_000);
    registry.input("c5", "ping\n".getBytes(StandardCharsets.UTF_8));

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertTrue(sink.text().contains("got:ping"), "expected the echoed answer, got: " + sink.text());
    assertTrue(log.contentOn(LogChannel.STDIN).contains("ping"), "stdin should be captured");
  }

  @Test
  void outputIsFramedIntoLogLines(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingLog log = new RecordingLog();

    registry.spawn(
        "c6", "printf 'alpha\\nbeta\\n'", Map.of(), (id, c, m) -> exited.countDown(), log);

    assertTrue(exited.await(30, TimeUnit.SECONDS));
    List<String> output = log.contentOn(LogChannel.OUTPUT);
    assertTrue(output.contains("alpha"), "got: " + output);
    assertTrue(output.contains("beta"), "got: " + output);
  }

  @Test
  void aLateAttachReplaysTheScrollback(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);

    // No initial sink: everything this prints lands only in the ring.
    registry.spawn(
        "c7", "echo replay-me; read -r ignored", Map.of(), (id, c, m) -> exited.countDown(), null);
    waitUntil(() -> registry.isRunning("c7"), 10_000);

    RecordingSink late = new RecordingSink();
    waitUntil(
        () -> {
          late.received.setLength(0);
          return registry.attach("c7", late) && late.text().contains("replay-me");
        },
        10_000);
    assertTrue(late.text().contains("replay-me"), "expected scrollback replay, got: " + late.text());

    registry.input("c7", "\n".getBytes(StandardCharsets.UTF_8));
    assertTrue(exited.await(30, TimeUnit.SECONDS));
  }

  @Test
  void terminateKillsTheWholeProcessGroup(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CountDownLatch exited = new CountDownLatch(1);
    AtomicReference<Boolean> manual = new AtomicReference<>();
    Path childPid = workspace.resolve("child.pid");

    // A background child of the shell: killing only the shell would leave it running, which is
    // exactly the orphan the pid-file/pgid dance exists to prevent.
    registry.spawn(
        "c8",
        "sleep 300 & echo $! > " + childPid + "; wait",
        Map.of(),
        (id, c, m) -> {
          manual.set(m);
          exited.countDown();
        },
        null);

    waitUntil(() -> Files.exists(childPid) && !readQuietly(childPid).isBlank(), 15_000);
    long grandchild = Long.parseLong(readQuietly(childPid).trim());

    assertTrue(registry.terminate("c8"), "terminate should find the session");
    assertTrue(exited.await(30, TimeUnit.SECONDS), "the command should have ended");
    assertEquals(Boolean.TRUE, manual.get(), "the exit should be reported as manual");
    waitUntil(() -> isNoLongerRunning(grandchild), 15_000);
    assertTrue(
        isNoLongerRunning(grandchild),
        "the backgrounded grandchild should have been killed with the group, but "
            + describe(grandchild));
  }

  /**
   * The shutdown path: every session must see SIGTERM (claude archives its remote session only on
   * TERM), and the whole stop must cost one shared grace, not one grace per session. Both scripts
   * trap TERM and exit at once, so a run that signals one, waits a grace, then signals the next
   * still writes both markers — the elapsed bound is what catches that.
   */
  @Test
  void terminateAllSignalsEverySessionBeforeWaitingOnAny(@TempDir Path workspace)
      throws Exception {
    long grace = 1_500;
    CommandRegistry registry = new CommandRegistry(workspace, grace);
    CountDownLatch exited = new CountDownLatch(2);
    List<Boolean> manual = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Path> markers = new ArrayList<>();
    List<Path> ready = new ArrayList<>();

    for (String id : List.of("ta-1", "ta-2")) {
      Path marker = workspace.resolve(id + ".termed");
      Path up = workspace.resolve(id + ".up");
      markers.add(marker);
      ready.add(up);
      // The trap swallows TERM only long enough to record it; the session then ends on its own.
      // Without the trap a TERM-less KILL would end it too, so the marker is the proof of TERM.
      registry.spawn(
          id,
          "trap 'touch " + marker + "; exit 0' TERM; sleep 600 & touch " + up + "; wait",
          Map.of(),
          (cid, c, m) -> {
            manual.add(m);
            exited.countDown();
          },
          null);
    }
    waitUntil(() -> ready.stream().allMatch(Files::exists), 15_000);

    long started = System.nanoTime();
    registry.terminateAll();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertTrue(exited.await(30, TimeUnit.SECONDS), "both commands should have ended");
    for (Path marker : markers) {
      assertTrue(Files.exists(marker), "the session should have received SIGTERM: " + marker);
    }
    assertEquals(List.of(true, true), List.copyOf(manual), "both exits are reported as manual");
    assertTrue(
        elapsedMillis < 2 * grace,
        "terminateAll took " + elapsedMillis + " ms, which is not one shared grace of " + grace);
    assertFalse(registry.isRunning("ta-1"));
    assertFalse(registry.isRunning("ta-2"));
  }

  /**
   * Sessions that ignore TERM run out the grace and must still die. Three of them is also what
   * proves the grace is shared: waiting it out per session would take three graces.
   */
  @Test
  void terminateAllKillsSessionsThatIgnoreTermWithinOneGrace(@TempDir Path workspace)
      throws Exception {
    long grace = 1_000;
    CommandRegistry registry = new CommandRegistry(workspace, grace);
    List<String> ids = List.of("ta-3a", "ta-3b", "ta-3c");
    CountDownLatch exited = new CountDownLatch(ids.size());
    List<Path> ready = new ArrayList<>();

    for (String id : ids) {
      Path up = workspace.resolve(id + ".up");
      ready.add(up);
      registry.spawn(
          id,
          "trap '' TERM; touch " + up + "; sleep 600",
          Map.of(),
          (cid, c, m) -> exited.countDown(),
          null);
    }
    waitUntil(() -> ready.stream().allMatch(Files::exists), 15_000);

    long started = System.nanoTime();
    registry.terminateAll();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertTrue(exited.await(30, TimeUnit.SECONDS), "TERM-ignoring commands must still end");
    for (String id : ids) {
      assertFalse(registry.isRunning(id), id + " should be dead");
    }
    assertTrue(
        elapsedMillis < 2 * grace,
        "terminateAll took " + elapsedMillis + " ms for three sessions; one grace is " + grace);
  }

  @Test
  void terminateAllIsSafeOnAnEmptyRegistryAndWhenRepeated(@TempDir Path workspace)
      throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 1_000);
    registry.terminateAll();

    CountDownLatch exited = new CountDownLatch(1);
    Path up = workspace.resolve("ta-4.up");
    registry.spawn(
        "ta-4", "touch " + up + "; sleep 600", Map.of(), (id, c, m) -> exited.countDown(), null);
    waitUntil(() -> Files.exists(up), 15_000);
    registry.terminateAll();
    registry.terminateAll();
    assertTrue(exited.await(30, TimeUnit.SECONDS));
    assertFalse(registry.isRunning("ta-4"));
  }

  /**
   * A chat must see SIGTERM, and see it before its stdin is closed: an EOF first can let {@code
   * claude} end without archiving its remote session. The script records which came first.
   */
  @Test
  void terminateAllSignalsAChatBeforeClosingItsInput(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 1_500);
    CountDownLatch exited = new CountDownLatch(1);
    AtomicReference<Boolean> manual = new AtomicReference<>();
    Path events = workspace.resolve("chat.events");
    Path up = workspace.resolve("chat.up");

    // `read` returns on EOF, so the log says whether stdin closed before the TERM trap ran.
    registry.spawnChat(
        "ta-5",
        "trap 'echo term >> " + events + "; exit 0' TERM; touch " + up + "; "
            + "while read -r line; do :; done; echo eof >> " + events + "; sleep 600 & wait",
        Map.of(),
        null,
        (id, c, m) -> {
          manual.set(m);
          exited.countDown();
        },
        null,
        null);
    waitUntil(() -> Files.exists(up), 15_000);

    registry.terminateAll();

    assertTrue(exited.await(30, TimeUnit.SECONDS), "the chat should have ended");
    assertEquals("term", readQuietly(events).trim(), "SIGTERM must arrive before stdin closes");
    assertEquals(Boolean.TRUE, manual.get(), "the exit should be reported as manual");
    assertFalse(registry.isRunning("ta-5"));
  }

  @Test
  void isRunningIsFalseForAnUnknownCommand(@TempDir Path workspace) {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    assertFalse(registry.isRunning("never-launched"));
    assertFalse(registry.terminate("never-launched"));
    assertFalse(registry.input("never-launched", new byte[] {1}));
    assertFalse(registry.resize("never-launched", 80, 24));
    assertFalse(registry.chatSend("never-launched", "hello"));
    assertFalse(
        registry.chatRename("never-launched", "\u2757 qits-614: b"),
        "an unknown or ended chat answers false, which a renamer reads as drop it");
  }

  /**
   * True when the pid no longer names a running process: either the kernel has dropped it, or it
   * is a zombie waiting to be collected.
   *
   * <p>Why {@code ProcessHandle.of(pid).isEmpty()} is not enough on its own. A killed process stays
   * in the process table as a zombie until its parent calls {@code wait()} for it. The grandchild
   * in this test is backgrounded by the session shell, so killing the group orphans it and the
   * kernel reparents it onto PID 1. PID 1 then decides how long the corpse lingers. On a developer
   * host PID 1 is systemd, which reaps orphans at once, so the pid vanishes and the plain
   * {@code ProcessHandle} check passes. In a CI step container PID 1 is whatever the step launched
   * — for this suite the Maven JVM — and a JVM never calls {@code wait()} for a process it did not
   * start. The zombie therefore stays forever, {@code /proc/<pid>} keeps answering, and
   * {@code ProcessHandle.of(pid)} keeps reporting the process as present.
   *
   * <p>That difference is a property of the container, not of the code under test. A zombie is
   * dead: it holds no memory, runs no code, and cannot be signalled. For the question this test
   * asks — did killing the session kill the backgrounded grandchild — gone and zombie are the same
   * answer, so the assertion must accept both.
   *
   * <p>Reading {@code /proc} is fair here. The whole class is {@code @EnabledOnOs(OS.LINUX)}, and
   * the registry under test needs PTYs and {@code setsid --ctty}, so there is no other platform to
   * serve.
   */
  private static boolean isNoLongerRunning(long pid) {
    if (ProcessHandle.of(pid).isEmpty()) {
      return true;
    }
    return "Z".equals(processState(pid));
  }

  /**
   * The single-letter process state from {@code /proc/<pid>/stat}, or {@code null} when there is no
   * such process.
   *
   * <p>The state is field 3, but field 2 is the executable name wrapped in parentheses and may
   * itself contain spaces and parentheses. Splitting the whole line on whitespace would therefore
   * pick the wrong field, so the scan starts after the last {@code ')'}.
   */
  private static String processState(long pid) {
    try {
      String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
      int endOfName = stat.lastIndexOf(')');
      if (endOfName < 0) {
        return null;
      }
      String[] fields = stat.substring(endOfName + 1).trim().split("\\s+");
      return fields.length > 0 && !fields[0].isEmpty() ? fields[0] : null;
    } catch (Exception e) {
      // No /proc entry at all: the process is gone, the strongest form of "not running".
      return null;
    }
  }

  /** What the pid looks like right now, so a failure says why it was judged still running. */
  private static String describe(long pid) {
    String state = processState(pid);
    return state == null
        ? "pid " + pid + " has no /proc entry"
        : "pid " + pid + " is still there in state " + state;
  }

  private static String readQuietly(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception e) {
      return "";
    }
  }

  private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
  }
}
