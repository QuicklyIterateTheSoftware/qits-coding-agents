package eu.wohlben.qits.agents;

import java.util.Optional;

/**
 * The configuration a launch resolves against: the instance-level preferences a request does not
 * state, and — since the configuration epic — the per-surface document this container was created
 * with.
 *
 * <p>On the host these were rows in a {@code setting} table read through {@code SettingsService}.
 * This daemon keeps nothing beyond the life of its container, so a preference that must outlive a
 * container recreate cannot live here — {@code domain.setting} deliberately stays host-side. What
 * replaces it is a resolution order, implemented by the daemon module:
 *
 * <ol>
 *   <li>the request parameter, when the caller stated one;
 *   <li>the checkout's own {@code .qits-config.yml}, which travels with the repository;
 *   <li>a daemon-level default from configuration.
 * </ol>
 *
 * <p>Only step 1 lives here, in {@link #resolve}; the implementation owns the rest. An interface
 * rather than a class because this module is framework-free and cannot read configuration itself —
 * {@code ControlSocket} is the single reader, exactly as it is for the hook port and the claude
 * mount.
 *
 * <p><b>What the configuration epic added, and where it sits.</b> {@link #surfaceConfigurations()}
 * makes this interface answer "the configuration for the surface being launched" rather than only
 * the daemon's two settings: it is the mounted document, read once at boot from a path the host
 * passes in. It is a <em>default</em> method answering the shipped constants, deliberately — this is
 * a released artifact, a daemon picks up a new library at its next release, and a host that has not
 * yet learned to mount a document must keep rendering exactly what it rendered before.
 */
public interface AgentDefaults {

  /** The harness to launch when the request names none. Never null. */
  AgentType defaultAgentType();

  /**
   * Whether to wire the turn-boundary activity hooks (BUSY/IDLE/WAITING/ENDED). The SessionStart
   * lineage hook is emitted regardless — see {@link CodingAgent#activityTracking(boolean)}.
   */
  boolean activityTrackingEnabled();

  /**
   * The model override for prompt refinement, or empty for the harness's own choice. Refinement is a
   * short, cheap, non-interactive call, so a small model is usually right.
   *
   * <p>Present on one of the two copies this library was merged from, because only one of the two
   * daemons runs {@link PromptRefinementService}. It came along with the service rather than being
   * dropped as "the other side did not have it": a host that never refines answers {@link
   * Optional#empty()} and is done, which is one line, whereas re-adding a setting later is a
   * released bump on both sides.
   */
  Optional<String> refinementModel();

  /**
   * The harness for this launch: what the request asked for, else the resolved default. The one
   * place a null request parameter becomes a concrete type.
   */
  default AgentType resolve(AgentType requested) {
    return requested != null ? requested : defaultAgentType();
  }

  /**
   * The per-surface configuration this container was created with, or the library's shipped
   * constants when it was created before that existed.
   *
   * <p>A host that mounts the document answers {@code AgentSurfaceConfigurations.readFrom(path)},
   * read <b>once at boot</b> and held — the path arrives the same way the hook port and the claude
   * mount do, and a malformed document must fail where an operator sees it rather than at the first
   * launch of the one surface that was wrong. Reading it per launch would also make the launch path
   * do file IO for a value that cannot change: a container keeps what it was born with.
   */
  default AgentSurfaceConfigurations surfaceConfigurations() {
    return AgentSurfaceConfigurations.shipped();
  }
}
