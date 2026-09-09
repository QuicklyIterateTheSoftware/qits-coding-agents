package eu.wohlben.qits.agents;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The Claude Code harness — a {@link CodingAgent} that renders its accumulated configuration into a
 * {@code claude} command. Interactive launches {@code exec claude}; a one-off run uses {@code claude
 * -p …}. Attached MCP servers are merged into one {@code --strict-mcp-config --mcp-config '{…}'} and
 * the collected allowlist into a single {@code --allowedTools '…'}; an autonomous run adds {@code
 * --dangerously-skip-permissions}.
 *
 * <p>The initial context / prompt is embedded directly as a shell-quoted argument (single-quoting is
 * injection-safe for any content), so a prompt can come from anywhere the caller reads it — a
 * literal, a classpath resource, a request body — without a side file.
 */
public class ClaudeCodeAgent extends CodingAgent {

  @Override
  public LaunchSpec start() {
    StringBuilder command = new StringBuilder("exec claude");
    if (flatOutput) {
      // --ax-screen-reader renders flat text (no alternate-screen TUI/animations), so the PTY
      // session log captures the readable conversation instead of terminal control sequences that
      // get wiped when the interactive UI exits.
      command.append(" --ax-screen-reader");
    }
    if (initialContext != null && !initialContext.isBlank()) {
      command.append(' ').append(shellQuote(initialContext));
    }
    if (remoteControlName != null) {
      // --remote-control [name], verified against CLI 2.1.226: "Start an interactive session with
      // Remote Control enabled (optionally named)". Interactive only — the flag parses under
      // --print and is dropped on the headless branch, which is why the chat shapes enable it over
      // the control channel instead and never set this field. The name is passed because the
      // auto-generated one is hostname-derived and every platform session would look alike in the
      // claude.ai session list; see AgentRemoteControl.
      command.append(" --remote-control ").append(shellQuote(remoteControlName));
    }
    appendFlags(command);
    return new LaunchSpec(command.toString(), true, environment);
  }

  @Override
  public LaunchSpec run(String prompt) {
    StringBuilder command = new StringBuilder("claude -p ").append(shellQuote(prompt));
    appendFlags(command);
    return new LaunchSpec(command.toString(), false, environment);
  }

  @Override
  public LaunchSpec chat() {
    // Bidirectional stream-json: user messages are fed on stdin as JSON, structured events
    // (assistant messages, tool calls, result) come back on stdout — driven programmatically over
    // plain pipes, not a PTY. --verbose emits every event, not just the final result.
    // --include-hook-events surfaces hook lifecycle events (e.g. Stop) in the stream, giving the
    // daemon turn-boundary awareness for busy/idle detection and well-timed service-event
    // injection. exec'd because the process is long-lived and managed.
    StringBuilder command =
        new StringBuilder(
            "exec claude --print --input-format stream-json --output-format stream-json"
                + " --include-hook-events --verbose");
    appendFlags(command);
    return new LaunchSpec(command.toString(), false, environment);
  }

