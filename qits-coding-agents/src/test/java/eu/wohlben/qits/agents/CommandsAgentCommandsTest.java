package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.commands.CommandLogWriter;
import eu.wohlben.qits.commands.CommandOutputSink;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.GuardedInput;
import eu.wohlben.qits.commands.LogChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CommandsAgentCommands} against a real {@link CommandRegistry} — the same real-process
 * approach {@code CommandRegistryTest} uses, since the registry is a concrete, final class with no
 * seam to fake. Chunk boundaries are read off the PTY's own echo of each write, timestamped as it
 * arrives, rather than off anything the launched script prints.
 */
@EnabledOnOs(OS.LINUX)
class CommandsAgentCommandsTest {

  @Test
  void aLongTurnIsWrittenInChunksPausedApartThenSubmitted(@TempDir Path workspace)
      throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CommandsAgentCommands commands = new CommandsAgentCommands(null, registry, null);
    CountDownLatch exited = new CountDownLatch(1);
    TimestampedSink sink = new TimestampedSink();

    registry.spawn("k1", "read -r turn", Map.of(), (id, c, m) -> exited.countDown(), null, sink);
    waitUntil(() -> registry.isRunning("k1"), 10_000);
    sink.clear();

    String text = "x".repeat(2000);
    assertTrue(commands.sendKeystrokes("k1", text));
    assertTrue(exited.await(30, TimeUnit.SECONDS), "the read should have completed");

    List<Burst> bursts = burstsByGap(sink.received(), 15);
    assertEquals(5, bursts.size(), "4 chunks plus the Enter, got: " + bursts);
    assertEquals(512, bursts.get(0).text().length());
    assertEquals(512, bursts.get(1).text().length());
    assertEquals(512, bursts.get(2).text().length());
    assertEquals(464, bursts.get(3).text().length());
    assertEquals(
        text,
        bursts.get(0).text() + bursts.get(1).text() + bursts.get(2).text() + bursts.get(3).text());
    assertTrue(
        bursts.get(4).text().startsWith("\r"),
        "the last burst should be the Enter, got: " + bursts.get(4).text());

