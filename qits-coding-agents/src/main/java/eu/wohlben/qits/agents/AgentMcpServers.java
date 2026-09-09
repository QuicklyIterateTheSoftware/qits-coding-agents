package eu.wohlben.qits.agents;

import java.util.List;

/**
 * Which MCP servers a scope attaches, how each is narrowed, and what is pre-approved on it —
 * supplied by the host daemon, the way {@link AgentDefaults} already supplies the instance
 * preferences.
 *
 * <p><b>This is the second seam the two copies of this library had to become one library.</b> Both
 * daemons owned a {@code serversFor(scope)} switch, and the two switches did not merely differ in
 * detail — they described two different products:
 *
 * <ul>
 *   <li>the projects daemon attaches <b>exactly one</b> server, {@code repository} served by
 *       qits-projects, because that container's job is a project's <em>plan</em>. A server it has no
 *       business calling is a tool it can waste a turn on;
 *   <li>the workspace daemon attaches <b>three</b> across three services — {@code repository},
 *       {@code observability} and, on {@link AgentMcpScope#ACTIONS}, {@code actions} — each with its
 *       own repository/workspace narrowing, because telemetry and the action library only answer for
 *       a workspace.
 * </ul>
 *
 * <p>Neither list is a default the other could fall back on, and a library that shipped one of them
 * would be a library that knows which product it is inside. So the mapping leaves: {@link
 * AgentLaunchService} renders whatever it is handed — attaching each server, marking the URLs
 * read-only on an autonomous run, and stripping the {@code mcp__<key>__} prefix for Kimi — and has
 * stopped knowing.
 *
 * <p>The pre-approval lists travel with the mapping for the same reason. They are not a property of
 * the harness; they are a policy about what this product's agents may do without asking, and the two
 * daemons' lists differ in both membership and order (order is visible: it is rendered into one
 * {@code --allowedTools} argument that unit tests assert byte for byte).
 *
 * <p>Implementations must validate any id they interpolate into a URL — the URL is embedded in a
 * single-quoted shell argument and the renderer does no escaping of its own. {@link
 * AgentMcpIds#requireId} is that check, kept here so both hosts use one grammar.
 */
public interface AgentMcpServers {

  /**
   * The servers to attach for {@code scope}, in the order they should be rendered.
   *
   * @throws eu.wohlben.qits.commands.InvalidCommandRequestException if an id the URL needs is
   *     missing or outside the platform's slug grammar — a launch, not a startup, failure, because
   *     the ids are read fresh on every launch.
   */
  List<ScopedMcp> serversFor(AgentMcpScope scope);

  /**
   * One server by key, narrowed as the surface's configuration asks — <b>the seam the configuration
   * epic needed and the one both daemons must now implement.</b>
   *
   * <p>Until this existed, an attachment's {@code narrowProject}/{@code narrowRepository}/{@code
   * narrowWorkspace} were read, validated and then <em>not rendered</em>: the library cannot build a
   * narrowed url without ids it must not know, and inventing one is the failure this module refuses
   * outright (see {@link McpEndpoints#mcpUrl}). They described what the host already did and nothing
   * more, which made three editable fields on the editor's form a lie the day somebody changed one.
   *
   * <p><b>What a host must implement.</b> Answer the server registered under {@code key} for {@code
   * scope}, with exactly the narrowing {@code narrowing} asks for and no other:
   *
   * <ul>
   *   <li>append the query parameters in the canonical order {@code projectId}, {@code
   *       repositoryId}, {@code workspaceId} — the rendered command line is asserted as a literal on
   *       both harnesses, so the order is part of the contract, not a detail;
   *   <li>validate every id you interpolate with {@link AgentMcpIds#requireId}, as {@code
   *       serversFor} already must: the url ends up inside a single-quoted shell argument and the
   *       renderer does no escaping of its own;
   *   <li>refuse — {@code InvalidCommandRequestException} — a narrowing you cannot satisfy, rather
   *       than dropping the parameter. A repository server narrowed to a workspace this container is
   *       not in must not quietly answer for the whole project;
   *   <li>answer {@link java.util.Optional#empty()} for a key you do not serve at that scope; the
   *       launch turns that into its own refusal naming the surface;
   *   <li>keep the pre-approval list you attach to it in {@code serversFor} — the library takes the
   *       attachment's list when it has one and yours when it does not;
   *   <li>and override {@link #honoursNarrowing()} to true once you do all of the above.
   * </ul>
   *
   * <p><b>The default implementation ignores the narrowing</b> and answers whatever {@code
   * serversFor} builds for the scope. That is deliberate and dated: this is a released artifact, a
   * daemon picks up a new library at its next release, and a host that has not adopted the seam yet
   * must keep rendering exactly what it rendered before. It is visible rather than silent —
   * {@link #honoursNarrowing()} is false, and a launch that attaches servers on such a host records
   * a note saying its narrowing was the host's rather than the document's.
   */
  default java.util.Optional<ScopedMcp> serverFor(
      String key, AgentMcpScope scope, AgentMcpNarrowing narrowing) {
    return serversFor(scope).stream().filter(server -> server.key().equals(key)).findFirst();
  }

  /**
   * Whether {@link #serverFor} honours the narrowing it is given, rather than answering the scope's
   * own. False until a host implements the seam above; the launch record says so, so a session's
   * addressing can be read afterwards rather than assumed.
   */
  default boolean honoursNarrowing() {
    return false;
  }
}
