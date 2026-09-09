package eu.wohlben.qits.agents;

import java.util.List;

/**
 * The projects daemon's scope→server mapping, as it stood when this library was seeded from the two
 * copies — the fixture half of the {@link AgentMcpServers} seam.
 *
 * <p>It lives in test scope on purpose. The mapping is <em>policy about which product the session is
 * inside</em>, so it belongs to qits-projects-daemon and moves there when that daemon takes this
 * library as a dependency. What it earns here is the thing the move must not break: the launch
 * commands this daemon renders are still asserted byte for byte, in the library's own suite, against
 * the same lists and the same order they had before the move. Keeping only one host's mapping in the
 * library would have made the other host's rendering unprovable here — and after the split a daemon
 * can no longer prove harness behaviour in the same commit as a harness change.
 *
 * <p><strong>Exactly one server</strong>, {@code repository}, served by qits-projects — the one
 * carrying the epic tools a project agent container exists for. The workspace daemon attaches three
 * ({@code actions}, {@code repository}, {@code observability}) across three services; none of the
 * other two is wired here, and that is a decision rather than an omission: this agent's job is the
 * project's <em>plan</em>, not a workspace's actions or another service's telemetry, and a server it
 * has no business calling is a tool it can waste a turn on. {@link AgentMcpScope#ACTIONS} is
 * therefore unreachable from this host, and asking for it is a refused launch rather than a silent
 * fall-through.
 *
 * <p>Nothing else can put a server back either: Claude is launched with {@code
 * --strict-mcp-config}, so the {@code --mcp-config} rendered from this list is the whole set and the
 * shared {@code /claude-home} volume's own MCP entries are ignored. Kimi gets a launch-local {@code
 * mcp.json} in a throwaway home for the same effect.
 */
final class ProjectHostMcpServers implements AgentMcpServers {

  /**
   * The read-only tools of the {@code repository} MCP server, pre-approved so the session can list
   * and inspect without a permission prompt. The mutating tools are left out so the agent still
   * prompts before changing anything. Names are the agent's MCP tool ids: {@code
   * mcp__<server>__<tool>}.
   *
   * <p>The server carries three surfaces, so this list does too: the repository tools, the epic ones
   * a refinement session drafts through, and the ticket ones the tickets desk triages through.
   * {@code list_epics} and {@code get_epic} are the survey the agent has to make before it can tell
   * "extend this draft" from "propose a new epic", and pre-approving them is the same call as
   * pre-approving {@code listRepositories}. {@code list_tickets} and {@code get_ticket} are the
   * exact parallel one surface down — the survey that tells "this is already filed" from "this is
   * new", made before every intake — and {@code get_ticket} returns the comment thread too, so the
   * whole conversation reads without a prompt.
   *
   * <p>Every <em>write</em> stays off the list and still prompts, on both surfaces and for the same
   * reason: {@code propose_epic} and the feature/task mutators change the project's plan, and {@code
   * create_ticket} / {@code update_ticket} / {@code transition_ticket} / the comment writers change
   * its record of work. Surveying is free; filing is not. This is where the two hosts differ most:
   * the workspace host pre-approves four named writes, because a dispatched agent is asked to use
   * them. A project agent is asked to plan, and planning is a prompt.
   *
   * <p>The snake_case half is not a slip: the epic and ticket tools declare those names on the
   * qits-projects side, and the id here must match the declared name character for character or the
   * pre-approval silently matches nothing.
   *
   * <p>The <em>order</em> is load-bearing, not cosmetic: it is rendered into one {@code
   * --allowedTools} argument that the suite asserts as a literal.
   */
  static final List<String> READ_ONLY_REPOSITORY_TOOLS =
      List.of(
          "mcp__repository__listRepositories",
          "mcp__repository__listBranches",
          "mcp__repository__listCommits",
          "mcp__repository__listCommitChanges",
          "mcp__repository__getCommitFileDiff",
          "mcp__repository__listActions",
          "mcp__repository__taskPrompt",
          "mcp__repository__list_epics",
          "mcp__repository__get_epic",
          "mcp__repository__list_tickets",
          "mcp__repository__get_ticket");

  private final McpEndpoints endpoints;
  private final String repoName;

  ProjectHostMcpServers(McpEndpoints endpoints, String repoName) {
    this.endpoints = endpoints;
    this.repoName = repoName;
  }

  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    String projectId = AgentMcpIds.requireId(endpoints.projectId(), "project id");
    String base = endpoints.mcpUrl("repository") + "?projectId=" + projectId;
    return switch (scope) {
      // Project scope, no narrowing: the session sees every repository in the project, which is
      // what a wrapper checkout is for.
      case PROJECT -> List.of(new ScopedMcp("repository", base, READ_ONLY_REPOSITORY_TOOLS));
      // Narrowed to the one repository this container checked out, so a per-repository session
      // does not see its siblings.
      case REPOSITORY ->
          List.of(
              new ScopedMcp(
                  "repository",
                  base + "&repositoryId=" + AgentMcpIds.requireId(repoName, "repository id"),
                  READ_ONLY_REPOSITORY_TOOLS));
      // Not reachable from this host: there is no actions server on the project's segment, so a
      // launch that asks for it must be refused rather than quietly served the repository one.
      case ACTIONS ->
          throw new eu.wohlben.qits.commands.InvalidCommandRequestException(
              "Scope ACTIONS is not served by this daemon");
    };
  }
}
