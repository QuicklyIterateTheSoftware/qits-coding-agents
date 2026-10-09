package eu.wohlben.qits.commands;

/**
 * How a command's process is driven and rendered. {@link #TERMINAL} is an interactive PTY streamed
 * to xterm.js (shells, {@code claude} in a terminal, one-off runs); {@link #CHAT} is a coding-agent
 * session driven over a line-delimited JSON protocol on plain pipes and rendered as a conversation.
 * The frontend routes the command view on this.
 */
public enum CommandKind {
  TERMINAL,
  CHAT
}
