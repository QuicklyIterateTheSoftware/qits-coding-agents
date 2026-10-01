package eu.wohlben.qits.agents;

import io.vertx.core.json.JsonObject;

/**
 * Reusable, harness-agnostic MCP server config objects. An MCP server is carried through a {@link
 * CodingAgent} as a {@code key → config} pair; these generators produce the {@code config} object in
 * the shape the MCP config format expects, so the same server definition can be attached to any
 * agent that speaks MCP (the agent serializes it however it must — only the outer flag wrapping is
 * agent-specific).
 *
 * <p>The config is a {@link JsonObject} rather than a {@code Map}: both harnesses interpolate the
 * serialized form into a shell argument or a heredoc, and their tests assert the rendered command
 * line as a literal, so key order is part of the contract. Building it explicitly keeps the order
 * out of a JSON codec's hands.
 */
public final class McpServers {

  private McpServers() {}

  /**
   * The config for an HTTP (Streamable HTTP) MCP server: {@code {"type":"http","url":"<url>"}}. The
   * {@code url}'s variable parts (scope ids) must already be validated by the caller, since an agent
   * may interpolate the serialized config into a shell argument (see {@link AgentLaunchService}).
   */
  public static JsonObject httpMcp(String url) {
    return new JsonObject().put("type", "http").put("url", url);
  }

  /**
   * The same, with request headers: {@code {"type":"http","url":"<url>","headers":{…}}} — how an
   * external catalog server presents its credential.
   *
   * <p>{@code headers} last, and only when there are any, so an attachment without a credential
   * renders byte for byte what {@link #httpMcp(String)} renders. The key order is the contract here:
   * both harnesses interpolate the serialized form into a shell argument and their tests assert the
   * rendered command line as a literal.
   *
   * <p><b>A header value must never be logged.</b> It is shell-quoted like everything else in the
   * rendered command, and the command a launch <em>records</em> is redacted before it is stored —
   * see {@code AgentLaunchMetadata.redactions}. This method is where the value enters the render, so
   * it is the place to say so.
   */
  public static JsonObject httpMcp(String url, java.util.Map<String, String> headers) {
    JsonObject config = httpMcp(url);
    if (headers == null || headers.isEmpty()) {
      return config;
    }
    JsonObject rendered = new JsonObject();
    headers.forEach(rendered::put);
    return config.put("headers", rendered);
  }

  /**
   * The same, with a <b>headers helper</b> instead of a static header: {@code
   * {"type":"http","url":"<url>","headersHelper":"<command>"}} — Claude Code's MCP config field
   * naming a command it runs at connect, whose stdout is a JSON object of headers.
   *
   * <p><b>Interim (qits-630), owner 2026-10-01.</b> It is how the central {@code qits} platform MCP
   * server gets a fresh bearer into a long session: the container's commissioned-client token lasts
   * one hour, so a header written once at launch would go stale. Follow-up qits-684 gives each
   * workspace a non-time-bounded token issued by its runner instead, and this renders a static {@code
   * headers} entry in its place — which is why this stays one method rather than two: the swap is one
   * change here, not a new render path to keep in step with {@link #httpMcp(String)} and {@link
   * #httpMcp(String, java.util.Map)}.
   */
  public static JsonObject httpMcpWithHeadersHelper(String url, String command) {
    return httpMcp(url).put("headersHelper", command);
  }
}
