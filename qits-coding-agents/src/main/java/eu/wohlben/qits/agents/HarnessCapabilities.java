package eu.wohlben.qits.agents;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * What one harness can be configured with, read off the binary in this container.
 *
 * <p><b>Why this is an abstraction and not a list.</b> The valid models and effort levels belong to
 * the harness binary in the image, not to this platform: they differ per harness, they change when
 * the image is rebuilt, and the two harnesses answer very differently. Claude Code has {@code
 * --effort} with levels its {@code --help} enumerates and <em>no command that lists models</em>;
 * Kimi Code has {@code kimi provider list --json}, a genuinely machine-readable model catalogue, and
 * <b>no effort concept at all</b>. A hardcoded list on this side would be wrong for one of them the
 * day either binary moves.
 *
 * <p><b>The three booleans each mean something an empty list does not.</b>
 *
 * <ul>
 *   <li>{@code modelsEnumerated} false says the harness has no listing command and {@code models} is
 *       a shipped alias set — so the editor leads with the free-text escape rather than treating the
 *       dropdown as exhaustive;
 *   <li>{@code effortSupported} false says the harness has no effort concept at all, so the editor
 *       shows <em>no</em> effort control rather than a disabled one carrying the other harness's
 *       values. The absence is the fact;
 *   <li>{@code probeFailed} true says these lists are the shipped fallback rather than a reading of
 *       the binary — an empty dropdown is worse than a slightly stale one, so a probe that fails or
 *       answers nothing still yields a usable report, flagged as what it is.
 * </ul>
 *
 * <p><b>The report says which values it enumerated, not which are legal.</b> A launch renders
 * whatever string the configuration holds, so a model id the harness never listed still works —
 * pinning a full model id is exactly what somebody comes to that field for, and an effort level this
 * report never saw renders too. Nothing here is a validator.
 *
 * <p><b>The field names are a wire contract</b> with {@code AgentHarnessCapabilityDto} in
 * qits-projects-service (commit {@code c6d6b2d}), which caches these reports per (harness, image
 * version) and serves the union to the editor's dropdowns. A daemon answers {@link #toJson()}
 * straight into the {@code capabilities} array of its {@code GET /agents/available} body, beside the
 * {@code imageVersion} and {@code reportedBy} only it can name; the host passes that body through to
 * its cache unreshaped, so a relay never becomes a third place the contract can drift.
 *
 * @param harness the harness this describes
 * @param harnessVersion what the binary answers for {@code --version}; empty when unread
 * @param models the model ids or aliases, in the order they were enumerated
 * @param modelsEnumerated false when {@code models} is a shipped alias set rather than a catalogue
 * @param effortSupported false when the harness has no effort flag at all
 * @param effortLevels the effort levels; empty when {@code effortSupported} is false
 * @param authenticated whether anybody is signed in on this container's shared credential volume.
 *     <b>Fail-closed</b>: false when nobody looked, because an editor that claimed a harness was
 *     signed in on the strength of a constant would be inventing the one fact the authentication
 *     feature exists to stop inventing
 * @param authDetail what the harness said about that, for a human. Never a credential
 * @param probeFailed whether this report fell back rather than reading the binary
 * @param probeDetail why it fell back; empty when it did not
 */
public record HarnessCapabilities(
    AgentType harness,
    String harnessVersion,
    List<String> models,
    boolean modelsEnumerated,
    boolean effortSupported,
    List<String> effortLevels,
    boolean authenticated,
    String authDetail,
    boolean probeFailed,
    String probeDetail) {

  /**
   * Claude Code's model aliases. <b>Not a catalogue the binary printed</b> — it cannot print one:
   * {@code claude} has {@code agents}, {@code auth}, {@code mcp}, {@code plugin}, {@code project},
   * {@code doctor}, {@code install} and nothing that lists models. Every Claude report therefore
   * carries {@code modelsEnumerated: false}, whether or not the probe succeeded.
   */
  public static final List<String> CLAUDE_MODELS = List.of("opus", "sonnet", "haiku", "fable");

  /**
   * The levels {@code claude --help} enumerates for {@code --effort}, as the shipped fallback. The
   * real report parses them out of the binary's own help text, because the docs say the available
   * levels depend on the model and this list is only ever a floor.
   */
  public static final List<String> CLAUDE_EFFORT_LEVELS =
      List.of("low", "medium", "high", "xhigh", "max");

  /**
   * Kimi's models, empty on purpose: they come from the container's own {@code config.toml}
   * providers, and there is no set this platform could ship that would be true of any particular
   * container. The same emptiness is shipped host-side, for the same reason.
   */
  public static final List<String> KIMI_MODELS = List.of();

  public HarnessCapabilities {
    harnessVersion = harnessVersion == null ? "" : harnessVersion;
    models = models == null ? List.of() : List.copyOf(models);
    effortLevels = effortLevels == null ? List.of() : List.copyOf(effortLevels);
    authDetail = authDetail == null ? "" : authDetail;
    probeDetail = probeDetail == null ? "" : probeDetail;
  }

  /**
   * What a harness reads as when nothing could be read off it — the usable report a failed probe
   * still produces. Not signed in, because nobody looked.
   */
  public static HarnessCapabilities shipped(AgentType harness, String why) {
    boolean claude = harness == AgentType.CLAUDE;
    return new HarnessCapabilities(
        harness,
        "",
        claude ? CLAUDE_MODELS : KIMI_MODELS,
        false,
        claude,
        claude ? CLAUDE_EFFORT_LEVELS : List.of(),
        false,
        "Nobody has checked whether this harness is signed in.",
        true,
        why == null ? "" : why);
  }

  /** The same report with the credential volume's answer folded in. */
  public HarnessCapabilities withAuth(boolean signedIn, String detail) {
    return new HarnessCapabilities(
        harness,
        harnessVersion,
        models,
        modelsEnumerated,
        effortSupported,
        effortLevels,
        signedIn,
        detail,
        probeFailed,
        probeDetail);
  }

  /**
   * The report as the daemon puts it on the wire — field for field {@code
   * AgentHarnessCapabilityDto}'s. Built explicitly rather than by a codec, the same discipline the
   * rest of this module's JSON keeps, so the contract is readable in one place.
   */
  public JsonObject toJson() {
    return new JsonObject()
        .put("harness", harness == null ? null : harness.name())
        .put("harnessVersion", harnessVersion)
        .put("models", new JsonArray(models))
        .put("modelsEnumerated", modelsEnumerated)
        .put("effortSupported", effortSupported)
        .put("effortLevels", new JsonArray(effortLevels))
        .put("authenticated", authenticated)
        .put("authDetail", authDetail)
        .put("probeFailed", probeFailed)
        .put("probeDetail", probeDetail);
  }
}
