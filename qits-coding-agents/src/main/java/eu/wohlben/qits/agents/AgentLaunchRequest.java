package eu.wohlben.qits.agents;

/**
 * One agent launch as the caller asks for it.
 *
 * <p>The pre-extraction controller took eight loose arguments and the owner ids besides. The ids are
 * ambient here, and the rest gathers into a record so the two launch shapes share one shape of
 * request.
 *
 * @param scope which MCP servers to attach, and how they are narrowed. Required.
 * @param surface where in the product this session was started from — what it is steered at and what
 *     its configuration is keyed by; null resolves to {@link #surfaceOrDefault()}'s shape-implied
 *     guess for one release. Orthogonal to {@code scope}: the scope addresses, the surface steers.
 * @param mode chat or the interactive TUI; null means {@link AgentLaunchMode#CHAT}
 * @param initialContext the seed turn, or null for none
 * @param resumeSessionId a session of this container to continue, or null for a fresh one
 * @param fork branch {@code resumeSessionId} into a new session instead of continuing it (Claude
 *     only)
 * @param deliverTaskPrompt seed with the one-sentence bootstrap turn so the agent fetches the real
 *     prompt over MCP, instead of pushing {@code initialContext} literally. On the host this was
 *     additionally gated on a stored draft existing; there is no draft store here, so the caller's
 *     word is taken — it is the one that has the draft.
 * @param agentType the harness to use, or null for the resolved default
 */
public record AgentLaunchRequest(
    AgentMcpScope scope,
    AgentSurface surface,
    AgentLaunchMode mode,
    String initialContext,
    String resumeSessionId,
    boolean fork,
    boolean deliverTaskPrompt,
    AgentType agentType) {

  public AgentLaunchMode modeOrDefault() {
    return mode == null ? AgentLaunchMode.CHAT : mode;
  }

  /**
   * The surface this launch is for, guessed from the request's shape when the caller named none.
   *
   * <p><b>A migration crutch with an expiry, not a contract.</b> It exists so the daemons can ship
   * ahead of the frontends that will send the key: an <em>unknown</em> surface is refused outright
   * ({@link AgentSurface#of}), but a <em>missing</em> one resolves for one release, and task
   * 747a0225 removes this method once both frontends send their own. A guess is exactly the quiet
   * default that makes a misconfigured caller look like a working one, which is why it is dated.
   *
   * <p>The guess is the honest reading of what the two daemons launch today, and it is lossy in
   * precisely the place this epic exists to fix:
   *
   * <ul>
   *   <li>a {@link AgentMcpScope#PROJECT}-scoped launch is the projects daemon's epics desk —
   *       {@link AgentSurface#PROJECT_EPICS} — in either mode, because that container's chat and its
   *       terminal are the same desk. Its tickets desk cannot be guessed: it sends the same scope and
   *       the same mode, and is told apart only by the {@code desk} field the projects daemon still
   *       accepts and maps to {@link AgentSurface#PROJECT_TICKETS} itself;
   *   <li>any other chat is a workspace container's chat tab — {@link AgentSurface#WORKSPACE_CHAT}.
   *       {@link AgentSurface#EPIC_CHAT} sends a byte-identical request, so the two collapse here;
   *       that collapse is the whole reason the surface had to become a value that travels;
   *   <li>any other interactive launch is a workspace container's agent tab — {@link
   *       AgentSurface#WORKSPACE_AGENT}, collapsing {@link AgentSurface#EPIC_AGENT} the same way.
   * </ul>
   *
   * <p>The two composed runs are never guessed: {@link AgentSurface#EPIC_AUTONOMOUS} and {@link
   * AgentSurface#TICKET_DISPATCH} are named by their call sites, which have no human to have
   * forgotten.
   */
  public AgentSurface surfaceOrDefault() {
    if (surface != null) {
      return surface;
    }
    if (scope == AgentMcpScope.PROJECT) {
      return AgentSurface.PROJECT_EPICS;
    }
    return modeOrDefault() == AgentLaunchMode.INTERACTIVE
        ? AgentSurface.WORKSPACE_AGENT
        : AgentSurface.WORKSPACE_CHAT;
  }
}
