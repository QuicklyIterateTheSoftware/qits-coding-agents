package eu.wohlben.qits.commands;

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
 */
public record AgentLaunchMetadata(String agentType, String agentSurface, String launchRecord) {

  /** The empty metadata a non-agent launch carries. */
  public static final AgentLaunchMetadata NONE = new AgentLaunchMetadata(null, null, null);

  /** A launch that records its harness and surface but nothing about its configuration. */
  public static AgentLaunchMetadata of(String agentType, String agentSurface) {
    return new AgentLaunchMetadata(agentType, agentSurface, null);
  }
}
