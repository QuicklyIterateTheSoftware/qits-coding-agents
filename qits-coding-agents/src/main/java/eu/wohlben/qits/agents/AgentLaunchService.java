package eu.wohlben.qits.agents;

import eu.wohlben.qits.agents.acp.AcpChatProtocol;
import eu.wohlben.qits.agents.acp.AcpSessionConfig;
import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.AgentSessionSource;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.CheckoutContext;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import eu.wohlben.qits.commands.StreamJsonChatProtocol;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Launches a coding agent into this container as a first-class command: rendered by a {@link
 * CodingAgent}, spawned through {@link AgentCommands}, and returned as a {@link Command} — so an
 * agent session shows up in the Commands list and is attachable and terminable like any other.
 *
 * <p><strong>It no longer owns the MCP scope→URL construction.</strong> Both daemons had a copy of
 * this class with its own {@code serversFor} switch — one server on the projects side, three on the
 * workspace side, with different narrowing and different pre-approval lists — and that switch was
 * the largest single reason the two copies could not simply be merged. It is now {@link
 * AgentMcpServers}, supplied by the host, and this class renders whatever it is handed: attaching
 * each server, marking URLs read-only for an autonomous run, and stripping the Claude tool prefix
 * for Kimi. What survives here is the safety rule the URLs are subject to — see {@link
 * AgentMcpIds}, which an implementation is expected to call.
 *
 * <p><strong>What the move into the container removed.</strong> The host version had twelve injected
 * collaborators spanning five sibling domains. Inside the container:
 *
 * <ul>
 *   <li>the owner ids leave every signature — the branch and commit are ambient on {@link
 *       CheckoutContext} and the rest is the host's own business;
 *   <li>{@code WorkspaceService.ensureContainer} is gone at all three call sites: the daemon is the
 *       container, and a running daemon is proof of it;
 *   <li>{@code RepositoryRepository} is gone — the project id is an environment value, see {@link
 *       McpEndpoints};
 *   <li>{@code QitsHostResolver} is gone — the daemon dialled qits, so it knows the address;
 *   <li>{@code WorkspacePromptDraftService} is gone — there is no draft store here, so the request
 *       carries the prompt and {@code deliverTaskPrompt} is taken at its word;
 *   <li>{@code ServiceEventSpool} is gone, deliberately: chats used to open seeded with the service
 *       events that fired while nothing was listening. {@code ServiceSupervisor} is in this repo but
 *       has no spool, and building one is a feature rather than part of a move. Recorded as an open
 *       item;
 *   <li>{@code SettingsService} becomes {@link AgentDefaults};
 *   <li>the three {@code QuarkusTransaction.requiringNew()} wrappers are gone with the database.
 * </ul>
 *
 * <p>The hook port and the claude mount arrive as constructor arguments rather than configuration
 * reads. This module is framework-free so it could not read them anyway, but the reason it matters is
 * that {@code ControlSocket} passes the very same values to {@code HookWebhook} and to the transcript
 * service: two readers of one setting is how a launch ends up rendering hooks at a port nothing is
 * listening on, which fails invisibly — the agent runs, and simply never reports lineage or activity.
 */
public final class AgentLaunchService {

