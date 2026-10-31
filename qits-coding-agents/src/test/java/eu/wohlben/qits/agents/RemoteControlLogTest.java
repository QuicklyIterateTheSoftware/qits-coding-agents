package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a Remote Control debug log back at harness exit (qits-1779). The fixtures are the shape
 * CLI 2.1.283 writes: the bridge lines a real teardown produced, buried in the uncategorised noise
 * that makes up most of the file.
 */
class RemoteControlLogTest {

  private static final String SESSION = "cse_01CiRkJvCf85w2aC9w8b2LHS";

  private static final String NOISE =
      """
      2026-10-31T16:03:34.100Z [DEBUG] Loading settings from /claude-home/.claude/settings.json
      2026-10-31T16:03:34.200Z [DEBUG] [MCP] server "qits" connected in 312ms
      2026-10-31T16:03:35.000Z [DEBUG] Stream event: content_block_delta
      """;

  private static final String CREATED =
      "2026-10-31T16:03:34.473Z [DEBUG] [remote-bridge] Created session " + SESSION + "\n";

  private static final String TEARDOWN =
      "2026-10-31T16:03:39.001Z [DEBUG] [bridge:repl] Hook cleanup: starting teardown for session="
          + SESSION
          + " reason=host_exit\n";

  private static String archived(String status) {
    return "2026-10-31T16:03:39.171Z [DEBUG] [code-session] Archive "
        + SESSION
        + " status="
        + status
        + "\n"
        + "2026-10-31T16:03:39.328Z [DEBUG] [remote-bridge] Torn down (archive="
        + status
        + ")\n";
  }

  @Test
  void anArchiveThatAnswered200(@TempDir Path dir) throws Exception {
    Path log = dir.resolve("s.remote.log");
    Files.writeString(log, NOISE + CREATED + NOISE + TEARDOWN + archived("200") + NOISE);

    Optional<RemoteControlLog.Outcome> outcome = RemoteControlLog.read(log);

    assertEquals(Optional.of(new RemoteControlLog.Outcome(SESSION, "200")), outcome);
    assertTrue(outcome.orElseThrow().archived());
  }

  @Test
  void anArchiveThatAnsweredSomethingElseSaysWhat(@TempDir Path dir) throws Exception {
    Path log = dir.resolve("s.remote.log");
    Files.writeString(log, CREATED + NOISE + TEARDOWN + archived("401"));

    RemoteControlLog.Outcome outcome = RemoteControlLog.read(log).orElseThrow();

    assertEquals(new RemoteControlLog.Outcome(SESSION, "401"), outcome);
    assertFalse(outcome.archived(), "listed on claude.ai still, and the status says why");
  }

  @Test
  void aLogWithNoArchiveLineIsNoneRatherThanEmpty(@TempDir Path dir) throws Exception {
    // The harness exited without being asked — an EOF first, or a KILL — so it never tried.
    Path log = dir.resolve("s.remote.log");
    Files.writeString(log, NOISE + CREATED + NOISE);

    assertEquals(
        Optional.of(new RemoteControlLog.Outcome(SESSION, RemoteControlLog.NO_ARCHIVE)),
        RemoteControlLog.read(log));
  }

  @Test
  void aLogThatNeverReachedTheBridgeHasNoSession(@TempDir Path dir) throws Exception {
    Path log = dir.resolve("s.remote.log");
    Files.writeString(log, NOISE);

    assertEquals(
        Optional.of(new RemoteControlLog.Outcome(null, RemoteControlLog.NO_ARCHIVE)),
        RemoteControlLog.read(log));
  }

  @Test
  void theLastSessionAndTheLastArchiveWin(@TempDir Path dir) throws Exception {
    // A reconnect creates a second bridge session; what the exit left behind is the last one.
    String second = "cse_02SecondSessionAfterReconnect";
    Path log = dir.resolve("s.remote.log");
    Files.writeString(
        log,
        CREATED
            + archived("500")
            + "2026-10-31T16:04:00.000Z [DEBUG] [remote-bridge] Created session "
            + second
            + "\n");

    assertEquals(
        Optional.of(new RemoteControlLog.Outcome(second, "500")), RemoteControlLog.read(log));
  }

  @Test
  void aMissingFileIsEmpty(@TempDir Path dir) {
    assertEquals(Optional.empty(), RemoteControlLog.read(dir.resolve("absent.remote.log")));
    assertEquals(Optional.empty(), RemoteControlLog.read(dir), "a directory is not a log");
    assertEquals(Optional.empty(), RemoteControlLog.read(null));
  }

  @Test
  void aMalformedByteDoesNotCostTheRest(@TempDir Path dir) throws Exception {
    Path log = dir.resolve("s.remote.log");
    byte[] torn = {(byte) 0xC3, (byte) 0x28, '\n'};
    Files.write(log, torn);
    Files.writeString(log, CREATED + archived("200"), java.nio.file.StandardOpenOption.APPEND);

    assertEquals(
        Optional.of(new RemoteControlLog.Outcome(SESSION, "200")), RemoteControlLog.read(log));
  }

  @Test
  void compactionKeepsOnlyTheBridgeLines(@TempDir Path dir) throws Exception {
    Path log = dir.resolve("s.remote.log");
    String other = "2026-10-31T16:03:38.000Z [DEBUG] [bridge] Poll loop stopped\n";
    Files.writeString(log, NOISE + CREATED + NOISE + other + TEARDOWN + archived("200") + NOISE);

    assertTrue(RemoteControlLog.compact(log));

    assertEquals(CREATED + other + TEARDOWN + archived("200"), Files.readString(log));
    assertEquals(
        Optional.of(new RemoteControlLog.Outcome(SESSION, "200")),
        RemoteControlLog.read(log),
        "the outcome survives its own compaction");
    try (var siblings = Files.list(dir)) {
      assertEquals(List.of(log), siblings.toList(), "no temporary is left behind");
    }
  }

  @Test
  void compactingWhatIsNotThereIsANoOp(@TempDir Path dir) {
    assertFalse(RemoteControlLog.compact(dir.resolve("absent.remote.log")));
    assertFalse(RemoteControlLog.compact(null));
  }

  @Test
  void theLogSitsBesideTheTranscript() {
    Path configDir = Path.of("/claude-home/.claude");
    String sid = "11111111-2222-3333-4444-555555555555";

    assertEquals(
        Path.of("/claude-home/.claude/projects/-workspace/" + sid + ".remote.log"),
        RemoteControlLog.resolve(configDir, sid));
    assertEquals(
        configDir.resolve(new ClaudeCodeAgent().transcriptPath("/workspace", sid)).getParent(),
        RemoteControlLog.resolve(configDir, "/workspace", sid).getParent());
  }
}
