package eu.wohlben.qits.agents;

import java.util.List;

/**
 * One external MCP server as a container receives it: <b>fully rendered</b>, url and header value
 * already resolved, so the container needs no second lookup and holds no qits-configuration
 * reference it would have to resolve from inside a workspace.
 *
 * <p>The library-side twin of {@code AgentResolvedMcpServerDto} in qits-projects-service. The
 * catalog itself — display name, the qits-configuration key the credential lives behind, which
 * surfaces attach it — stays there; what crosses into a container is this, and the mounted document
 * is the only shape on this platform that carries a credential's value.
 *
 * <p><b>{@link #headerValue} is why this record has a {@code toString}.</b> A record's default one
 * prints every component, and this object passes through a launch path whose neighbours are logged
 * as a matter of course. Redacting it here means the value cannot leak by accident from a debug
 * line, an exception message that interpolated a configuration, or a future {@code toString} of
 * something that holds one. The other places it must not reach are handled where they are: the
 * rendered command is stored redacted, and the launch record names attached servers <em>by key</em>.
 *
 * @param key the key this server renders under. Never {@code repository}, {@code actions} or {@code
 *     observability} — those are the platform's own, and an entry displacing one would be a session
 *     that looks entirely normal while talking to somebody else's server
 * @param url an http(s) url; the only transport both harnesses can carry (Kimi carries servers
 *     protocol-native over ACP and has no place for a stdio command)
 * @param headerName the header the credential is presented in; empty when the server takes none
 * @param headerValue that header's value; empty exactly when {@code headerName} is
 * @param allowedTools the tools pre-approved on this server — operator-editable, unlike the
 *     built-ins' shipped lists, because the platform ships no constant for a server it never heard of
 */
public record AgentExternalMcpServer(
    String key, String url, String headerName, String headerValue, List<String> allowedTools) {

  public AgentExternalMcpServer {
    headerName = headerName == null ? "" : headerName;
    headerValue = headerValue == null ? "" : headerValue;
    allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
  }

  /** Whether this server presents a credential at all. */
  public boolean hasCredential() {
    return !headerName.isBlank() && !headerValue.isBlank();
  }

  /** Everything but the credential. See this record's note on why the default is overridden. */
  @Override
  public String toString() {
    return "AgentExternalMcpServer[key="
        + key
        + ", url="
        + url
        + ", headerName="
        + headerName
        + ", headerValue="
        + (headerValue.isEmpty() ? "" : "<redacted>")
        + ", allowedTools="
        + allowedTools
        + "]";
  }
}
