package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.acp.AcpSessionConfig;
import eu.wohlben.qits.commands.AgentLaunchMetadata;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.AgentSessionSource;
import eu.wohlben.qits.commands.ChatProtocolFactory;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandExitListener;
import eu.wohlben.qits.commands.CommandKind;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.CheckoutContext;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launch hub as <b>the workspace daemon</b> drives it, against a recording {@link AgentCommands}
 * and {@link WorkspaceHostMcpServers}.
 *
 * <p>These were {@code @QuarkusTest}s with twelve injected beans and a database. What is worth
 * keeping from them is the half that still exists: the MCP scope narrowing, the read-only marking and
 * allowlists, the credential overlay, the session lineage, and the auth redirect. Those translate
 * directly.
 *
 * <p>The twin of {@link AgentLaunchServiceProjectHostTest}: two copies of the class under test
 * became one, and the move is only behaviour-free if both hosts' renderings still hold.
 */
class AgentLaunchServiceWorkspaceHostTest {

  private static final String REPO = "11111111-1111-1111-1111-111111111111";
  private static final String PROJECT = "22222222-2222-2222-2222-222222222222";
  private static final String WORKSPACE = "feature-x";
  private static final String CLAUDE_MOUNT = "/claude-home";
  private static final int HOOKS_PORT = 13337;
  private static final String KIMI_SESSION = "session_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @TempDir Path workspaceRoot;

  private Commands commands;
  private boolean loggedIn;
  private AgentType defaultType;
  private boolean activityTracking;

  /**
   * What this container was created with. {@link AgentSurfaceConfigurations#shipped()} is a
   * container born before the configuration document existed, which is what every test but the
   * equivalence suite runs as.
   */
  private AgentSurfaceConfigurations configurations;

  @BeforeEach
  void setUp() {
    commands = new Commands();
    loggedIn = true;
    defaultType = AgentType.CLAUDE;
    activityTracking = true;
    configurations = AgentSurfaceConfigurations.shipped();
  }

  // --- fakes ------------------------------------------------------------------------------------

  /** Records every launch instead of spawning one. */
  private static final class Commands implements AgentCommands {
    private final List<Launch> launches = new ArrayList<>();
    private final Map<String, String> ownedSessions = new HashMap<>();
    private final List<String> chatSends = new ArrayList<>();
    private final List<String> keystrokes = new ArrayList<>();

    private record Launch(
        String name,
        String script,
        boolean interactive,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef session,
        ChatProtocolFactory protocolFactory,
        AgentLaunchMetadata agent,
        CommandKind kind) {}

    private Launch last() {
      return launches.get(launches.size() - 1);
    }

    private Command record(Launch launch) {
      launches.add(launch);
      return Command.running(
          launch.commandId() == null ? "generated" : launch.commandId(),
          launch.kind(),
          "main",
          "abc1234",
          null,
          launch.name(),
          launch.script(),
          launch.interactive(),
          launch.agent().agentType(),
          launch.agent().agentSurface(),
          launch.agent().launchRecord(),
          Instant.now());
    }

    @Override
    public Command launchAgent(
        String name,
        String script,
        boolean interactive,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef agentSession,
        CommandExitListener onExit,
        AgentLaunchMetadata agent) {
      return record(
          new Launch(
              name,
              script,
              interactive,
              environment,
              commandId,
              agentSession,
              null,
              agent,
              CommandKind.TERMINAL));
    }

    @Override
    public Command launchChat(
        String name,
        String script,
        Map<String, String> environment,
        String commandId,
        AgentSessionRef agentSession,
        CommandExitListener onExit,
        ChatProtocolFactory protocolFactory,
        AgentLaunchMetadata agent) {
      return record(
          new Launch(
              name,
              script,
              false,
              environment,
              commandId,
              agentSession,
              protocolFactory,
              agent,
              CommandKind.CHAT));
    }

    @Override
    public boolean chatSend(String commandId, String text) {
      chatSends.add(text);
      return true;
    }

    @Override
    public boolean sendKeystrokes(String commandId, String text) {
      keystrokes.add(text);
      return true;
    }

    @Override
    public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {}

    @Override
    public boolean ownsSession(String sessionId) {
      return ownedSessions.containsKey(sessionId);
    }

