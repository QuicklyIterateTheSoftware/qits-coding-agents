package eu.wohlben.qits.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The transport is driven against a real process rather than a mock, because what is under test is
 * what reaches the harness's <em>stdin</em> — and the cheapest way to observe that is a process
 * that announces itself and then echoes its input back on stdout ({@code cat}), so every write
 * comes back onto the wire in order.
 */
class StreamJsonChatProtocolTest {

  private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\"}";

  @Test
  void aNamedSessionAsksForRemoteControlAsSoonAsTheHarnessSaysInit() throws Exception {
    Process process = echoAfter(INIT);
    StreamJsonChatProtocol protocol =
        new StreamJsonChatProtocol(process, "cmd-1", "refining/some-epic");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});

    assertEquals(INIT, take(lines));
    JsonObject echoed = new JsonObject(take(lines));
    assertEquals("control_request", echoed.getString("type"));
    JsonObject request = echoed.getJsonObject("request");
    assertEquals("remote_control", request.getString("subtype"));
    assertTrue(request.getBoolean("enabled"), "the request enables the bridge");
    assertEquals(
        "refining/some-epic",
        request.getString("name"),
        "the branch names the session, so a remote list is readable");

    protocol.close();
    process.destroy();
  }

  @Test
  void itAsksOnlyOnceEvenIfTheHarnessReInitialises() throws Exception {
    Process process = echoAfter(INIT, INIT);
    StreamJsonChatProtocol protocol = new StreamJsonChatProtocol(process, "cmd-1", "a-branch");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});

    assertEquals(INIT, take(lines));
    assertEquals(INIT, take(lines), "the second init passes through");
    assertTrue(take(lines).contains("remote_control"), "the echo of the one request that was sent");

    assertUserTurnOnly(protocol, lines, "the second init raised no second request");

    protocol.close();
    process.destroy();
  }

  @Test
  void withoutANameNothingIsAskedFor() throws Exception {
    Process process = echoAfter(INIT);
    StreamJsonChatProtocol protocol = new StreamJsonChatProtocol(process, "cmd-1", "  ");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});

    assertEquals(INIT, take(lines));
    assertUserTurnOnly(
        protocol,
        lines,
        "the user turn is the first thing written to stdin — a blank name leaves the bridge down");

    protocol.close();
    process.destroy();
  }

  // --- renaming a live session -------------------------------------------------------------------

  /**
   * The harness's local answer to {@code /rename probe}, as CLI 2.1.283 printed it under {@code
   * --print --input-format stream-json --output-format stream-json --verbose} — trimmed to the fields
   * that tell it apart, which are the ones the protocol reads.
   */
  private static final String RENAME_ASSISTANT =
      "{\"type\":\"assistant\",\"message\":{\"model\":\"<synthetic>\",\"role\":\"assistant\","
          + "\"content\":[{\"type\":\"text\",\"text\":\"Session renamed to: probe\"}]},"
          + "\"local_command_run\":{\"command\":\"rename\",\"args\":\"probe\"}}";

  private static final String RENAME_RESULT =
      "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":0,"
          + "\"result\":\"Session renamed to: probe\",\"local_command\":\"rename\"}";

  @Test
  void aRenameBeforeInitIsCarriedByTheEnableItself() throws Exception {
    Process process = echoAfter(INIT);
    StreamJsonChatProtocol protocol = new StreamJsonChatProtocol(process, "cmd-1", "qits-614: b");
    assertTrue(protocol.rename("\u2757 qits-614: b"), "a named session can be renamed");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});

    assertEquals(INIT, take(lines));
    JsonObject echoed = new JsonObject(take(lines));
    assertEquals("control_request", echoed.getString("type"));
    assertEquals(
        "\u2757 qits-614: b",
        echoed.getJsonObject("request").getString("name"),
        "the enable carries the new name; no /rename turn is queued ahead of the first real turn");
    assertUserTurnOnly(protocol, lines, "nothing else was written");

    protocol.close();
    process.destroy();
  }

  @Test
  void aRenameAfterInitWritesTheTestedSlashCommandAndEchoesNothing() throws Exception {
    Process process = echoAfter(INIT);
    StreamJsonChatProtocol protocol = new StreamJsonChatProtocol(process, "cmd-1", "qits-614: b");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});
    assertEquals(INIT, take(lines));
    assertTrue(take(lines).contains("remote_control"));

    assertTrue(protocol.rename("\u2757 qits-614: \"quoted\" b"));

    // The only line is cat's echo of what reached stdin — the protocol put nothing on the wire itself.
    assertEquals(
        "{\"type\":\"user\",\"message\":{\"role\":\"user\","
            + "\"content\":\"/rename \u2757 qits-614: \\\"quoted\\\" b\"}}",
        take(lines),
        "the exact shape the owner tested: content is a string, and the name is JSON-escaped");
    assertNull(lines.poll(300, TimeUnit.MILLISECONDS), "a rename is not echoed into the stream");

    protocol.close();
    process.destroy();
  }

  @Test
  void withRemoteControlOffThereIsNothingToRename() throws Exception {
    Process process = echoAfter(INIT);
    StreamJsonChatProtocol protocol = new StreamJsonChatProtocol(process, "cmd-1", null);
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});
    assertEquals(INIT, take(lines));

    assertFalse(protocol.rename("\u2757 qits-614: b"));
    assertUserTurnOnly(protocol, lines, "the refused rename wrote nothing");

    protocol.close();
    process.destroy();
  }

  @Test
  void theHarnessAnswerToOurRenameIsSwallowedAndAPersonsOwnIsNot() throws Exception {
    Process process = renameAnsweringHarness();
    StreamJsonChatProtocol protocol = new StreamJsonChatProtocol(process, "cmd-1", "qits-614: b");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    protocol.start(lines::add, () -> {});
    assertEquals(INIT, take(lines));
    assertTrue(take(lines).contains("remote_control"));

    assertTrue(protocol.rename("probe"));
    assertTrue(take(lines).contains("/rename probe"), "the turn reached the harness");

    // The assistant and result answering it never reach the wire: the next lines are the ping's.
    assertUserTurnOnly(protocol, lines, "the rename's answer was swallowed");
    assertNull(lines.poll(300, TimeUnit.MILLISECONDS), "and no stray result follows it");

    // A person who types /rename has sent a turn, and sees its answer: nothing of ours is pending.
    protocol.sendUser("/rename probe");
    List<String> seen = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      seen.add(take(lines));
    }
    assertTrue(seen.contains(RENAME_ASSISTANT), "a person's own rename is answered visibly");
    assertTrue(seen.contains(RENAME_RESULT));

    protocol.close();
    process.destroy();
  }

  /**
   * A harness that says init, echoes every stdin line, and answers any line holding a {@code
   * /rename} the way the real one does — the synthetic assistant line, then the result.
   */
  private static Process renameAnsweringHarness() throws IOException {
    String script =
        "printf '%s\\n' \"$1\"; while IFS= read -r l; do printf '%s\\n' \"$l\"; "
            + "case \"$l\" in *'\"/rename '*) printf '%s\\n' \"$2\" \"$3\";; esac; done";
    return new ProcessBuilder("bash", "-c", script, "harness", INIT, RENAME_ASSISTANT, RENAME_RESULT)
        .start();
  }

  /**
   * Sends a turn and asserts the only two lines that follow are its synthetic echo and the harness's
   * echo of the stream-json turn — in whichever order they race — and no control request.
   */
  private static void assertUserTurnOnly(
      StreamJsonChatProtocol protocol, BlockingQueue<String> lines, String because)
      throws InterruptedException {
    protocol.sendUser("ping");
    String first = take(lines);
    String second = take(lines);
    assertTrue(!first.contains("control_request") && !second.contains("control_request"), because);
    assertTrue(
        first.contains("\"role\":\"user\"") || second.contains("\"role\":\"user\""),
        "the turn reached the harness");
  }

  /** A process that prints {@code announcements} and then echoes its stdin back on stdout. */
  private static Process echoAfter(String... announcements) throws IOException {
    StringBuilder script = new StringBuilder();
    for (String announcement : announcements) {
      script.append("printf '%s\\n' ").append('\'').append(announcement).append("' ; ");
    }
    script.append("exec cat");
    return new ProcessBuilder("bash", "-c", script.toString()).start();
  }

  private static String take(BlockingQueue<String> lines) throws InterruptedException {
    String line = lines.poll(10, TimeUnit.SECONDS);
    assertTrue(line != null, "expected a line on the wire");
    return line;
  }
}
