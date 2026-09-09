package eu.wohlben.qits.agents;

import eu.wohlben.qits.agents.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The resolved configuration a container was created with — every surface it may serve — parsed out
 * of the JSON file mounted into it.
 *
 * <p><b>A snapshot, not a subscription.</b> A container keeps what it was born with for its whole
 * life; an edit in qits-projects applies to the next container. That is the deliberate trade: the
 * launch path stays a pure local render with no runtime dependency on the store, which is worth more
 * than immediacy because a container's life is already scoped to a piece of work.
 *
 * <p><b>The path is passed in, like the hook port and the claude mount.</b> This module is
 * framework-free and reads no configuration of its own — {@code ControlSocket} is the single reader
 * on the daemon side, and one setting with two readers is how a launch ends up rendering hooks at a
 * port nothing is listening on. So the host hands {@link #readFrom} a path and this class does the
 * rest.
 *
 * <p><b>Absent is not broken.</b> No path, or no file at that path, answers {@link Optional#empty()}:
 * it means a container created before this shipped, and the launch falls back to the shipped
 * constants (see {@link AgentSurfaceConfigurations}). A file that is there but does not parse, or
 * carries a harness or a permission mode nobody understands, throws {@link
 * InvalidAgentConfigurationException} naming the offending key. Discovering a bad edit at boot is
 * the whole reason the document is a mounted file rather than an environment variable.
 *
 * <p>The shape is {@code AgentConfigurationDocumentDto} in qits-projects-service, field for field.
 * The two are copies rather than a shared type — that service depends on no daemon library and this
 * library reads no service — so the field <em>names</em> are the contract, and {@link
 * #CURRENT_VERSION} is what a daemon reading a newer shape refuses on rather than mis-rendering a
 * launch three hours later.
 *
 * @param version the document shape's version, as written by the service
 * @param generatedAt when the snapshot was taken, ISO-8601 — a container's record of how old the
 *     configuration it holds is, which is the only honest way to read a snapshot
 * @param surfaces every surface the document carried, keyed by its surface key in document order
 */
public record AgentConfigurationDocument(
    int version, String generatedAt, Map<String, AgentSurfaceConfiguration> surfaces) {

  /**
   * The newest shape this library understands. Must track the service's {@code CURRENT_VERSION}.
   *
   * <p><b>2 since the external MCP catalog.</b> A surface entry is now {@code {configuration,
   * externalMcpServers}} rather than the configuration record flat, because the rendered external
   * servers carry credentials and had nowhere honest to sit inside a record the editor also reads.
   * Version 1 is still read — it costs one branch, and a container created between the two releases
   * is a real thing for as long as it lives.
   */
  public static final int CURRENT_VERSION = 2;

  /**
   * The keys the platform's own MCP servers are registered under. Reserved: an external entry taking
   * one of these would not attach twice, it would silently displace the platform's server in the one
   * key-to-config object both harnesses render — and the session would look entirely normal while
   * talking to somebody else's repository server.
   *
   * <p>The store validates this on write ({@code AgentMcpCatalog.requireCatalogKey}); it is checked
   * again here, at boot, and once more at render, because a document can reach a container from an
   * older service and this is the one collision whose failure is invisible.
   */
  public static final List<String> RESERVED_SERVER_KEYS =
      List.of("repository", "actions", "observability");

  public AgentConfigurationDocument {
    surfaces = surfaces == null ? Map.of() : Map.copyOf(surfaces);
  }

  /**
   * Reads the document mounted at {@code path}, or empty when there is none.
   *
   * @param path where the host mounted it; null or blank is the same as absent
   * @throws InvalidAgentConfigurationException if the file is there and cannot be trusted
   */
  public static Optional<AgentConfigurationDocument> readFrom(String path) {
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    Path file;
    try {
      file = Path.of(path.trim());
    } catch (InvalidPathException e) {
      throw new InvalidAgentConfigurationException(
          "Agent configuration path is not a path: " + path, e);
    }
    if (!Files.exists(file)) {
      // The absent case, and the common one for a while: a container created before this shipped.
      return Optional.empty();
    }
    if (Files.isDirectory(file)) {
      throw new InvalidAgentConfigurationException(
          "Agent configuration at " + file + " is a directory, not a document");
    }
    String raw;
    try {
      raw = Files.readString(file);
    } catch (IOException e) {
      // Present but unreadable is broken, not absent: something mounted it and got it wrong.
      throw new InvalidAgentConfigurationException(
          "Agent configuration at " + file + " could not be read", e);
    }
    return Optional.of(parse(raw, file.toString()));
  }

  /**
   * Parses one document, naming {@code origin} in every failure so the message says which file was
   * wrong rather than only what was wrong with it.
   */
  public static AgentConfigurationDocument parse(String raw, String origin) {
    Json root = Json.parse(raw);
    if (!root.isObject()) {
      throw invalid(origin, null, "is not a JSON object");
    }
    if (!root.has("version") || root.path("version").asInt(-1) < 0) {
      throw invalid(origin, "version", "must be a number");
    }
    int version = root.path("version").asInt(-1);
    if (version > CURRENT_VERSION) {
      // Refuse rather than read what we can: a shape this daemon does not understand is exactly the
      // case where a partial read renders a launch that looks right and is not.
      throw invalid(
          origin,
          "version",
          "is "
              + version
              + ", newer than this library understands ("
              + CURRENT_VERSION
              + "). Release the daemon that carries a newer qits-coding-agents");
    }
    if (!root.path("surfaces").isArray()) {
      throw invalid(origin, "surfaces", "must be an array");
    }
    Map<String, AgentSurfaceConfiguration> surfaces = new LinkedHashMap<>();
    int index = 0;
    for (Json entry : root.path("surfaces")) {
      String at = "surfaces[" + index++ + "]";
      if (!entry.isObject()) {
        throw invalid(origin, at, "must be an object");
      }
      // A version-2 entry wraps the configuration beside the rendered external servers; a
      // version-1 entry IS the configuration. Which shape it is, is readable off the entry, so the
      // branch does not have to trust the version field to be honest about its own body.
      Json configuration = entry.path("configuration").isObject() ? entry.path("configuration") : entry;
      AgentSurfaceConfiguration surface =
          parseSurface(configuration, parseExternalServers(entry, origin, at), origin, at);
      if (surfaces.put(surface.surface(), surface) != null) {
        throw invalid(origin, at + ".surface", "is configured twice: " + surface.surface());
      }
    }
    return new AgentConfigurationDocument(
        version, root.path("generatedAt").asText(""), surfaces);
  }

  private static AgentSurfaceConfiguration parseSurface(
      Json entry,
      List<AgentExternalMcpServer> externalServers,
      String origin,
      String at) {
    String key = entry.path("surface").asText("").trim();
    if (key.isEmpty()) {
      throw invalid(origin, at + ".surface", "must name a surface");
    }
    // A key outside AgentSurface.KNOWN is deliberately NOT refused. The store may have learned a
    // ninth surface before this library does, and a daemon that cannot look the key up loses
    // nothing by carrying it — whereas failing to boot on it would force the two repositories to
    // release in lockstep, which is the one thing the rollout order cannot do.
    AgentType harness =
        AgentType.parse(entry.path("harness").asText(""))
            .orElseThrow(
                () ->
                    invalid(
                        origin,
                        at + ".harness",
                        "is not a known harness: " + entry.path("harness").asText("")));
    AgentPermissionMode permissionMode =
        AgentPermissionMode.parse(entry.path("permissionMode").asText(""))
            .orElseThrow(
                () ->
                    invalid(
                        origin,
                        at + ".permissionMode",
                        "is not a known permission mode: "
                            + entry.path("permissionMode").asText("")));
    return new AgentSurfaceConfiguration(
        key,
        harness,
        entry.path("model").asText(""),
        entry.path("effort").asText(""),
        entry.path("remoteControl").asBoolean(false),
        permissionMode,
        entry.path("activityTracking").asBoolean(true),
        entry.path("systemPrompt").asText(""),
        entry.path("initialPrompt").asText(""),
        parseAttachments(entry, origin, at),
        externalServers,
        false);
  }

  /**
   * The rendered external MCP servers on a version-2 surface entry — the one place in this document
   * a credential appears, and the reason the document is a mounted file rather than an environment
   * variable.
   *
   * <p>Validated here rather than trusted: the key must exist, must not be one of {@link
   * #RESERVED_SERVER_KEYS}, and must not repeat; the url must be there; and a header name and a
   * header value are set together or not at all — a header with no value is a server that will 401
   * on the agent's first tool call with nothing in the configuration to say why, which surfaces as
   * a confused agent hours later rather than as an error anybody reads.
   */
  private static List<AgentExternalMcpServer> parseExternalServers(
      Json entry, String origin, String at) {
    Json servers = entry.path("externalMcpServers");
    if (servers.isMissing() || servers.isNull()) {
      return List.of();
    }
    if (!servers.isArray()) {
      throw invalid(origin, at + ".externalMcpServers", "must be an array");
    }
    List<AgentExternalMcpServer> external = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    int index = 0;
    for (Json server : servers) {
      String where = at + ".externalMcpServers[" + index++ + "]";
      if (!server.isObject()) {
        throw invalid(origin, where, "must be an object");
      }
      String key = server.path("key").asText("").trim();
      if (key.isEmpty()) {
        throw invalid(origin, where + ".key", "must name a server");
      }
      if (RESERVED_SERVER_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
        throw invalid(
            origin,
            where + ".key",
            "is '"
                + key
                + "', which is one of this platform's own servers ("
                + String.join(", ", RESERVED_SERVER_KEYS)
                + "). An external entry under that name would displace it silently");
      }
      if (keys.contains(key)) {
        throw invalid(origin, where + ".key", "attaches '" + key + "' a second time");
      }
      keys.add(key);
      String url = server.path("url").asText("").trim();
      if (url.isEmpty()) {
        throw invalid(origin, where + ".url", "must be an http(s) url");
      }
      String headerName = server.path("headerName").asText("").trim();
      String headerValue = server.path("headerValue").asText("");
      if (headerName.isEmpty() != headerValue.isEmpty()) {
        // Never naming the value, in a message that ends up in a log.
        throw invalid(
            origin,
            where,
            "sets a header name without a value or a value without a name; a server presents both"
                + " or neither");
      }
      external.add(
          new AgentExternalMcpServer(
              key, url, headerName, headerValue, allowedTools(server, origin, where)));
    }
    return List.copyOf(external);
  }

  private static List<AgentMcpAttachment> parseAttachments(Json entry, String origin, String at) {
    Json servers = entry.path("mcpServers");
    if (servers.isMissing() || servers.isNull()) {
      // A surface that says nothing about MCP takes the host's whole mapping, which is what a
      // container with no document at all does. Distinguishable from an explicit empty list, which
      // is a surface that attaches nothing on purpose.
      return null;
    }
    if (!servers.isArray()) {
      throw invalid(origin, at + ".mcpServers", "must be an array");
    }
    List<AgentMcpAttachment> attachments = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    int index = 0;
    for (Json server : servers) {
      String where = at + ".mcpServers[" + index++ + "]";
      if (!server.isObject()) {
        throw invalid(origin, where, "must be an object");
      }
      String key = server.path("server").asText("").trim();
      if (key.isEmpty()) {
        throw invalid(origin, where + ".server", "must name a server");
      }
      if (keys.contains(key)) {
        // Not a merge, a refusal. Both harnesses render one key-to-config object — Claude's
        // --mcp-config and Kimi's ACP session/new list — so a repeated key does not attach twice,
        // it silently displaces, and a session talking to the wrong 'repository' server looks
        // entirely normal. The reserved-key check the external catalog needs (task c7a985bd) is
        // this same rule with a second list to check against.
        throw invalid(origin, where + ".server", "attaches '" + key + "' a second time");
      }
      keys.add(key);
      attachments.add(
          new AgentMcpAttachment(
              key,
              server.path("narrowProject").asBoolean(false),
              server.path("narrowRepository").asBoolean(false),
              server.path("narrowWorkspace").asBoolean(false),
              server.path("readOnly").asBoolean(false),
              allowedTools(server, origin, where)));
    }
    return List.copyOf(attachments);
  }

  private static List<String> allowedTools(Json server, String origin, String where) {
    Json tools = server.path("allowedTools");
    if (tools.isMissing() || tools.isNull()) {
      return List.of();
    }
    if (!tools.isArray()) {
      throw invalid(origin, where + ".allowedTools", "must be an array");
    }
    List<String> ids = new ArrayList<>();
    for (Json tool : tools) {
      if (!tool.isTextual() || tool.asText("").isBlank()) {
        throw invalid(origin, where + ".allowedTools", "must hold tool ids");
      }
      ids.add(tool.asText(""));
    }
    return List.copyOf(ids);
  }

  private static InvalidAgentConfigurationException invalid(
      String origin, String key, String problem) {
    String where = origin == null || origin.isBlank() ? "Agent configuration" : origin;
    return new InvalidAgentConfigurationException(
        key == null ? where + " " + problem : where + ": " + key + " " + problem);
  }
}
