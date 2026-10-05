package eu.wohlben.qits.agents;

import java.util.List;
import java.util.Map;

/**
 * One MCP server as a launch attaches it: the key it is registered under, its already-narrowed URL,
 * and the tools pre-approved on it.
 *
 * <p>A top-level record rather than a nested one, because it is now the currency of a seam: {@link
 * AgentMcpServers} produces these and {@link AgentLaunchService} renders them, and the two sides
 * live in different repositories once a daemon takes this library as a dependency.
 *
 * <p>{@code allowedTools} is a pre-approval on Claude and something stronger on Kimi — {@code
 * enabledTools} there is the session's whole tool surface, so a name left out does not cost a
 * prompt, it removes the tool. Whoever builds this list is deciding both.
 *
 * <p>{@code headers} are fixed request headers the host wants on this server — a RUNNER workspace's
 * {@code Authorization: Bearer <QITS_TOKEN>}, which every platform server needs once the agent
 * reaches the platform through the edge (qits-625). Empty by default, and an empty map renders
 * exactly what a server without headers rendered before. Held as a {@link Map#copyOf} copy, whose
 * iteration order is salted per JVM, so a renderer sorts the keys rather than iterating it.
 */
public record ScopedMcp(
    String key, String url, List<String> allowedTools, Map<String, String> headers) {

  /** A server without headers — the shape every host built before qits-625. */
  public ScopedMcp(String key, String url, List<String> allowedTools) {
    this(key, url, allowedTools, Map.of());
  }

  public ScopedMcp {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }
}