    @Override
    public Optional<String> agentTypeForSession(String sessionId) {
      return Optional.ofNullable(ownedSessions.get(sessionId));
    }
  }

  private static final CheckoutContext CHECKOUT_CONTEXT =
      new CheckoutContext() {
        @Override
        public String branch() {
          return "feature/x";
        }

        @Override
        public String commitHash() {
          return "abc1234";
        }
      };

  /** This daemon's servers: the central {@code qits} server, plus the image's browser. */
  private static final AgentMcpServers MCP_SERVERS = new WorkspaceHostMcpServers();

  private AgentLaunchService service() {
    return serviceWith(MCP_SERVERS);
  }

  private AgentLaunchService serviceWith(AgentMcpServers mcpServers) {
    ProcessRunner probe =
        (command, cwd, env, timeout) ->
            new ProcessRunner.Result(loggedIn ? 0 : 1, loggedIn ? "" : "signed out", "", false);
    AgentDefaults defaults =
        new AgentDefaults() {
          @Override
          public AgentType defaultAgentType() {
            return defaultType;
          }

          @Override
          public boolean activityTrackingEnabled() {
            return activityTracking;
          }

          @Override
          public Optional<String> refinementModel() {
            return Optional.empty();
          }

          @Override
          public AgentSurfaceConfigurations surfaceConfigurations() {
            return configurations;
          }
        };
    CommandStore store = new CommandStore();
    AgentTranscriptService transcripts =
        new AgentTranscriptService(
            store,
            new CommandLogService(store, null),
            new AgentSessionStore(),
            workspaceRoot.toString(),
            null);
    return new AgentLaunchService(
        commands,
        new AgentAuthStatus(probe, CLAUDE_MOUNT, workspaceRoot),
        transcripts,
        new AgentTranscriptTailService(transcripts, new CommandLogService(store, null)),
        defaults,
        mcpServers,
        CHECKOUT_CONTEXT,
        CLAUDE_MOUNT,
        HOOKS_PORT);
  }

  private static AgentLaunchRequest chat(AgentMcpScope scope) {
    // The surface is required now, so the scope-only helper names one. Every surface this host
    // serves steers with nothing, so every launch here renders exactly what it rendered before the
    // axis existed — which is the point these tests are making.
    return chat(scope, AgentSurface.WORKSPACE_CHAT);
  }

  private static AgentLaunchRequest chat(AgentMcpScope scope, AgentSurface surface) {
    return new AgentLaunchRequest(
        scope, surface, AgentLaunchMode.CHAT, null, null, false, false, null);
  }

  // --- MCP scoping ------------------------------------------------------------------------------

  @Nested
  class McpScoping {

    @Test
    void theOnlyPlatformServerIsQitsBesideTheBrowser() {
      // qits-1174: the per-service servers are retired.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script =
          service
              .renderChat(
                  AgentMcpScope.ACTIONS, AgentSurface.WORKSPACE_CHAT, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(script.contains("\"qits\":{\"type\":\"http\""), script);
      assertTrue(script.contains("\"browser\":{\"type\":\"stdio\""), script);
      assertFalse(script.contains("\"repository\":"), script);
      assertFalse(script.contains("\"actions\":"), script);
      assertFalse(script.contains("\"observability\":"), script);
    }
  }

  // --- rendering --------------------------------------------------------------------------------

  @Nested
  class Rendering {

    @Test
    void aChatCarriesTheScopedServerTheHomeOverlayAndTheHook() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec = service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE);

      assertTrue(spec.script().contains("--input-format stream-json"));
      assertTrue(spec.script().contains(WorkspaceHostMcpServers.PLATFORM_URL), spec.script());
      assertTrue(spec.script().contains("--dangerously-skip-permissions"));
      assertEquals(CLAUDE_MOUNT, spec.environment().get("HOME"), "the one credential that crosses in");
      assertTrue(
          spec.script().contains("127.0.0.1:" + HOOKS_PORT + "/hooks/claude-code?commandId="),
          spec.script());
    }

    @Test
    void theHookUrlUsesTheInjectedPortRatherThanASecondConfigRead() {
      AgentLaunchService service =
          new AgentLaunchService(
              commands, null, null, null, null, MCP_SERVERS, CHECKOUT_CONTEXT, CLAUDE_MOUNT, 24680);

      assertEquals(
          "http://127.0.0.1:24680/hooks/claude-code?commandId=c1", service.sessionReportUrl("c1"));
    }

