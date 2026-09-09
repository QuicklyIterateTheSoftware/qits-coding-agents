package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.InvalidCommandRequestException;

/**
 * One agent launch as the caller asks for it.
 *
 * <p>The pre-extraction controller took eight loose arguments and the owner ids besides. The ids are
 * ambient here, and the rest gathers into a record so the two launch shapes share one shape of
 * request.
 *
 * @param scope which MCP servers to attach, and how they are narrowed. Required.
 * @param surface where in the product this session was started from — what it is steered at and what
 *     its configuration is keyed by. Required: a launch that names none is refused. Orthogonal to
 *     {@code scope}: the scope addresses, the surface steers.
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
   * The surface this launch is for, refusing the launch when the caller named none.
   *
   * <p>There used to be a guess here — a {@link AgentMcpScope#PROJECT}-scoped launch read as the
   * epics desk, anything else as a workspace container's chat or agent tab — so the daemons could
   * ship ahead of the frontends that send the key. Both frontends send it now, so the guess is gone
   * (task 747a0225): it was lossy in exactly the place this epic exists to fix ({@link
   * AgentSurface#EPIC_CHAT} and {@link AgentSurface#WORKSPACE_CHAT} send byte-identical requests and
   * collapsed onto one another), and a default that resolves a caller which forgot the key is the
   * quiet kind that makes a misconfigured caller look like a working one.
   *
   * <p>A missing surface is now refused the same way an unknown one is — {@link
   * InvalidCommandRequestException}, which a daemon's API reports as a 400 rather than "Internal
   * error". Note this is <em>not</em> the same case as a container created without a configuration
   * document: that one keeps falling back to the library's shipped constants, permanently, because
   * a container older than the store is a real and lasting shape. A caller that omits the surface is
   * simply broken.
   */
  public AgentSurface requiredSurface() {
    if (surface == null) {
      throw new InvalidCommandRequestException("A launch must name its agent surface");
    }
    return surface;
  }
}
