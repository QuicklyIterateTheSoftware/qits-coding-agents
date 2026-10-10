package eu.wohlben.qits.agents;

/**
 * How narrow an attached MCP server's url should be.
 *
 * @deprecated since qits-1174. The servers that took {@code projectId}, {@code repositoryId} and
 *     {@code workspaceId} on their url are retired; {@code qits} takes none. Kept only so hosts that
 *     still implement {@link AgentMcpServers#serverFor} compile. Remove with that parameter.
 * @param project the url carries {@code projectId}
 * @param repository the url carries {@code repositoryId}
 * @param workspace the url carries {@code workspaceId}
 */
@Deprecated
public record AgentMcpNarrowing(boolean project, boolean repository, boolean workspace) {

  /** The narrowing an attachment asks for. */
  public static AgentMcpNarrowing of(AgentMcpAttachment attachment) {
    return new AgentMcpNarrowing(
        attachment.narrowProject(), attachment.narrowRepository(), attachment.narrowWorkspace());
  }

  /** Whether this asks for no narrowing at all. */
  public boolean isEmpty() {
    return !project && !repository && !workspace;
  }
}