    @Test
    void anInteractiveLaunchEmbedsTheSeedAndRendersTheRepl() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec =
          service.renderInteractive(
              AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, "do the thing", pinned, AgentType.CLAUDE);

      assertTrue(spec.script().startsWith("exec claude 'do the thing'"), spec.script());
      assertTrue(spec.interactive());
    }

    @Test
    void aClaudeLaunchStartsTheImagesBrowserAndPreApprovesIt() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      for (LaunchSpec spec :
          List.of(
              service.renderChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE),
              service.renderAutonomousChat(
                  AgentMcpScope.ACTIONS, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE))) {
        assertTrue(
            spec.script().contains("\"browser\":{\"type\":\"stdio\",\"command\":\"qits-browser-mcp\"}"),
            spec.script());
        assertTrue(spec.script().contains("mcp__browser__*"), spec.script());
      }
    }

    @Test
    void theBrowserIsInTheLaunchRecord() {
      service().launchChat(chat(AgentMcpScope.REPOSITORY));

      assertTrue(
          commands.last().agent().launchRecord().contains("{\"server\":\"browser\",\"readOnly\":false}"),
          commands.last().agent().launchRecord());
    }

    @Test
    void kimiTakesNoHomeOverlay() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      LaunchSpec spec = service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.KIMI);

      assertFalse(
          spec.environment().containsKey("HOME"), "Kimi reads KIMI_CODE_HOME, set container-wide");
      assertEquals("exec kimi acp", spec.script());
    }

    @Test
    void activityTrackingOffStillWiresTheLineageHook() {
      activityTracking = false;
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script = service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_WORK, pinned, AgentType.CLAUDE).script();

      assertTrue(script.contains("\"SessionStart\""), "lineage is not optional");
      assertFalse(script.contains("\"UserPromptSubmit\""), "the turn-boundary hooks are");
    }

    @Test
    void theLoginTerminalOverlaysTheRightHomeForEachHarness() {
      assertEquals(CLAUDE_MOUNT, service().renderLogin(AgentType.CLAUDE).environment().get("HOME"));
      assertEquals("exec claude", service().renderLogin(AgentType.CLAUDE).script());
      assertEquals(
          CLAUDE_MOUNT + "/.kimi-code",
          service().renderLogin(AgentType.KIMI).environment().get("KIMI_CODE_HOME"));
      assertEquals("exec kimi login", service().renderLogin(AgentType.KIMI).script());
    }
  }

  // --- session lineage --------------------------------------------------------------------------

  @Nested
  class Lineage {

    @Test
    void aFreshClaudeLaunchPinsANewSession() {
      AgentLaunchService.PinnedSession pinned = service().pinSession(null, false, AgentType.CLAUDE);

      assertNotNull(pinned.ref());
      assertEquals(AgentSessionSource.PINNED, pinned.ref().source());
      assertNotNull(pinned.commandId());
    }

    @Test
    void aFreshKimiLaunchPinsNothingBecauseItCannot() {
      AgentLaunchService.PinnedSession pinned = service().pinSession(null, false, AgentType.KIMI);

      assertNull(pinned.ref(), "the id arrives later, on the SessionStart hook");
      assertNotNull(pinned.commandId());
    }

    @Test
    void resumeOfASessionFromAPreviousContainerIsRefused() {
      // THE behaviour change of this move. CommandStore does not outlive the container, so it
      // cannot vouch for a session an earlier one drove. It fails closed: refused, not allowed.
      AgentLaunchService service = service();

      InvalidCommandRequestException refused =
          assertThrows(
              InvalidCommandRequestException.class,
              () ->
                  service.pinSession(
                      "33333333-3333-3333-3333-333333333333", false, AgentType.CLAUDE));

      assertTrue(refused.getMessage().contains("not started in this container"), refused.getMessage());
    }

    @Test
    void resumeOfAKnownSessionContinuesItInPlace() {
      commands.ownedSessions.put("33333333-3333-3333-3333-333333333333", "CLAUDE");

      AgentLaunchService.PinnedSession pinned =
          service().pinSession("33333333-3333-3333-3333-333333333333", false, AgentType.CLAUDE);

      assertEquals(AgentSessionSource.RESUMED, pinned.ref().source());
      assertEquals("33333333-3333-3333-3333-333333333333", pinned.ref().sessionId());
    }

    @Test
    void aForkBranchesIntoAFreshIdAndRecordsItsOrigin() {
      commands.ownedSessions.put("33333333-3333-3333-3333-333333333333", "CLAUDE");

      AgentLaunchService.PinnedSession pinned =
          service().pinSession("33333333-3333-3333-3333-333333333333", true, AgentType.CLAUDE);

      assertEquals(AgentSessionSource.FORKED, pinned.ref().source());
      assertEquals("33333333-3333-3333-3333-333333333333", pinned.ref().forkedFromSessionId());
      assertFalse(pinned.ref().sessionId().equals("33333333-3333-3333-3333-333333333333"));
    }

    @Test
    void kimiRefusesToFork() {
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");

      assertThrows(
          InvalidCommandRequestException.class,
          () -> service().pinSession(KIMI_SESSION, true, AgentType.KIMI));
    }

    @Test
    void aResumeKeepsTheResumedSessionsHarnessOverTheDefault() {
      // You cannot resume a Kimi session under Claude: the transcript layout and auth probe differ.
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");
      defaultType = AgentType.CLAUDE;

      Command command =
          service()
              .launchChat(
                  new AgentLaunchRequest(
                      AgentMcpScope.REPOSITORY,
                      AgentSurface.WORKSPACE_CHAT,
                      AgentLaunchMode.CHAT,
                      null,
                      KIMI_SESSION,
                      false,
                      false,
                      AgentType.CLAUDE));

      assertEquals("KIMI", commands.last().agent().agentType(), "the session's harness wins");
      assertNotNull(command);
    }

    @Test
    void aMalformedSessionIdIsARequestError() {
      commands.ownedSessions.put("not-a-uuid", "CLAUDE");

      assertThrows(
          InvalidCommandRequestException.class,
          () -> service().pinSession("not-a-uuid", false, AgentType.CLAUDE));
    }
  }

  // --- launch flow ------------------------------------------------------------------------------

  @Nested
  class Flow {

    @Test
    void aChatLaunchNamesItselfAfterTheScopeAndHarness() {
      service().launchChat(chat(AgentMcpScope.ACTIONS));

      assertEquals("Claude Code", commands.last().name());
      assertEquals(CommandKind.CHAT, commands.last().kind());
      assertEquals("CLAUDE", commands.last().agent().agentType());
    }

    @Test
    void theFourSurfacesThisContainerServesAreToldApartOnlyByTheKeyTheyReport() {
      // The heart of it, on the daemon where it matters: epic.chat and workspace.chat send
      // byte-identical requests and render a byte-identical command with a byte-identical name.
      // Before the surface travelled, nothing downstream could tell which of them it was serving —
      // so one configuration could not be given to an epic's chat without giving it to every ad-hoc
      // workspace chat as well.
      AgentLaunchService service = service();
      // One pinned identity for both, so the only thing that could differ is the surface: the
      // session id and the hook url are per-launch and would otherwise mask the comparison.
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      assertEquals(
          service
              .renderChat(AgentMcpScope.REPOSITORY, AgentSurface.EPIC_CHAT, pinned, AgentType.CLAUDE)
              .script(),
          service
              .renderChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, pinned, AgentType.CLAUDE)
              .script(),
          "identical today, deliberately");

      Command epic = service.launchChat(chat(AgentMcpScope.REPOSITORY, AgentSurface.EPIC_CHAT));
      Command workspace =
          service.launchChat(chat(AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT));

      assertEquals(epic.actionName(), workspace.actionName(), "and identically named");
      assertEquals("epic.chat", epic.agentSurface());
      assertEquals("workspace.chat", workspace.agentSurface());
    }

    @Test
    void anInteractiveLaunchReportsItsAgentTabSurface() {
      Command command =
          service()
              .launch(
                  new AgentLaunchRequest(
                      AgentMcpScope.REPOSITORY,
                      AgentSurface.WORKSPACE_AGENT,
                      AgentLaunchMode.INTERACTIVE,
                      null,
                      null,
                      false,
                      false,
                      null));

      assertEquals("workspace.agent", command.agentSurface());
      assertEquals(
          "Claude Code terminal",
          command.actionName(),
          "the name does not move with the surface: the frontend still matches it");
    }

    @Test
    void everyChatCarriesItsHarnessTransport() {
      defaultType = AgentType.KIMI;
      service().launchChat(chat(AgentMcpScope.REPOSITORY));
      assertNotNull(commands.last().protocolFactory(), "Kimi has no stdin chat mode");

      defaultType = AgentType.CLAUDE;
      service().launchChat(chat(AgentMcpScope.REPOSITORY));
      assertNotNull(
          commands.last().protocolFactory(),
          "Claude's stream-json transport is supplied too — it carries the Remote Control enable");
    }

    @Test
    void aSeedIsDeliveredAsTheFirstUserTurn() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.WORKSPACE_CHAT,
                  AgentLaunchMode.CHAT,
                  "start here",
                  null,
                  false,
                  false,
                  null));

      assertEquals(List.of("start here"), commands.chatSends);
    }

    @Test
    void deliverTaskPromptSeedsTheBootstrapInsteadOfTheLiteral() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.WORKSPACE_CHAT,
                  AgentLaunchMode.CHAT,
                  "ignored",
                  null,
                  false,
                  true,
                  null));

      assertEquals(
          List.of(AgentLaunchService.TASK_PROMPT_BOOTSTRAP),
          commands.chatSends,
          "the caller owns the draft now, so its word is taken");
    }

    @Test
    void aBlankSeedSendsNothing() {
      service()
          .launchChat(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.WORKSPACE_CHAT,
                  AgentLaunchMode.CHAT,
                  "   ",
                  null,
                  false,
                  false,
                  null));

      assertTrue(commands.chatSends.isEmpty());
    }

    @Test
    void aSignedOutAgentIsRefusedRatherThanSwappedForALoginTerminal() {
      // THE behaviour this feature removes. The three launch paths used to answer launchLogin's
      // bare REPL, and the caller attached to it exactly as it would to a real session: you asked
      // for a chat about an epic, got a login terminal, and nothing in the answer said so.
      loggedIn = false;

      AgentNotSignedInException refused =
          assertThrows(
              AgentNotSignedInException.class, () -> service().launchChat(chat(AgentMcpScope.REPOSITORY)));

      assertEquals(AgentType.CLAUDE, refused.harness());
      assertTrue(refused.getMessage().contains("Claude Code"), refused.getMessage());
      assertTrue(refused.getMessage().contains("sign-in terminal"), "it names where to sign in");
      assertTrue(commands.launches.isEmpty(), "and nothing at all was launched");
    }

    @Test
    void theUnattendedPathsFailLoudlyRatherThanStartingAPromptNobodyWatches() {
      // A dispatch that "started an agent" which is actually a login prompt is green-while-dead:
      // the caller reports success, the container sits at a sign-in screen, the work never happens.
      loggedIn = false;

      assertThrows(
          AgentNotSignedInException.class, () -> service().launchAutonomous("Composed run"));
      assertThrows(
          AgentNotSignedInException.class,
          () ->
              service()
                  .launch(
                      new AgentLaunchRequest(
                          AgentMcpScope.REPOSITORY,
                          AgentSurface.WORKSPACE_AGENT,
                          AgentLaunchMode.INTERACTIVE,
                          null,
                          null,
                          false,
                          false,
                          null)));
      assertTrue(commands.launches.isEmpty());
    }

    @Test
    void theLoginTerminalStaysReachableOnItsOwn() {
      // Somebody has to complete the OAuth once per credential volume. What went is the
      // substitution, not the door.
      loggedIn = false;

      Command login = service().launchLogin(AgentType.CLAUDE);

      assertEquals("Claude sign-in", login.actionName());
      assertEquals(CommandKind.TERMINAL, login.kind());
      assertTrue(login.interactive(), "the operator finishes OAuth over a real PTY");
      assertTrue(commands.chatSends.isEmpty(), "nothing is seeded into a login terminal");
    }

    @Test
    void anAutonomousRunAlwaysSeedsTheBootstrap() {
      service().launchAutonomous("Resolve conflicts");

      assertEquals("Resolve conflicts", commands.last().name());
      assertEquals(List.of(AgentLaunchService.TASK_PROMPT_BOOTSTRAP), commands.chatSends);
    }

    @Test
    void anInteractiveLaunchIsATerminalCommand() {
      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.WORKSPACE_AGENT,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  null,
                  false,
                  false,
                  null));

      assertEquals(CommandKind.TERMINAL, commands.last().kind());
      assertEquals("Claude Code terminal", commands.last().name());
    }

    @Test
    void launchDefaultsToChatWhenNoModeIsGiven() {
      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY,
                  AgentSurface.WORKSPACE_CHAT,
                  null,
                  null,
                  null,
                  false,
                  false,
                  null));

      assertEquals(CommandKind.CHAT, commands.last().kind());
    }

    @Test
    void aMissingScopeAndAForkWithoutAResumeAreRequestErrors() {
      assertThrows(InvalidCommandRequestException.class, () -> service().launch(chat(null)));
      assertThrows(InvalidCommandRequestException.class, () -> service().launch(null));
      assertThrows(
          InvalidCommandRequestException.class,
          () ->
              service()
                  .launch(
                      new AgentLaunchRequest(
                          AgentMcpScope.REPOSITORY,
                          AgentSurface.WORKSPACE_CHAT,
                          AgentLaunchMode.CHAT,
                          null,
                          null,
                          true,
                          false,
                          null)));
    }
  }

  // --- the ACP session config -------------------------------------------------------------------

  @Nested
  class AcpConfig {

    @Test
    void kimiGetsNoQitsServerWithoutFixedHeaders() {
      // Kimi has no headers helper, so without a fixed header the central server could only answer
      // 401 (qits-625).
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config =
          service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, pinned);

      assertEquals("/workspace", config.cwd());
      assertEquals(List.of(), config.mcpServers());
    }

    @Test
    void kimiAttachesNoBrowserBecauseItsSessionCarriesNoStdioServer() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config =
          service.buildAcpSessionConfig(
              AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, pinned);

      assertFalse(
          config.mcpServers().stream().anyMatch(server -> server.name().equals("browser")),
          config.mcpServers().toString());
    }

    @Test
    void aResumedSessionIsCarriedAndAFreshOneIsNot() {
      commands.ownedSessions.put(KIMI_SESSION, "KIMI");
      AgentLaunchService service = service();

      assertEquals(
          KIMI_SESSION,
          service
              .buildAcpSessionConfig(
                  AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, service.pinSession(KIMI_SESSION, false, AgentType.KIMI))
              .resumeSessionId());
      assertNull(
          service
              .buildAcpSessionConfig(
                  AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, service.pinSession(null, false, AgentType.KIMI))
              .resumeSessionId());
    }
  }

  // --- rendering from the configuration -----------------------------------------------------

  /**
   * The same equivalence as {@link AgentLaunchServiceProjectHostTest}'s, on the host where it is
   * hardest: five surfaces, two servers each, a differently ordered pre-approval list, and the four
   * human surfaces seeded identically because they render identically today. A launch from the
   * seeded document is the launch from the constants, byte for byte.
   */
  @Nested
  class ConfiguredRendering {

    /** The one platform server every surface of this host attaches. */
    private static SeededConfigurationDocument withQits(
        SeededConfigurationDocument document, AgentSurface surface) {
      return document.surface(
          surface,
          // Remote control as the store seeds it: on for every chat-shaped surface, off for the two
          // agent tabs. That reads backwards and is what the code does — a chat has bridged over the
          // SDK control channel since the transport learned to ask, and the interactive shape, which
          // is the one the flag was made for, never passed it.
          AgentSurfaceConfigurations.shippedRemoteControl(surface),
          "",
          SeededConfigurationDocument.server("qits", false, false, false, false, List.of()));
    }

    private AgentSurfaceConfigurations seeded() {
      SeededConfigurationDocument document = new SeededConfigurationDocument();
      for (AgentSurface surface :
          List.of(
              AgentSurface.EPIC_CHAT,
              AgentSurface.EPIC_AGENT,
              AgentSurface.WORKSPACE_CHAT,
              AgentSurface.WORKSPACE_AGENT)) {
        withQits(document, surface);
      }
      withQits(document, AgentSurface.TICKET_DISPATCH);
      return document.configurations();
    }

    @Test
    void allFourHumanSurfacesRenderWhatTheConstantsRendered() {
      AgentLaunchService service = service();

      for (AgentSurface surface :
          List.of(
              AgentSurface.EPIC_CHAT,
              AgentSurface.EPIC_AGENT,
              AgentSurface.WORKSPACE_CHAT,
              AgentSurface.WORKSPACE_AGENT)) {
        for (AgentType type : List.of(AgentType.CLAUDE, AgentType.KIMI)) {
          AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, type);

          configurations = AgentSurfaceConfigurations.shipped();
          LaunchSpec chat = service.renderChat(AgentMcpScope.REPOSITORY, surface, pinned, type);
          LaunchSpec interactive =
              service.renderInteractive(AgentMcpScope.REPOSITORY, surface, "seed", pinned, type);
          AcpSessionConfig acp =
              service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, surface, pinned);

          configurations = seeded();

          assertEquals(
              chat.script(),
              service.renderChat(AgentMcpScope.REPOSITORY, surface, pinned, type).script(),
              surface + " chat on " + type);
          assertEquals(
              interactive.script(),
              service
                  .renderInteractive(AgentMcpScope.REPOSITORY, surface, "seed", pinned, type)
                  .script(),
              surface + " interactive on " + type);
          // Kimi carries its servers protocol-native rather than on the command line, so the
          // command-line assertion above would not see a moved server there at all.
          AcpSessionConfig configuredAcp =
              service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, surface, pinned);
          assertEquals(acp.mcpServers().size(), configuredAcp.mcpServers().size());
          for (int i = 0; i < acp.mcpServers().size(); i++) {
            assertEquals(acp.mcpServers().get(i).name(), configuredAcp.mcpServers().get(i).name());
            assertEquals(acp.mcpServers().get(i).url(), configuredAcp.mcpServers().get(i).url());
            assertEquals(
                acp.mcpServers().get(i).enabledTools(),
                configuredAcp.mcpServers().get(i).enabledTools(),
                "enabledTools is Kimi's whole tool surface, not a pre-approval");
          }
        }
      }
    }

    @Test
    void aDispatchedRunIsSteeredToDelegateRatherThanToTypeTheCode() {
      // The other half of the ticket, on this host: ticket.dispatch shipped an empty prompt, so a
      // dispatch wrote everything in the main loop. It is the same constant epic.autonomous gets —
      // one cut workspace or a whole project makes no difference to how the work is divided.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      assertEquals(
          AgentLaunchService.COMPOSED_RUN_PROMPT,
          AgentLaunchService.systemPromptFor(AgentSurface.TICKET_DISPATCH));

      configurations = AgentSurfaceConfigurations.shipped();
      String script =
          service
              .renderAutonomousChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.TICKET_DISPATCH, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(
          script.contains("--append-system-prompt 'You are orchestrating this run rather than"),
          script);
      assertTrue(
          script.contains("Sonnet for mechanical, narrow, well-specified edits"),
          "the model-choice rule is the part a dispatch cannot be told later");
    }

    @Test
    void aSurfaceTheDocumentNeverHeardOfStillLaunches() {
      // The store may not have been told about a surface this daemon knows. It answers the shipped
      // constants — the host's whole mapping — rather than failing a configuration lookup.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = AgentSurfaceConfigurations.shipped();
      String constants =
          service
              .renderChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.EPIC_AGENT, pinned, AgentType.CLAUDE)
              .script();

      configurations =
          new SeededConfigurationDocument()
              .surface(AgentSurface.WORKSPACE_CHAT, true, "")
              .configurations();

      assertEquals(
          constants,
          service
              .renderChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.EPIC_AGENT, pinned, AgentType.CLAUDE)
              .script());
    }
  }

  // --- fixed platform headers from the host (qits-625) -----------------------------------------

  /**
   * A RUNNER workspace reaches every platform service through the edge, so its host hands the
   * library a fixed {@code Authorization} header on each platform server. Everything above runs on a
   * host with none, and renders exactly what it did before the headers existed.
   */
  @Nested
  class FixedPlatformHeaders {

    private static final Map<String, String> BEARER = Map.of("Authorization", "Bearer t");

    private AgentMcpServers runnerHost(Map<String, String> headers) {
      return new WorkspaceHostMcpServers(headers);
    }

    private String claudeScript(AgentMcpServers servers, AgentMcpScope scope) {
      AgentLaunchService service = serviceWith(servers);
      return service
          .renderChat(
              scope,
              AgentSurface.WORKSPACE_CHAT,
              service.pinSession(null, false, AgentType.CLAUDE),
              AgentType.CLAUDE)
          .script();
    }

    @Test
    void claudesQitsEntryCarriesTheHeaderInsteadOfTheHelperAndIsStillPreApproved() {
      String script = claudeScript(runnerHost(BEARER), AgentMcpScope.REPOSITORY);

      assertTrue(
          script.contains(
              "\"qits\":{\"type\":\"http\",\"url\":\""
                  + WorkspaceHostMcpServers.PLATFORM_URL
                  + "\",\"headers\":{\"Authorization\":\"Bearer t\"}}"),
          script);
      assertFalse(script.contains("headersHelper"), script);
      assertTrue(script.contains("--allowedTools 'mcp__qits__*"), script);
    }

    @Test
    void anEmptyHeaderMapRendersExactlyWhatAHostWithoutHeadersRenders() {
      for (AgentMcpScope scope : AgentMcpScope.values()) {
        assertEquals(
            claudeScript(MCP_SERVERS, scope).replaceAll("[0-9a-f-]{36}", "<id>"),
            claudeScript(runnerHost(Map.of()), scope).replaceAll("[0-9a-f-]{36}", "<id>"),
            scope.toString());
      }
    }

    @Test
    void theHeaderKeyOrderIsSortedNotTheMapsIterationOrder() {
      // Map.copyOf's iteration order is salted per JVM, and the rendered command line is asserted
      // as a literal — so the keys render sorted whatever order the host built them in.
      Map<String, String> reversed = new java.util.LinkedHashMap<>();
      reversed.put("X-Qits-Trace", "on");
      reversed.put("Authorization", "Bearer t");

      String script = claudeScript(runnerHost(reversed), AgentMcpScope.REPOSITORY);

      assertTrue(
          script.contains("\"headers\":{\"Authorization\":\"Bearer t\",\"X-Qits-Trace\":\"on\"}"),
          script);
      assertFalse(script.contains("\"X-Qits-Trace\":\"on\",\"Authorization\""), script);
    }

    @Test
    void theBearerIsHandedOverForRedactionRatherThanStored() {
      serviceWith(runnerHost(BEARER)).launchChat(chat(AgentMcpScope.REPOSITORY));
      AgentLaunchMetadata metadata = commands.last().agent();

      assertEquals(List.of("Bearer t"), metadata.redactions());
      assertFalse(metadata.redact(commands.last().script()).contains("Bearer t"));
      assertTrue(commands.last().script().contains("Bearer t"), "the script that RUNS is intact");
    }

    @Test
    void kimisSessionNewCarriesTheHeadersAndTheQitsServer() {
      AgentLaunchService service = serviceWith(runnerHost(BEARER));

      AcpSessionConfig config =
          service.buildAcpSessionConfig(
              AgentMcpScope.REPOSITORY,
              AgentSurface.WORKSPACE_CHAT,
              service.pinSession(null, false, AgentType.KIMI));

      assertEquals(
          List.of("qits"),
          config.mcpServers().stream().map(AcpSessionConfig.AcpMcpServer::name).toList());
      assertEquals(BEARER, config.mcpServers().get(0).headers());
      assertEquals(WorkspaceHostMcpServers.PLATFORM_URL, config.mcpServers().get(0).url());
    }

    @Test
    void kimiLeavesTheQitsServerOutWhenItHasNoHeaders() {
      // Kimi has no headers helper: without a fixed header the central server could only answer
      // 401, so it is not attached at all — the DIRECT host's Kimi session is what it was.
      AgentLaunchService service = serviceWith(MCP_SERVERS);

      AcpSessionConfig config =
          service.buildAcpSessionConfig(
              AgentMcpScope.PROJECT,
              AgentSurface.WORKSPACE_CHAT,
              service.pinSession(null, false, AgentType.KIMI));

      assertEquals(List.of(), config.mcpServers());
    }
  }
}
