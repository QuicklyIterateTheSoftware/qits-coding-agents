package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where in the product a session was started from — the key everything a session is configured with
 * hangs off. It replaces {@code AgentDesk}, the two-valued enum that was this epic's proof of
 * concept: the desk added a steering axis to exactly one surface, with the prompt inlined, because
 * there was nowhere to put a prompt. {@code EPICS} is {@link #PROJECT_EPICS} and {@code TICKETS} is
 * {@link #PROJECT_TICKETS}, and both render byte for byte what the enum rendered.
 *
 * <p>The eight the platform has today:
 *
 * <ul>
 *   <li>{@link #PROJECT_EPICS} — the refinement agent on a project's epics overview. The projects
 *       daemon's {@code PROJECT}-scoped chat, steered by nothing at all.
 *   <li>{@link #PROJECT_TICKETS} — the triage agent on a project's tickets overview. The one surface
 *       that carries a system prompt today.
 *   <li>{@link #EPIC_CHAT} / {@link #EPIC_AGENT} — the refining route's chat and agent tabs.
 *   <li>{@link #WORKSPACE_CHAT} / {@link #WORKSPACE_AGENT} — the workspace detail route's chat and
 *       agents tabs.
 *   <li>{@link #EPIC_AUTONOMOUS} — the composed task-prompt run, which no human presses a button
 *       for.
 *   <li>{@link #TICKET_DISPATCH} — the agent a ticket dispatch starts in a freshly cut workspace.
 * </ul>
 *
 * <p><b>The four workspace-daemon surfaces send byte-identical launch requests today.</b> {@link
 * #EPIC_CHAT} and {@link #WORKSPACE_CHAT} differ only in which container the request reaches; the
 * daemon cannot tell them apart and neither can anything downstream. That is what this type is for:
 * until the surface is a value that travels there is nothing to key a configuration by, and the
 * frontend's {@code " (tickets desk)"} string match stays the only way to tell two sessions apart.
 *
 * <p><b>A record over a key rather than an enum, because the vocabulary stays open.</b> Adding a
 * ninth surface is a constant here and a shipped default beside it — not a migration, not a schema
 * change — and the store in qits-projects answers an unknown surface with a neutral default rather
 * than a 404 for exactly the same reason. Open does not mean unchecked: {@link #of} refuses a key
 * outside {@link #KNOWN} with an {@link InvalidCommandRequestException}, the way an unknown {@link
 * AgentMcpScope} is refused, because a misspelled surface that silently fell through to a default
 * would be a misconfigured caller that looks like a working one.
 *
 * <p><b>Beside {@link AgentMcpScope}, never folded into it.</b> The scope is <em>addressing</em> —
 * how narrow the MCP urls are, a runtime choice of the caller — and the surface is <em>what the
 * session is for</em>. Both axes cross freely: a tickets desk can be narrowed to one repository, and
 * narrowing to a repository must not quietly change what the agent is steered at.
 */
public record AgentSurface(String key) {

  /** The refinement agent on a project's epics overview. Projects daemon, {@code PROJECT} scope. */
  public static final AgentSurface PROJECT_EPICS = new AgentSurface("project.epics");

  /** The triage agent on a project's tickets overview. Projects daemon, {@code PROJECT} scope. */
  public static final AgentSurface PROJECT_TICKETS = new AgentSurface("project.tickets");

  /** The refining route's chat tab. Workspace daemon, {@code REPOSITORY} scope. */
  public static final AgentSurface EPIC_CHAT = new AgentSurface("epic.chat");

  /** The refining route's agent tab. Workspace daemon, {@code REPOSITORY} scope, interactive. */
  public static final AgentSurface EPIC_AGENT = new AgentSurface("epic.agent");

  /** The workspace detail route's chat tab. Workspace daemon, {@code REPOSITORY} scope. */
  public static final AgentSurface WORKSPACE_CHAT = new AgentSurface("workspace.chat");

  /** The workspace detail route's agents tab. Workspace daemon, interactive. */
  public static final AgentSurface WORKSPACE_AGENT = new AgentSurface("workspace.agent");

  /** The composed task-prompt run nobody presses a button for. Read-only marked servers. */
  public static final AgentSurface EPIC_AUTONOMOUS = new AgentSurface("epic.autonomous");

  /** The agent a ticket dispatch starts in a freshly cut workspace. */
  public static final AgentSurface TICKET_DISPATCH = new AgentSurface("ticket.dispatch");

  /**
   * The vocabulary, in the order the editor lists it: the two project desks, the four a human opens
   * in a workspace container, then the two composed runs.
   *
   * <p>The same order and the same keys as {@code AgentSurfaceDefaults.SURFACES} in
   * qits-projects-service. The two lists are copies rather than a shared type — that service depends
   * on no daemon library and this library reads no service — so what crosses between them is the
   * <em>key</em>, character for character. A rename on either side is a wire break neither
   * repository's suite would notice.
   */
  public static final List<AgentSurface> KNOWN =
      List.of(
          PROJECT_EPICS,
          PROJECT_TICKETS,
          EPIC_CHAT,
          EPIC_AGENT,
          WORKSPACE_CHAT,
          WORKSPACE_AGENT,
          EPIC_AUTONOMOUS,
          TICKET_DISPATCH);

  public AgentSurface {
    key = key == null ? null : key.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * The surface named by {@code raw}, or empty when it is blank or outside {@link #KNOWN}. The
   * lenient half of the parser, for a caller that has its own answer for "nobody named one" — see
   * {@link AgentLaunchRequest#surfaceOrDefault()}.
   */
  public static Optional<AgentSurface> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    AgentSurface candidate = new AgentSurface(raw);
    return KNOWN.contains(candidate) ? Optional.of(candidate) : Optional.empty();
  }

  /**
   * The surface named by {@code raw}, refusing the launch when it is blank or unknown.
   *
   * <p>The message shape is load-bearing: it is what a daemon's API reports as a 400 rather than
   * "Internal error", the same way an unparseable {@link AgentMcpScope} is reported.
   */
  public static AgentSurface of(String raw) {
    return parse(raw)
        .orElseThrow(() -> new InvalidCommandRequestException("Unknown agent surface: " + raw));
  }

  @Override
  public String toString() {
    return key;
  }
}
