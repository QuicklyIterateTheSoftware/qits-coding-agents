package eu.wohlben.qits.agents;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The workspace daemon's {@link AgentMcpServers}, as a test fixture: the central {@code qits} server
 * when it has an address for it, plus the browser the workspace image ships (qits-1174).
 */
final class WorkspaceHostMcpServers implements AgentMcpServers {

  /** Where the central {@code qits} platform server is. */
  static final String PLATFORM_URL = "http://dev-qits-platform-access-mcp-service:8080/mcp";

  /** The browser the workspace image ships, as the daemon attaches it: every tool pre-approved. */
  static final LocalMcp BROWSER =
      new LocalMcp("browser", "qits-browser-mcp", List.of("mcp__browser__*"));

  private final Optional<String> platformUrl;
  private final Map<String, String> platformHeaders;

  WorkspaceHostMcpServers() {
    this(Optional.of(PLATFORM_URL), Map.of());
  }

  /** A RUNNER workspace's host (qits-625): {@code platformHeaders} ride on the {@code qits} server. */
  WorkspaceHostMcpServers(Map<String, String> platformHeaders) {
    this(Optional.of(PLATFORM_URL), platformHeaders);
  }

  WorkspaceHostMcpServers(Optional<String> platformUrl, Map<String, String> platformHeaders) {
    this.platformUrl = platformUrl;
    this.platformHeaders = Map.copyOf(platformHeaders);
  }

  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    return platformUrl
        .map(url -> List.of(new ScopedMcp("qits", url, List.of(), platformHeaders)))
        .orElse(List.of());
  }

  @Override
  public Map<String, String> platformHeaders() {
    return platformHeaders;
  }

  @Override
  public List<LocalMcp> localServers() {
    return List.of(BROWSER);
  }
}
