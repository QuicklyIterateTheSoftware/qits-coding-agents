package eu.wohlben.qits.agents;

import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pluggable coding-agent harness, configured fluently and rendered to a launch command. This base
 * class <em>is</em> the builder: it holds the common, harness-agnostic configuration (MCP servers, a
 * tool allowlist, an initial prompt, whether to skip permission prompts) and exposes the fluent
 * setters, so a concrete agent only has to know how to turn that configuration into <em>its own</em>
 * command line. Obtain one via {@link CodingAgentFactory#ofType}.
 *
 * <pre>{@code
 * LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE)
 *     .mcpServer("repository", McpServers.httpMcp(scopedUrl))
 *     .allowedTools(READ_ONLY_REPOSITORY_TOOLS)
 *     .initialContext(seed)
 *     .start();
 * }</pre>
 *
 * <p>Rendering never spawns a process — {@link #start()}/{@link #run(String)} return a {@link
 * LaunchSpec} that {@link AgentCommands} hands to the command registry. Plain framework-free Java (no
 * CDI), so it is trivially unit-testable. Implementations: {@link ClaudeCodeAgent}, {@link
 * KimiCodeAgent}.
 */
public abstract class CodingAgent {

  /** Attached MCP servers as {@code key → config}; insertion-ordered for deterministic output. */
  protected final Map<String, JsonObject> mcpServers = new LinkedHashMap<>();

  /** Tools pre-approved across all attached servers (an agent renders these however it must). */
  protected final List<String> allowedTools = new ArrayList<>();

  /** Environment overlay for the launched process. */
  protected final Map<String, String> environment = new HashMap<>();

  /** The interactive seed prompt, or null. */
  protected String initialContext;

  /** Steering text appended to the harness's own system prompt, or null. */
  protected String systemPromptAppendix;

  /** Model override for the launched session, or null for the harness default. */
  protected String model;

  /**
   * How hard the session should think, or null for the harness's own choice. A free string rather
   * than an enum: the levels belong to the binary in the image (see {@link HarnessCapabilities}),
   * they differ per model, and a configuration that pins one this platform has never heard of must
   * still render.
   */
  protected String effort;

  /**
   * The name a Remote Control session is listed under, or null to leave Remote Control off.
   *
   * <p>Only the interactive shape has a flag for it ({@code --remote-control "<name>"}); the chat
   * shapes enable it over the SDK control channel instead, from {@code StreamJsonChatProtocol}, and
   * never touch this field. See {@link AgentRemoteControl} for why a name is passed at all.
   */
  protected String remoteControlName;

  /** Whether to run without permission prompts. */
  protected boolean skipPermissions;

  /**
   * What this harness could not render of what it was configured with — <b>not</b> errors, and not
   * silence either.
   *
   * <p>The two harnesses are genuinely asymmetric: Kimi Code has no effort concept and no
   * system-prompt appendix, so a surface configured with either renders a command without it. That
   * is the right rendering and the wrong <em>silence</em>: a Kimi tickets desk that reads as
   * configured while behaving like the default one is exactly the failure a launch-time asymmetry
   * must not be allowed to hide. So the drop is collected here, recorded on the launched command
   * (see {@code AgentLaunchRecord}), and readable afterwards.
   */
  protected final List<String> renderNotes = new ArrayList<>();

  /**
   * Whether an interactive session renders flat text instead of a full-screen TUI (default false).
   * Off everywhere for now; enable per-launch with {@link #flatOutput()} when a readable captured
   * session log matters more than the interactive UI.
   */
  protected boolean flatOutput;

  /**
   * Whether a one-off {@link #run(String)} renders plain text instead of a structured stream (default
   * false). Set via {@link #plainTextOutput()} by callers that consume stdout verbatim (e.g. prompt
   * refinement); a no-op for harnesses whose run is plain text already.
   */
  protected boolean plainTextOutput;

