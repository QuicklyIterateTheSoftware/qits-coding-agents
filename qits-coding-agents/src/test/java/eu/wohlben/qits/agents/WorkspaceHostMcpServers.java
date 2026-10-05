package eu.wohlben.qits.agents;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The workspace daemon's scope→server mapping, as it stood when this library was seeded from the two
 * copies — the other fixture half of the {@link AgentMcpServers} seam. See {@link
 * ProjectHostMcpServers} for why both live in test scope: the mapping is the host's policy, and what
 * it earns here is that both hosts' rendered commands stay asserted byte for byte inside this
 * repository's own suite.
 *
 * <p>{@code repository} and {@code observability} are two servers on two services (qits-projects and
 * qits-observability), not one server with two halves. They are listed together wherever the session
 * is narrowed to a workspace, because that is the pairing the old single {@code repository} server
 * presented.
 */
final class WorkspaceHostMcpServers implements AgentMcpServers {

  /**
   * The read-only tools of the {@code actions} MCP server, pre-approved so the session can
   * list/inspect actions without a permission prompt. The mutating tools are left out so the agent
   * still prompts before changing anything. Names are the agent's MCP tool ids: {@code
   * mcp__<server>__<tool>}.
   */
  static final List<String> READ_ONLY_ACTION_TOOLS =
      List.of(
          "mcp__actions__listGlobalActions",
          "mcp__actions__getGlobalAction",
          "mcp__actions__listRepositoryActions",
          "mcp__actions__getRepositoryAction");

  /**
   * The read-only tools of the {@code repository} MCP server, pre-approved the same way.
   *
   * <p>{@code list_tickets} and {@code get_ticket} sit here at the same standing as the navigation
   * reads: surveying the tickets on a repository is how a session finds out what it was sent to do,
   * and {@code get_ticket} returns the whole comment thread with the ticket, so one call is the
   * reading half of the ticket domain. Neither changes anything.
   *
   * <p>{@code list_epics} and {@code get_epic} sit here for the same reason and at the same
   * standing. Surveying the project's plan is how a dispatched session finds out what it was sent to
   * do, and {@code get_epic} returns the whole feature/task tree in one call, so it is the reading
   * half of the epic domain. Neither changes anything. Leaving them out would not merely cost a
   * prompt: qits-projects' "Start implementation" dispatch composes a first turn that tells the
   * agent to read its epic with {@code get_epic}, and on the kimi path {@code enabledTools} is the
   * session's whole tool surface rather than a pre-approval, so that instruction would be
   * unreachable rather than prompted.
   *
   * <p>The membership and the <em>order</em> both differ from {@link
   * ProjectHostMcpServers#READ_ONLY_REPOSITORY_TOOLS} — {@code listWorkspaces} is here and the epic
   * and ticket pairs are the other way round — which is precisely why one merged list could not have
   * been the union: the list is rendered into one {@code --allowedTools} argument the suites assert
   * as a literal, so merging would have moved a byte on one side or the other.
   */
  static final List<String> READ_ONLY_REPOSITORY_TOOLS =
      List.of(
          "mcp__repository__listRepositories",
          "mcp__repository__listBranches",
          "mcp__repository__listWorkspaces",
          "mcp__repository__listCommits",
          "mcp__repository__listCommitChanges",
          "mcp__repository__getCommitFileDiff",
          "mcp__repository__listActions",
          "mcp__repository__taskPrompt",
          "mcp__repository__list_tickets",
          "mcp__repository__get_ticket",
          "mcp__repository__list_epics",
          "mcp__repository__get_epic");

  /**
   * The two ticket-thread writes of the {@code repository} MCP server — the one deliberate exception
   * to "only reads are pre-approved", and kept in its own bucket rather than smuggled into the
   * read-only list above so the exception has to be read to be taken.
   *
   * <p>A workspace agent that investigated or fixed something should be able to say so on the
   * ticket's thread without a prompt in the way. Commenting is additive — it appends to a thread
   * rather than changing a ticket's state — and a comment stays editable, so pre-approving the pair
   * costs at worst a wrongly-worded note, never a changed plan. Autonomous runs lose both anyway:
   * the {@code agentReadOnly=true} marker puts qits-projects' own tool filter in front of every
   * mutating tool, and this list cannot buy past it. For kimi the bucket is not a convenience at all
   * — {@code enabledTools} is a hard set there, so a tool left out of it does not exist for the
   * session, and without these two the thread is unreachable rather than prompted.
   *
   * <p>What is deliberately absent is the filing half of the domain: {@code create_ticket} and
   * {@code update_ticket} stay unlisted like every other write on every server here. Filing a ticket
   * and editing somebody else's are plan-changing acts, and they stay a prompted act for Claude and
   * out of reach for kimi; the projects-daemon front desk is the filing surface.
   */
  static final List<String> TICKET_THREAD_TOOLS =
      List.of("mcp__repository__add_ticket_comment", "mcp__repository__update_ticket_comment");

