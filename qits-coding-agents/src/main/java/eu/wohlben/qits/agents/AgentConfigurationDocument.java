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

  /** The newest shape this library understands. Must track the service's {@code CURRENT_VERSION}. */
  public static final int CURRENT_VERSION = 1;

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
      AgentSurfaceConfiguration surface = parseSurface(entry, origin, at);
      if (surfaces.put(surface.surface(), surface) != null) {
        throw invalid(origin, at + ".surface", "is configured twice: " + surface.surface());
      }
    }
    return new AgentConfigurationDocument(
        version, root.path("generatedAt").asText(""), surfaces);
  }

  private static AgentSurfaceConfiguration parseSurface(Json entry, String origin, String at) {
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
        false);
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
