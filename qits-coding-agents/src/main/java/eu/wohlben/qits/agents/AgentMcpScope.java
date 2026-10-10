package eu.wohlben.qits.agents;

/**
 * The MCP scope a launch request names. <b>It no longer picks any servers</b> (qits-1174): the one
 * platform server, {@code qits}, is scoped by the caller's own bearer, not by its url.
 *
 * <p>It stays because callers still send {@code scope} on the launch wire and both daemons parse it.
 * Remove it together with that wire field.
 */
public enum AgentMcpScope {
  ACTIONS,
  REPOSITORY,
  PROJECT
}
