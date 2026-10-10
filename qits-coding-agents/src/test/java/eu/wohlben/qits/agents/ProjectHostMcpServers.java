package eu.wohlben.qits.agents;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The projects daemon's {@link AgentMcpServers}, as a test fixture: the central {@code qits} server
 * when it has an address for it, and nothing else (qits-1174).
 */
final class ProjectHostMcpServers implements AgentMcpServers {

  /** Where the central {@code qits} platform server is. */
  static final String PLATFORM_URL = "http://dev-qits-platform-access-mcp-service:8080/mcp";

  private final Optional<String> platformUrl;
  private final Map<String, String> platformHeaders;

  ProjectHostMcpServers() {
    this(Optional.of(PLATFORM_URL), Map.of());
  }

  ProjectHostMcpServers(Optional<String> platformUrl, Map<String, String> platformHeaders) {
    this.platformUrl = platformUrl;
    this.platformHeaders = Map.copyOf(platformHeaders);
  }

  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    return platformUrl
        .map(url -> List.of(new ScopedMcp("qits", url, List.of("mcp__qits__*"), platformHeaders)))
        .orElse(List.of());
  }

  @Override
  public Map<String, String> platformHeaders() {
    return platformHeaders;
  }
}
