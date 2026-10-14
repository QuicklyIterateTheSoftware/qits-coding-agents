package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The work-item affordances (qits-1204): the {@code MessageDisplay} work-links hook, the work-items
 * status line and the footer links, rendered into the interactive launch's {@code --settings} layer
 * and nowhere else. The settings are parsed back out of the rendered command, so what is asserted is
 * what the shell would hand the harness.
 */
class ClaudeWorkLinksTest {

  private static final String REPORT_URL =
      "http://127.0.0.1:13337/hooks/claude-code?commandId=00000000-0000-0000-0000-000000000001";

  private static CodingAgent claude() {
    return CodingAgentFactory.ofType(AgentType.CLAUDE);
  }

  /**
   * The {@code --settings} argument of {@code script}, un-shell-quoted and parsed, or null when the
   * script has none. Reads POSIX single-quoting the way a shell does: {@code '…'} literal, {@code \'}
   * outside quotes a quote, a space ending the word.
   */
  static JsonObject settingsOf(String script) {
    int flag = script.indexOf(" --settings ");
    if (flag < 0) {
      return null;
    }
    StringBuilder word = new StringBuilder();
    int i = flag + " --settings ".length();
    boolean quoted = false;
    for (; i < script.length(); i++) {
      char c = script.charAt(i);
      if (quoted) {
        if (c == '\'') {
          quoted = false;
        } else {
          word.append(c);
        }
      } else if (c == '\'') {
        quoted = true;
      } else if (c == '\\' && i + 1 < script.length()) {
        word.append(script.charAt(++i));
      } else if (c == ' ') {
        break;
      } else {
        word.append(c);
      }
    }
    return new JsonObject(word.toString());
  }

  @Test
  void theInteractiveLaunchCarriesTheHookTheStatusLineAndOneFooterLinkPerSlug() {
    String script =
        claude()
            .sessionReporting(REPORT_URL)
            .workLinks(true, List.of("qits", "acme"), "example.test")
            .start()
            .script();
    JsonObject settings = settingsOf(script);

    assertNotNull(settings, script);
    JsonObject hooks = settings.getJsonObject("hooks");
    assertTrue(hooks.containsKey("SessionStart"), "merged into the session-report hooks");
    JsonObject hook =
        hooks
            .getJsonArray("MessageDisplay")
            .getJsonObject(0)
            .getJsonArray("hooks")
            .getJsonObject(0);
    assertEquals(
        new JsonObject()
            .put("type", "command")
            .put("command", "qits agents claude hook work-links")
            .put("timeout", 5),
        hook);
    assertEquals(
        new JsonObject()
            .put("type", "command")
            .put("command", "qits agents claude statusline work-items")
            .put("refreshInterval", 30),
        settings.getJsonObject("statusLine"));

    JsonArray footer = settings.getJsonArray("footerLinksRegexes");
    assertEquals(2, footer.size());
    assertEquals(
        new JsonObject()
            .put("type", "regex")
            .put("pattern", "(?<![\\w/.-])(?<key>qits-\\d+)(?![\\w-])")
            .put("url", "https://qits.example.test/projects/qits/work/detail/{key}")
            .put("label", "{key}"),
        footer.getJsonObject(0));
    assertEquals(
        "https://qits.example.test/projects/acme/work/detail/{key}",
        footer.getJsonObject(1).getString("url"));
    assertEquals(
        "(?<![\\w/.-])(?<key>acme-\\d+)(?![\\w-])", footer.getJsonObject(1).getString("pattern"));
  }

  @Test
  void theFooterPatternMatchesAKeyAndNotAPathOrALongerWord() {
    String pattern =
        settingsOf(claude().workLinks(true, List.of("qits"), "example.test").start().script())
            .getJsonArray("footerLinksRegexes")
            .getJsonObject(0)
            .getString("pattern");
    // Java regex agrees with JavaScript on everything this pattern uses.
    java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);