  /** Session ids are generated UUIDs; only hex and dashes ever appear. */
  private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F-]{36}");

  /**
   * The one-sentence bootstrap turn pushed in place of the composed prompt: it carries the user's
   * authority ("do this"), while the {@code taskPrompt} MCP tool carries the content (the refined
   * markdown + attached images). Trivially deliverable in every launch shape — argv for interactive
   * and autonomous, a stream-json turn for chat — which is the whole point of the push→fetch
   * inversion (an image can't ride an argv or a PTY keystroke, but it rides a tool result).
   *
   * <p>The shipped default, and the one place the two copies of this class disagreed on a
   * <em>literal</em>: the projects copy said "for this project", the workspace copy "for this
   * workspace". Neither is wrong for its host and nothing asserts the text, so rather than pick a
   * winner the sentence became a constructor argument with this as the fallback, and each daemon
   * passes its own noun. It is also where this ends up anyway: the configuration epic makes the
   * initial prompt a per-surface stored value, and a host-supplied string is the same seam one step
   * earlier.
   */
  public static final String TASK_PROMPT_BOOTSTRAP =
      "Fetch the current task prompt for this workspace with the taskPrompt tool, then implement"
          + " what it describes.";

  /**
   * The steering a {@link AgentSurface#PROJECT_TICKETS} launch carries, appended to the harness's
   * own system prompt. It names the desk, the tools it works through, and the two things a triage session gets
   * wrong without being told: that a ticket's description has to say how to <em>see</em> the
   * problem, and that resolving is reversible, so nothing about a ticket needs guarding as if it
   * were final.
   *
   * <p>The last line is the boundary between the two desks. A tickets session that meets something
   * plan-shaped must hand it back rather than file an epic from here: an epic is drafted against the
   * project's plan, by a session steered at the plan, and one written as a side effect of triage
   * lands with none of that context.
   *
   * <p>A Java text block rather than a classpath resource, because this module is framework-free and
   * has no resources directory — deliberately, see {@link ClaudeCodeAgent}'s class javadoc: a prompt
   * is embedded as a shell-quoted argument, so it can be a literal without a side file, and a
   * literal is what a unit test can assert byte for byte.
   */
  static final String TICKETS_DESK_PROMPT =
      """
      You are this project's tickets front desk: intake and triage for the small-scoped work \
      that sits beside the epic plans — bugs and improvements.

      Work through the repository MCP server's ticket tools. Survey with list_tickets and \
      get_ticket before anything else; a ticket carries its own comment thread, so get_ticket \
      is the whole conversation and not just the fields. File with create_ticket, typed BUG or \
      IMPROVEMENT, and say in the description how to see the problem, not only that it exists. \
      Assign with update_ticket. Discuss on the thread with add_ticket_comment, and correct \
      your own notes with update_ticket_comment rather than posting a second one after the \
      first. Resolve with transition_ticket once the work is confirmed done, and reopen the \
      same way when it turns out not to be: resolving is reversible, and nothing about a ticket \
      freezes.

      When something is too big for a ticket — when it needs a plan rather than a fix — say so \
      and point at the epics desk. Do not file an epic from here.\
      """;

  /** Kimi session ids are opaque {@code session_}-prefixed path-safe slugs. */
  private static final String KIMI_SESSION_PATTERN = "session_[A-Za-z0-9_-]{1,128}";

  private final AgentCommands commands;
  private final AgentAuthStatus authStatus;
  private final AgentTranscriptService transcripts;
  private final AgentTranscriptTailService transcriptTail;
  private final AgentDefaults defaults;
  private final AgentMcpServers mcpServers;
  private final CheckoutContext checkout;
  private final String claudeMount;
  private final int hooksPort;
  private final String taskPromptBootstrap;

  /**
   * @param claudeMount where the shared credential volume mounts. Agent launches point {@code HOME}
   *     here so the in-container {@code claude} reads the operator's one-time OAuth login off the
   *     volume instead of a per-session secret — the one credential that crosses into the sandbox.
   *     Blank leaves {@code HOME} at the image default.
   * @param hooksPort the loopback port this daemon's own hook webhook binds. Must be the value
   *     {@code HookWebhook} was given, not a second read of the same key.
   */
  public AgentLaunchService(
      AgentCommands commands,
      AgentAuthStatus authStatus,
      AgentTranscriptService transcripts,
      AgentTranscriptTailService transcriptTail,
      AgentDefaults defaults,
      AgentMcpServers mcpServers,
      CheckoutContext checkout,
      String claudeMount,
      int hooksPort) {
    this(
        commands,
        authStatus,
        transcripts,
        transcriptTail,
        defaults,
        mcpServers,
        checkout,
        claudeMount,
        hooksPort,
        TASK_PROMPT_BOOTSTRAP);
  }

  /**
   * @param taskPromptBootstrap the sentence a {@code deliverTaskPrompt} launch is seeded with, or
   *     null for {@link #TASK_PROMPT_BOOTSTRAP}. A host with its own noun for the unit of work
   *     passes it here rather than the library guessing which product it is inside.
   */
  public AgentLaunchService(
      AgentCommands commands,
      AgentAuthStatus authStatus,
      AgentTranscriptService transcripts,
      AgentTranscriptTailService transcriptTail,
      AgentDefaults defaults,
      AgentMcpServers mcpServers,
      CheckoutContext checkout,
      String claudeMount,
      int hooksPort,
      String taskPromptBootstrap) {
    this.commands = commands;
    this.authStatus = authStatus;
    this.transcripts = transcripts;
    this.transcriptTail = transcriptTail;
    this.defaults = defaults;
    this.mcpServers = mcpServers;
    this.checkout = checkout;
    this.claudeMount = claudeMount;
    this.hooksPort = hooksPort;
    this.taskPromptBootstrap =
        taskPromptBootstrap == null ? TASK_PROMPT_BOOTSTRAP : taskPromptBootstrap;
  }

  /** The bootstrap sentence this host seeds a {@code deliverTaskPrompt} launch with. */
  public String taskPromptBootstrap() {
    return taskPromptBootstrap;
  }

  /** Dispatches to {@link #launchChat} or {@link #launchInteractive} on the request's mode. */
  public Command launch(AgentLaunchRequest request) {
    if (request == null || request.scope() == null) {
      throw new InvalidCommandRequestException("scope is required");
    }
    if (request.fork() && (request.resumeSessionId() == null || request.resumeSessionId().isBlank())) {
      throw new InvalidCommandRequestException("fork requires resumeSessionId");
    }
    return request.modeOrDefault() == AgentLaunchMode.INTERACTIVE
        ? launchInteractive(request)
        : launchChat(request);
  }

  /**
   * Launches the coding agent as a <strong>chat</strong> command, with the MCP server(s) for the
   * request's scope attached. Claude drives it over stream-json; Kimi has no stdin chat mode, so its
   * chat rides an in-JVM ACP client with the scoped servers carried on {@code session/new}. Either
   * way the session is rendered as one conversation and tracked in the command registry
   * (re-attachable, logged). Tools run auto-approved.
   */
  public Command launchChat(AgentLaunchRequest request) {
    // A bad request is refused before anything is resolved or probed: the surface is required, and
    // a caller that forgot it gets a 400 rather than somebody's guess at what it meant.
    AgentSurface surface = request.requiredSurface();

    // Resolve the harness once and thread it through every helper, so auth, render, transport and
    // the recorded command all agree. A resume keeps the resumed session's original harness (you
    // cannot resume a Claude session under Kimi); otherwise explicit choice → default → CLAUDE.
    AgentType type = resolveHarness(request.resumeSessionId(), request.agentType());

    // The agent cannot authenticate until an operator has signed in on the shared credential
    // volume. When nobody has, this REFUSES and says so — it does not quietly become a login
    // terminal, which is what it used to do: you asked for a chat about an epic, got a bare REPL,
    // and nothing in the answer said so. The caller knows what it was trying to open and offers
    // launchLogin as a deliberate next step.
    requireSignedIn(type);

    PinnedSession pinned = pinSession(request.resumeSessionId(), request.fork(), type);
    Rendered rendered = renderedChat(request.scope(), surface, pinned, type);
    LaunchSpec spec = rendered.spec();
    // Claude drives chat over stream-json; Kimi has no stdin chat, so its chat rides an in-JVM ACP
    // client with the scoped MCP servers carried on session/new.
    ChatProtocolFactory protocolFactory =
        type == AgentType.KIMI
            ? process ->
                new AcpChatProtocol(process, buildAcpSessionConfig(request.scope(), surface, pinned))
            : claudeChatProtocol(pinned, surface, configurationFor(surface));

    Command command =
        commands.launchChat(
            nameFor(request.scope(), surface, type),
            spec.script(),
            spec.environment(),
            pinned.commandId(),
            pinned.ref(),
            chatTranscriptSweep(),
            protocolFactory,
            new AgentLaunchMetadata(
                type.name(),
                surface.key(),
                rendered.record().toJson(),
                rendered.redactions()));
    // The live transcript import: the durable head a mid-run re-attach replays from.
    transcriptTail.startTail(command.id(), type);
    // Both turns go over stdin, in order: a stream-json chat only speaks over stdin, so neither can
    // be a CLI argument, and the pipe buffers them until the harness starts reading.
    for (String turn : openingTurns(configurationFor(surface), request)) {
      commands.chatSend(command.id(), turn);
    }
    return command;
  }

  /**
   * Spawns an autonomous agent run as a <strong>chat</strong> command that <strong>fetches</strong>
   * its task over MCP: the narrowed repository server is attached (read-only marked) and the seed
   * turn is the {@link #TASK_PROMPT_BOOTSTRAP}, so the run reads the composed draft via
   * {@code taskPrompt}. Riding the chat pipeline — instead of a one-shot {@code claude -p}, which
   * printed nothing until it exited — renders the run as a live conversation on its command page, and
   * a human can follow up in the same session once the autonomous turn finishes.
   */
  public Command launchAutonomous(String name) {
    // Composed flows carry no per-launch choice, so they resolve the default harness.
    AgentType type = defaults.defaultAgentType();
    // Nobody is watching a composed run, so a login terminal here would be the green-while-dead
    // shape outright: the dispatch reports it started an agent, the container sits at a sign-in
    // screen, and the work never happens. It fails loudly instead.
    requireSignedIn(type);

    PinnedSession pinned = pinSession(null, false, type);
    // Its own surface rather than the epics desk's. This is a different session from a human's
    // epics chat — read-only marked servers, a bootstrap seed, nobody watching — and it has always
    // deserved its own key; borrowing the desk's was what there was before there were surfaces.
    Rendered rendered =
        renderedAutonomousChat(
            AgentMcpScope.REPOSITORY, AgentSurface.EPIC_AUTONOMOUS, pinned, type);
    LaunchSpec spec = rendered.spec();
    ChatProtocolFactory protocolFactory =
        type == AgentType.KIMI
            ? process ->
                new AcpChatProtocol(
                    process,
                    buildAcpSessionConfig(
                        AgentMcpScope.REPOSITORY, AgentSurface.EPIC_AUTONOMOUS, pinned, true))
            : claudeChatProtocol(
                pinned,
                AgentSurface.EPIC_AUTONOMOUS,
                configurationFor(AgentSurface.EPIC_AUTONOMOUS));
    Command command =
        commands.launchChat(
            name,
            spec.script(),
            spec.environment(),
            pinned.commandId(),
            pinned.ref(),
            chatTranscriptSweep(),
            protocolFactory,
            new AgentLaunchMetadata(
                type.name(),
                AgentSurface.EPIC_AUTONOMOUS.key(),
                rendered.record().toJson(),
                rendered.redactions()));
    transcriptTail.startTail(command.id(), type);
    // The bootstrap rides stdin as the first user turn (a chat only speaks over stdin); the agent
    // then pulls the real composed prompt back over MCP via taskPrompt. It is this surface's
    // configured initial prompt when the document carries one, and the host's own sentence when it
    // does not — see initialPrompt.
    commands.chatSend(
        command.id(), taskPromptTurn(configurationFor(AgentSurface.EPIC_AUTONOMOUS)));
    return command;
  }

  /**
   * Launches the full interactive agent TUI (the plain {@code claude} or {@code kimi} REPL in
   * xterm.js, kind {@code TERMINAL}) as a first-class agent session: same MCP scope servers,
   * credential overlay and skip-permissions as chat, plus a session id and the session-report hook —
   * so the run is resumable, forkable (Claude only), and its transcript is imported on exit like any
   * chat. The PTY byte stream stays terminal-only; the structured conversation comes from the
   * transcript.
   */
  public Command launchInteractive(AgentLaunchRequest request) {
    // As in launchChat: the surface is required, and a request without one is refused before any
    // harness is resolved or any credential volume is read.
    AgentSurface surface = request.requiredSurface();
    AgentType type = resolveHarness(request.resumeSessionId(), request.agentType());
    if (type == AgentType.KIMI && request.fork()) {
      throw new InvalidCommandRequestException("fork is not supported by Kimi Code");
    }
    requireSignedIn(type);

    PinnedSession pinned = pinSession(request.resumeSessionId(), request.fork(), type);
    // The first turn is the argv seed — the REPL opens on it — and anything after it is typed in
    // once the session is up. See openingTurns for why a second turn is the rare shape.
    List<String> turns = openingTurns(configurationFor(surface), request);
    Rendered rendered =
        renderedInteractive(
            request.scope(), surface, turns.isEmpty() ? null : turns.get(0), pinned, type);
    LaunchSpec spec = rendered.spec();
    Command command =
        commands.launchAgent(
            interactiveNameFor(request.scope(), surface, type),
            spec.script(),
            true,
            spec.environment(),
            pinned.commandId(),
            pinned.ref(),
            transcriptSweep(),
            new AgentLaunchMetadata(
                type.name(),
                surface.key(),
                rendered.record().toJson(),
                rendered.redactions()));
    for (String turn : turns.subList(Math.min(1, turns.size()), turns.size())) {
      commands.sendKeystrokes(command.id(), turn);
    }
    return command;
  }

  /**
   * The turns a session opens with, in order: <b>the surface's configured initial prompt first</b>,
   * then whatever the caller composed.
   *
   * <p>The two are different things and this is where that stops being blurred. The initial prompt
   * belongs to the surface — it is the same sentence every session started from that place opens
   * with, written once in the editor — and the caller's is this one session's. So the configured one
   * goes first, and the caller's follows it as a second turn rather than replacing it.
   *
   * <p><b>They almost never both arrive.</b> The owner settled that a user prompt landing alongside
   * an initial prompt is the failure shape rather than the normal one — a human's prompt arrives
   * later, after reading what came back — so this deliberately does not design for simultaneity: a
   * chat writes both to stdin in order, and an interactive launch seeds the first on argv and types
   * the second into the PTY. Typing into a TUI that is still starting is exactly the race that makes
   * a second opening turn a shape to avoid, and it is avoided by not sending one, not by buffering.
   *
   * <p>{@code deliverTaskPrompt} keeps deciding <em>whether</em> the task-prompt fetch is what this
   * run does. When it is, the fetch instruction is the only opening turn: the caller's composed text
   * is the draft the agent is about to pull over MCP, and pushing it as well would deliver it twice
   * in two shapes.
   */
  private List<String> openingTurns(
      AgentSurfaceConfiguration configuration, AgentLaunchRequest request) {
    if (request.deliverTaskPrompt()) {
      return List.of(taskPromptTurn(configuration));
    }
    List<String> turns = new ArrayList<>();
    String initial = initialPrompt(configuration);
    if (!initial.isBlank()) {
      turns.add(initial);
    }
    String composed = request.initialContext();
    if (composed != null && !composed.isBlank()) {
      turns.add(composed);
    }
    return List.copyOf(turns);
  }

  /**
   * The turn a composed run opens with: <b>the surface's configured initial prompt, and the host's
   * own bootstrap sentence when it has none.</b>
   *
   * <p>This is where {@link #TASK_PROMPT_BOOTSTRAP} and the initial prompt meet, and the resolution
   * is that they are the same field one release apart. The sentence {@code epic.autonomous} and
   * {@code ticket.dispatch} push today <em>is</em> an initial prompt: one line, per surface, carrying
   * the user's authority while {@code taskPrompt} carries the content. So the store seeds it as
   * those two surfaces' initial prompt — each with its own daemon's noun, "this project" and "this
   * workspace", because harmonising two live literals would change what one of them pushes on the
   * day the store ships — and a configured value simply wins here.
   *
   * <p>The host's constructor argument does not go away; it becomes the fallback for a container
   * born without a document, which is the same rung every other shipped constant falls back to.
   */
  private String taskPromptTurn(AgentSurfaceConfiguration configuration) {
    String initial = initialPrompt(configuration);
    return initial.isBlank() ? taskPromptBootstrap : initial;
  }

  /**
   * The surface's initial prompt with its placeholders filled in — {@code {{epic}}}, {@code
   * {{repository}}}, {@code {{branch}}} and the rest of {@link AgentPromptTemplate#NAMES}.
   *
   * <p>The checkout answers what it knows and the host answers the rest through {@link
   * AgentDefaults#ambientFacts()}; a name nobody can answer is left literal rather than rendered as
   * {@code null}, so a prompt with a hole in it reads as one.
   */
  private String initialPrompt(AgentSurfaceConfiguration configuration) {
    String template = configuration.initialPrompt();
    if (template == null || template.isBlank()) {
      return "";
    }
    Map<String, String> facts = new HashMap<>(defaults.ambientFacts());
    if (checkout != null) {
      facts.put("branch", checkout.branch());
      facts.put("commit", checkout.commitHash());
    }
    return AgentPromptTemplate.render(template, facts);
  }

  /**
   * Launches an interactive agent login terminal (a normal PTY command, kind {@code TERMINAL}) so an
   * operator can complete the one-time sign-in (Claude: OAuth through the REPL onboarding; Kimi: the
   * device-code flow). Writes to the shared credential volume, so it signs in every container at
   * once.
   *
   * <p><b>A door, not a fallback.</b> It used to be what the three launch paths returned when the
   * harness was signed out, which made every one of them able to answer with a session nobody asked
   * for. They refuse now (see {@link #requireSignedIn}) and this stays exactly where it was, opened
   * deliberately by a caller that tells the user what it is.
   */
  public Command launchLogin(AgentType agentType) {
    LaunchSpec spec = renderLogin(agentType);
    String name =
        switch (agentType) {
          case CLAUDE -> "Claude sign-in";
          case KIMI -> "Kimi sign-in";
        };
    // No surface: a sign-in terminal is not a session anyone started from anywhere in the product,
    // and giving it one would key it to a configuration it must not render — and so no launch
    // record either, for the same reason: there was no configuration to record.
    return commands.launchAgent(
        name,
        spec.script(),
        true,
        spec.environment(),
        null,
        null,
        null,
        AgentLaunchMetadata.of(agentType.name(), null));
  }

  /**
   * Refuses this launch when nobody has signed the harness in on the shared credential volume.
   *
   * <p>The one place the auth gate is applied, and the change is what it does with the answer: the
   * three call sites used to <em>substitute</em> {@link #launchLogin}'s bare REPL for the session
   * that was asked for. The swap was invisible — the caller received a command and attached to it,
   * exactly as it would for a real session — so a user could not tell a signed-out platform from a
   * working one, and an unattended dispatch reported that it had started an agent when it had
   * started a sign-in prompt nobody would ever look at.
   *
   * <p>Asked at launch, and deliberately not read off the capability report: that report is taken
   * once at container start and an operator can sign in a minute later, so a cached answer is a fine
   * <em>display</em> and a wrong gate.
   *
   * @throws AgentNotSignedInException naming the harness, for a caller to turn into its own answer
   */
  private void requireSignedIn(AgentType agentType) {
    if (!authStatus.isLoggedIn(agentType)) {
      throw new AgentNotSignedInException(agentType);
    }
  }

  /** Renders the interactive login command with the shared-volume credential overlay. */
  LaunchSpec renderLogin(AgentType agentType) {
    Map<String, String> env = new HashMap<>();
    if (claudeMount != null && !claudeMount.isBlank()) {
      switch (agentType) {
        case CLAUDE -> env.put("HOME", claudeMount);
        // Kimi uses KIMI_CODE_HOME, set at the container level; login must run against the real
        // volume home (no per-launch mktemp farm) so credential writes survive.
        case KIMI -> env.put("KIMI_CODE_HOME", claudeMount + "/.kimi-code");
      }
    }
    return switch (agentType) {
      case CLAUDE ->
          // Run the `claude` REPL, NOT the `claude auth login` subcommand. The REPL's first-run
          // onboarding renders a paste-the-code prompt over the PTY and reads it from stdin, so an
          // operator can complete sign-in in the terminal. The `auth login` subcommand blocks on a
          // loopback HTTP callback the host browser can never reach.
          new LaunchSpec("exec claude", true, env);
      case KIMI ->
          // Kimi login is a device-code flow that prints the verification URL + user code and
          // polls, so it works plainly over a TTY.
          new LaunchSpec("exec kimi login", true, env);
    };
  }

  /**
   * A launch's session identity, generated before anything exists: the command id (rendered into the
   * session-report hook URL) and the first {@link AgentSessionRef} of its session list.
   */
  record PinnedSession(String commandId, AgentSessionRef ref) {}

  /**
   * The harness for this launch. A resume is pinned to the resumed session's recorded harness (a
   * Claude session can't be resumed under Kimi, and its transcript layout and auth probe are
   * harness-specific), overriding any explicit choice or default. A fresh launch resolves normally:
   * explicit choice → the configured default → CLAUDE.
   */
  private AgentType resolveHarness(String resumeSessionId, AgentType explicit) {
    if (resumeSessionId != null) {
      Optional<AgentType> resumed =
          commands.agentTypeForSession(resumeSessionId).flatMap(AgentType::parse);
      if (resumed.isPresent()) {
        return resumed.get();
      }
    }
    return defaults.resolve(explicit);
  }

  /**
   * Pins the launch's session identity. Fresh launches pin a brand-new UUID ({@code PINNED}); Kimi
   * Code cannot pin a fresh session id, so its fresh launches return a {@code null} ref and the id is
   * learned from the harness's SessionStart hook. Resume reuses {@code resumeSessionId} in place
   * ({@code RESUMED}); fork branches it into a fresh pin ({@code FORKED}, with the origin recorded).
   *
   * <p>Resume and fork require the session to belong to <em>this container</em>. That is narrower
   * than a "belongs to this project" check, and it fails closed: a session driven before a
   * container recreate is refused, because {@code CommandStore} did not survive to vouch for it.
   */
  PinnedSession pinSession(String resumeSessionId, boolean fork, AgentType agentType) {
    String commandId = UUID.randomUUID().toString();
    if (resumeSessionId == null) {
      if (fork) {
        throw new InvalidCommandRequestException("fork requires resumeSessionId");
      }
      if (agentType == AgentType.KIMI) {
        // Kimi cannot pin a new session id; the SessionStart hook will report it later.
        return new PinnedSession(commandId, null);
      }
      return new PinnedSession(
          commandId,
          new AgentSessionRef(
              UUID.randomUUID().toString(), AgentSessionSource.PINNED, null, null, Instant.now()));
    }
    requireSessionId(resumeSessionId, "session id", agentType);
    if (!commands.ownsSession(resumeSessionId)) {
      throw new InvalidCommandRequestException(
          "Session "
              + resumeSessionId
              + " was not started in this container and cannot be resumed here");
    }
    if (fork) {
      if (agentType == AgentType.KIMI) {
        throw new InvalidCommandRequestException("fork is not supported by Kimi Code");
      }
      return new PinnedSession(
          commandId,
          new AgentSessionRef(
              UUID.randomUUID().toString(),
              AgentSessionSource.FORKED,
              resumeSessionId,
              null,
              Instant.now()));
    }
    return new PinnedSession(
        commandId,
        new AgentSessionRef(resumeSessionId, AgentSessionSource.RESUMED, null, null, Instant.now()));
  }

  private void requireSessionId(String value, String label, AgentType agentType) {
    if (agentType == AgentType.KIMI) {
      if (value == null || !value.matches(KIMI_SESSION_PATTERN)) {
        throw new InvalidCommandRequestException("Invalid " + label + ": " + value);
      }
      return;
    }
    requireUuid(value, label);
  }

  /** Configures the agent's session flags + report hook from the pinned identity. */
  private CodingAgent withSession(
      CodingAgent agent, PinnedSession pinned, AgentSurfaceConfiguration configuration) {
    AgentSessionRef ref = pinned.ref();
    if (ref != null) {
      switch (ref.source()) {
        case PINNED -> agent.sessionId(ref.sessionId());
        case RESUMED -> agent.resume(ref.sessionId());
        case FORKED -> agent.resume(ref.forkedFromSessionId()).fork(ref.sessionId());
        case SWITCHED, REPORTED ->
            throw new IllegalStateException(
                "SWITCHED/REPORTED are hook-reported, never a launch source");
      }
    }
    return agent
        .activityTracking(configuration.activityTracking())
        .sessionReporting(sessionReportUrl(pinned.commandId()));
  }

  /**
   * The loopback hook endpoint for {@code commandId} — the agent's lifecycle hooks POST their stdin
   * JSON here, to this daemon's own webhook, which relays it home over the control socket. It targets
   * {@code 127.0.0.1}, so no resolver host is baked into the hook command. The {@code commandId}
   * rides as a query parameter so the webhook stays a dumb forwarder.
   */
  public String sessionReportUrl(String commandId) {
    return "http://127.0.0.1:" + hooksPort + "/hooks/claude-code?commandId=" + commandId;
  }

  /** The post-exit transcript import, composed onto the registry exit listener at spawn. */
  private CommandExitListener transcriptSweep() {
    // onCommandExit swallows its own failures, so the sweep can never break exit handling.
    return (commandId, exitCode, terminatedManually) -> transcripts.onCommandExit(commandId);
  }

  /**
   * The chat exit chain: stop the live tail first (so no tail write can race the sweep), then the
   * reconciling sweep, which waits for the harness's JSONL flush to catch up with what the tail
   * already imported before its delete-and-reimport.
   */
  private CommandExitListener chatTranscriptSweep() {
    return (commandId, exitCode, terminatedManually) -> {
      long importedLive = transcriptTail.stopAndDrain(commandId);
      transcripts.onChatExit(commandId, importedLive);
    };
  }

  /**
   * Renders the stream-json chat launch for {@code scope} with its MCP servers attached, {@code
   * desk}'s steering appended to the system prompt, and {@code HOME} pointed at the shared
   * credential volume. Package-visible so the credential overlay is assertable without spawning
   * anything.
   */
  LaunchSpec renderChat(
      AgentMcpScope scope, AgentSurface surface, PinnedSession pinned, AgentType agentType) {
    return renderedChat(scope, surface, pinned, agentType).spec();
  }

  /**
   * A rendered launch and the record of what it was rendered from — the pair every launch path needs,
   * because the command carries both.
   */
  record Rendered(LaunchSpec spec, AgentLaunchRecord record, List<String> redactions) {}

  /** {@link #renderChat} with the launch record beside it. */
  Rendered renderedChat(
      AgentMcpScope scope, AgentSurface surface, PinnedSession pinned, AgentType agentType) {
    return render(scope, surface, pinned, agentType, false, false, null);
  }

  /**
   * The Claude chat transport, carrying the Remote Control enable when the surface's knob is on.
   *
   * <p>The enable rides the transport rather than the command line because {@code --remote-control}
   * is dropped by the harness under {@code --print} — see {@link StreamJsonChatProtocol} — which is
   * why remote control is a chat-side mechanism here and a flag on the interactive shape, the
   * opposite way round from the intuition. It used to be unconditional and named after the branch;
   * it is now the configured knob, named by {@link AgentRemoteControl#sessionName}, and a surface
   * with the knob off gets no bridge.
   */
  private ChatProtocolFactory claudeChatProtocol(
      PinnedSession pinned, AgentSurface surface, AgentSurfaceConfiguration configuration) {
    String name =
        configuration.remoteControl()
            ? AgentRemoteControl.sessionName(surface, checkout == null ? null : checkout.branch())
            : null;
    return process -> new StreamJsonChatProtocol(process, pinned.commandId(), name);
  }

  /**
   * Builds the ACP session inputs for a Kimi chat: the same scoped MCP servers {@link
   * AgentMcpServers} produces (carried protocol-native on {@code session/new}, no {@code mcp.json}), with per-server
   * bare {@code enabledTools} (the shared prefix-strip); the resumed session id when this launch
   * resumes; and the session-id sink that records the id kimi returns from {@code session/new} on the
   * command (kimi can't pin a fresh id, so the id is learned, not pinned).
   */
  AcpSessionConfig buildAcpSessionConfig(
      AgentMcpScope scope, AgentSurface surface, PinnedSession pinned) {
    return buildAcpSessionConfig(scope, surface, pinned, false);
  }

  /**
   * {@link #buildAcpSessionConfig} with the autonomous read-only marking: {@code readOnly} appends
   * the {@code agentReadOnly} marker to each server URL so the mutating repository tools are fenced —
   * the ACP counterpart of {@link #renderAutonomousChat}'s URL marking.
   */
  AcpSessionConfig buildAcpSessionConfig(
      AgentMcpScope scope, AgentSurface surface, PinnedSession pinned, boolean readOnly) {
    AgentSurfaceConfiguration configuration = configurationFor(surface);
    List<AcpSessionConfig.AcpMcpServer> servers = new ArrayList<>();
    List<ScopedMcp> attached = attachedServers(scope, configuration, readOnly);
    for (ScopedMcp server : attached) {
      servers.add(
          new AcpSessionConfig.AcpMcpServer(
              server.key(),
              server.url(),
              KimiCodeAgent.stripServerPrefix(server.key(), server.allowedTools())));
    }
    // Kimi carries servers protocol-native on session/new — (key, url, tools) and now headers — so
    // an external catalog server rides here rather than through a config file, which is also why the
    // catalog is url-transport only: there is no place on this message for a stdio command.
    for (AgentExternalMcpServer server : externalServers(configuration, attached)) {
      servers.add(
          new AcpSessionConfig.AcpMcpServer(
              server.key(),
              server.url(),
              // An operator's list, so either form of tool name is honoured — the same entry
              // pre-approves the same tools on both harnesses.
              KimiCodeAgent.stripServerPrefix(server.key(), server.allowedTools(), true),
              server.hasCredential()
                  ? Map.of(server.headerName(), server.headerValue())
                  : Map.of()));
    }
    AgentSessionRef ref = pinned.ref();
    String resumeSessionId =
        ref != null && ref.source() == AgentSessionSource.RESUMED ? ref.sessionId() : null;
    String commandId = pinned.commandId();
    return new AcpSessionConfig(
        AgentTranscriptService.CONTAINER_CWD,
        servers,
        resumeSessionId,
        id -> commands.reportAgentSession(commandId, id, null));
  }

  /**
   * Renders the autonomous run as a stream-json chat: the {@code scope} MCP servers attached (so
   * {@code taskPrompt} is reachable), the credential overlay and skip-permissions — like {@link
   * #renderChat}, but with each server URL read-only marked.
   */
  LaunchSpec renderAutonomousChat(
      AgentMcpScope scope, AgentSurface surface, PinnedSession pinned, AgentType agentType) {
    return renderedAutonomousChat(scope, surface, pinned, agentType).spec();
  }

  /** {@link #renderAutonomousChat} with the launch record beside it. */
  Rendered renderedAutonomousChat(
      AgentMcpScope scope, AgentSurface surface, PinnedSession pinned, AgentType agentType) {
    return render(scope, surface, pinned, agentType, false, true, null);
  }

  /**
   * Appends the read-only marker query parameter to an MCP URL. The name must match {@code
   * ReadOnlyRepositoryToolFilter.READ_ONLY_PARAM} on the host.
   */
  private static String readOnlyMarked(String url) {
    return url + (url.contains("?") ? "&" : "?") + "agentReadOnly=true";
  }

  /**
   * Renders the interactive TUI launch: the same scope servers and overlays as chat, but the full
   * REPL ({@code start()}) with an optional seed prompt embedded. No {@code flatOutput} — xterm.js
   * renders the TUI; the readable conversation is the imported transcript.
   */
  LaunchSpec renderInteractive(
      AgentMcpScope scope,
      AgentSurface surface,
      String initialContext,
      PinnedSession pinned,
      AgentType agentType) {
    return renderedInteractive(scope, surface, initialContext, pinned, agentType).spec();
  }

  /** {@link #renderInteractive} with the launch record beside it. */
  Rendered renderedInteractive(
      AgentMcpScope scope,
      AgentSurface surface,
      String initialContext,
      PinnedSession pinned,
      AgentType agentType) {
    return render(scope, surface, pinned, agentType, true, false, initialContext);
  }

  /**
   * The one render, and the one place a launch record is built from what it rendered.
   *
   * <p>The three shapes were three copies of the same six lines with one difference each: a chat
   * calls {@code chat()}, an interactive launch calls {@code start()} and carries an argv seed, and
   * an autonomous run marks every server url read-only. Recording what a launch ran with is the
   * change that made keeping them apart untenable — a record built in three places is three records
   * that can disagree about the same session.
   *
   * <p>{@code unattended} is the autonomous run's fence: nobody is watching a composed run's first
   * turn under skip-permissions, so every server url is read-only marked and the host's tool filter
   * hides the mutating repository tools. The fence is the union of the run's shape and the
   * configuration's own {@code readOnly} flag — the two composed surfaces are seeded with it set, so
   * the two agree rather than one overriding the other.
   */
  private Rendered render(
      AgentMcpScope scope,
      AgentSurface surface,
      PinnedSession pinned,
      AgentType agentType,
      boolean interactive,
      boolean unattended,
      String initialContext) {
    AgentSurfaceConfiguration configuration = configurationFor(surface);
    CodingAgent agent = CodingAgentFactory.ofType(agentType);
    List<ScopedMcp> attached = attachedServers(scope, configuration, unattended);
    for (ScopedMcp server : attached) {
      // Deliberately without agent.allowedTools: no launch shape renders --allowedTools today (every
      // one of them skips permissions, which makes a pre-approval list moot), and the built-ins'
      // lists reach a session through the per-server channels instead — Kimi's enabledTools on the
      // ACP session. Adding them here would move a rendered command line this epic promised not to
      // move.
      agent.mcpServer(server.key(), McpServers.httpMcp(server.url()));
    }
    // The catalog's servers, after the platform's own: the key order is deliberate, because both
    // harnesses interpolate the serialized object into a shell argument the suites assert literally.
    // Their pre-approval lists DO render, because an external server is the first case where the
    // permission mode is likely to be anything but skip — and a prompting session with an
    // unapproved third-party tool would stop on its first call.
    for (AgentExternalMcpServer server : externalServers(configuration, attached)) {
      agent.mcpServer(
          server.key(),
          McpServers.httpMcp(
              server.url(),
              server.hasCredential() ? Map.of(server.headerName(), server.headerValue()) : Map.of()));
      agent.allowedTools(server.allowedTools());
    }
    if (interactive && initialContext != null && !initialContext.isBlank()) {
      agent.initialContext(initialContext);
    }
    CodingAgent rendered = configured(agent, agentType, pinned, configuration, interactive);
    LaunchSpec spec = interactive ? rendered.start() : rendered.chat();
    return new Rendered(
        spec,
        launchRecord(configuration, agentType, rendered, attached, interactive),
        // The header values this render just interpolated into the script. The command is stored
        // with them replaced; the process is spawned with the script as rendered.
        externalServers(configuration, attached).stream()
            .filter(AgentExternalMcpServer::hasCredential)
            .map(AgentExternalMcpServer::headerValue)
            .toList());
  }

  /**
   * What the harness could not render, plus what the <em>host</em> could not: a daemon that has not
   * adopted {@link AgentMcpServers#serverFor} addressed the attached servers as its scope mapping
   * builds them, whatever narrowing the document asked for. Recorded rather than silent, because
   * "which server was this session actually talking to" is precisely what a launch record is for —
   * and it disappears from every record the day both daemons implement the seam.
   */
  private List<String> notes(AgentSurfaceConfiguration configuration, CodingAgent rendered) {
    if (!configuration.attachesConfiguredServers() || mcpServers.honoursNarrowing()) {
      return rendered.renderNotes();
    }
    List<String> notes = new ArrayList<>(rendered.renderNotes());
    notes.add(
        "This daemon does not yet honour per-attachment MCP narrowing, so the attached servers were"
            + " addressed as its own scope mapping builds them.");
    return List.copyOf(notes);
  }

  /**
   * What this launch ran with, as a record on the command. Built from the agent that was just
   * rendered rather than from the configuration alone, so it says what the harness <em>did</em> —
   * including the knobs it could not honour, which is the difference between a record and a copy of
   * the row.
   */
  private AgentLaunchRecord launchRecord(
      AgentSurfaceConfiguration configuration,
      AgentType agentType,
      CodingAgent rendered,
      List<ScopedMcp> attached,
      boolean interactive) {
    List<AgentLaunchRecord.AttachedServer> servers = new ArrayList<>();
    for (ScopedMcp server : attached) {
      servers.add(
          new AgentLaunchRecord.AttachedServer(
              server.key(), server.url().contains("agentReadOnly=true")));
    }
    // Claude only: Kimi has no remote-control mechanism, so a Kimi session asked for no bridge and
    // records no name — the knob it was configured with is still recorded, and the note says why
    // nothing came of it.
    String remoteControlName =
        configuration.remoteControl() && agentType == AgentType.CLAUDE
            ? AgentRemoteControl.sessionName(
                configuration.surface(), checkout == null ? null : checkout.branch())
            : "";
    return new AgentLaunchRecord(
        configuration.surface(),
        agentType,
        configuration.model(),
        // What the harness took, not what the row held: a Kimi launch records no effort because it
        // rendered none, and the note beside it says the configuration asked for one.
        agentType == AgentType.CLAUDE ? configuration.effort() : "",
        configuration.permissionMode(),
        configuration.remoteControl(),
        remoteControlName,
        configuration.activityTracking(),
        servers,
        // By key. The record is stored, answered by the API and read by whoever can see a session,
        // and a header value has no business in any of those.
        configuration.externalMcpServerKeys(),
        !configuration.shipped(),
        notes(configuration, rendered));
  }

  /**
   * Everything a surface's configuration puts on the agent, in one place: the credential overlay,
   * the session lineage, the steering, the harness knobs and the permission mode.
   *
   * <p>{@code interactive} decides only where remote control attaches. The knob itself is the
   * surface's and is <b>not</b> validated against the launch shape — when it is on the mechanism is
   * used, and which mechanism that is depends on what the shape has: a flag for the REPL, the SDK
   * control channel for a chat (wired on the transport, not here). See {@link AgentRemoteControl}.
   */
  private CodingAgent configured(
      CodingAgent agent,
      AgentType agentType,
      PinnedSession pinned,
      AgentSurfaceConfiguration configuration,
      boolean interactive) {
    CodingAgent configured =
        withPermissions(
            withSteering(
                withSession(withAgentHome(agent, agentType), pinned, configuration), configuration),
            configuration);
    // Every surface can set these; before this epic only one refinement flow could set a model and
    // nothing at all could set an effort level. Empty is the harness's own choice, which is what
    // every launch has always taken.
    if (!configuration.model().isBlank()) {
      configured.model(configuration.model());
    }
    if (!configuration.effort().isBlank()) {
      configured.effort(configuration.effort());
    }
    if (interactive && configuration.remoteControl()) {
      configured.remoteControl(
          AgentRemoteControl.sessionName(
              configuration.surface(), checkout == null ? null : checkout.branch()));
    }
    return configured;
  }

  /**
   * Appends the surface configuration's steering to the agent's system prompt, if it has any. An
   * <em>empty</em> appendix is a first-class value and not an absence: {@link
   * AgentSurface#PROJECT_EPICS} steers with nothing on purpose, which is the reason a steering axis
   * could be added without touching a single running launch.
   *
   * <p>This is where the {@code switch} over the desk went. The prompt is now whatever the surface's
   * configuration holds — an operator's edit, or the shipped constant when the container was born
   * without a document.
   */
  private CodingAgent withSteering(CodingAgent agent, AgentSurfaceConfiguration configuration) {
    String prompt = configuration.systemPromptAppendix();
    return prompt == null ? agent : agent.appendSystemPrompt(prompt);
  }

  /**
   * The system-prompt appendix a surface <em>ships</em> with, or {@code null} for one that steers
   * with nothing — the fallback for a container born without a configuration document. The epics
   * desk is steered by the tools it was given and by the container it runs in,
   * which is how this whole surface worked before there was a second desk; only the tickets desk has
   * to say what it is, because it shares every one of those tools with the desk beside it.
   */
  static String systemPromptFor(AgentSurface surface) {
    String shipped = AgentSurfaceConfigurations.shippedSystemPrompt(surface);
    return shipped.isEmpty() ? null : shipped;
  }

  /**
   * Applies the configured permission mode.
   *
   * <p>Every launch shape in both daemons rendered {@code --dangerously-skip-permissions}
   * unconditionally, which made it an invariant nobody chose — and the amplifier that would make an
   * attached third-party MCP server dangerous inside a container holding the platform's own
   * credentials. It is now a choice, seeded as what it was, so nothing moves until somebody moves
   * it.
   */
  private CodingAgent withPermissions(
      CodingAgent agent, AgentSurfaceConfiguration configuration) {
    return configuration.permissionMode() == AgentPermissionMode.SKIP_PERMISSIONS
        ? agent.skipPermissions()
        : agent;
  }

  /**
   * What this surface runs as: the mounted document's row, or the shipped constants for a container
   * created before the document existed. Resolved once per launch and threaded through every
   * renderer, so one launch cannot render half of one configuration and half of another.
   */
  private AgentSurfaceConfiguration configurationFor(AgentSurface surface) {
    return defaults
        .surfaceConfigurations()
        .resolve(surface, defaults.defaultAgentType(), defaults.activityTrackingEnabled());
  }

  /**
   * The MCP servers this launch attaches — <b>the line this epic draws through the MCP wiring</b>.
   *
   * <p>The host's {@link AgentMcpServers} still says how a server key becomes a url at a given
   * {@link AgentMcpScope}: that needs the container's own project, repository and workspace ids,
   * which this library deliberately does not have, and inventing a url here is the one failure this
   * module refuses outright (see {@link McpEndpoints#mcpUrl}). The <b>configuration</b> says which of
   * those servers attach, in what order, with which pre-approval, and whether they are fenced.
   * Policy from the document, addressing from the host.
   *
   * <p>A configuration that attaches nothing of its own — the shipped fallback — takes the host's
   * whole mapping, which is exactly what every launch did before this existed. A configuration that
   * names a server the host does not serve at this scope <b>refuses the launch</b> rather than
   * dropping it: a session missing a server it was configured with looks entirely normal and simply
   * cannot do half its job, which is the failure shape this estate calls green-while-dead.
   *
   * <p>An attachment with an empty pre-approval list takes the host's shipped one. Pre-approval
   * lists are not operator-editable in v1 — they are policy about what this product's agents may do
   * without asking, keyed by server — so an empty list means "the shipped one", not "approve
   * nothing".
   *
   * <p>{@code narrowProject}/{@code narrowRepository}/{@code narrowWorkspace} are not honoured here
   * and cannot be until the host seam maps a key <em>plus a narrowing</em> to a url; they are the
   * editor's description of what the host already builds, and today's seeded values are exactly
   * that. See {@link AgentMcpAttachment}.
   */
  private List<ScopedMcp> attachedServers(
      AgentMcpScope scope, AgentSurfaceConfiguration configuration, boolean unattended) {
    if (!configuration.attachesConfiguredServers()) {
      List<ScopedMcp> hosted = mcpServers.serversFor(scope);
      return unattended ? hosted.stream().map(AgentLaunchService::markReadOnly).toList() : hosted;
    }
    List<ScopedMcp> attached = new ArrayList<>();
    for (AgentMcpAttachment attachment : configuration.mcpServers()) {
      // Narrowed as the document asks, by the host — which is the only side that can, because a
      // narrowed url needs the container's own ids. A host that has not adopted the seam answers its
      // scope mapping unchanged, and the launch record says the addressing was the host's.
      ScopedMcp server =
          mcpServers
              .serverFor(attachment.server(), scope, AgentMcpNarrowing.of(attachment))
              .orElseThrow(
                  () ->
                      new InvalidCommandRequestException(
                          "The "
                              + configuration.surface()
                              + " configuration attaches the '"
                              + attachment.server()
                              + "' MCP server, which this daemon does not serve at scope "
                              + scope));
      ScopedMcp resolved =
          new ScopedMcp(
              server.key(),
              server.url(),
              attachment.allowedTools().isEmpty()
                  ? server.allowedTools()
                  : attachment.allowedTools());
      attached.add(unattended || attachment.readOnly() ? markReadOnly(resolved) : resolved);
    }
    return List.copyOf(attached);
  }

  /**
   * The catalog servers this launch attaches, <b>with the reserved keys checked again at render</b>.
   *
   * <p>The store validates this on write, and this is not a second opinion about it: a document can
   * reach a container from an older service, from a hand-edited mount, or from a store that learned
   * the rule after the row was written. The failure it prevents is the one that is invisible — both
   * harnesses render <em>one</em> key-to-config object, so an external entry keyed {@code
   * repository} does not attach twice, it displaces the platform's own server, and the session looks
   * entirely normal while talking to somebody else's. That is worth refusing a launch over.
   *
   * <p>It also refuses a key that collides with a built-in this launch is actually attaching, which
   * is the same failure arriving from the other side.
   */
  private List<AgentExternalMcpServer> externalServers(
      AgentSurfaceConfiguration configuration, List<ScopedMcp> attached) {
    List<AgentExternalMcpServer> external = configuration.externalMcpServers();
    if (external.isEmpty()) {
      return external;
    }
    for (AgentExternalMcpServer server : external) {
      String key = server.key() == null ? "" : server.key().toLowerCase(java.util.Locale.ROOT);
      boolean displacesBuiltIn =
          AgentConfigurationDocument.RESERVED_SERVER_KEYS.contains(key)
              || attached.stream().anyMatch(builtIn -> builtIn.key().equalsIgnoreCase(key));
      if (displacesBuiltIn) {
        throw new InvalidCommandRequestException(
            "The "
                + configuration.surface()
                + " configuration attaches an external MCP server keyed '"
                + server.key()
                + "', which is one of this platform's own ("
                + String.join(", ", AgentConfigurationDocument.RESERVED_SERVER_KEYS)
                + "). It would displace it silently and the session would look normal while talking"
                + " to somebody else's server.");
      }
    }
    return external;
  }

  /** The same server with its url read-only marked. */
  private static ScopedMcp markReadOnly(ScopedMcp server) {
    return new ScopedMcp(server.key(), readOnlyMarked(server.url()), server.allowedTools());
  }

  /**
   * Points the agent's {@code HOME} at the shared credential volume so the in-container {@code
   * claude} reads the operator's one-time OAuth login. CWD stays {@code /workspace}, so project
   * detection (the repo's own {@code .claude/}, {@code CLAUDE.md}) is unaffected. Kimi Code uses the
   * container-level {@code KIMI_CODE_HOME} and its own per-launch symlink farm, so no {@code HOME}
   * overlay is needed.
   */
  private CodingAgent withAgentHome(CodingAgent agent, AgentType agentType) {
    if (agentType == AgentType.CLAUDE && claudeMount != null && !claudeMount.isBlank()) {
      agent.environment("HOME", claudeMount);
    }
    return agent;
  }

  private String nameFor(AgentMcpScope scope, AgentSurface surface, AgentType agentType) {
    return harnessName(
        scope,
        surface,
        switch (agentType) {
          case CLAUDE -> "Claude Code";
          case KIMI -> "Kimi Code";
        });
  }

  private String interactiveNameFor(AgentMcpScope scope, AgentSurface surface, AgentType agentType) {
    return harnessName(
        scope,
        surface,
        switch (agentType) {
          case CLAUDE -> "Claude Code terminal";
          case KIMI -> "Kimi Code terminal";
        });
  }

  /**
   * The command's name — and, for {@link AgentSurface#PROJECT_TICKETS}, still a
   * <strong>cross-repo contract</strong> for one more release. The frontend segregates a project's
   * sessions into the two desks by matching {@code " (tickets desk)"} in this name, because until now
   * a command carried no surface field of its own.
   *
   * <p><b>The name deliberately does not move with the surface.</b> The command now reports {@code
   * agentSurface}, which is what lets the frontend stop parsing a display string — but the two sides
   * ship separately, and renaming here in the same release would move every ticket session into the
   * epics list of a frontend that has not shipped yet. Task 46e32cb3 deletes the string match, and
   * the name becomes free to change once it has.
   *
   * <p>Every other surface keeps the scope-derived names it has always had, so nothing that was
   * running gets renamed. The surface wins over the scope where they would both speak: a tickets
   * session says which desk it is, not how its one MCP URL was narrowed, because the narrowing is not
   * what a reader of the session list is telling sessions apart by.
   */
  private static String harnessName(
      AgentMcpScope scope, AgentSurface surface, String harnessLabel) {
    if (AgentSurface.PROJECT_TICKETS.equals(surface)) {
      return harnessLabel + " (tickets desk)";
    }
    return switch (scope) {
      case ACTIONS -> harnessLabel + " (actions + repository MCP)";
      case REPOSITORY -> harnessLabel + " (repository MCP)";
      case PROJECT -> harnessLabel + " (project MCP)";
    };
  }

  private String requireUuid(String value, String label) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) {
      throw new InvalidCommandRequestException("Invalid " + label + ": " + value);
    }
    return value;
  }
}
