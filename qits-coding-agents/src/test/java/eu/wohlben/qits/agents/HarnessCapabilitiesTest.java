package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The capability report and its two per-harness probes, <b>against fixtures of what the binaries in
 * today's workspace image actually print</b>.
 *
 * <p>The fixtures are the point of this suite. Parsing a {@code --help} page is brittle by nature,
 * and the failure it invites is silent: a harness upgrade rewords one line, the parser finds
 * nothing, and the editor's effort dropdown is empty with nothing anywhere saying why. Pinning the
 * real output here turns that into a test failure at the image bump, which is where somebody can
 * read it.
 *
 * <p>Verified against Claude Code 2.1.226 and Kimi Code 0.28.1, the versions pinned in the image at
 * the time of writing.
 */
class HarnessCapabilitiesTest {

  /**
   * {@code claude --help}, the region around {@code --effort}, verbatim — including the formatter's
   * line wrap, which is what makes the levels list arrive on a different line from the flag.
   */
  private static final String CLAUDE_HELP =
      """
        --deny-tools, --disallowed-tools <tools...>
            Comma or space-separated list of tool names to deny (e.g. "Bash(git *)
            Edit")
        --effort <level>                      Effort level for the current session
                                              (low, medium, high, xhigh, max)
        --environment <environment_id>        Create a new cloud session that runs on
                                              the given self-hosted environment
                                              (ccpool_...).
        --model <model>                       Model for the current session. Provide
                                              an alias for the latest model (e.g.
                                              'fable', 'opus', or 'sonnet') or a
                                              model's full name (e.g.
                                              'claude-fable-5').
        --remote-control [name]               Start an interactive session with Remote
                                              Control enabled (optionally named)
      """;

  /** {@code claude --version}, verbatim. */
  private static final String CLAUDE_VERSION = "2.1.226 (Claude Code)\n";

  /** {@code kimi --version}, verbatim. */
  private static final String KIMI_VERSION = "0.28.1\n";

  /**
   * {@code kimi provider list --json} on a container whose {@code config.toml} configures nothing —
   * verbatim, and the common case on this platform today.
   */
  private static final String KIMI_NO_PROVIDERS =
      """
      {
        "providers": {},
        "models": {}
      }
      """;

  /**
   * {@code kimi provider list --json} with providers configured. The <em>shape</em> is verbatim from
   * the binary — it prints {@code JSON.stringify({providers: config.providers, models: config.models
   * ?? {}}, null, 2)}, and a model alias is a key of {@code models} whose value names its provider —
   * with plausible entries filled in, because no container of this estate has a provider configured
   * to copy an answer from yet.
   */
  private static final String KIMI_PROVIDERS =
      """
      {
        "providers": {
          "moonshot": {
            "type": "anthropic",
            "baseUrl": "https://api.moonshot.ai/anthropic"
          }
        },
        "models": {
          "k2": {
            "provider": "moonshot",
            "model": "kimi-k2-0905-preview",
            "maxContextSize": 262144
          },
          "k2-turbo": {
            "provider": "moonshot",
            "model": "kimi-k2-turbo-preview",
            "maxContextSize": 262144
          }
        }
      }
      """;

  @Nested
  class Claude {

    @Test
    void theEffortLevelsComeOutOfTheBinarysOwnHelp() {
      assertEquals(
          List.of("low", "medium", "high", "xhigh", "max"),
          ClaudeCodeAgent.parseEffortLevels(CLAUDE_HELP));
    }

    @Test
    void aHelpPageThatNoLongerEnumeratesThemAnswersNothing() {
      // Which the report turns into the shipped list plus probeFailed, rather than an empty
      // dropdown: an empty dropdown is worse than a slightly stale one, and a silent empty one is
      // worst of all.
      assertEquals(
          List.of(),
          ClaudeCodeAgent.parseEffortLevels("  --effort <level>   Effort level for this session\n"));
      assertEquals(List.of(), ClaudeCodeAgent.parseEffortLevels("no such flag here"));
      assertEquals(List.of(), ClaudeCodeAgent.parseEffortLevels(null));
    }

