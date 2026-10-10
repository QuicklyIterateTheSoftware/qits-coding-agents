package eu.wohlben.qits.agents;

import java.util.List;

/**
 * One platform MCP server as a surface's configuration attaches it: which server, and the tools
 * pre-approved on it. The wire half of {@code AgentMcpAttachmentDto} in qits-projects-service.
 *
 * <p>Since qits-1174 the only platform server is {@code qits}. The narrowing flags and {@code
 * readOnly} are still read from the document but no longer change anything: {@code qits} takes no
 * url parameters, and the caller's own bearer decides what a call may do. An attachment that still
 * names a retired server ({@link AgentConfigurationDocument#RETIRED_SERVER_KEYS}) is skipped at
 * launch.
 *
 * @param server the server key, {@code qits}
 * @param narrowProject no longer used
 * @param narrowRepository no longer used
 * @param narrowWorkspace no longer used
 * @param readOnly no longer used
 * @param allowedTools the pre-approved tool ids, in render order
 */
public record AgentMcpAttachment(
    String server,
    boolean narrowProject,
    boolean narrowRepository,
    boolean narrowWorkspace,
    boolean readOnly,
    List<String> allowedTools) {

  public AgentMcpAttachment {
    allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
  }
}