    java.util.regex.Matcher match = compiled.matcher("see qits-1204, then");
    assertTrue(match.find());
    assertEquals("qits-1204", match.group("key"));
    assertFalse(compiled.matcher("/projects/qits-1204").find());
    assertFalse(compiled.matcher("myqits-1204").find());
    assertFalse(compiled.matcher("qits-1204-b").find());
  }

  @Test
  void blankAndRepeatedSlugsAreDropped() {
    JsonObject settings =
        settingsOf(
            claude()
                .workLinks(true, Arrays.asList("qits", " ", null, "qits", ""), "example.test")
                .start()
                .script());

    assertEquals(1, settings.getJsonArray("footerLinksRegexes").size());
  }

  @Test
  void noSlugsLeavesTheFooterOutButKeepsTheHookAndStatusLine() {
    JsonObject settings =
        settingsOf(claude().workLinks(true, List.of(), "example.test").start().script());

    assertFalse(settings.containsKey("footerLinksRegexes"));
    assertTrue(settings.getJsonObject("hooks").containsKey("MessageDisplay"));
    assertTrue(settings.containsKey("statusLine"));
  }

  @Test
  void noDomainLeavesTheFooterOutButKeepsTheHookAndStatusLine() {
    for (String domain : Arrays.asList(null, "", "  ")) {
      JsonObject settings =
          settingsOf(claude().workLinks(true, List.of("qits"), domain).start().script());

      assertFalse(settings.containsKey("footerLinksRegexes"), String.valueOf(domain));
      assertTrue(settings.getJsonObject("hooks").containsKey("MessageDisplay"));
      assertTrue(settings.containsKey("statusLine"));
    }
  }

  @Test
  void withoutSessionReportingTheSettingsHoldOnlyTheWorkLinks() {
    JsonObject settings =
        settingsOf(claude().workLinks(true, List.of("qits"), "example.test").start().script());

    assertNotNull(settings);
    assertEquals(List.of("hooks", "statusLine", "footerLinksRegexes"), List.copyOf(settings.fieldNames()));
    assertEquals(List.of("MessageDisplay"), List.copyOf(settings.getJsonObject("hooks").fieldNames()));
  }

  @Test
  void offRendersExactlyWhatTheInteractiveLaunchRenderedBefore() {
    String before = claude().sessionReporting(REPORT_URL).activityTracking(true).start().script();
    String off =
        claude()
            .sessionReporting(REPORT_URL)
            .activityTracking(true)
            .workLinks(false, List.of("qits"), "example.test")
            .start()
            .script();

    assertEquals(before, off);
    assertEquals(
        "exec claude", claude().workLinks(false, List.of("qits"), "example.test").start().script());
  }

  @Test
  void aOneOffRunAndAChatCarryNoneOfIt() {
    String runBefore = claude().sessionReporting(REPORT_URL).run("hi").script();
    String chatBefore = claude().sessionReporting(REPORT_URL).chat().script();

    String run =
        claude()
            .sessionReporting(REPORT_URL)
            .workLinks(true, List.of("qits"), "example.test")
            .run("hi")
            .script();
    String chat =
        claude()
            .sessionReporting(REPORT_URL)
            .workLinks(true, List.of("qits"), "example.test")
            .chat()
            .script();

    assertEquals(runBefore, run);
    assertEquals(chatBefore, chat);
    for (String script : List.of(run, chat)) {
      JsonObject settings = settingsOf(script);
      assertFalse(settings.getJsonObject("hooks").containsKey("MessageDisplay"), script);
      assertFalse(settings.containsKey("statusLine"), script);
      assertFalse(settings.containsKey("footerLinksRegexes"), script);
    }
    // And with nothing else to put in a settings layer, there is no layer at all.
    assertNull(
        settingsOf(claude().workLinks(true, List.of("qits"), "example.test").chat().script()));
    assertNull(
        settingsOf(claude().workLinks(true, List.of("qits"), "example.test").run("hi").script()));
  }

  @Test
  void aQuoteInASlugOrTheDomainCannotLeaveTheArgument() {
    String script =
        claude().workLinks(true, List.of("it's"), "ex'; rm -rf / #.test").start().script();

    // The whole layer is still one word, and it round-trips intact.
    JsonObject footer = settingsOf(script).getJsonArray("footerLinksRegexes").getJsonObject(0);
    assertEquals("https://qits.ex'; rm -rf / #.test/projects/it's/work/detail/{key}", footer.getString("url"));
    assertEquals("(?<![\\w/.-])(?<key>it's-\\d+)(?![\\w-])", footer.getString("pattern"));
    assertTrue(script.endsWith("'"), script);
  }

  @Test
  void regexMetacharactersInASlugAreEscapedForJavaScript() {
    assertEquals("a\\.b\\+c-d", ClaudeCodeAgent.escapeJsRegex("a.b+c-d"));
    assertEquals("\\(x\\)\\[y\\]\\{z\\}\\|\\^\\$\\*\\?\\/\\\\", ClaudeCodeAgent.escapeJsRegex("(x)[y]{z}|^$*?/\\"));
    assertEquals("qits", ClaudeCodeAgent.escapeJsRegex("qits"));
  }

  private static AgentDefaults defaultsFor(String entityId) {
    return new AgentDefaults() {
      @Override
      public AgentType defaultAgentType() {
        return AgentType.CLAUDE;
      }

      @Override
      public boolean activityTrackingEnabled() {
        return false;
      }

      @Override
      public java.util.Optional<String> refinementModel() {
        return java.util.Optional.empty();
      }

      @Override
      public java.util.Optional<String> entityId() {
        return java.util.Optional.ofNullable(entityId);
      }
    };
  }

  @Test
  void theDefaultsLinkTheEntitysOwnProject() {
    assertEquals(List.of("qits"), defaultsFor("qits-1204").workLinkProjects());
    assertEquals(List.of("my-proj"), defaultsFor("my-proj-12").workLinkProjects());
    assertEquals(List.of(), defaultsFor(null).workLinkProjects());
    assertEquals(List.of(), defaultsFor("qits").workLinkProjects());
    assertEquals(List.of(), defaultsFor("Qits-12").workLinkProjects());
    assertEquals(List.of(), defaultsFor("-12").workLinkProjects());
    assertTrue(defaultsFor(null).workLinksEnabled());
    assertTrue(defaultsFor(null).platformDomain().isEmpty());
  }
}