  /**
   * Appends the session flags, the model, the system-prompt appendix, the MCP config, the allowlist,
   * the session-report hook and the skip-permissions flag.
   */
  private void appendFlags(StringBuilder command) {
    validateSessionConfiguration();
    if (resumeSessionId != null) {
      command.append(" --resume ").append(resumeSessionId);
      if (forkRequested) {
        command.append(" --fork-session --session-id ").append(sessionId);
      }
    } else if (sessionId != null) {
      command.append(" --session-id ").append(sessionId);
    }
    if (model != null && !model.isBlank()) {
      command.append(" --model ").append(shellQuote(model));
    }
    if (effort != null && !effort.isBlank()) {
      // --effort <level>, whose --help enumerates (low, medium, high, xhigh, max) — but the launch
      // renders whatever string the configuration holds rather than checking it against that list.
      // The levels depend on the model, the list belongs to the binary in the image, and a launch
      // that second-guessed it would be this platform hardcoding a catalogue again. An unknown
      // level fails at the harness, where the answer is authoritative.
      command.append(" --effort ").append(shellQuote(effort));
    }
    if (systemPromptAppendix != null && !systemPromptAppendix.isBlank()) {
      // --append-system-prompt, not --system-prompt: the harness's own prompt is what makes the
      // tools and the checkout usable, and replacing it to add a desk's steering would cost far
      // more than it buys.
      command.append(" --append-system-prompt ").append(shellQuote(systemPromptAppendix));
    }
    if (!mcpServers.isEmpty()) {
      JsonObject servers = new JsonObject();
      mcpServers.forEach(servers::put);
      String json = new JsonObject().put("mcpServers", servers).encode();
      command.append(" --strict-mcp-config --mcp-config ").append(shellQuote(json));
    }
    if (!allowedTools.isEmpty()) {
      command.append(" --allowedTools ").append(shellQuote(String.join(",", allowedTools)));
    }
    if (sessionReportingUrl != null) {
      command.append(" --settings ").append(shellQuote(activityHookSettings().encode()));
    }
    if (skipPermissions) {
      command.append(" --dangerously-skip-permissions");
    }
  }

  /**
   * A settings layer wiring the agent-activity lifecycle hooks, each POSTing the hook's stdin JSON
   * ({@code {hook_event_name, session_id, transcript_path, source, …}}) to the daemon's loopback hook
   * webhook. {@code SessionStart} is <b>always</b> emitted — it fires on {@code startup}/{@code
   * resume}/{@code clear}/{@code compact} and drives session-lineage, which is non-optional. When
   * {@link #activityTracking} is on, the turn-boundary events ({@code UserPromptSubmit}/{@code
   * Stop}/{@code Notification}/{@code SessionEnd}) are added too, so qits can show the live
   * "cooking / idle / waiting" state.
   */
  private JsonObject activityHookSettings() {
    if (sessionReportingUrl.contains("'")) {
      // Defense in depth: the URL is composed of a fixed loopback host, a port, and a UUID command
      // id, none of which can contain a quote — but it ends up inside a single-quoted argv.
      throw new IllegalArgumentException(
          "Session-reporting URL must not contain quotes: " + sessionReportingUrl);
    }
    String post =
        "curl -fsS -m 5 -X POST -H \"Content-Type: application/json\" --data-binary @- "
            + sessionReportingUrl;
    JsonArray group =
        new JsonArray()
            .add(
                new JsonObject()
                    .put(
                        "hooks",
                        new JsonArray()
                            .add(new JsonObject().put("type", "command").put("command", post))));
    JsonObject hooks = new JsonObject();
    hooks.put("SessionStart", group); // always — session-lineage
    if (activityTracking) {
      hooks.put("UserPromptSubmit", group);
      hooks.put("Stop", group);
      hooks.put("Notification", group);
      hooks.put("SessionEnd", group);
    }
    return new JsonObject().put("hooks", hooks);
  }

  // --- the capability probe ---------------------------------------------------------------------

