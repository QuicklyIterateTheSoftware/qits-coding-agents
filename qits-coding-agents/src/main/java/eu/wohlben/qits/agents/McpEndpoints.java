package eu.wohlben.qits.agents;

/**
 * Where the agent reached the per-service MCP servers from inside its container.
 *
 * @deprecated since qits-1174. The per-service servers are retired, and the library never read this
 *     interface itself. Kept only so hosts built against the older library still compile.
 */
@Deprecated
public interface McpEndpoints {

  /** The base url of one named MCP server. Throws rather than inventing one. */
  String mcpUrl(String server);

  /** The project this container serves. */
  String projectId();
}