  /**
   * {@code transition_ticket} — the second named exception, and it exists because of one caller.
   *
   * <p>qits-projects' "Assign agent" dispatches an agent onto a ticket and its first turn (composed
   * by {@code TicketDispatchController.instruction}) ends by telling the agent to resolve that
   * ticket once its changes are released. Nothing else on this platform asks a workspace agent to
   * move a ticket's status, and without the tool here that sentence is an instruction the session
   * cannot carry out: on the kimi path {@code enabledTools} is the session's whole tool surface, so
   * an unlisted tool does not exist rather than being prompted for.
   *
   * <p>Its own bucket, not appended to {@link #TICKET_THREAD_TOOLS}, because the two are exceptions
   * for different reasons and the reasons are what a reader has to weigh. Commenting is additive and
   * costs a wrongly-worded note; resolving is a statement about somebody's bug that people act on.
   * What makes it acceptable is that it is <em>reversible through the same door</em> — reopening is
   * the same tool — so the worst case is a status a person flips back, and that the dispatch is a
   * person pressing a button on a specific ticket rather than an agent choosing a ticket to close.
   *
   * <p>The fence that matters is unchanged and is not this list. An <b>autonomous</b> run carries the
   * {@code agentReadOnly=true} marker, and qits-projects' {@code ReadOnlyRepositoryToolFilter} hides
   * all five ticket writes behind it.
   */
  static final List<String> TICKET_RESOLUTION_TOOLS =
      List.of("mcp__repository__transition_ticket");

  /**
   * {@code mark_task_implemented} and {@code mark_task_implementing} — the third named exception, and
   * like the second it exists because of one caller.
   *
   * <p>qits-projects' "Start implementation" stands a workspace on {@code epic/<slug>} and dispatches
   * an agent into it, and the first turn it composes ({@code EpicDispatchController.instruction})
   * tells that agent to mark each task implementing as it starts and implemented as the work lands.
   * Nothing else asks a workspace agent to touch an epic's tasks, and on the kimi path {@code
   * enabledTools} is the session's whole tool surface, so an unlisted tool is a dead letter rather
   * than a prompt.
   *
   * <p>What makes pre-approving a <em>write</em> acceptable here is narrow and worth stating. It
   * records a fact about work the agent itself just did, so the agent is the authority on it rather
   * than a party guessing at somebody else's state. qits-projects accepts it only while the owning
   * epic is in IMPLEMENTATION ({@code EpicLifecycle.requireImplementation}), so it is reachable
   * exactly during the dispatch it was added for. And it moves a <em>feature's or a task's own
   * status</em>, never an epic's plan or scope: by then the epic's scope is frozen, and neither tool
   * can add, remove or reword a feature or a task — only move the one it is given along the same
   * lifecycle an epic or a ticket walks.
   *
   * <p>It is an interim. qits-projects' own note on the tool says merge-derived markers are the
   * intended answer, and when they arrive this bucket goes with the prompt-driven step.
   *
   * <p>{@code transition_task} sits beside both for a related but distinct reason: a feature or a
   * task now carries its own status along the same eight-word lifecycle an epic or a ticket walks,
   * rather than taking its epic's. An agent working a task may need to move it on its own — to
   * VERIFIED once it has checked its own fix, say — without touching its epic or its siblings, and
   * {@code transition_ticket} is already pre-approved for the equivalent move on a ticket. The same
   * fence applies: qits-projects refuses the move with a 409 while the owning epic is still
   * REPORTED, because the plan is still a draft there, and the move never reaches an epic's plan or
   * scope — only the one feature or task it names.
   */
  static final List<String> TASK_IMPLEMENTATION_TOOLS =
      List.of(
          "mcp__repository__mark_task_implemented",
          "mcp__repository__mark_task_implementing",
          "mcp__repository__transition_task");

  /**
   * The repository server's full pre-approval: its reads, plus the two ticket exceptions and the
   * epic task-status bucket.
   */
  static final List<String> REPOSITORY_TOOLS =
      Stream.of(
              READ_ONLY_REPOSITORY_TOOLS,
              TICKET_THREAD_TOOLS,
              TICKET_RESOLUTION_TOOLS,
              TASK_IMPLEMENTATION_TOOLS)
          .flatMap(List::stream)
          .toList();

