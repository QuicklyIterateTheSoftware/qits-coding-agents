package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An agent's own config checkout (qits-1382), rendered into a Claude launch: its memory and plugin
 * set merged into the <em>one</em> {@code --settings} layer, the checkout as a {@code --plugin-dir},
 * and the skills sentence on the system prompt. The settings are parsed back out of the rendered
 * command with {@link ClaudeWorkLinksTest#settingsOf}, so what is asserted is what the shell hands
 * the harness.
 */
class ClaudeAgentConfigTest {

  private static final String REPORT_URL =
      "http://127.0.0.1:13337/hooks/claude-code?commandId=00000000-0000-0000-0000-000000000001";

  private static final String SESSION = "00000000-0000-0000-0000-0000000000aa";

  private static final Path DIR = Path.of("/agent-config/agent-1");

  private static final String SENTENCE =
      "Skills you write for every later agent go in /agent-config/agent-1/skills/<name>/SKILL.md;"
          + " they are reviewed before they are shared.";

  private static final JsonObject PLUGINS =
      new JsonObject()
          .put("a@claude-plugins-official", true)
          .put("b@claude-plugins-official", false);

  /** A chat configured with every flag the settings layer sits among. */
  private static CodingAgent configured(AgentType type) {
    return CodingAgentFactory.ofType(type)
        .sessionId(SESSION)
        .model("haiku")
        .appendSystemPrompt("steer")
        .sessionReporting(REPORT_URL)
        .skipPermissions();
  }

  private static int occurrences(String haystack, String needle) {
    return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
  }

  @Test
  void withoutADirTheCommandIsByteIdenticalToBefore() {
    String hooks =
        "{\"hooks\":{\"SessionStart\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"curl -fsS"
            + " -m 5 -X POST -H \\\"Content-Type: application/json\\\" --data-binary @- "
            + REPORT_URL
            + "\"}]}]},\"attribution\":{\"commit\":\"\",\"pr\":\"\",\"sessionUrl\":false}}";

    assertEquals(
        "exec claude --print --input-format stream-json --output-format stream-json"
            + " --include-hook-events --verbose --session-id "
            + SESSION
            + " --model 'haiku' --append-system-prompt 'steer' --settings '"
            + hooks
            + "' --dangerously-skip-permissions",
        configured(AgentType.CLAUDE).chat().script());
    assertEquals(
        configured(AgentType.CLAUDE).chat().script(),
        configured(AgentType.CLAUDE).agentConfig(null, PLUGINS).chat().script(),
        "a null dir renders nothing of it");
  }

  @Test
  void aDirMergesIntoTheOneSettingsLayerBesideTheHooks() {
    String script = configured(AgentType.CLAUDE).agentConfig(DIR, PLUGINS).chat().script();

    assertEquals(1, occurrences(script, " --settings "), "the CLI keeps only the last: " + script);
    JsonObject settings = ClaudeWorkLinksTest.settingsOf(script);
    assertNotNull(settings, script);
    assertTrue(settings.getJsonObject("hooks").containsKey("SessionStart"), script);
    assertEquals("/agent-config/agent-1/memory", settings.getString("autoMemoryDirectory"));
    assertEquals(PLUGINS, settings.getJsonObject("enabledPlugins"));
    assertTrue(
        script.contains(" --plugin-dir '/agent-config/agent-1' --dangerously-skip-permissions"),
        script);
    assertTrue(
        script.contains(" --append-system-prompt 'steer\n\n" + SENTENCE.replace("'", "'\\''") + "'"),
        script);
  }

  @Test
  void everyLaunchTurnsOffClaudesAttributionInItsOneSettingsLayer() {
    // qits-1666: no Co-Authored-By or Claude-Session trailer, no PR line. One --settings only, since
    // the CLI keeps the last.
    JsonObject off = new JsonObject(ClaudeCodeAgent.NO_ATTRIBUTION).getJsonObject("attribution");
    for (String script :
        List.of(
            configured(AgentType.CLAUDE).agentConfig(DIR, PLUGINS).chat().script(),
            configured(AgentType.CLAUDE).start().script(),
            configured(AgentType.CLAUDE).run("go").script(),
            CodingAgentFactory.ofType(AgentType.CLAUDE).start().script())) {
      assertEquals(1, occurrences(script, " --settings "), script);
      assertEquals(off, ClaudeWorkLinksTest.settingsOf(script).getJsonObject("attribution"), script);
    }
  }

  @Test
  void withoutHooksTheDirStillRendersASettingsLayer() {
    String script =
        CodingAgentFactory.ofType(AgentType.CLAUDE).agentConfig(DIR, PLUGINS).chat().script();

    assertEquals(1, occurrences(script, " --settings "), script);
    JsonObject settings = ClaudeWorkLinksTest.settingsOf(script);
    assertEquals(
        new JsonObject()
            .put("autoMemoryDirectory", "/agent-config/agent-1/memory")
            .put("enabledPlugins", PLUGINS)
            .put("attribution", new JsonObject(ClaudeCodeAgent.NO_ATTRIBUTION).getJsonObject("attribution")),
        settings);
    // No configured prompt: the sentence is the whole appendix.
    assertTrue(
        script.contains(" --append-system-prompt '" + SENTENCE.replace("'", "'\\''") + "' "),
        script);
    assertTrue(script.contains(" --plugin-dir '/agent-config/agent-1'"), script);
  }

  @Test
  void theInteractiveWorkLinksShareTheSameLayer() {
    String script =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .sessionReporting(REPORT_URL)
            .workLinks(true, List.of("qits"), "example.test")
            .agentConfig(DIR, PLUGINS)
            .start()
            .script();

    assertEquals(1, occurrences(script, " --settings "), script);
    JsonObject settings = ClaudeWorkLinksTest.settingsOf(script);
    assertTrue(settings.getJsonObject("hooks").containsKey("MessageDisplay"), script);
    assertTrue(settings.containsKey("statusLine"), script);
    assertEquals("/agent-config/agent-1/memory", settings.getString("autoMemoryDirectory"));
    assertEquals(PLUGINS, settings.getJsonObject("enabledPlugins"));
  }

  @Test
  void kimiIgnoresTheDirEntirely() {
    java.util.function.Supplier<CodingAgent> kimi =
        () ->
            CodingAgentFactory.ofType(AgentType.KIMI)
                .model("haiku")
                .appendSystemPrompt("steer")
                .sessionReporting(REPORT_URL)
                .skipPermissions();

    assertEquals(
        kimi.get().start().script(), kimi.get().agentConfig(DIR, PLUGINS).start().script());
    assertEquals(
        kimi.get().run("go").script(), kimi.get().agentConfig(DIR, PLUGINS).run("go").script());
    assertEquals(kimi.get().chat().script(), kimi.get().agentConfig(DIR, PLUGINS).chat().script());
    CodingAgent plain = kimi.get();
    CodingAgent withDir = kimi.get().agentConfig(DIR, PLUGINS);
    plain.start();
    withDir.start();
    assertEquals(plain.renderNotes(), withDir.renderNotes(), "not even a note");
  }
}
