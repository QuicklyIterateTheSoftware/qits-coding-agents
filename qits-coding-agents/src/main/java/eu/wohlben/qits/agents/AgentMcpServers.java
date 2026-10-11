package eu.wohlben.qits.agents;

import java.util.List;
import java.util.Map;

/**
 * The MCP servers a host daemon offers its agents, the way {@link AgentDefaults} supplies the
 * instance preferences. {@link AgentLaunchService} renders whatever it is handed.
 *
 * <p>Since qits-1174 a host offers one platform server, {@code qits} — the central platform-access
 * MCP server, scoped by the caller's own bearer — plus any servers that run inside the container
 * ({@link #localServers}). The per-service servers ({@code repository}, {@code observability},
 * {@code actions}) are retired.
 */
public interface AgentMcpServers {

  /**
   * The platform servers a launch attaches when its surface's configuration names none of its own,
   * in render order: {@code qits} when the host has an address for it, else nothing.
   *
   * @param scope ignored by current hosts; see {@link AgentMcpScope}
   */
  List<ScopedMcp> serversFor(AgentMcpScope scope);

  /**
   * One platform server by key, for a surface's configuration that names it. Answer {@link
   * java.util.Optional#empty()} for a key this host does not serve, or for {@code qits} when it has
   * no address for it. The default answers from {@link #serversFor}.
   */
  default java.util.Optional<ScopedMcp> serverFor(String key, AgentMcpScope scope) {
    return serversFor(scope).stream().filter(server -> server.key().equals(key)).findFirst();
  }

  /**
   * The servers that run inside the agent's own container (the workspace image's browser, say),
   * attached to every launch after the platform servers. Empty by default.
   *
   * <p>Claude only: Kimi's ACP session carries url servers alone, so a Kimi launch attaches none of
   * these.
   */
  default List<LocalMcp> localServers() {
    return List.of();
  }

  /**
   * Headers the host puts on every platform server it attaches — on a RUNNER workspace, {@code
   * Authorization: Bearer <QITS_TOKEN>}, because the agent reaches the platform through the edge
   * (qits-625). Empty by default.
   *
   * <p>The host attaches these itself, to the {@link ScopedMcp#headers()} of the servers it builds;
   * the library does not read this method. They are never sent to an external catalog server.
   */
  default Map<String, String> platformHeaders() {
    return Map.of();
  }
}