    @Test
    void theVersionIsTheFirstTokenOfTheVersionLine() {
      assertEquals(
          "2.1.226", ClaudeCodeAgent.parseVersion(new ProcessRunner.Result(0, CLAUDE_VERSION, "", false)));
      assertEquals(
          "", ClaudeCodeAgent.parseVersion(new ProcessRunner.Result(1, "", "boom", false)));
    }

    @Test
    void theReportSaysWhatItEnumeratedAndWhatItShipped() {
      HarnessCapabilities report =
          probe(AgentType.CLAUDE, Map.of("claude --help", CLAUDE_HELP, "claude --version", CLAUDE_VERSION));

      assertEquals("2.1.226", report.harnessVersion());
      assertEquals(List.of("low", "medium", "high", "xhigh", "max"), report.effortLevels());
      assertTrue(report.effortSupported());
      assertEquals(List.of("opus", "sonnet", "haiku", "fable"), report.models());
      assertFalse(
          report.modelsEnumerated(),
          "there is no listing command, so the alias set is shipped and the editor must say so");
      assertFalse(report.probeFailed());
    }

    @Test
    void aBinaryThatCannotBeRunStillAnswersAUsableReport() {
      Probes probes = new Probes(Map.of()).failing();

      HarnessCapabilities report =
          new ClaudeCodeAgent().capabilities(probes, Path.of("/workspace"), Map.of());

      assertTrue(report.probeFailed());
      assertTrue(report.probeDetail().contains("exited"), report.probeDetail());
      assertEquals(HarnessCapabilities.CLAUDE_MODELS, report.models());
      assertEquals(HarnessCapabilities.CLAUDE_EFFORT_LEVELS, report.effortLevels());
    }

    @Test
    void theProbeRunsUnderTheSameCredentialHomeALaunchDoes() {
      Probes probes = new Probes(Map.of("claude --help", CLAUDE_HELP, "claude --version", CLAUDE_VERSION));

      new HarnessCapabilityService(probes, null, "/claude-home", Path.of("/workspace"))
          .report(AgentType.CLAUDE);

      assertEquals(Map.of("HOME", "/claude-home"), probes.environments.get(0));
      assertEquals(
          List.of(List.of("claude", "--help"), List.of("claude", "--version")),
          probes.commands,
          "one place, two commands, and this is the list a harness upgrade has to keep answering");
    }
  }

  @Nested
  class Kimi {

    @Test
    void theModelAliasesAreTheKeysOfTheModelsObject() {
      assertEquals(List.of("k2", "k2-turbo"), KimiCodeAgent.parseModelAliases(KIMI_PROVIDERS));
    }

    @Test
    void aContainerWithNoProvidersHasAnEmptyCatalogueRatherThanABrokenProbe() {
      assertEquals(List.of(), KimiCodeAgent.parseModelAliases(KIMI_NO_PROVIDERS));

      HarnessCapabilities report =
          probe(
              AgentType.KIMI,
              Map.of(
                  "kimi provider list --json", KIMI_NO_PROVIDERS, "kimi --version", KIMI_VERSION));

      assertEquals(List.of(), report.models());
      assertTrue(report.modelsEnumerated(), "it answered; there is simply nothing configured");
      assertFalse(report.probeFailed());
      assertEquals("0.28.1", report.harnessVersion());
    }

    @Test
    void outputThatIsNotThatShapeIsAProbeFailure() {
      // Distinguishable from an empty catalogue on purpose: "nothing is configured" and "the command
      // no longer answers what we parse" must not look alike.
      assertEquals(null, KimiCodeAgent.parseModelAliases("Segmentation fault"));
      assertEquals(null, KimiCodeAgent.parseModelAliases("{\"providers\":{}}"));
    }

    @Test
    void itReportsNoEffortConceptRatherThanAnEmptyListOfClaudesLevels() {
      // The absence is the point: the editor shows no effort control for a Kimi surface, not a
      // disabled one carrying the other harness's values.
      HarnessCapabilities report =
          probe(
              AgentType.KIMI,
              Map.of("kimi provider list --json", KIMI_PROVIDERS, "kimi --version", KIMI_VERSION));

      assertFalse(report.effortSupported());
      assertEquals(List.of(), report.effortLevels());
      assertEquals(List.of("k2", "k2-turbo"), report.models());
    }

