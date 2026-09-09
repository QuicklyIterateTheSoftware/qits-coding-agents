package eu.wohlben.qits.agents;

import java.util.List;

/**
 * One MCP server as a launch attaches it: the key it is registered under, its already-narrowed URL,
 * and the tools pre-approved on it.
 *
 * <p>A top-level record rather than a nested one, because it is now the currency of a seam: {@link
 * AgentMcpServers} produces these and {@link AgentLaunchService} renders them, and the two sides
 * live in different repositories once a daemon takes this library as a dependency.
 *
 * <p>{@code allowedTools} is a pre-approval on Claude and something stronger on Kimi — {@code
 * enabledTools} there is the session's whole tool surface, so a name left out does not cost a
 * prompt, it removes the tool. Whoever builds this list is deciding both.
 */
public record ScopedMcp(String key, String url, List<String> allowedTools) {}
