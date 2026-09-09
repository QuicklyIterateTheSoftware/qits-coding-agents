package eu.wohlben.qits.agents;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * The document qits-projects seeds a container with, built here so both hosts' suites can assert the
 * one equivalence this epic rests on: <b>a launch rendered from the seeded configuration is the
 * launch rendered from the constants, byte for byte</b>.
 *
 * <p>It mirrors {@code AgentSurfaceDefaults} in qits-projects-service (commit {@code 5520f1d}) —
 * every surface seeded with what the two daemons hardcode today, so that turning the store on
 * changes nothing. The values are supplied by each host's suite rather than copied here, because the
 * pre-approval lists differ per host in both membership and order and the whole point is that the
 * seed reproduces <em>that</em> host's list rather than a merged one.
 *
 * <p>Written as JSON rather than as records, deliberately: it goes through {@link
 * AgentConfigurationDocument#parse} the way a mounted file does, so the field names the service
 * writes are exercised by the same test that proves the render did not move.
 */
final class SeededConfigurationDocument {

  private final JsonArray surfaces = new JsonArray();

  SeededConfigurationDocument surface(
      AgentSurface surface, boolean remoteControl, String systemPrompt, JsonObject... servers) {
    JsonArray attachments = new JsonArray();
    for (JsonObject server : servers) {
      attachments.add(server);
    }
    surfaces.add(
        new JsonObject()
            .put("surface", surface.key())
            .put("harness", "CLAUDE")
            // Empty for every surface: no launch shape passes --model or --effort today.
            .put("model", "")
            .put("effort", "")
            .put("remoteControl", remoteControl)
            // Every launch shape in both daemons calls skipPermissions(), unconditionally.
            .put("permissionMode", "SKIP_PERMISSIONS")
            .put("activityTracking", true)
            .put("systemPrompt", systemPrompt)
            .put("initialPrompt", "")
            .put("mcpServers", attachments)
            .put("shipped", true));
    return this;
  }

  static JsonObject server(
      String key,
      boolean narrowProject,
      boolean narrowRepository,
      boolean narrowWorkspace,
      boolean readOnly,
      List<String> allowedTools) {
    return new JsonObject()
        .put("server", key)
        .put("narrowProject", narrowProject)
        .put("narrowRepository", narrowRepository)
        .put("narrowWorkspace", narrowWorkspace)
        .put("readOnly", readOnly)
        .put("allowedTools", new JsonArray(allowedTools));
  }

  /** The document as a container receives it, parsed into the resolution source. */
  AgentSurfaceConfigurations configurations() {
    String document =
        new JsonObject()
            .put("version", AgentConfigurationDocument.CURRENT_VERSION)
            .put("generatedAt", "2026-09-09T10:00:00Z")
            .put("surfaces", surfaces)
            .encode();
    return AgentSurfaceConfigurations.of(
        AgentConfigurationDocument.parse(document, "seeded-configuration"));
  }
}