    @Test
    void nothingIsShippedAsAFallbackBecauseNothingWouldBeTrue() {
      HarnessCapabilities shipped = HarnessCapabilities.shipped(AgentType.KIMI, "no binary");

      assertEquals(List.of(), shipped.models());
      assertFalse(shipped.effortSupported());
      assertTrue(shipped.probeFailed());
    }
  }

  @Nested
  class TheReport {

    @Test
    void authIsFoldedInAndFailsClosed() {
      // Fail-closed, and the same rule the host-side cache keeps: an answer that claimed a harness
      // was signed in because nobody looked would be inventing the one fact the authentication
      // feature exists to stop inventing.
      HarnessCapabilities unchecked =
          new HarnessCapabilityService(
                  new Probes(Map.of("claude --help", CLAUDE_HELP)), null, "", Path.of("/workspace"))
              .report(AgentType.CLAUDE);

      assertFalse(unchecked.authenticated());
      assertTrue(unchecked.authDetail().contains("Nobody has checked"), unchecked.authDetail());
    }

    @Test
    void everyHarnessAnswersSomething() {
      List<HarnessCapabilities> all =
          new HarnessCapabilityService(
                  new Probes(Map.of()).failing(), null, "/claude-home", Path.of("/workspace"))
              .reportAll();

      assertEquals(2, all.size());
      assertEquals(AgentType.CLAUDE, all.get(0).harness());
      assertEquals(AgentType.KIMI, all.get(1).harness());
      assertTrue(all.stream().allMatch(HarnessCapabilities::probeFailed));
    }

    @Test
    void theWireShapeIsTheHostsContract() {
      // Field for field AgentHarnessCapabilityDto in qits-projects-service (commit c6d6b2d), which
      // caches these per (harness, image version) behind the editor's dropdowns. A daemon puts this
      // object straight into the capabilities array of GET /agents/available; the extra members —
      // imageVersion, reportedBy — are the ones only a daemon can name.
      JsonObject wire =
          probe(AgentType.CLAUDE, Map.of("claude --help", CLAUDE_HELP, "claude --version", CLAUDE_VERSION))
              .withAuth(true, "Signed in on the shared credential volume.")
              .toJson();

      assertEquals(
          List.of(
              "harness",
              "harnessVersion",
              "models",
              "modelsEnumerated",
              "effortSupported",
              "effortLevels",
              "authenticated",
              "authDetail",
              "probeFailed",
              "probeDetail"),
          List.copyOf(wire.fieldNames()));
      assertEquals("CLAUDE", wire.getString("harness"));
      assertTrue(wire.getBoolean("authenticated"));
      assertFalse(wire.encode().contains("claude-home"), "a report carries no credential and no path");
    }
  }

  private static HarnessCapabilities probe(AgentType harness, Map<String, String> outputs) {
    return CodingAgentFactory.ofType(harness)
        .capabilities(new Probes(outputs), Path.of("/workspace"), Map.of());
  }

  /** A {@link ProcessRunner} answering scripted output per argv, and recording what it was asked. */
  private static final class Probes implements ProcessRunner {
    private final Map<String, String> outputs;
    private final List<List<String>> commands = new ArrayList<>();
    private final List<Map<String, String>> environments = new ArrayList<>();
    private boolean failing;

    Probes(Map<String, String> outputs) {
      this.outputs = new HashMap<>(outputs);
    }

    Probes failing() {
      this.failing = true;
      return this;
    }

    @Override
    public Result exec(List<String> command, Path cwd, Map<String, String> env, Duration timeout) {
      commands.add(List.copyOf(command));
      environments.add(Map.copyOf(env));
      if (failing) {
        return new Result(127, "", "command not found", false);
      }
      String output = outputs.get(String.join(" ", command));
      return output == null
          ? new Result(1, "", "no fixture for " + command, false)
          : new Result(0, output, "", false);
    }
  }
}
