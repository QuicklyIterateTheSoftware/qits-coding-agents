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
}