  /**
   * The read-only tools of the {@code observability} MCP server — the five telemetry reads.
   *
   * <p>They live on their own server because qits-observability and qits-projects both declared one
   * called {@code repository}, so one MCP url could only ever reach one of them; the telemetry half
   * is {@code observability}, served at {@code /observability/mcp}.
   */
  static final List<String> READ_ONLY_OBSERVABILITY_TOOLS =
      List.of(
          "mcp__observability__telemetryErrors",
          "mcp__observability__telemetryTrace",
          "mcp__observability__telemetrySlowSpans",
          "mcp__observability__telemetrySearchLogs",
          "mcp__observability__telemetryMetrics");

  /** Where the central {@code qits} platform server is, on a host that attaches it (qits-625). */
  static final String PLATFORM_URL = "http://dev-qits-platform-access-mcp-service:8080/mcp";

  private final McpEndpoints endpoints;
  private final String repoId;
  private final String workspaceId;
  private final Map<String, String> platformHeaders;

  WorkspaceHostMcpServers(McpEndpoints endpoints, String repoId, String workspaceId) {
    this(endpoints, repoId, workspaceId, Map.of());
  }

  /**
   * A RUNNER workspace's host (qits-625): {@code platformHeaders} ride on the repository,
   * observability and {@code qits} servers — never on {@code actions}, which is not a platform
   * service — and the {@code qits} server is attached only when there are headers, so the
   * no-header host above renders exactly what it rendered before.
   */
  WorkspaceHostMcpServers(
      McpEndpoints endpoints,
      String repoId,
      String workspaceId,
      Map<String, String> platformHeaders) {
    this.endpoints = endpoints;
    this.repoId = repoId;
    this.workspaceId = workspaceId;
    this.platformHeaders = Map.copyOf(platformHeaders);
  }

  @Override
  public Map<String, String> platformHeaders() {
    return platformHeaders;
  }

  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    String repo = AgentMcpIds.requireId(repoId, "repository id");
    String projectId = AgentMcpIds.requireId(endpoints.projectId(), "project id");
    // Project-scoped, then narrowed to this one repository so a per-subtree session only sees its
    // own repo, not its siblings in the project.
    ScopedMcp narrowedRepositoryServer =
        new ScopedMcp(
            "repository",
            endpoints.mcpUrl("repository")
                + "?projectId="
                + projectId
                + "&repositoryId="
                + repo
                + "&workspaceId="
                + workspaceId,
            REPOSITORY_TOOLS,
            platformHeaders);
    // Telemetry is bucketed per workspace, and qits-observability's tool filter hides the tools
    // outright unless both narrowings are present — so this server is only worth listing where they
    // are, and carries exactly the two scopes that service reads (no projectId: it has no notion of
    // one).
    ScopedMcp observabilityServer =
        new ScopedMcp(
            "observability",
            endpoints.mcpUrl("observability")
                + "?repositoryId="
                + repo
                + "&workspaceId="
                + workspaceId,
            READ_ONLY_OBSERVABILITY_TOOLS,
            platformHeaders);
    List<ScopedMcp> platform =
        platformHeaders.isEmpty()
            ? List.of()
            : List.of(new ScopedMcp("qits", PLATFORM_URL, List.of(), platformHeaders));
    List<ScopedMcp> hosted = switch (scope) {
      case ACTIONS ->
          // The "configure this repository" session: the actions server for the action library,
          // plus the (narrowed) repository server for the repository reads (branches, workspaces,
          // commits) — the session needs both to configure the repository fully.
          List.of(
              new ScopedMcp(
                  "actions",
                  endpoints.mcpUrl("actions") + "?repositoryId=" + repo,
                  READ_ONLY_ACTION_TOOLS),
              narrowedRepositoryServer,
              observabilityServer);
      case REPOSITORY -> List.of(narrowedRepositoryServer, observabilityServer);
      case PROJECT ->
          // Project scope only, no repository narrowing — the session sees every repository in the
          // project. It still runs in this repository's workspace (the terminal needs a checkout).
          // No observability server: without the repository/workspace narrowing its tools are
          // filtered away at the far end, so offering it would advertise a dead end.
          List.of(
              new ScopedMcp(
                  "repository",
                  endpoints.mcpUrl("repository") + "?projectId=" + projectId,
                  REPOSITORY_TOOLS,
                  platformHeaders));
    };
    return Stream.concat(hosted.stream(), platform.stream()).toList();
  }

  /** The browser the workspace image ships, as the daemon attaches it: every tool pre-approved. */
  static final LocalMcp BROWSER = new LocalMcp("browser", "qits-browser-mcp", List.of("mcp__browser__*"));

  @Override
  public List<LocalMcp> localServers() {
    return List.of(BROWSER);
  }
}
