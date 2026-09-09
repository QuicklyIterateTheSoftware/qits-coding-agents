package eu.wohlben.qits.agents;

/**
 * How narrow one attached MCP server's url should be — which of the container's own ids it carries.
 *
 * <p>The half of an {@link AgentMcpAttachment} the <b>host</b> has to act on. The document says
 * which servers attach and how narrowly; only the host can turn that into a url, because it needs
 * the container's project, repository and workspace ids, which this library deliberately does not
 * have. So this record crosses the {@link AgentMcpServers} seam and nothing else about the
 * attachment does — a pre-approval list and a read-only fence are the library's to apply.
 *
 * <p>The three render in one canonical order — {@code projectId}, {@code repositoryId}, {@code
 * workspaceId} — because that is the order both daemons build their urls in today and the rendered
 * command line is asserted as a literal on both harnesses. A host implementing {@link
 * AgentMcpServers#serverFor} must keep that order, or every one of those assertions moves for a
 * reason nobody intended.
 *
 * @param project the url carries {@code projectId}
 * @param repository the url carries {@code repositoryId}
 * @param workspace the url carries {@code workspaceId}
 */
public record AgentMcpNarrowing(boolean project, boolean repository, boolean workspace) {

  /** The narrowing an attachment asks for. */
  public static AgentMcpNarrowing of(AgentMcpAttachment attachment) {
    return new AgentMcpNarrowing(
        attachment.narrowProject(), attachment.narrowRepository(), attachment.narrowWorkspace());
  }

  /** Whether this asks for no narrowing at all — the whole platform, unscoped. */
  public boolean isEmpty() {
    return !project && !repository && !workspace;
  }
}
