package eu.wohlben.qits.commands;

import java.util.List;

/**
 * What a launch records about itself beyond the script it ran: which harness drove it, which surface
 * it was started from, and what it was configured with.
 *
 * <p>One record rather than three more positional parameters. {@code launchAgent} and {@code
 * launchChat} already carried nine arguments each, two of them these strings, and the configuration
 * epic adds a third; the parameter object takes the arity <em>down</em> and gives the three related
 * values a place to be documented together.
 *
 * <p>{@code launchRecord} is deliberately an opaque string here. This module knows nothing about
 * agents — it launches processes — and the shape of what a session ran as belongs to {@code
 * qits-coding-agents}, which builds it as JSON and hands it over. What this module owes it is
 * storage and an answer, not an opinion.
 *
 * @param agentType the coding-agent harness this launch drove, or null for a non-agent command
 * @param agentSurface where in the product the session was started from ({@code AgentSurface}'s
 *     key); null for a non-agent command and for the sign-in terminal, which is nobody's surface
 * @param launchRecord what the session was resolved to run as, as JSON — see {@code
 *     AgentLaunchRecord}. Never carries a credential
 * @param redactions literal substrings of the script that must not be <b>stored</b> — an external
 *     MCP server's header value, and nothing else so far. The process is spawned with the real
 *     script; what the command keeps, answers and shows is this script with each of these replaced.
 *     See {@link #redact}
 */
public record AgentLaunchMetadata(
    String agentType, String agentSurface, String launchRecord, List<String> redactions) {

  /** What a redacted value is replaced by. Not a value, and obviously not one. */
  public static final String REDACTED = "<redacted>";

  /** The empty metadata a non-agent launch carries. */
  public static final AgentLaunchMetadata NONE =
      new AgentLaunchMetadata(null, null, null, List.of());

  public AgentLaunchMetadata {
    redactions = redactions == null ? List.of() : List.copyOf(redactions);
  }

  /** A launch that records its harness and surface but nothing about its configuration. */
  public static AgentLaunchMetadata of(String agentType, String agentSurface) {
    return new AgentLaunchMetadata(agentType, agentSurface, null, List.of());
  }

  /** A launch record with nothing to redact — the ordinary case. */
  public AgentLaunchMetadata(String agentType, String agentSurface, String launchRecord) {
    this(agentType, agentSurface, launchRecord, List.of());
  }

  /**
   * {@code script} with every redaction replaced — <b>what a command stores, and never what it
   * runs</b>.
   *
   * <p>The rendered command line is kept on the command, answered by the daemon's API and shown on
   * a command page, which is exactly right for every launch this platform had until an external MCP
   * server's credential started riding in one. So the two diverge here, in the one place a command
   * is recorded: the registry spawns the script it was handed, and the store keeps this.
   *
   * <p>A stored script with {@code &lt;redacted&gt;} in it is no longer runnable, and that is the
   * point rather than a cost — it was never re-run, only read, and a reader can see that something
   * was withheld instead of wondering whether the session had a credential at all.
   */
  public String redact(String script) {
    if (script == null || redactions.isEmpty()) {
      return script;
    }
    String redacted = script;
    for (String secret : redactions) {
      if (secret != null && !secret.isBlank()) {
        redacted = redacted.replace(secret, REDACTED);
      }
    }
    return redacted;
  }
}
