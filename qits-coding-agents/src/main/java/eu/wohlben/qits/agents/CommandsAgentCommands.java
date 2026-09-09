package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import java.util.Map;
import java.util.Optional;

/** The production {@link AgentCommands}: a thin adapter over the commands module's three owners. */
public final class CommandsAgentCommands implements AgentCommands {

  private final CommandService commands;
  private final CommandRegistry registry;
  private final CommandStore store;

  public CommandsAgentCommands(
      CommandService commands, CommandRegistry registry, CommandStore store) {
    this.commands = commands;
    this.registry = registry;
    this.store = store;
  }

  @Override
  public Command launchAgent(
      String name,
      String script,
      boolean interactive,
      Map<String, String> environment,
      String commandId,
      AgentSessionRef agentSession,
      CommandExitListener onExit,
      String agentType,
      String agentSurface) {
    return commands.launchAgent(
        name,
        script,
        interactive,
        environment,
        commandId,
        agentSession,
        onExit,
        agentType,
        agentSurface);
  }

  @Override
  public Command launchChat(
      String name,
      String script,
      Map<String, String> environment,
      String commandId,
      AgentSessionRef agentSession,
      CommandExitListener onExit,
      ChatProtocolFactory protocolFactory,
      String agentType,
      String agentSurface) {
    return commands.launchChat(
        name,
        script,
        environment,
        commandId,
        agentSession,
        onExit,
        protocolFactory,
        agentType,
        agentSurface);
  }

  @Override
  public boolean chatSend(String commandId, String text) {
    return registry.chatSend(commandId, text);
  }

  @Override
  public boolean sendKeystrokes(String commandId, String text) {
    // A carriage return rather than a newline: that is what a terminal sends for Enter, and what the
    // attached xterm.js writes on the same channel.
    return registry.input(commandId, (text + "\r").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Override
  public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {
    commands.reportAgentSession(commandId, sessionId, transcriptPath);
  }

  @Override
  public boolean ownsSession(String sessionId) {
    return store.ownsSession(sessionId);
  }

  @Override
  public Optional<String> agentTypeForSession(String sessionId) {
    return store.agentTypeForSession(sessionId);
  }
}