    // The pause is 30 ms; 20 ms leaves headroom for scheduler jitter while still being nowhere
    // near what back-to-back writes with no pause at all would measure (sub-millisecond).
    for (int i = 1; i < 4; i++) {
      long gapMillis =
          Duration.between(bursts.get(i - 1).startedAt(), bursts.get(i).startedAt()).toMillis();
      assertTrue(
          gapMillis >= 20, "chunk " + i + " arrived only " + gapMillis + " ms after the previous one");
    }
  }

  @Test
  void aShortTurnIsWrittenInOneChunkThenSubmitted(@TempDir Path workspace) throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CommandsAgentCommands commands = new CommandsAgentCommands(null, registry, null);
    CountDownLatch exited = new CountDownLatch(1);
    TimestampedSink sink = new TimestampedSink();

    registry.spawn("k2", "read -r turn", Map.of(), (id, c, m) -> exited.countDown(), null, sink);
    waitUntil(() -> registry.isRunning("k2"), 10_000);
    sink.clear();

    String text = "a short turn";
    assertTrue(commands.sendKeystrokes("k2", text));
    assertTrue(exited.await(30, TimeUnit.SECONDS), "the read should have completed");

    List<Burst> bursts = burstsByGap(sink.received(), 15);
    assertEquals(2, bursts.size(), "one text write and the Enter, got: " + bursts);
    assertEquals(text, bursts.get(0).text());
    assertTrue(bursts.get(1).text().startsWith("\r"));
  }

  @Test
  void aChunkThatFailsToWriteStopsTypingWithoutSubmitting(@TempDir Path workspace)
      throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CommandsAgentCommands commands = new CommandsAgentCommands(null, registry, null);
    CountDownLatch exited = new CountDownLatch(1);
    RecordingLog log = new RecordingLog();
    Path ready = workspace.resolve("k3.ready");

    // In canonical mode (the PTY's default) a read() blocks until a line arrives, so a process
    // could not consume just the first chunk — nothing terminates it until the Enter every later
    // chunk is also waiting on. `stty raw` turns that off, so `head -c` reads exactly the first
    // chunk's bytes and exits the moment they land, which removes the session, and does so well
    // before the second chunk's write, TerminalTurns.CHUNK_PAUSE later.
    registry.spawn(
        "k3",
        "stty raw; touch " + ready + "; head -c " + TerminalTurns.CHUNK_CHARS + " > /dev/null",
        Map.of(),
        (id, c, m) -> exited.countDown(),
        log);
    waitUntil(() -> Files.exists(ready), 10_000);

    String text = "y".repeat(TerminalTurns.CHUNK_CHARS + 88);
    boolean result = commands.sendKeystrokes("k3", text);

    assertFalse(result, "a failed chunk write should make sendKeystrokes return false");
    assertTrue(exited.await(30, TimeUnit.SECONDS), "head should have exited after the first chunk");
    assertEquals(
        List.of("y".repeat(TerminalTurns.CHUNK_CHARS)),
        log.contentOn(LogChannel.STDIN),
        "only the first chunk should have reached the terminal, and no Enter");
  }

  @Test
  void sendKeystrokesUnlessDraftHeldForDraftWritesNothing(@TempDir Path workspace)
      throws Exception {
    CommandRegistry registry = new CommandRegistry(workspace, 2_000);
    CommandsAgentCommands commands = new CommandsAgentCommands(null, registry, null);
    TimestampedSink sink = new TimestampedSink();

    registry.spawn("k4", "sleep 30", Map.of(), (id, c, m) -> {}, null, sink);
    try {
      waitUntil(() -> registry.isRunning("k4"), 10_000);
      registry.personInput("k4", "h".getBytes(StandardCharsets.UTF_8));
      assertTrue(registry.hasDraft("k4"), "a typed character should leave a draft");
      // Let the "h" keystroke's own echo land before clearing, so the clear does not race it:
      // otherwise it can arrive after the clear and be mistaken for something the guarded write did.
      waitUntil(() -> !sink.received().isEmpty(), 5_000);
      sink.clear();

      GuardedInput result = commands.sendKeystrokesUnlessDraft("k4", "y".repeat(600));

      assertEquals(GuardedInput.HELD_FOR_DRAFT, result);
      assertEquals(
          List.of(), sink.received(), "nothing should have been written while a draft is held");
    } finally {
      registry.terminate("k4");
    }
  }

  // --- test doubles and helpers -----------------------------------------------------------------

  private record Timestamped(Instant at, String text) {}

  /** One contiguous run of {@link Timestamped} writes, grouped by {@link #burstsByGap}. */
  private record Burst(String text, Instant startedAt) {}

  /**
   * Collects everything broadcast to an attached client, each entry timestamped as it arrives —
   * which is how a test observes the separate PTY writes {@link CommandsAgentCommands} makes,
   * since the registry itself offers no hook to intercept them. Optionally counts a latch down
   * once at least {@code latchAtLength} characters have arrived, so a test can wait for one chunk's
   * echo before acting on it.
   */
  private static final class TimestampedSink implements CommandOutputSink {
    private final List<Timestamped> received = new ArrayList<>();
    private final CountDownLatch latch;
    private final int latchAtLength;
    private int accumulated;

    TimestampedSink() {
      this(null, 0);
    }

    TimestampedSink(CountDownLatch latch, int latchAtLength) {
      this.latch = latch;
      this.latchAtLength = latchAtLength;
    }

    @Override
    public synchronized void write(String data) {
      received.add(new Timestamped(Instant.now(), data));
      accumulated += data.length();
      if (latch != null && accumulated >= latchAtLength) {
        latch.countDown();
      }
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    synchronized List<Timestamped> received() {
      return List.copyOf(received);
    }

    synchronized void clear() {
      received.clear();
      accumulated = 0;
    }
  }

  /**
   * Groups timestamped writes into bursts: a gap of at least {@code minGapMillis} between two
   * writes starts a new burst. 15 ms sits well under {@link TerminalTurns#CHUNK_PAUSE} (30 ms) and
   * well over the sub-millisecond latency between a master write and its kernel echo, so a single
   * chunk's write is never split into two bursts and two chunks are never merged into one.
   */
  private static List<Burst> burstsByGap(List<Timestamped> events, long minGapMillis) {
    List<Burst> bursts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Instant burstStart = null;
    Instant last = null;
    for (Timestamped event : events) {
      boolean newBurst = last == null || Duration.between(last, event.at()).toMillis() >= minGapMillis;
      if (newBurst) {
        if (burstStart != null) {
          bursts.add(new Burst(current.toString(), burstStart));
        }
        current.setLength(0);
        burstStart = event.at();
      }
      current.append(event.text());
      last = event.at();
    }
    if (burstStart != null) {
      bursts.add(new Burst(current.toString(), burstStart));
    }
    return bursts;
  }

  /** Captures framed log lines the way {@code CommandLogService} would. */
  private record CapturedLine(long sequence, LogChannel channel, String content) {}

  private static final class RecordingLog implements CommandLogWriter {
    private final List<CapturedLine> lines = Collections.synchronizedList(new ArrayList<>());

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
