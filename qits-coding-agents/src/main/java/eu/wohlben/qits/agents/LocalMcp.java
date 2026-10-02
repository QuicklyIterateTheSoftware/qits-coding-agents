package eu.wohlben.qits.agents;

import java.util.List;

/**
 * One MCP server that runs as a process inside the agent's own container (stdio transport): the key
 * it is registered under, the command that starts it, and the tools pre-approved on it.
 *
 * <p>The command is a program the container image ships, named without arguments. Its flags live
 * with the image, which is the one place that knows what the program needs (a browser path, a
 * scratch directory), so a host only names it. The command is embedded in a single-quoted shell
 * argument and the renderer does no escaping of its own: {@link #LocalMcp} refuses anything outside
 * a plain program name or path.
 */
public record LocalMcp(String key, String command, List<String> allowedTools) {

  public LocalMcp {
    if (key == null || !key.matches("[a-z][a-z0-9-]*")) {
      throw new IllegalArgumentException("key must match [a-z][a-z0-9-]*: " + key);
    }
    if (command == null || !command.matches("[A-Za-z0-9/._-]+")) {
      throw new IllegalArgumentException("command must be a plain program name or path: " + command);
    }
    allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
  }
}
