package eu.wohlben.qits.agents;

import java.util.List;

/**
 * What one session surface runs as: the row the editor holds, as a container reads it back out of
 * its mounted document.
 *
 * <p>The library-side twin of {@code AgentSurfaceConfigurationDto} in qits-projects-service. Every
 * value here was a constant, a {@code switch} arm or a text block inside {@link AgentLaunchService}
 * a release ago, and the shipped constants are still there — see {@link
 * AgentSurfaceConfigurations#shipped} — because a container created before this epic shipped has no
 * document and must render exactly what it rendered then.
 *
 * <p>{@code model}, {@code effort}, {@code systemPrompt} and {@code initialPrompt} are never null:
 * empty is a first-class value. {@link AgentSurface#PROJECT_EPICS} steers with an empty system
 * prompt <em>on purpose</em>, and an empty model is the harness's own default.
 *
 * @param surface the surface key this configures. A key outside {@link AgentSurface#KNOWN} is kept
 *     as it was read rather than refused: a document written by a newer service may name a ninth
 *     surface, and a daemon that does not know it simply never looks it up
 * @param harness the coding-agent harness. Read and validated; not yet the resolution source for
 *     {@link AgentLaunchService}, which still takes the request parameter then {@link
 *     AgentDefaults}, because the checkout's own {@code .qits-config.yml} rung sits between them and
 *     is the host's to order
 * @param model the model to pass, or empty for the harness's own. Rendered by task c667e740
 * @param effort the effort level, or empty. Claude only; rendered by task c667e740
 * @param remoteControl whether this surface's remote-control mechanism is enabled. <b>Not validated
 *     against the launch shape</b> — when the knob is on, the flag is set. The seed has it on for
 *     the chat surfaces and off for the interactive ones, which is the opposite of the intuition and
 *     is what the code does: {@code --remote-control} is dropped under {@code --print}, so a chat
 *     enables it over the SDK control channel instead ({@code StreamJsonChatProtocol}). Wired by
 *     task c667e740
 * @param permissionMode auto-approve or prompt
 * @param activityTracking whether to wire the turn-boundary activity hooks, per surface rather than
 *     per daemon
 * @param systemPrompt the appendix to the harness's own system prompt; empty for a surface that
 *     steers with nothing
 * @param initialPrompt the turn pushed at session start; delivered by task d517c81a
 * @param mcpServers the built-in servers this surface attaches, in render order. {@code null} means
 *     <em>whatever the host attaches for the launch's scope</em> — the shipped fallback, and the
 *     only honest answer for a container with no document, since the constants this library ships
 *     cannot know which of two products' mappings it is inside
 * @param shipped true when this is the library's shipped default rather than a row that was read out
 *     of a document
 */
public record AgentSurfaceConfiguration(
    String surface,
    AgentType harness,
    String model,
    String effort,
    boolean remoteControl,
    AgentPermissionMode permissionMode,
    boolean activityTracking,
    String systemPrompt,
    String initialPrompt,
    List<AgentMcpAttachment> mcpServers,
    boolean shipped) {

  public AgentSurfaceConfiguration {
    model = model == null ? "" : model;
    effort = effort == null ? "" : effort;
    systemPrompt = systemPrompt == null ? "" : systemPrompt;
    initialPrompt = initialPrompt == null ? "" : initialPrompt;
    mcpServers = mcpServers == null ? null : List.copyOf(mcpServers);
  }

  /** The system-prompt appendix, or null when this surface steers with nothing. */
  public String systemPromptAppendix() {
    return systemPrompt.isEmpty() ? null : systemPrompt;
  }

  /** Whether the servers to attach are this configuration's rather than the host's whole mapping. */
  public boolean attachesConfiguredServers() {
    return mcpServers != null;
  }
}
