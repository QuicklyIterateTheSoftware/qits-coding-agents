package eu.wohlben.qits.agents;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * What a session was <b>actually launched with</b> — recorded on the command at launch, not inferred
 * afterwards from what the store holds.
 *
 * <p><b>This is what makes "recreate only" a safe decision rather than an opaque one.</b> A
 * container keeps the configuration document it was born with for its whole life, an edit in
 * qits-projects applies to the next container, and none of that is surfaced anywhere. The cost of
 * that trade is that a session which behaved oddly last week cannot be read off the store: the row
 * has moved, the container is gone, and the document nobody kept is the only thing that would have
 * explained it. One record written at launch closes that gap for the whole epic, and it is the
 * evidence the rollout's per-surface verification reads rather than logs.
 *
 * <p><b>Credentials are excluded, structurally.</b> Attached external MCP servers are recorded by
 * <em>key</em>. Not by url with a header stripped, not by a redacted value — by key, so there is no
 * shape here a credential could travel in. The record is stored on a command, answered by the
 * daemon's API and read by whoever can see a session; a header value has no business in any of
 * those.
 *
 * @param surface where in the product the session was started from
 * @param harness the coding-agent harness that ran
 * @param model the model passed, or empty for the harness's own
 * @param effort the effort level passed, or empty. Empty on a Kimi session even when one was
 *     configured — see {@code notes}
 * @param permissionMode auto-approved or prompting
 * @param remoteControl whether the surface's remote-control knob was on
 * @param remoteControlName the session name a bridge was asked for under, or empty when it was not
 * @param activityTracking whether the turn-boundary hooks were wired
 * @param mcpServers the platform's own servers this launch attached, in render order
 * @param externalMcpServers the catalog entries it attached, <b>by key</b>
 * @param configured false when this launch rendered the library's shipped constants because its
 *     container was born without a document — the difference between "configured this way" and
 *     "nobody had configured it", which a reader cannot otherwise tell
 * @param notes what the harness could not render of what it was configured with (a Kimi effort
 *     level, a Kimi system prompt), and anything else the launch wants a reader to know
 */
public record AgentLaunchRecord(
    String surface,
    AgentType harness,
    String model,
    String effort,
    AgentPermissionMode permissionMode,
    boolean remoteControl,
    String remoteControlName,
    boolean activityTracking,
    List<AttachedServer> mcpServers,
    List<String> externalMcpServers,
    boolean configured,
    List<String> notes) {

  /** One attached platform server as the launch rendered it: which, and whether it was fenced. */
  public record AttachedServer(String server, boolean readOnly) {}

  public AgentLaunchRecord {
    model = model == null ? "" : model;
    effort = effort == null ? "" : effort;
    remoteControlName = remoteControlName == null ? "" : remoteControlName;
    mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    externalMcpServers = externalMcpServers == null ? List.of() : List.copyOf(externalMcpServers);
    notes = notes == null ? List.of() : List.copyOf(notes);
  }

  /**
   * The record as it is stored on the command: one JSON object, built explicitly so the key order is
   * this file's rather than a codec's, the same discipline {@link McpServers} keeps.
   */
  public String toJson() {
    JsonArray servers = new JsonArray();
    for (AttachedServer server : mcpServers) {
      servers.add(new JsonObject().put("server", server.server()).put("readOnly", server.readOnly()));
    }
    return new JsonObject()
        .put("surface", surface)
        .put("harness", harness == null ? null : harness.name())
        .put("model", model)
        .put("effort", effort)
        .put("permissionMode", permissionMode == null ? null : permissionMode.name())
        .put("remoteControl", remoteControl)
        .put("remoteControlName", remoteControlName)
        .put("activityTracking", activityTracking)
        .put("mcpServers", servers)
        .put("externalMcpServers", new JsonArray(externalMcpServers))
        .put("configured", configured)
        .put("notes", new JsonArray(notes))
        .encode();
  }
}