  /** The qits-chosen session UUID to pin at launch (create-only), or null. */
  protected String sessionId;

  /** An existing session UUID to continue, or null. */
  protected String resumeSessionId;

  /** Whether {@link #resumeSessionId} is branched into {@link #sessionId} instead of continued. */
  protected boolean forkRequested;

  /** The endpoint the harness reports session-identity changes to, or null. */
  protected String sessionReportingUrl;

  /**
   * Whether to also emit the turn-boundary activity hooks (UserPromptSubmit / Stop / Notification /
   * SessionEnd) alongside the always-on SessionStart hook. Gated by {@link
   * AgentDefaults#activityTrackingEnabled()}; SessionStart is emitted regardless (it drives
   * session-lineage). Only meaningful when {@link #sessionReportingUrl} is set.
   */
  protected boolean activityTracking;

  /** Attaches an MCP server under {@code key} with a harness-agnostic {@link McpServers} config. */
  public CodingAgent mcpServer(String key, JsonObject config) {
    this.mcpServers.put(key, config);
    return this;
  }

  /** Pre-approves these tools so the session can use them without a permission prompt. */
  public CodingAgent allowedTools(Collection<String> tools) {
    this.allowedTools.addAll(tools);
    return this;
  }

  /** Seeds an interactive session with a first prompt (only used by {@link #start()}). */
  public CodingAgent initialContext(String context) {
    this.initialContext = context;
    return this;
  }

  /**
   * Appends {@code text} to the harness's own system prompt, steering every turn of the session
   * rather than only its first one. Distinct from {@link #initialContext}, which is a user turn: a
   * seed can be argued away or fall out of the context window, an appendix cannot, which is what a
   * standing instruction ("you are this desk, work through these tools") needs. Honoured by
   * harnesses that expose the seam; see {@link KimiCodeAgent#appendSystemPrompt} for the one that
   * does not.
   */
  public CodingAgent appendSystemPrompt(String text) {
    this.systemPromptAppendix = text;
    return this;
  }

  /** Pins the session to a specific model (a harness-specific id or alias, e.g. "haiku"). */
  public CodingAgent model(String model) {
    this.model = model;
    return this;
  }

  /**
   * Sets the session's effort level. Honoured by harnesses that have the concept; {@link
   * KimiCodeAgent#effort} is the one that does not and says so rather than dropping it silently.
   */
  public CodingAgent effort(String effort) {
    this.effort = effort;
    return this;
  }

  /**
   * Enables Remote Control on an interactive session, listed under {@code name}.
   *
   * <p>Honoured only where the harness has a flag for it — {@link ClaudeCodeAgent#start()}. A chat
   * is not an interactive session and its enable rides the transport instead, so this is never set
   * on a chat render; a harness with no remote-control mechanism at all reports the drop.
   */
  public CodingAgent remoteControl(String name) {
    this.remoteControlName = name == null || name.isBlank() ? null : name.trim();
    return this;
  }

  /** What this harness could not render of what it was configured with — see {@link #renderNotes}. */
  public List<String> renderNotes() {
    return List.copyOf(renderNotes);
  }

  /** Records one such drop. Never a credential and never a whole prompt — a sentence a human reads. */
  protected void note(String note) {
    renderNotes.add(note);
  }

  /** Overlays an environment variable on the launched process. */
  public CodingAgent environment(String key, String value) {
    this.environment.put(key, value);
    return this;
  }

  /** Runs without permission prompts. */
  public CodingAgent skipPermissions() {
    this.skipPermissions = true;
    return this;
  }

  /**
   * Renders an interactive session as flat text (no full-screen TUI/animations), so a captured PTY
   * session log stays readable. Off by default.
   */
  public CodingAgent flatOutput() {
    this.flatOutput = true;
    return this;
  }

