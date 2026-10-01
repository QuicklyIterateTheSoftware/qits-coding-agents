package eu.wohlben.qits.commands;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Claude Code chat transport: the process already speaks the stream-json event envelope on
 * plain pipes, so this protocol is a straight pass-through. Stdout lines are emitted verbatim onto
 * the {@link ChatWire}; a user turn is written to stdin as a stream-json {@code user} message and
 * echoed into the stream as a synthetic {@code {"type":"user","text":…}} line (the same one unified
 * stream the frontend renders).
 *
 * <p>The only thing the move changed is the JSON library — {@code ObjectMapper} to {@link
 * JsonObject}, because the daemon carries no Jackson databind. The envelopes are built explicitly
 * rather than from a map literal, which is a little longer and makes the wire shape readable.
 *
 * <p>The same stdin channel also carries the <strong>Remote Control</strong> enable, when a session
 * name is given. It is not a launch flag: {@code --remote-control} parses under {@code --print} but
 * the harness drops it on the headless branch (verified against CLI 2.1.226), and the
 * {@code remoteControlAtStartup} setting is read on the same interactive-only path — the control
 * request below is the one route that attaches a bridge to a stream-json session.
 */
public final class StreamJsonChatProtocol implements ChatProtocol {

  private static final Logger LOG = System.getLogger(StreamJsonChatProtocol.class.getName());

  private final Process process;
  private final String commandId;
  private final boolean remoteControlWanted;
  private final BufferedWriter stdin;
  private final Object stdinLock = new Object();

  private volatile ChatWire wire;

  /**
   * The name the bridge is (or will be) listed under. Guarded by {@link #stdinLock} together with
   * {@link #remoteControlRequested}, so a {@link #rename} racing the init can never leave the enable
   * carrying the old name while the rename believes it was swapped in time.
   */
  private String remoteControlName;

  private boolean remoteControlRequested;

  /**
   * {@code /rename} turns written whose local answer has not come back yet. Each one is answered by
   * exactly one {@code result} carrying {@code "local_command":"rename"}, which is what retires it;
   * see {@link #isRenameReply}.
   */
  private final AtomicInteger pendingRenames = new AtomicInteger();

  StreamJsonChatProtocol(Process process, String commandId) {
    this(process, commandId, null);
  }

  /**
   * {@code remoteControlName} names the session in a remote list — pass the branch or another label
   * that says which piece of work this is, or null to leave Remote Control off.
   */
  public StreamJsonChatProtocol(Process process, String commandId, String remoteControlName) {
    this.process = process;
    this.commandId = commandId;
    this.remoteControlName =
        remoteControlName == null || remoteControlName.isBlank() ? null : remoteControlName.trim();
    this.remoteControlWanted = this.remoteControlName != null;
    this.stdin =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
  }

  @Override
  public void start(ChatWire wire, Runnable onEnd) {
    this.wire = wire;
    Thread reader = new Thread(() -> readLoop(onEnd), "chat-" + commandId);
    reader.setDaemon(true);
    reader.start();
  }

