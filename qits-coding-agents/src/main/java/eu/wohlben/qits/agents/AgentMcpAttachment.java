package eu.wohlben.qits.agents;

import java.util.List;

/**
 * One of the platform's own MCP servers as a surface's configuration attaches it: which server, how
 * narrow its url is, whether it is read-only marked, and the tools pre-approved on it.
 *
 * <p>The wire half of {@code AgentMcpAttachmentDto} in qits-projects-service, and the configured
 * counterpart of {@link ScopedMcp} — which is what the <em>host</em> answers, url already built.
 * Reading the two side by side is how the line this epic draws through the MCP wiring is read:
 *
 * <ul>
 *   <li>the <b>document</b> says which servers attach, in which order, with which pre-approval and
 *       whether they are fenced. That is policy, it is edited in one place, and it travels;
 *   <li>the <b>host</b> ({@link AgentMcpServers}) says how a server key becomes a url at a given
 *       {@link AgentMcpScope}. That is addressing, it needs the container's own project, repository
 *       and workspace ids, and this library deliberately has none of them.
 * </ul>
 *
 * <p>Which is why {@link #narrowProject()}, {@link #narrowRepository()} and {@link #narrowWorkspace()}
 * are carried and validated here but <b>not rendered</b>: the library cannot build a narrowed url
 * without ids it must not know. Today they are the editor's description of what the host already
 * does — the seeded values are exactly the narrowing each daemon's {@code serversFor} builds — and
 * the render asserts nothing about them. Honouring them needs a seam that maps a key <em>plus a
 * narrowing</em> to a url, which is a change to {@link AgentMcpServers} and to both hosts; it is
 * recorded rather than smuggled in, because inventing a url here is the one failure mode this whole
 * module refuses (see {@link McpEndpoints#mcpUrl}).
 *
 * @param server {@code repository}, {@code observability} or {@code actions}
 * @param narrowProject the url carries {@code projectId} — the host's business, see above
 * @param narrowRepository the url carries {@code repositoryId}
 * @param narrowWorkspace the url carries {@code workspaceId}
 * @param readOnly append {@code agentReadOnly=true}, the unattended-run fence
 * @param allowedTools the pre-approved tool ids, in render order — shipped, not operator-editable;
 *     the order is load-bearing because it is rendered into one {@code --allowedTools} argument the
 *     suites assert as a literal
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
