package eu.wohlben.qits.agents;

import static eu.wohlben.qits.agents.McpServers.httpMcp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Pure-renderer tests for the coding-agent builder — no CDI, just the shell it produces. */
public class CodingAgentFactoryTest {

  private static final String ACTIONS_URL =
      "http://localhost:8080/actions/mcp?repositoryId=11111111-1111-1111-1111-111111111111";

  /** Every launch ends with the settings that turn off Claude's attribution (qits-1666). */
  private static final String NA = ClaudeCodeAgent.noAttributionFlag();

  /**
   * {@link ClaudeCodeAgent#processEnv} is a static seam, shared with every other test in the JVM —
   * restore it to the real process environment after every test here, whether or not the test body
   * touched it, so a left-over stub never leaks into an unrelated test.
   */
  @AfterEach
  public void restoreProcessEnvironment() {
    ClaudeCodeAgent.processEnv = System::getenv;
  }

  @Test
  public void interactiveWithoutMcpJustExecsClaude() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).start();

    assertEquals("exec claude" + NA, spec.script());
    assertTrue(spec.interactive());
  }

  @Test
  public void flatOutputAddsTheScreenReaderFlag() {
    // Opt-in (off by default); renders flat text so a captured PTY session log stays readable.
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).flatOutput().start();

    assertEquals("exec claude --ax-screen-reader" + NA, spec.script());
  }

  @Test
  public void interactiveWithMcpRendersStrictConfigAndAllowlist() {
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .mcpServer("actions", httpMcp(ACTIONS_URL))
            .allowedTools(
                List.of("mcp__actions__listGlobalActions", "mcp__actions__getGlobalAction"))
            .start();

    String cmd = spec.script();
    assertTrue(cmd.startsWith("exec claude "), cmd);
    assertTrue(cmd.contains("--strict-mcp-config"), cmd);
    assertTrue(
        cmd.contains(
            "--mcp-config '{\"mcpServers\":{\"actions\":{\"type\":\"http\",\"url\":\""
                + ACTIONS_URL
                + "\"}}}'"),
        cmd);
    assertTrue(
        cmd.contains(
            "--allowedTools 'mcp__actions__listGlobalActions,mcp__actions__getGlobalAction'"),
        cmd);
  }

  @Test
  public void interactiveWithInitialContextEmbedsTheSeedDirectly() {
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .initialContext("please look at the failing test")
            .start();

    assertEquals("exec claude 'please look at the failing test'" + NA, spec.script());
  }

  @Test
  public void runRendersPrintModeWithTheEmbeddedPrompt() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).run("resolve the conflict");

    assertEquals("claude -p 'resolve the conflict'" + NA, spec.script());
    assertFalse(spec.interactive());
  }

  @Test
  public void promptWithShellMetacharactersStaysASingleArgument() {
    // An untrusted prompt (e.g. built from attacker-controlled commit messages) full of shell
    // metacharacters: a single quote, a command separator, a command substitution and backticks.
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).run("a'b`c$(d);e");

    // POSIX single-quoting neutralizes all of them: everything is literal inside '…', and the one
    // embedded ' is escaped as '\'' so it can't close the quote early. The whole prompt is one arg
    // —
    // no separator runs, no substitution expands.
    assertEquals("claude -p 'a'\\''b`c$(d);e'" + NA, spec.script());
  }

  @Test
  public void modelFlagRendersOnEveryLaunchVariant() {
    assertEquals(
        "claude -p 'hi' --model 'haiku'" + NA,
        CodingAgentFactory.ofType(AgentType.CLAUDE).model("haiku").run("hi").script());
    assertEquals(
        "exec claude --model 'haiku'" + NA,
        CodingAgentFactory.ofType(AgentType.CLAUDE).model("haiku").start().script());
    assertTrue(
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .model("haiku")
            .chat()
            .script()
            .endsWith(" --model 'haiku'" + NA));
  }

  @Test
  public void modelFlagIsAbsentWhenUnset() {
    assertFalse(CodingAgentFactory.ofType(AgentType.CLAUDE).run("hi").script().contains("--model"));
  }

  @Test
  public void chatRendersTheStreamJsonProtocol() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).chat();

    assertEquals(
        "exec claude --print --input-format stream-json --output-format stream-json"
            + " --include-hook-events --verbose"
            + NA,
        spec.script());
    assertFalse(spec.interactive());
  }

  @Test
  public void skipPermissionsAddsTheAutonomousFlag() {
    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).skipPermissions().run("go");

    assertTrue(spec.script().endsWith(" --dangerously-skip-permissions"), spec.script());
  }

  private static final String SESSION_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SESSION_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REPORT_URL =
      "http://qits:8080/api/commands/cccccccc-cccc-cccc-cccc-cccccccccccc/agent-session";

  @Test
  public void sessionIdPinsAFreshSessionOnEveryLaunchVariant() {
    assertEquals(
        "exec claude --session-id " + SESSION_A + NA,
        CodingAgentFactory.ofType(AgentType.CLAUDE).sessionId(SESSION_A).start().script());
    assertEquals(
        "claude -p 'hi' --session-id " + SESSION_A + NA,
        CodingAgentFactory.ofType(AgentType.CLAUDE).sessionId(SESSION_A).run("hi").script());
    assertTrue(
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .sessionId(SESSION_A)
            .chat()
            .script()
            .endsWith(" --session-id " + SESSION_A + NA));
  }

  @Test
  public void resumeContinuesTheSessionInPlace() {
    assertEquals(
        "exec claude --resume " + SESSION_A + NA,
        CodingAgentFactory.ofType(AgentType.CLAUDE).resume(SESSION_A).start().script());
  }

  @Test
  public void resumeWithForkBranchesIntoTheNewPinnedId() {
    assertEquals(
        "exec claude --resume " + SESSION_A + " --fork-session --session-id " + SESSION_B + NA,
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .resume(SESSION_A)
            .fork(SESSION_B)
            .start()
            .script());
  }

  @Test
  public void forkWithoutResumeThrows() {
    CodingAgent agent = CodingAgentFactory.ofType(AgentType.CLAUDE).fork(SESSION_B);

    assertThrows(IllegalStateException.class, agent::start);
  }

  @Test
  public void pinAndResumeWithoutForkThrows() {
    // A pinned id is create-only; combining it with resume is contradictory unless forking.
    CodingAgent agent =
        CodingAgentFactory.ofType(AgentType.CLAUDE).sessionId(SESSION_B).resume(SESSION_A);

    assertThrows(IllegalStateException.class, agent::start);
  }

  @Test
  public void nonUuidSessionIdsAreRejected() {
    // The ids are interpolated into an argv, so only canonical UUIDs may pass.
    assertThrows(
        IllegalArgumentException.class,
        () -> CodingAgentFactory.ofType(AgentType.CLAUDE).sessionId("$(reboot)").start());
    assertThrows(
        IllegalArgumentException.class,
        () -> CodingAgentFactory.ofType(AgentType.CLAUDE).resume("1-2-3-4-5").start());
  }

  @Test
  public void sessionReportingRendersASessionStartHookSettingsLayer() {
    String cmd =
        CodingAgentFactory.ofType(AgentType.CLAUDE).sessionReporting(REPORT_URL).chat().script();

    // One --settings layer whose SessionStart hook POSTs the hook's stdin JSON to the endpoint.
    assertTrue(cmd.contains(" --settings '"), cmd);
    assertTrue(cmd.contains("\"SessionStart\""), cmd);
    assertTrue(
        cmd.contains(
            "curl -fsS -m 5 -X POST -H \\\"Content-Type: application/json\\\" --data-binary @- "
                + REPORT_URL),
        cmd);
    // Without activity tracking, only SessionStart is wired (lineage) — no turn-boundary events.
    assertFalse(cmd.contains("\"UserPromptSubmit\""), cmd);
    assertFalse(cmd.contains("\"Stop\""), cmd);
    assertFalse(cmd.contains("\"Notification\""), cmd);
    assertFalse(cmd.contains("\"SessionEnd\""), cmd);
  }

  @Test
  public void activityTrackingAddsTheTurnBoundaryHooks() {
    String cmd =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .sessionReporting(REPORT_URL)
            .activityTracking(true)
            .chat()
            .script();

    // SessionStart stays (lineage) and the four activity events are added.
    assertTrue(cmd.contains("\"SessionStart\""), cmd);
    assertTrue(cmd.contains("\"UserPromptSubmit\""), cmd);
    assertTrue(cmd.contains("\"Stop\""), cmd);
    assertTrue(cmd.contains("\"Notification\""), cmd);
    assertTrue(cmd.contains("\"SessionEnd\""), cmd);
  }

  @Test
  public void sessionReportingUrlWithAQuoteIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CodingAgentFactory.ofType(AgentType.CLAUDE)
                .sessionReporting("http://qits:8080/a'b")
                .chat());
  }

  @Test
  public void transcriptLocatorsFollowTheProjectsConvention() {
    CodingAgent agent = CodingAgentFactory.ofType(AgentType.CLAUDE);

    // cwd /workspace escapes to -workspace (every non-alphanumeric character becomes a dash).
    assertEquals(
        Path.of("projects", "-workspace", SESSION_A + ".jsonl"),
        agent.transcriptPath("/workspace", SESSION_A));
    assertEquals(
        Path.of("projects", "-workspace", SESSION_A, "subagents"),
        agent.subagentsDir("/workspace", SESSION_A));
  }

  @Test
  public void multipleMcpServersMergeIntoOneConfigAndOneAllowlist() {
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .mcpServer("actions", httpMcp(ACTIONS_URL))
            .allowedTools(List.of("mcp__actions__listGlobalActions"))
            .mcpServer("repository", httpMcp("http://localhost:8080/projects/mcp?projectId=p"))
            .allowedTools(List.of("mcp__repository__listBranches"))
            .start();

    String cmd = spec.script();
    // Both servers appear in one mcp-config object (insertion order)...
    assertTrue(cmd.contains("\"actions\":{"), cmd);
    assertTrue(cmd.contains("\"repository\":{"), cmd);
    // ...and every allowlist entry is joined into a single --allowedTools argument.
    assertTrue(
        cmd.contains(
            "--allowedTools 'mcp__actions__listGlobalActions,mcp__repository__listBranches'"),
        cmd);
  }

  // --- the qits-egress forward proxy (qits-2658) ---------------------------------------------------

  @Test
  public void egressProxyEnvRequiresBothNonBlankHalves() {
    // Pins the process-env fallback to empty, so this exercises only the overlay-map half of
    // resolveEnv — the container this suite happens to run in (an agent container) carries both
    // keys for real, which the process-env fallback tests below cover on their own terms.
    ClaudeCodeAgent.processEnv = key -> null;

    assertTrue(ClaudeCodeAgent.egressProxyEnv(Map.of()).isEmpty());
    assertTrue(ClaudeCodeAgent.egressProxyEnv(Map.of("QITS_EGRESS_PROXY", "p")).isEmpty());
    assertTrue(ClaudeCodeAgent.egressProxyEnv(Map.of("QITS_TOKEN", "t")).isEmpty());
    assertTrue(
        ClaudeCodeAgent.egressProxyEnv(Map.of("QITS_EGRESS_PROXY", " ", "QITS_TOKEN", "t"))
            .isEmpty());
    assertTrue(
        ClaudeCodeAgent.egressProxyEnv(Map.of("QITS_EGRESS_PROXY", "p", "QITS_TOKEN", " "))
            .isEmpty());

    Map<String, String> env =
        ClaudeCodeAgent.egressProxyEnv(Map.of("QITS_EGRESS_PROXY", "p", "QITS_TOKEN", "t"));
    assertEquals(
        Map.of(
            "HTTPS_PROXY", "http://agent:t@p",
            "https_proxy", "http://agent:t@p",
            "HTTP_PROXY", "http://agent:t@p",
            "http_proxy", "http://agent:t@p"),
        env);
  }

  @Test
  public void urlEncodeEscapesEverythingButTheUnreservedCharacters() {
    assertEquals("abcXYZ019-_.~", ClaudeCodeAgent.urlEncode("abcXYZ019-_.~"));
    // A token can hold base64 ('+', '/', '='), a literal '@' or ':', or a space — none of which are
    // safe, unescaped, inside a URL's userinfo component.
    assertEquals("a%2Bb%2Fc%3Dd%40e%3Af%20g", ClaudeCodeAgent.urlEncode("a+b/c=d@e:f g"));
  }

  @Test
  public void startRunAndChatAllRouteThroughTheEgressProxyWhenBothHalvesArePresent() {
    String url = "http://agent:a%2Bb@127.0.0.1:3128";

    LaunchSpec started =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .environment("QITS_EGRESS_PROXY", "127.0.0.1:3128")
            .environment("QITS_TOKEN", "a+b")
            .start();
    LaunchSpec ran =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .environment("QITS_EGRESS_PROXY", "127.0.0.1:3128")
            .environment("QITS_TOKEN", "a+b")
            .run("hi");
    LaunchSpec chatted =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .environment("QITS_EGRESS_PROXY", "127.0.0.1:3128")
            .environment("QITS_TOKEN", "a+b")
            .chat();

    for (LaunchSpec spec : List.of(started, ran, chatted)) {
      assertEquals(url, spec.environment().get("HTTPS_PROXY"), spec.script());
      assertEquals(url, spec.environment().get("https_proxy"), spec.script());
      assertEquals(url, spec.environment().get("HTTP_PROXY"), spec.script());
      assertEquals(url, spec.environment().get("http_proxy"), spec.script());
    }
  }

  @Test
  public void theProxyUrlCarriesWhicheverTokenWasSetLast() {
    // Mirrors the launch-time guarantee (AgentLaunchServiceProjectHostTest's EnvironmentOverlay
    // suite): a value applied to the environment overlay after an earlier one wins, so a per-agent
    // token laid over a container's own replaces it here too, not just in the MCP bearer.
    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .environment("QITS_EGRESS_PROXY", "proxy:1")
            .environment("QITS_TOKEN", "containerToken")
            .environment("QITS_TOKEN", "overlaidToken")
            .start();

    assertEquals("http://agent:overlaidToken@proxy:1", spec.environment().get("HTTPS_PROXY"));
  }

  @Test
  public void withoutTheEgressProxyNothingChanges() {
    // Pins the process-env fallback to empty: this container the suite happens to run in carries
    // both keys for real (qits-2658's own live bug), so without this the "no proxy anywhere" premise
    // below would not hold. The process-env fallback has its own tests, above.
    ClaudeCodeAgent.processEnv = key -> null;

    // No QITS_EGRESS_PROXY at all: a launch overlaid with only a token (every launch before
    // qits-2658) gets no proxy env vars and no settings key — neither half of either feature.
    LaunchSpec tokenOnly =
        CodingAgentFactory.ofType(AgentType.CLAUDE).environment("QITS_TOKEN", "t").start();
    assertFalse(tokenOnly.environment().containsKey("HTTPS_PROXY"));
    assertFalse(tokenOnly.script().contains("skipWebFetchPreflight"), tokenOnly.script());

    // A proxy with no token to act as: still no proxy env vars (nothing to authenticate it with),
    // but skipWebFetchPreflight depends only on QITS_EGRESS_PROXY, so it is still added.
    LaunchSpec proxyOnly =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .environment("QITS_EGRESS_PROXY", "p")
            .run("hi");
    assertFalse(proxyOnly.environment().containsKey("HTTP_PROXY"));
    assertTrue(proxyOnly.script().contains("skipWebFetchPreflight"), proxyOnly.script());

    assertFalse(
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .environment("QITS_TOKEN", "t")
            .chat()
            .script()
            .contains("skipWebFetchPreflight"));
  }

  @Test
  public void settingsCarrySkipWebFetchPreflightOnlyWithTheEgressProxySet() {
    String withProxy =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .environment("QITS_EGRESS_PROXY", "proxy:3128")
            .run("hi")
            .script();
    assertTrue(withProxy.contains("\"skipWebFetchPreflight\":true"), withProxy);

    String blankProxy =
        CodingAgentFactory.ofType(AgentType.CLAUDE)
            .environment("QITS_EGRESS_PROXY", " ")
            .run("hi")
            .script();
    assertFalse(blankProxy.contains("skipWebFetchPreflight"), blankProxy);

    String withoutProxy = CodingAgentFactory.ofType(AgentType.CLAUDE).run("hi").script();
    assertFalse(withoutProxy.contains("skipWebFetchPreflight"), withoutProxy);
  }

  // --- process-environment fallback (qits-2658 live bug) ------------------------------------------
  //
  // The image init hook (50-egress.sh) exports QITS_EGRESS_PROXY straight into the daemon's own
  // process environment, never into a launch's `environment` overlay map — and a project agent's or
  // a desk's QITS_TOKEN is likewise only ever the container's own process-env value, never repeated
  // as an overlay entry. These tests drive ClaudeCodeAgent.processEnv directly rather than the real
  // environment, so they do not depend on (or risk polluting) the test JVM's actual process env.

  @Test
  public void egressProxyFallsBackToTheProcessEnvironmentForTheProxyAddressOnly() {
    // (a) QITS_EGRESS_PROXY only in the process env, QITS_TOKEN only in the overlay map: the proxy
    // URL carries the map's token, and skipWebFetchPreflight is still added.
    ClaudeCodeAgent.processEnv = Map.of("QITS_EGRESS_PROXY", "127.0.0.1:3128")::get;

    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE).environment("QITS_TOKEN", "overlaidToken").start();

    assertEquals(
        "http://agent:overlaidToken@127.0.0.1:3128", spec.environment().get("HTTPS_PROXY"));
    assertTrue(spec.script().contains("\"skipWebFetchPreflight\":true"), spec.script());
  }

  @Test
  public void egressProxyFallsBackToTheProcessEnvironmentForBothHalves() {
    // (b) Both QITS_EGRESS_PROXY and QITS_TOKEN only in the process env (a project agent or a desk,
    // whose token never repeats in the overlay map): the proxy URL carries the process token.
    ClaudeCodeAgent.processEnv =
        Map.of("QITS_EGRESS_PROXY", "127.0.0.1:3128", "QITS_TOKEN", "processToken")::get;

    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).start();

    assertEquals(
        "http://agent:processToken@127.0.0.1:3128", spec.environment().get("HTTPS_PROXY"));
    assertTrue(spec.script().contains("\"skipWebFetchPreflight\":true"), spec.script());
  }

  @Test
  public void egressProxyStaysEmptyWhenNeitherKeyIsAnywhere() {
    // (c) Neither key in the overlay map nor in the process env: nothing changes, as before the fix.
    ClaudeCodeAgent.processEnv = key -> null;

    LaunchSpec spec = CodingAgentFactory.ofType(AgentType.CLAUDE).start();

    assertFalse(spec.environment().containsKey("HTTPS_PROXY"));
    assertFalse(spec.script().contains("skipWebFetchPreflight"), spec.script());
  }

  @Test
  public void theOverlaidTokenStillWinsOverATokenOnlyInTheProcessEnvironment() {
    // The process env is a fallback, never an override: a workspace's per-agent QITS_TOKEN, applied
    // as an overlay entry, must beat the container's own token sitting underneath it in the process
    // environment — exactly as it already did when both of those lived only in the overlay map.
    ClaudeCodeAgent.processEnv =
        Map.of("QITS_EGRESS_PROXY", "127.0.0.1:3128", "QITS_TOKEN", "containerToken")::get;

    LaunchSpec spec =
        CodingAgentFactory.ofType(AgentType.CLAUDE).environment("QITS_TOKEN", "overlaidToken").start();

    assertEquals(
        "http://agent:overlaidToken@127.0.0.1:3128", spec.environment().get("HTTPS_PROXY"));
  }
}