  private void readLoop(Runnable onEnd) {
    try (BufferedReader out =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = out.readLine()) != null) {
        if (!line.isEmpty()) {
          enableRemoteControlOnInit(line);
          if (isRenameReply(line)) {
            continue;
          }
          wire.emit(line);
        }
      }
    } catch (IOException e) {
      LOG.log(Level.DEBUG, () -> "Chat output pump ended for command " + commandId, e);
    } finally {
      onEnd.run();
    }
  }

  /**
   * Asks the harness to attach Remote Control, once, when it announces itself with {@code
   * system/init}. Waiting for init rather than writing at start is what makes the request land: the
   * control channel is only answered once the session is up, and init is the first thing it says.
   * The reply ({@code control_response}) and the bridge's {@code system/bridge_state} events ride
   * the same stdout stream and pass onto the wire like any other line — the frontend renders the
   * conversation kinds it knows and ignores these.
   */
  private void enableRemoteControlOnInit(String line) {
    if (!remoteControlWanted) {
      return;
    }
    synchronized (stdinLock) {
      if (remoteControlRequested) {
        return;
      }
    }
    JsonObject event;
    try {
      event = new JsonObject(line);
    } catch (RuntimeException notJson) {
      return; // A non-JSON line is not the init event; keep waiting.
    }
    if (!"system".equals(event.getString("type")) || !"init".equals(event.getString("subtype"))) {
      return;
    }
    // Best effort throughout: a chat whose bridge cannot be raised is still a working chat.
    synchronized (stdinLock) {
      if (remoteControlRequested) {
        return;
      }
      remoteControlRequested = true;
      // Read under the lock: a rename that arrived before this point swapped the name, and the
      // enable must carry it — the session is then never listed under the stale one at all.
      String request =
          new JsonObject()
              .put("type", "control_request")
              .put("request_id", "qits-remote-control-" + commandId)
              .put(
                  "request",
                  new JsonObject()
                      .put("subtype", "remote_control")
                      .put("enabled", true)
                      .put("name", remoteControlName))
              .encode();
      try {
        stdin.write(request);
        stdin.write("\n");
        stdin.flush();
      } catch (IOException e) {
        LOG.log(Level.DEBUG, () -> "Remote Control enable failed for command " + commandId, e);
      }
    }
  }

  @Override
  public void sendUser(String text) {
    String turn =
        new JsonObject()
            .put("type", "user")
            .put(
                "message",
                new JsonObject()
                    .put("role", "user")
                    .put(
                        "content",
                        new JsonArray().add(new JsonObject().put("type", "text").put("text", text))))
            .encode();
    synchronized (stdinLock) {
      try {
        stdin.write(turn);
        stdin.write("\n");
        stdin.flush();
      } catch (IOException e) {
        LOG.log(Level.DEBUG, () -> "Chat stdin write failed for command " + commandId, e);
        return;
      }
    }
    ChatWire bound = wire;
    if (bound == null) {
      return; // sendUser before start() bound the wire — unreachable via spawnChat, guarded anyway.
    }
    bound.emit(new JsonObject().put("type", "user").put("text", text).encode());
  }

  /**
   * Renames the session in the remote list, when this protocol was asked to raise a bridge at all.
   *
   * <p>Three answers. A session launched with Remote Control off has no list entry to rename, so
   * this is {@code false} and nothing is written. A session whose harness has not said {@code
   * system/init} yet has not sent the enable, so the stored name is simply swapped and the enable
   * carries the new one — no {@code /rename} turn is queued ahead of the session's first real turn.
   * After the enable, the rename is the {@code /rename} slash command written as a stream-json user
   * turn whose {@code content} is a plain <em>string</em>: that exact shape is what the owner tested
   * renaming a running headless session with ({@code "Session renamed to: …"}), and the harness
   * dispatches a slash command only from string content, never from a text content block.
   *
   * <p>Unlike {@link #sendUser} it is not echoed into the stream, and the harness's local answer is
   * swallowed by {@link #isRenameReply}: a rename is something the platform did to the session, and
   * showing it would put a turn in the conversation that nobody typed.
   */
  @Override
  public boolean rename(String name) {
    if (!remoteControlWanted || name == null || name.isBlank()) {
      return false;
    }
    String trimmed = name.trim();
    synchronized (stdinLock) {
      if (!remoteControlRequested) {
        remoteControlName = trimmed;
        return true;
      }
      remoteControlName = trimmed;
      String turn =
          new JsonObject()
              .put("type", "user")
              .put(
                  "message",
                  new JsonObject().put("role", "user").put("content", "/rename " + trimmed))
              .encode();
      // Counted before the write, so a harness that answers faster than this thread returns from
      // flush() still finds the reply expected.
      pendingRenames.incrementAndGet();
      try {
        stdin.write(turn);
        stdin.write("\n");
        stdin.flush();
        return true;
      } catch (IOException e) {
        pendingRenames.decrementAndGet();
        LOG.log(Level.DEBUG, () -> "Chat rename failed for command " + commandId, e);
        return false;
      }
    }
  }

  /**
   * Whether {@code line} is the harness's local answer to a {@code /rename} this protocol wrote.
   *
   * <p>Observed against CLI 2.1.283 ({@code --print --input-format stream-json --output-format
   * stream-json --verbose}): a {@code /rename probe} turn is answered with no model call by exactly
   * two lines and no user echo — a synthetic {@code assistant} message ({@code "model":"<synthetic>"},
   * text {@code "Session renamed to: probe"}) carrying {@code "local_command_run":{"command":"rename",
   * "args":"probe"}}, then a {@code result} with {@code "local_command":"rename"}, {@code
   * "num_turns":0} and the same text. Both name the command, so they are told apart by that field and
   * not by guessing from the text. The {@code result} matters most: a client reads every {@code
   * result} as "the turn finished", and a rename landing while the agent is mid-turn would otherwise
   * read as that turn ending early.
   *
   * <p>Only while a rename of ours is outstanding: a person who types {@code /rename} into the chat
   * themselves sent a turn through {@link #sendUser} and sees its answer like any other. The {@code
   * result} retires one pending rename; the {@code assistant} line before it does not, so the pair is
   * swallowed together.
   */
  private boolean isRenameReply(String line) {
    if (pendingRenames.get() <= 0 || !line.contains("rename")) {
      return false;
    }
    JsonObject event;
    try {
      event = new JsonObject(line);
    } catch (RuntimeException notJson) {
      return false;
    }
    String type = event.getString("type");
    if ("assistant".equals(type)) {
      Object run = event.getValue("local_command_run");
      return run instanceof JsonObject command && "rename".equals(command.getString("command"));
    }
    if ("result".equals(type) && "rename".equals(event.getValue("local_command"))) {
      pendingRenames.updateAndGet(n -> Math.max(0, n - 1));
      return true;
    }
    return false;
  }

  @Override
  public void close() {
    try {
      stdin.close();
    } catch (IOException ignored) {
      // best effort
    }
  }
}
