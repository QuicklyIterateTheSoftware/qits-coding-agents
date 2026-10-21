package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

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
 * @param agentConfigDir this agent's own config checkout — {@code memory/}, {@code skills/}, a
 *     {@code settings.json} whose {@code enabledPlugins} is all that is read, and the plugin manifest
 *     that makes the directory a plugin — or null for none, which renders exactly what a launch
 *     rendered before the field existed. Claude only: Kimi ignores it. Set by the host, never read
 *     off a request body: it is a path on the container's filesystem.
 * @param environment variables this one launch overlays on the agent's process environment, after
 *     everything the library sets itself — a value here wins, and a key mapped to an empty string
 *     removes that variable from the child. A non-blank {@code QITS_TOKEN} also becomes the bearer
 *     on every platform MCP server of this launch, in place of the host's {@link
 *     AgentMcpServers#platformHeaders()}, so the session's tools act with the same credential as its
 *     shell. Null is no overlay, which renders exactly what a launch rendered before the field
 *     existed. Set by the host, never read off a request body: it can carry a credential.
 */
public record AgentLaunchRequest(
    AgentMcpScope scope,
    AgentSurface surface,
    AgentLaunchMode mode,
    String initialContext,
    String resumeSessionId,
    boolean fork,
    boolean deliverTaskPrompt,
    AgentType agentType,
    Path agentConfigDir,
    Map<String, String> environment) {

  /** What a variable name in {@link #environment} must look like: a POSIX shell identifier. */
  private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  public AgentLaunchRequest {
    environment = environment == null ? Map.of() : Map.copyOf(environment);
    for (String name : environment.keySet()) {
      // A removal is rendered as a shell `unset`, so a name that is not an identifier is refused
      // rather than interpolated.
      if (!VARIABLE_NAME.matcher(name).matches()) {
        throw new InvalidCommandRequestException("Invalid environment variable name: " + name);
      }
    }
  }

  /** A launch without an environment overlay — the shape every caller had before it existed. */
  public AgentLaunchRequest(
      AgentMcpScope scope,
      AgentSurface surface,
      AgentLaunchMode mode,
      String initialContext,
      String resumeSessionId,
      boolean fork,
      boolean deliverTaskPrompt,
      AgentType agentType,
      Path agentConfigDir) {
    this(
        scope,
        surface,
        mode,
        initialContext,
        resumeSessionId,
        fork,
        deliverTaskPrompt,
        agentType,
        agentConfigDir,
        Map.of());
  }

  /** A launch without an agent config dir — the shape every caller had before it existed. */
  public AgentLaunchRequest(
      AgentMcpScope scope,
      AgentSurface surface,
      AgentLaunchMode mode,
      String initialContext,
      String resumeSessionId,
      boolean fork,
      boolean deliverTaskPrompt,
      AgentType agentType) {
    this(
        scope,
        surface,
        mode,
        initialContext,
        resumeSessionId,
        fork,
        deliverTaskPrompt,
        agentType,
        null,
        Map.of());
  }

  /** This request with {@code dir} as the agent's own config checkout. */
  public AgentLaunchRequest withAgentConfigDir(Path dir) {
    return new AgentLaunchRequest(
        scope,
        surface,
        mode,
        initialContext,
        resumeSessionId,
        fork,
        deliverTaskPrompt,
        agentType,
        dir,
        environment);
  }

  /** This request with {@code overlay} as its environment overlay; null is none. */
  public AgentLaunchRequest withEnvironment(Map<String, String> overlay) {
    return new AgentLaunchRequest(
        scope,
        surface,
        mode,
        initialContext,
        resumeSessionId,
        fork,
        deliverTaskPrompt,
        agentType,
        agentConfigDir,
        overlay);
  }

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