  /**
   * Claude Code's report: the version from {@code claude --version}, the effort levels parsed out of
   * {@code claude --help}, and the shipped alias set for models.
   *
   * <p><b>There is no listing command for models</b> — {@code claude} has {@code agents}, {@code
   * auth}, {@code mcp}, {@code plugin}, {@code project}, {@code doctor}, {@code install} and nothing
   * that prints a catalogue — so {@code modelsEnumerated} is false on every Claude report, including
   * a successful one. That is not a probe failure; it is what this harness offers, and it is why the
   * editor leads with the free-text escape.
   *
   * <p>The effort levels do come from the binary, because they are the one thing it will say: the
   * flag's own help text enumerates them. Parsing help output is brittle by nature, which is exactly
   * why the fixture in the suite is a verbatim copy of what the pinned CLI prints — a harness upgrade
   * that rewords that line fails a test here instead of quietly emptying a dropdown.
   */
  @Override
  public HarnessCapabilities capabilities(
      ProcessRunner processes, Path cwd, Map<String, String> environment) {
    ProcessRunner.Result help =
        processes.exec(List.of("claude", "--help"), cwd, environment, PROBE_TIMEOUT);
    if (help.timedOut() || help.exitCode() != 0) {
      return HarnessCapabilities.shipped(
          AgentType.CLAUDE,
          help.timedOut()
              ? "claude --help did not answer within " + PROBE_TIMEOUT.toSeconds() + "s"
              : "claude --help exited " + help.exitCode());
    }
    List<String> levels = parseEffortLevels(help.output());
    return new HarnessCapabilities(
        AgentType.CLAUDE,
        parseVersion(processes.exec(List.of("claude", "--version"), cwd, environment, PROBE_TIMEOUT)),
        HarnessCapabilities.CLAUDE_MODELS,
        // Never enumerated, and this is the honest word for it rather than a failure.
        false,
        true,
        levels.isEmpty() ? HarnessCapabilities.CLAUDE_EFFORT_LEVELS : levels,
        false,
        "",
        levels.isEmpty(),
        levels.isEmpty() ? "claude --help no longer enumerates --effort levels" : "");
  }

  /**
   * The {@code --effort} levels out of {@code claude --help}. The flag's description carries them in
   * parentheses — {@code --effort <level>  Effort level for the current session (low, medium, high,
   * xhigh, max)} — wrapped across lines by the help formatter, so the window after the flag is
   * whitespace-collapsed before the parenthesised list is read out of it.
   */
  static List<String> parseEffortLevels(String help) {
    if (help == null) {
      return List.of();
    }
    int flag = help.indexOf("--effort");
    if (flag < 0) {
      return List.of();
    }
    String window =
        help.substring(flag, Math.min(help.length(), flag + 400)).replaceAll("\\s+", " ");
    java.util.regex.Matcher parenthesised =
        java.util.regex.Pattern.compile("\\(([^)]*)\\)").matcher(window);
    if (!parenthesised.find()) {
      return List.of();
    }
    List<String> levels = new java.util.ArrayList<>();
    for (String candidate : parenthesised.group(1).split(",")) {
      String level = candidate.trim();
      // A level is a bare word. Anything else means the help text has moved on to describing
      // something other than a list, and an invented level is worse than a short one.
      if (level.matches("[a-z][a-z0-9-]*")) {
        levels.add(level);
      }
    }
    return List.copyOf(levels);
  }

  /** {@code claude --version} prints {@code 2.1.226 (Claude Code)}; the version is the first token. */
  static String parseVersion(ProcessRunner.Result result) {
    if (result == null || result.timedOut() || result.exitCode() != 0) {
      return "";
    }
    String output = result.output() == null ? "" : result.output().trim();
    int space = output.indexOf(' ');
    return space < 0 ? output : output.substring(0, space);
  }

  /**
   * Claude Code persists a session's transcript under {@code
   * $CLAUDE_CONFIG_DIR/projects/<escaped-cwd>/<sessionId>.jsonl}, where the escaped cwd replaces
   * every non-alphanumeric character with {@code -} (verified against CLI 2.1.204, the pinned image
   * version).
   */
  @Override
  public Path transcriptPath(String cwd, String sessionId) {
    return Path.of("projects", escapeCwd(cwd), sessionId + ".jsonl");
  }

  /**
   * Task-tool subagents persist beside the main JSONL: {@code
   * projects/<escaped-cwd>/<sessionId>/subagents/agent-<agentId>.jsonl} plus a sibling {@code
   * agent-<agentId>.meta.json} carrying {@code {agentType, description, toolUseId, spawnDepth}}.
   */
  @Override
  public Path subagentsDir(String cwd, String sessionId) {
    return Path.of("projects", escapeCwd(cwd), sessionId, "subagents");
  }

  private static String escapeCwd(String cwd) {
    return cwd.replaceAll("[^A-Za-z0-9]", "-");
  }

  /**
   * POSIX single-quoting: safe for any content, since only {@code '} is special inside {@code '…'}.
   */
  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
