package eu.wohlben.qits.agents;

/**
 * How an agent session's MCP servers are scoped — a first-class launch parameter, resolved to a set
 * of narrowed server URLs by the host's {@link AgentMcpServers}.
 *
 * <ul>
 *   <li>{@link #ACTIONS} — the "configure this repository" pairing: the {@code actions} server
 *       scoped to the repository the session runs in (for managing that repository's actions), plus
 *       the {@code repository} server narrowed to it (its branches, workspaces and commits).
 *   <li>{@link #REPOSITORY} — the {@code repository} server, scoped to the session's project and
 *       narrowed to that one repository, for driving a single repository from within a subtree or a
 *       wrapper checkout.
 *   <li>{@link #PROJECT} — the {@code repository} server scoped to the whole project, with no
 *       repository narrowing, so the session can drive every repository in it. The default shape for
 *       a project agent.
 * </ul>
 *
 * <p><b>This enum is the union of two enums.</b> The workspace daemon's copy had all three values;
 * the projects daemon's had {@code PROJECT} and {@code REPOSITORY} only, because a project agent
 * addresses qits-projects and nothing else, and a scope naming a server that daemon cannot reach
 * would only have failed at launch. The union is what moved, and it costs the projects side nothing:
 * a scope is <em>asked for</em> by a caller and <em>answered</em> by the host's {@link
 * AgentMcpServers}, so a host that attaches nothing for {@code ACTIONS} simply never sees it.
 *
 * <p>Where the {@code observability} server rides is likewise the host's answer rather than a value
 * here: telemetry only answers for one workspace, so it is never the thing a session is scoped
 * <em>to</em> — it is what a narrowing unlocks.
 *
 * <p>Orthogonal to {@link AgentSurface}: the scope <em>addresses</em>, the surface <em>steers</em>.
 * Both axes cross freely — a tickets desk can be narrowed to one repository, and narrowing to a
 * repository must not quietly change what the session is steered at.
 */
public enum AgentMcpScope {
  ACTIONS,
  REPOSITORY,
  PROJECT
}
