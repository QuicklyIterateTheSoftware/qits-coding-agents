package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.GuardedInput;
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
      AgentLaunchMetadata agent) {
    return commands.launchAgent(
        name, script, interactive, environment, commandId, agentSession, onExit, agent);
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
      AgentLaunchMetadata agent) {
    return commands.launchChat(
        name, script, environment, commandId, agentSession, onExit, protocolFactory, agent);
  }

  @Override
  public boolean chatSend(String commandId, String text) {
    return registry.chatSend(commandId, text);
  }

  @Override
  public boolean chatRename(String commandId, String name) {
    return registry.chatRename(commandId, name);
  }

  @Override
  public boolean sendKeystrokes(String commandId, String text) {
    // The turn's own newlines become literal "\n", so it stays one turn, and the Enter follows as
    // a write of its own (TerminalTurns).
    return registry.input(commandId, TerminalTurns.text(text)) && submit(commandId);
  }

  @Override
  public boolean hasDraft(String commandId) {
    return registry.hasDraft(commandId);
  }

  @Override
  public GuardedInput sendKeystrokesUnlessDraft(String commandId, String text) {
    GuardedInput typed = registry.inputUnlessDraft(commandId, TerminalTurns.text(text));
    if (typed == GuardedInput.WRITTEN && !submit(commandId)) {
      return GuardedInput.NOT_RUNNING;
    }
    return typed;
  }

  /**
   * The Enter after a typed turn, {@link TerminalTurns#SUBMIT_PAUSE} later. Written with the text, a
   * TUI reads it as part of a paste and leaves the turn in the input line. False when the session
   * ended in between.
   */
  private boolean submit(String commandId) {
    try {
      Thread.sleep(TerminalTurns.SUBMIT_PAUSE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    return registry.input(commandId, TerminalTurns.enter());
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