  /**
   * Renders a one-off run as plain text on stdout (no stream-json), for callers that consume stdout
   * verbatim. Harnesses that default to structured output (Kimi's {@code -p}) honor it; harnesses
   * whose run is plain text already ignore it.
   */
  public CodingAgent plainTextOutput() {
    this.plainTextOutput = true;
    return this;
  }

  /** Pins a fresh session under this qits-generated UUID (create-only external naming). */
  public CodingAgent sessionId(String uuid) {
    this.sessionId = uuid;
    return this;
  }

  /** Continues an existing session in place (same ID, transcript appended). */
  public CodingAgent resume(String sessionId) {
    this.resumeSessionId = sessionId;
    return this;
  }

  /**
   * Branches the resumed session into a new one pinned as {@code newUuid} — the fork inherits the
   * full conversation history but persists separately. Requires {@link #resume(String)}.
   */
  public CodingAgent fork(String newUuid) {
    this.sessionId = newUuid;
    this.forkRequested = true;
    return this;
  }

  /**
   * Has the harness report session identity changes (the initial start and any in-session switch,
   * e.g. an in-TUI {@code /resume}) to this endpoint — the daemon's own loopback hook webhook.
   */
  public CodingAgent sessionReporting(String url) {
    this.sessionReportingUrl = url;
    return this;
  }

  /**
   * Enables the turn-boundary activity hooks (BUSY/IDLE/WAITING/ENDED) on top of the always-on
   * SessionStart lineage hook. Off by default; the launch path sets it from {@link AgentDefaults}.
   */
  public CodingAgent activityTracking(boolean on) {
    this.activityTracking = on;
    return this;
  }

  /**
   * Render-time guard for the session configuration — harness-agnostic, called by implementations
   * before interpolating anything into an argv. Session ids must pass {@link #isSessionIdValid};
   * {@code fork} without {@code resume} has nothing to branch; pinning <em>and</em> resuming without
   * a fork is contradictory (a pinned id is create-only).
   */
  protected void validateSessionConfiguration() {
    requireValidSessionId(sessionId, "session id");
    requireValidSessionId(resumeSessionId, "resume session id");
    if (forkRequested && resumeSessionId == null) {
      throw new IllegalStateException("fork requires resume: no session to branch from");
    }
    if (sessionId != null && resumeSessionId != null && !forkRequested) {
      throw new IllegalStateException(
          "sessionId and resume are mutually exclusive unless forking: a pinned id is create-only");
    }
  }

  /**
   * Whether {@code value} is acceptable as a session id for this harness. The default accepts
   * canonical UUIDs only; subclasses may relax this (e.g. Kimi Code uses {@code session_<uuid>}).
   */
  protected boolean isSessionIdValid(String value) {
    return value == null || value.matches(UUID_PATTERN);
  }

  /** Canonical UUID only — {@code UUID.fromString} is laxer (e.g. accepts {@code 1-2-3-4-5}). */
  protected static final String UUID_PATTERN =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  private void requireValidSessionId(String value, String what) {
    if (!isSessionIdValid(value)) {
      throw new IllegalArgumentException("Invalid " + what + ": " + value);
    }
  }

  /**
   * Where this harness persists the transcript of a session run under {@code cwd}, relative to its
   * config dir (harness-owned convention — no caller ever computes a path itself).
   */
  public abstract Path transcriptPath(String cwd, String sessionId);

  /** Where this harness persists a session's subagent sidechains, relative to its config dir. */
  public abstract Path subagentsDir(String cwd, String sessionId);

  /** Renders the configured agent as an interactive launch (a human attaches a terminal). */
  public abstract LaunchSpec start();

  /** Renders the configured agent as a one-off launch that runs {@code prompt} to completion. */
  public abstract LaunchSpec run(String prompt);

  /**
   * Renders the configured agent as a bidirectional streaming chat session: user messages in and
   * structured events out over stdin/stdout (stream-json), driven programmatically over plain pipes.
   */
  public abstract LaunchSpec chat();
}
