package eu.wohlben.qits.agents;

import java.util.List;
import java.util.Optional;

/**
 * What a surface runs as, resolved: the mounted document if a container was born with one, else the
 * constants this library ships.
 *
 * <p>This is the resolution source behind {@link AgentDefaults}, which stops being "the daemon's two
 * settings" and becomes "the configuration for the surface being launched". A host that mounts a
 * document overrides {@link AgentDefaults#surfaceConfigurations()} with {@link #readFrom} over the
 * path it was given; a host that has not adopted the mount yet keeps the default and renders exactly
 * what it rendered before this existed.
 *
 * <p><b>What the shipped fallback deliberately does not carry.</b> Everything but the system prompt
 * comes from somewhere honest rather than from a table copied out of qits-projects:
 *
 * <ul>
 *   <li>the <b>MCP servers</b> are {@code null}, meaning "whatever the host attaches for this
 *       launch's scope". A shipped attachment table could not be right for both hosts — the projects
 *       daemon attaches one server, the workspace daemon three, with different narrowing and
 *       differently ordered pre-approval lists — and a library that shipped one of them would be a
 *       library that knows which product it is inside, which is the thing {@link AgentMcpServers}
 *       exists to prevent;
 *   <li>the <b>activity tracking</b> flag is the host's daemon-wide setting, passed in, because that
 *       is the value a container with no document has always used;
 *   <li>the <b>harness</b> is the host's default for the same reason;
 *   <li>the <b>system prompt</b> is the one thing the library genuinely ships: {@link
 *       AgentLaunchService#TICKETS_DESK_PROMPT} for {@link AgentSurface#PROJECT_TICKETS}, {@link
 *       AgentLaunchService#COMPOSED_RUN_PROMPT} for the two composed runs ({@link
 *       AgentSurface#EPIC_AUTONOMOUS} and {@link AgentSurface#TICKET_DISPATCH}), and nothing for the
 *       other five. It was a text block in a {@code switch} arm a release ago and it still is; the
 *       document makes it editable, it does not make it move.
 * </ul>
 *
 * <p>The initial prompt is empty here even for the two composed runs, which do push a bootstrap
 * sentence: that sentence is the host's {@code taskPromptBootstrap} constructor argument — the
 * projects daemon says "this project" where the workspace daemon says "this workspace" — and folding
 * it into a shipped configuration would pick a winner between two live literals. The store seeds it
 * per surface instead, each with its own daemon's noun, and {@code
 * AgentLaunchService.taskPromptTurn} takes the configured value over the host's when there is one.
 */
public final class AgentSurfaceConfigurations {

  private static final AgentSurfaceConfigurations SHIPPED = new AgentSurfaceConfigurations(null);

  /** The document this container was born with, or null when it was born without one. */
  private final AgentConfigurationDocument document;

  private AgentSurfaceConfigurations(AgentConfigurationDocument document) {
    this.document = document;
  }

  /** No document: every surface answers the library's shipped constants. */
  public static AgentSurfaceConfigurations shipped() {
    return SHIPPED;
  }

  /** The document a host has already parsed; null is the same as {@link #shipped()}. */
  public static AgentSurfaceConfigurations of(AgentConfigurationDocument document) {
    return document == null ? SHIPPED : new AgentSurfaceConfigurations(document);
  }

  /**
   * Reads the document mounted at {@code path} — the one call a host daemon makes at boot.
   *
   * @throws InvalidAgentConfigurationException if a document is there and cannot be trusted
   */
  public static AgentSurfaceConfigurations readFrom(String path) {
    return of(AgentConfigurationDocument.readFrom(path).orElse(null));
  }

  /** The document this container was born with, or empty when it was born without one. */
  public Optional<AgentConfigurationDocument> document() {
    return Optional.ofNullable(document);
  }

  /** Whether a document was mounted at all — the absent-versus-broken distinction, after the fact. */
  public boolean documentPresent() {
    return document != null;
  }

  /**
   * What {@code surface} runs as. Never empty and never a failure: a surface the document does not
   * mention answers the shipped default, the same rule the store applies on its own side, because a
   * daemon that knows a surface nobody has configured still has to launch it.
   *
   * @param defaultHarness the host's resolved default harness, for the shipped answer
   * @param activityTracking the host's daemon-wide activity-tracking setting, for the shipped answer
   */
  public AgentSurfaceConfiguration resolve(
      AgentSurface surface, AgentType defaultHarness, boolean activityTracking) {
    if (document != null) {
      AgentSurfaceConfiguration configured = document.surfaces().get(surface.key());
      if (configured != null) {
        return configured;
      }
    }
    return shippedFor(surface, defaultHarness, activityTracking);
  }

  /**
   * The system-prompt appendix a surface ships with. Three surfaces have one and the other five
   * steer with nothing:
   *
   * <ul>
   *   <li>the <b>tickets desk</b> has to say what it is, because it shares every tool with the desk
   *       beside it;
   *   <li>the <b>two composed runs</b> carry {@link AgentLaunchService#COMPOSED_RUN_PROMPT}, which
   *       tells an unattended main loop to orchestrate and delegate rather than type the code
   *       itself — the one instruction nobody is present to give them mid-run.
   * </ul>
   *
   * <p>The text blocks themselves stay where they have always been, in {@link AgentLaunchService}:
   * this stops them being the <em>only</em> copy, not the last one. Nothing downstream may depend on
   * a prompt being here — an operator who clears the box gets an unsteered run.
   */
  public static String shippedSystemPrompt(AgentSurface surface) {
    if (AgentSurface.PROJECT_TICKETS.equals(surface)) {
      return AgentLaunchService.TICKETS_DESK_PROMPT;
    }
    if (AgentSurface.EPIC_AUTONOMOUS.equals(surface)
        || AgentSurface.TICKET_DISPATCH.equals(surface)) {
      return AgentLaunchService.COMPOSED_RUN_PROMPT;
    }
    return "";
  }

  /**
   * Whether a surface ships with remote control on — <b>true for every shape but the two agent
   * tabs</b>, which reads backwards and is what the code does.
   *
   * <p>A chat has bridged since the day the transport learned to ask for one: {@code
   * claudeChatProtocol} enabled Remote Control over the SDK control channel on every chat it opened,
   * because {@code --remote-control} is dropped by the harness under {@code --print}. An interactive
   * launch, which is the shape the flag was made for, never passed it. So "what a container with no
   * document rendered before" is: chats bridged, terminals not — and this is that, surface by
   * surface. The same table is seeded in qits-projects ({@code AgentSurfaceDefaults.shipped}), for
   * the same reason and with the same two exceptions.
   */
  static boolean shippedRemoteControl(AgentSurface surface) {
    return !AgentSurface.EPIC_AGENT.equals(surface) && !AgentSurface.WORKSPACE_AGENT.equals(surface);
  }

  /** The constants a container with no document renders — see this class's note on each of them. */
  public static AgentSurfaceConfiguration shippedFor(
      AgentSurface surface, AgentType defaultHarness, boolean activityTracking) {
    return new AgentSurfaceConfiguration(
        surface.key(),
        defaultHarness == null ? AgentType.CLAUDE : defaultHarness,
        "",
        "",
        shippedRemoteControl(surface),
        // Every launch shape in both daemons calls skipPermissions(), unconditionally.
        AgentPermissionMode.SKIP_PERMISSIONS,
        activityTracking,
        shippedSystemPrompt(surface),
        "",
        null,
        // No external servers, and there never can be a shipped one: the catalog is operator-defined
        // and starts empty, so a constant naming an entry would name a row nobody has created.
        List.of(),
        true);
  }
}
