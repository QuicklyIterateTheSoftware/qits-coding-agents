package eu.wohlben.qits.agents;

import eu.wohlben.qits.agents.json.Json;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Installs and lists Claude Code plugins on the shared credential volume.
 *
 * <p>Two things changed by moving inside the container. The install still shells out — {@code claude
 * plugin install} is the only supported way to do it — but the <em>listing</em> does not: it was
 * {@code docker exec … cat <mount>/.claude/settings.json}, and a file on a locally mounted volume is
 * just a file. And the workspace ids the host validated and turned into a container name are gone;
 * there is one container and this is it.
 *
 * <p>Installing is per volume, enabling is per agent. An agent with its own config checkout keeps
 * its own {@code settings.json} there, and only its {@code enabledPlugins} matters: {@link
 * #enableFor} installs onto the shared volume and enables in the agent's file, {@link #listFor}
 * reads it back, and {@link #launchEnabledPlugins} is what a launch renders from it.
 */
public final class AgentPluginService {

  /** The marketplace plugins are installed from. */
  static final String MARKETPLACE = "claude-plugins-official";

  private static final Pattern PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private final ProcessRunner processes;
  private final String claudeMount;
  private final Path workspaceRoot;
  private final AgentDefaults defaults;

  public AgentPluginService(
      ProcessRunner processes, String claudeMount, Path workspaceRoot, AgentDefaults defaults) {
    this.processes = processes;
    this.claudeMount = claudeMount;
    this.workspaceRoot = workspaceRoot;
    this.defaults = defaults;
  }

  /**
   * The plugins recorded in the shared volume's {@code settings.json}. An absent file (nothing ever
   * installed) reads as an empty list, not an error.
   */
  public List<InstalledPluginDto> listInstalled() {
    requireClaude();
    return installed();
  }

  /**
   * Installs {@code pluginId} from the official marketplace onto the shared volume ({@code claude
   * plugin install <id>@claude-plugins-official} with {@code HOME} on the mount). Idempotent — the
   * CLI no-ops an already-installed plugin. Returns the refreshed installed set so the caller can
   * reflect the flip without a second round trip.
   */
  public List<InstalledPluginDto> install(String pluginId) {
    requireClaude();
    if (pluginId == null || !PLUGIN_ID.matcher(pluginId).matches()) {
      throw new InvalidCommandRequestException("Invalid plugin id: " + pluginId);
    }
    ProcessRunner.Result result =
        processes.exec(
            List.of("claude", "plugin", "install", pluginId + "@" + MARKETPLACE),
            workspaceRoot,
            Map.of("HOME", claudeMount),
            TIMEOUT);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "Failed to install plugin " + pluginId + ": " + result.output());
    }
    return installed();
  }

  /**
   * The plugins recorded in an agent config checkout's {@code settings.json} — {@link
   * #listInstalled}, for one agent rather than the shared volume. An absent file is an empty list.
   */
  public List<InstalledPluginDto> listFor(Path agentConfigDir) {
    requireClaude();
    return parseEnabledPlugins(readOrNull(agentSettingsPath(agentConfigDir)));
  }

  /**
   * Installs {@code pluginId} onto the shared volume, as {@link #install} does, then enables it for
   * one agent: {@code "<id>@claude-plugins-official": true} in {@code
   * <agentConfigDir>/settings.json}'s {@code enabledPlugins}, created if missing, every other entry
   * and key kept. Returns that agent's refreshed list.
   *
   * <p>A settings file that is there but is not a JSON object is refused rather than overwritten:
   * reading it as empty is the right leniency for a listing, and the wrong one for a write that
   * would replace whatever it held.
   */
  public List<InstalledPluginDto> enableFor(Path agentConfigDir, String pluginId) {
    install(pluginId);
    Path path = agentSettingsPath(agentConfigDir);
    String raw = readOrNull(path);
    JsonObject settings;
    if (raw == null || raw.isBlank()) {
      settings = new JsonObject();
    } else if (Json.parse(raw).raw() instanceof JsonObject parsed) {
      settings = parsed;
    } else {
      throw new IllegalStateException("Not a JSON object, refusing to overwrite: " + path);
    }
    if (!(settings.getValue("enabledPlugins") instanceof JsonObject)) {
      settings.put("enabledPlugins", new JsonObject());
    }
    settings.getJsonObject("enabledPlugins").put(pluginId + "@" + MARKETPLACE, true);
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, settings.encodePrettily() + "\n");
    } catch (IOException e) {
      throw new IllegalStateException("Failed to write " + path, e);
    }
    return listFor(agentConfigDir);
  }

  /**
   * The ids of the plugins installed on the shared volume — the keys of {@code plugins} in its
   * {@code installed_plugins.json} ({@code code-review@claude-plugins-official}). Empty when the
   * file is absent or malformed. A pure read of the volume, so it is not gated on the default
   * harness, like {@link #launchEnabledPlugins}.
   */
  public Set<String> installedIds() {
    Json plugins = Json.parse(readOrNull(installedPluginsPath())).path("plugins");
    return new LinkedHashSet<>(plugins.fields().keySet());
  }

  /**
   * The {@code enabledPlugins} object a launch with an agent config checkout renders into its
   * {@code --settings}: the agent's own {@code enabledPlugins}, plus {@code false} for every plugin
   * the shared volume's {@code settings.json} names and the agent's does not. The overrides are what
   * make the agent's file the whole answer — flag settings outrank user settings key by key, so a
   * plugin left out would stay enabled from the shared file. Missing or malformed files read as
   * {@code {}}, and keys other than {@code enabledPlugins} are ignored.
   *
   * @param claudeMount where the shared credential volume mounts, or null/blank for none
   */
  public static JsonObject launchEnabledPlugins(String claudeMount, Path agentConfigDir) {
    JsonObject merged = enabledPluginsOf(readOrNull(agentSettingsPath(agentConfigDir)));
    if (claudeMount != null && !claudeMount.isBlank()) {
      JsonObject shared = enabledPluginsOf(readOrNull(sharedSettingsPath(claudeMount)));
      for (String id : shared.fieldNames()) {
        if (!merged.containsKey(id)) {
          merged.put(id, false);
        }
      }
    }
    return merged;
  }

  /**
   * Reads and parses {@code enabledPlugins} out of the shared volume's {@code settings.json}. A
   * local read: the volume is mounted into this container, so there is no process to fork.
   */
  private List<InstalledPluginDto> installed() {
    // No settings.json on the volume yet (nothing ever installed) — not an error, just empty.
    return parseEnabledPlugins(readOrNull(settingsPath()));
  }

  /** The shared volume's Claude Code settings file. */
  Path settingsPath() {
    return sharedSettingsPath(claudeMount);
  }

  /** The shared volume's record of what {@code claude plugin install} installed. */
  Path installedPluginsPath() {
    return Path.of(claudeMount, ".claude", "plugins", "installed_plugins.json");
  }

  private static Path sharedSettingsPath(String claudeMount) {
    return Path.of(claudeMount, ".claude", "settings.json");
  }

  /** An agent config checkout's settings file, of which only {@code enabledPlugins} is read. */
  static Path agentSettingsPath(Path agentConfigDir) {
    return agentConfigDir.resolve("settings.json");
  }

  /** The file's content, or null when it cannot be read — absent reads as nothing, not an error. */
  private static String readOrNull(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      return null;
    }
  }

  /** The {@code enabledPlugins} object of a settings document, or a fresh empty one. */
  private static JsonObject enabledPluginsOf(String json) {
    return Json.parse(json).path("enabledPlugins").raw() instanceof JsonObject enabled
        ? enabled.copy()
        : new JsonObject();
  }

  private void requireClaude() {
    if (defaults.defaultAgentType() != AgentType.CLAUDE) {
      throw new InvalidCommandRequestException("LSP plugins are only supported by Claude Code");
    }
  }

  /**
   * Parses the {@code enabledPlugins} object of a Claude Code {@code settings.json}: each key is a
   * marketplace-qualified plugin id ({@code jdtls-lsp@claude-plugins-official}) and each value a
   * boolean ({@code true} = enabled, {@code false} = installed-but-disabled). An id absent from the
   * object is not installed and produces no entry. Any missing object / malformed JSON reads as an
   * empty list — the file's exact schema is undocumented, so tolerate shape drift rather than 500.
   * Static + package-visible so it can be unit-tested without a volume.
   */
  static List<InstalledPluginDto> parseEnabledPlugins(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    Json enabled = Json.parse(json).path("enabledPlugins");
    if (!enabled.isObject()) {
      return List.of();
    }
    List<InstalledPluginDto> result = new ArrayList<>();
    enabled
        .fields()
        .forEach((key, value) -> result.add(new InstalledPluginDto(key, value.asBoolean(false))));
    return result;
  }
}
