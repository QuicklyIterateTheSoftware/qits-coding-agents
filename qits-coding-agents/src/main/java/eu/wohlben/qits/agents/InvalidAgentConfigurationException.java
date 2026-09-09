package eu.wohlben.qits.agents;

/**
 * A mounted agent-configuration document this container cannot make sense of — thrown at boot, by
 * {@link AgentConfigurationDocument#readFrom}, naming the offending key.
 *
 * <p><b>Absent is not broken.</b> A missing file is a container created before this shipped, and it
 * falls back to the library's shipped constants without a word; that is the estate's usual rule. A
 * <em>malformed</em> one is loud and immediate, because the alternative is discovering it as a weird
 * agent three hours later — an unknown permission mode that silently became "skip everything", or a
 * surface whose servers quietly did not attach.
 *
 * <p>A boot failure and not an {@code InvalidCommandRequestException}: nobody made a request. The
 * daemon that reads the document decides what a boot failure means for it — refusing to start is the
 * honest answer, since every launch it serves would otherwise be misconfigured.
 */
public class InvalidAgentConfigurationException extends RuntimeException {

  public InvalidAgentConfigurationException(String message) {
    super(message);
  }

  public InvalidAgentConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
