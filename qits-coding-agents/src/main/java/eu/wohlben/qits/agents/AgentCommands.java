package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import java.util.Map;
import java.util.Optional;

/**
 * Everything {@link AgentLaunchService} needs from the commands module, behind one interface.
 *
 * <p>Every method here already exists on {@code CommandService}, {@code CommandRegistry} or {@code
 * CommandStore}, and none of them has another caller — they were added in the commands pass
 * specifically for this. {@link CommandsAgentCommands} is the adapter and is the only
 * implementation.
 *
 * <p>The seam exists because those three classes are {@code final} and this reactor has no mocking
 * framework. Without it the only way to exercise a launch would be to spawn a real {@code claude},
 * so the assertions that actually matter — the rendered MCP scope, the credential overlay, the
 * session lineage — would go untested in the one class where the most behaviour changed.
 */
public interface AgentCommands {

  /**
   * Spawns an interactive PTY agent command (kind {@code TERMINAL}).
   *
   * <p>{@code agentSurface} is {@link AgentSurface#key()} — recorded on the command so the launch's
   * answer says which surface it is. Null for the sign-in terminal, which is nobody's surface.
   */
  Command launchAgent(
      String name,
      String script,
      boolean interactive,
      Map<String, String> environment,
      String commandId,
      AgentSessionRef agentSession,
      CommandExitListener onExit,
      String agentType,
      String agentSurface);

  /** Spawns a pipe-driven chat command (kind {@code CHAT}), recording its surface. */
  Command launchChat(
      String name,
      String script,
      Map<String, String> environment,
      String commandId,
      AgentSessionRef agentSession,
      CommandExitListener onExit,
      ChatProtocolFactory protocolFactory,
      String agentType,
      String agentSurface);

  /** Delivers a user turn to a running chat. */
  boolean chatSend(String commandId, String text);

  /** Records a hook-reported session identity on a command. */
  void reportAgentSession(String commandId, String sessionId, String transcriptPath);

  /**
   * Whether this container has driven {@code sessionId}.
   *
   * <p>Fails closed, and that is a real behaviour change worth naming: the store does not outlive
   * the container, so resuming a session from a <em>previous</em> container is refused rather than
   * allowed. The transcript is still on the shared volume; the record that this container owns the
   * session is not.
   */
  boolean ownsSession(String sessionId);

  /** The harness a known session was driven with, so a resume cannot switch harness mid-session. */
  Optional<String> agentTypeForSession(String sessionId);
}
