package eu.wohlben.qits.agents;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether a session's tool calls are auto-approved or prompted for.
 *
 * <p>{@link #SKIP_PERMISSIONS} is what every launch shape in both daemons renders today — {@code
 * --dangerously-skip-permissions}, unconditionally — so it is what every surface ships as and what
 * a container with no document falls back to. The knob exists because that was an invariant nobody
 * chose: it is also the amplifier that makes an attached third-party MCP server dangerous inside a
 * container holding platform credentials, and the external-server catalog is the first real reason
 * for a surface to be anything but this.
 *
 * <p>The store's copy is {@code AgentPermissionMode} in qits-projects-service. The two are copies
 * rather than a shared type — that service depends on no daemon library — so what crosses between
 * them is the <em>name</em>, character for character, and {@link #parse} is the one place a document
 * string becomes this enum.
 */
public enum AgentPermissionMode {

  /** Tools run auto-approved: the harness's skip-permissions flag. */
  SKIP_PERMISSIONS,

  /** The harness asks before a tool that is not pre-approved runs. */
  PROMPT;

  /**
   * Parse a document value (case-insensitive, trimmed), or empty for a blank or unknown one. The
   * document reader turns that emptiness into a boot failure naming the key — a permission mode
   * nobody understands must not quietly become "skip everything".
   */
  public static Optional<AgentPermissionMode> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      // Locale.ROOT for the same reason AgentType.parse pins it: a Turkish JVM mangles 'i'.
      return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
