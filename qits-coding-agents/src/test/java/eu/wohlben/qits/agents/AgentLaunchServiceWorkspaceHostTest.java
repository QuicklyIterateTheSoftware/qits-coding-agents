package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.acp.AcpSessionConfig;
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
        String agentType,
        String agentSurface,
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
          launch.agentType(),
          launch.agentSurface(),
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
        String agentType,
        String agentSurface) {
      return record(
          new Launch(
              name,
              script,
              interactive,
              environment,
              commandId,
              agentSession,
              null,
              agentType,
              agentSurface,
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
        String agentType,
        String agentSurface) {
      return record(
          new Launch(
              name,
              script,
              false,
              environment,
              commandId,
              agentSession,
              protocolFactory,
              agentType,
              agentSurface,
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

  /**
   * Each server on its owning service's segment, as {@code DaemonMcpEndpoints} resolves them — not
   * a {@code /mcp/<server>} family, which no longer exists.
   */
  private static final McpEndpoints ENDPOINTS =
      new McpEndpoints() {
        @Override
        public String mcpUrl(String server) {
          return switch (server) {
            case "repository" -> "http://qits:8080/projects/mcp";
            case "observability" -> "http://qits:8080/observability/mcp";
            case "actions" -> "http://qits:8080/actions/mcp";
            default -> throw new IllegalArgumentException("Unknown MCP server: " + server);
          };
        }

        @Override
        public String projectId() {
          return PROJECT;
        }
      };

  /** This daemon's mapping, narrowed to the repository and workspace the container was cut for. */
  private static final AgentMcpServers MCP_SERVERS = serversWithRepo(REPO);

  /** The same mapping with a different repo id — the id-validation cases. */
  private static AgentMcpServers serversWithRepo(String repoId) {
    return new WorkspaceHostMcpServers(ENDPOINTS, repoId, WORKSPACE);
  }

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
    // No surface named: the shape-implied guess resolves it, and every surface this host serves
    // steers with nothing — so every launch here renders exactly what it rendered before the axis
    // existed.
    return new AgentLaunchRequest(
        scope, null, AgentLaunchMode.CHAT, null, null, false, false, null);
  }

  private static AgentLaunchRequest chat(AgentMcpScope scope, AgentSurface surface) {
    return new AgentLaunchRequest(
        scope, surface, AgentLaunchMode.CHAT, null, null, false, false, null);
  }

  // --- MCP scoping ------------------------------------------------------------------------------

  @Nested
  class McpScoping {

    @Test
    void repositoryScopeNarrowsByProjectRepositoryAndWorkspace() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.REPOSITORY);

      assertEquals(2, servers.size(), "the repository server plus the observability one");
      assertEquals("repository", servers.get(0).key());
      assertEquals(
          "http://qits:8080/projects/mcp?projectId="
              + PROJECT
              + "&repositoryId="
              + REPO
              + "&workspaceId="
              + WORKSPACE,
          servers.get(0).url());
    }

    @Test
    void thePlatformsSlugRepositoryIdsAreValidScopeIds() {
      // Repository ids on this platform are directory-name slugs (qits-stt) — the join key
      // everywhere. Validating them as strict UUIDs 400'd every launch on every real repository:
      // D2's `Invalid repository id: qits-stt`. UUIDs still pass as a subset of the slug grammar.
      List<ScopedMcp> servers =
          serversWithRepo("qits-stt").serversFor(AgentMcpScope.REPOSITORY);

      assertTrue(servers.get(0).url().contains("repositoryId=qits-stt"));
    }

    @Test
    void aScopeIdOutsideTheSlugAlphabetIsStillRefused() {
      // The relaxation is exactly to the platform grammar, no further: these values are
      // interpolated into single-quoted launch args, so a quote, a space, or a leading dash must
      // still refuse the launch.
      assertThrows(
          InvalidCommandRequestException.class,
          () -> serversWithRepo("qits'stt").serversFor(AgentMcpScope.REPOSITORY));
      assertThrows(
          InvalidCommandRequestException.class,
          () -> serversWithRepo("-leading").serversFor(AgentMcpScope.REPOSITORY));
      assertThrows(
          InvalidCommandRequestException.class,
          () -> serversWithRepo("").serversFor(AgentMcpScope.REPOSITORY));
    }

    @Test
    void theObservabilityServerIsSeparateAndCarriesOnlyTheScopesItReads() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.REPOSITORY);

      assertEquals("observability", servers.get(1).key());
      assertEquals(
          "http://qits:8080/observability/mcp?repositoryId=" + REPO + "&workspaceId=" + WORKSPACE,
          servers.get(1).url(),
          "qits-observability reads repositoryId and workspaceId, and has no notion of a project");
    }

    @Test
    void actionsScopePairsTheActionServerWithTheNarrowedRepositoryAndObservabilityServers() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.ACTIONS);

      assertEquals(3, servers.size());
      assertEquals("actions", servers.get(0).key());
      assertEquals("http://qits:8080/actions/mcp?repositoryId=" + REPO, servers.get(0).url());
      assertEquals("repository", servers.get(1).key());
      assertTrue(servers.get(1).url().contains("workspaceId=" + WORKSPACE));
      assertEquals("observability", servers.get(2).key());
    }

    @Test
    void projectScopeDropsTheRepositoryNarrowingAndWithItObservability() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.PROJECT);

      assertEquals(1, servers.size(), "telemetry answers per workspace, so it has nothing to say");
      assertEquals(
          "http://qits:8080/projects/mcp?projectId=" + PROJECT,
          servers.get(0).url(),
          "project scope sees every repository, so it must not carry repositoryId");
    }

    @Test
    void onlyReadOnlyToolsArePreApproved() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.ACTIONS);

      assertTrue(servers.get(0).allowedTools().contains("mcp__actions__listGlobalActions"));
      assertFalse(
          servers.get(0).allowedTools().stream().anyMatch(t -> t.contains("create")),
          "a mutating tool must still prompt");
      assertTrue(servers.get(1).allowedTools().contains("mcp__repository__taskPrompt"));
      assertFalse(
          servers.get(1).allowedTools().stream().anyMatch(t -> t.contains("integrateBranch")));
    }

    @Test
    void theTicketReadsArePreApprovedWithTheOtherRepositoryReads() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.REPOSITORY);

      // get_ticket returns the ticket and its whole comment thread, so the survey is two tools.
      assertTrue(servers.get(0).allowedTools().contains("mcp__repository__list_tickets"));
      assertTrue(servers.get(0).allowedTools().contains("mcp__repository__get_ticket"));
    }

    @Test
    void theEpicReadsArePreApprovedWithTheOtherRepositoryReads() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.REPOSITORY);

      // get_epic returns the whole feature/task tree, so the survey is two tools here too; the epic
      // dispatch's first turn tells the agent to read its epic with them.
      assertTrue(servers.get(0).allowedTools().contains("mcp__repository__list_epics"));
      assertTrue(servers.get(0).allowedTools().contains("mcp__repository__get_epic"));
    }

    @Test
    void theTaskMarkerWriteIsPreApprovedButThePlanEditsAreNot() {
      // mark_task_implemented is a pre-approved write for one caller: the epic dispatch, whose first
      // turn tells the agent to mark each task implemented as it lands. It records a fact about work
      // the agent itself did and moves a marker, not a plan — so the tools that would change the
      // plan stay out and remain a prompted act for Claude, unreachable for kimi.
      for (AgentMcpScope scope : AgentMcpScope.values()) {
        List<String> tools = repositoryServer(MCP_SERVERS.serversFor(scope)).allowedTools();
        assertTrue(tools.contains("mcp__repository__mark_task_implemented"), scope.name());
        assertFalse(tools.contains("mcp__repository__add_task"), scope.name());
        assertFalse(tools.contains("mcp__repository__update_task"), scope.name());
        assertFalse(tools.contains("mcp__repository__remove_task"), scope.name());
        assertFalse(tools.contains("mcp__repository__update_epic"), scope.name());
      }
    }

    @Test
    void theTicketThreadWritesAndTheResolveArePreApprovedAndTheFilingOnesAreNot() {
      // Two deliberate exceptions to "only reads". Commenting is additive and editable, so
      // pre-approving it costs a wrongly-worded note, not a changed plan; the resolve is there for
      // the ticket dispatch, whose first turn tells the agent to close the ticket once its work is
      // released, and it is reversible through the same tool. For kimi, whose enabledTools is a
      // hard set, both are the difference between the tool existing and not.
      // Filing stays out: create_ticket and update_ticket remain a prompted act for Claude and
      // unreachable for kimi.
      // Every scope that wires the repository server, so a branch added later cannot quietly drop
      // them or quietly widen past them.
      for (AgentMcpScope scope : AgentMcpScope.values()) {
        assertTicketPreApproval(repositoryServer(MCP_SERVERS.serversFor(scope)), scope.name());
      }
    }

    private ScopedMcp repositoryServer(
        List<ScopedMcp> servers) {
      return servers.stream()
          .filter(s -> s.key().equals("repository"))
          .findFirst()
          .orElseThrow(() -> new AssertionError("every scope wires the repository server"));
    }

    private void assertTicketPreApproval(ScopedMcp server, String scope) {
      List<String> tools = server.allowedTools();
      assertTrue(tools.contains("mcp__repository__add_ticket_comment"), scope);
      assertTrue(tools.contains("mcp__repository__update_ticket_comment"), scope);
      assertTrue(tools.contains("mcp__repository__transition_ticket"), scope);
      assertFalse(tools.contains("mcp__repository__create_ticket"), scope);
      assertFalse(tools.contains("mcp__repository__update_ticket"), scope);
    }

    @Test
    void theTelemetryToolsArePreApprovedUnderTheServerThatActuallyDeclaresThem() {
      List<ScopedMcp> servers = MCP_SERVERS.serversFor(AgentMcpScope.REPOSITORY);

      assertTrue(
          servers.get(1).allowedTools().contains("mcp__observability__telemetryErrors"),
          "the allowlist names the tool as the agent sees it: mcp__<server>__<tool>");
      assertFalse(
          servers.get(0).allowedTools().stream().anyMatch(t -> t.contains("telemetry")),
          "a telemetry entry on the repository server would allowlist a tool nothing declares");
    }
  }

  // --- rendering --------------------------------------------------------------------------------

  @Nested
  class Rendering {

    @Test
    void aChatCarriesTheScopedServerTheHomeOverlayAndTheHook() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec = service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE);

      assertTrue(spec.script().contains("--input-format stream-json"));
      assertTrue(spec.script().contains("workspaceId=" + WORKSPACE));
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
    void anAutonomousRunMarksEveryServerReadOnly() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec =
          service.renderAutonomousChat(AgentMcpScope.ACTIONS, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE);

      assertEquals(
          3,
          spec.script().split("agentReadOnly=true", -1).length - 1,
          "every server is fenced, or the unattended turn could mutate through MCP");
    }

    @Test
    void anInteractiveLaunchEmbedsTheSeedAndRendersTheRepl() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      LaunchSpec spec =
          service.renderInteractive(
              AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, "do the thing", pinned, AgentType.CLAUDE);

      assertTrue(spec.script().startsWith("exec claude 'do the thing'"), spec.script());
      assertTrue(spec.interactive());
    }

    @Test
    void kimiTakesNoHomeOverlay() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      LaunchSpec spec = service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned, AgentType.KIMI);

      assertFalse(
          spec.environment().containsKey("HOME"), "Kimi reads KIMI_CODE_HOME, set container-wide");
      assertEquals("exec kimi acp", spec.script());
    }

    @Test
    void activityTrackingOffStillWiresTheLineageHook() {
      activityTracking = false;
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      String script = service.renderChat(AgentMcpScope.REPOSITORY, AgentSurface.PROJECT_EPICS, pinned, AgentType.CLAUDE).script();

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
                      null,
                      AgentLaunchMode.CHAT,
                      null,
                      KIMI_SESSION,
                      false,
                      false,
                      AgentType.CLAUDE));

      assertEquals("KIMI", commands.last().agentType(), "the session's harness wins");
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

      assertEquals("Claude Code (actions + repository MCP)", commands.last().name());
      assertEquals(CommandKind.CHAT, commands.last().kind());
      assertEquals("CLAUDE", commands.last().agentType());
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
          "Claude Code terminal (repository MCP)",
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
                  null,
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
                  null,
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
                  null,
                  AgentLaunchMode.CHAT,
                  "   ",
                  null,
                  false,
                  false,
                  null));

      assertTrue(commands.chatSends.isEmpty());
    }

    @Test
    void aSignedOutAgentRedirectsToTheLoginTerminal() {
      loggedIn = false;

      service().launchChat(chat(AgentMcpScope.REPOSITORY));

      assertEquals("Claude sign-in", commands.last().name());
      assertEquals(CommandKind.TERMINAL, commands.last().kind());
      assertTrue(commands.last().interactive(), "the operator finishes OAuth over a real PTY");
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
                  null,
                  AgentLaunchMode.INTERACTIVE,
                  null,
                  null,
                  false,
                  false,
                  null));

      assertEquals(CommandKind.TERMINAL, commands.last().kind());
      assertEquals("Claude Code terminal (repository MCP)", commands.last().name());
    }

    @Test
    void launchDefaultsToChatWhenNoModeIsGiven() {
      service()
          .launch(
              new AgentLaunchRequest(
                  AgentMcpScope.REPOSITORY, null, null, null, null, false, false, null));

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
                          null,
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
    void scopedServersRideSessionNewWithBareToolNames() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config = service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, pinned);

      assertEquals("/workspace", config.cwd());
      assertEquals(2, config.mcpServers().size());
      AcpSessionConfig.AcpMcpServer server = config.mcpServers().get(0);
      assertEquals("repository", server.name());
      assertTrue(
          server.enabledTools().contains("taskPrompt"),
          "kimi takes bare names, not the mcp__server__ form: " + server.enabledTools());
      assertFalse(server.enabledTools().contains("mcp__repository__taskPrompt"));
      assertEquals("observability", config.mcpServers().get(1).name());
      assertTrue(config.mcpServers().get(1).enabledTools().contains("telemetryErrors"));
    }

    @Test
    void theTicketThreadAndTheResolveRideKimisHardEnabledToolsSetButFilingDoesNot() {
      // enabledTools is not a pre-approval for kimi, it is the session's whole tool surface: a name
      // left out does not exist. So the thread pair has to be here for a kimi session to answer on
      // a ticket at all, and transition_ticket has to be here or a dispatched kimi run is told to
      // resolve the ticket with a tool it cannot see — and create/update_ticket being absent is
      // what keeps filing out of reach rather than merely prompted.
      AgentLaunchService service = service();

      AcpSessionConfig config =
          service.buildAcpSessionConfig(
              AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, service.pinSession(null, false, AgentType.KIMI));

      List<String> enabled = config.mcpServers().get(0).enabledTools();
      assertTrue(enabled.contains("list_tickets"), enabled.toString());
      assertTrue(enabled.contains("get_ticket"), enabled.toString());
      assertTrue(enabled.contains("add_ticket_comment"), enabled.toString());
      assertTrue(enabled.contains("update_ticket_comment"), enabled.toString());
      assertTrue(enabled.contains("transition_ticket"), enabled.toString());
      assertFalse(enabled.contains("create_ticket"), enabled.toString());
      assertFalse(enabled.contains("update_ticket"), enabled.toString());
    }

    @Test
    void theEpicReadsAndTheTaskMarkerRideKimisHardEnabledToolsSetButThePlanEditsDoNot() {
      // Same asymmetry for the epic dispatch: a name left out of enabledTools does not exist, so a
      // dispatched kimi run told to read its epic and mark tasks implemented needs all three here,
      // while the plan-changing tools stay absent and so stay out of reach.
      AgentLaunchService service = service();

      AcpSessionConfig config =
          service.buildAcpSessionConfig(
              AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, service.pinSession(null, false, AgentType.KIMI));

      List<String> enabled = config.mcpServers().get(0).enabledTools();
      assertTrue(enabled.contains("list_epics"), enabled.toString());
      assertTrue(enabled.contains("get_epic"), enabled.toString());
      assertTrue(enabled.contains("mark_task_implemented"), enabled.toString());
      assertFalse(enabled.contains("update_epic"), enabled.toString());
      assertFalse(enabled.contains("add_task"), enabled.toString());
      assertFalse(enabled.contains("update_task"), enabled.toString());
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

    @Test
    void theAutonomousVariantMarksTheUrlsReadOnly() {
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.KIMI);

      AcpSessionConfig config =
          service.buildAcpSessionConfig(AgentMcpScope.REPOSITORY, AgentSurface.WORKSPACE_CHAT, pinned, true);

      assertTrue(config.mcpServers().get(0).url().contains("agentReadOnly=true"));
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

    /** The pair every surface of this host attaches, as its own serversFor builds it. */
    private static SeededConfigurationDocument withPair(
        SeededConfigurationDocument document, AgentSurface surface, boolean readOnly) {
      return document.surface(
          surface,
          // Remote control as the store seeds it: on for every chat-shaped surface, off for the two
          // agent tabs. That reads backwards and is what the code does — a chat has bridged over the
          // SDK control channel since the transport learned to ask, and the interactive shape, which
          // is the one the flag was made for, never passed it.
          AgentSurfaceConfigurations.shippedRemoteControl(surface),
          "",
          SeededConfigurationDocument.server(
              "repository", true, true, true, readOnly, WorkspaceHostMcpServers.REPOSITORY_TOOLS),
          // No projectId: qits-observability has no notion of one, and its tool filter hides
          // everything unless both of its narrowings are present.
          SeededConfigurationDocument.server(
              "observability",
              false,
              true,
              true,
              readOnly,
              WorkspaceHostMcpServers.READ_ONLY_OBSERVABILITY_TOOLS));
    }

    private AgentSurfaceConfigurations seeded() {
      SeededConfigurationDocument document = new SeededConfigurationDocument();
      for (AgentSurface surface :
          List.of(
              AgentSurface.EPIC_CHAT,
              AgentSurface.EPIC_AGENT,
              AgentSurface.WORKSPACE_CHAT,
              AgentSurface.WORKSPACE_AGENT)) {
        withPair(document, surface, false);
      }
      withPair(document, AgentSurface.TICKET_DISPATCH, true);
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
    void theOrderTheDocumentAttachesInIsTheOrderRendered() {
      // Load-bearing rather than cosmetic: both harnesses interpolate the serialized server object
      // into a shell argument the suites assert as a literal, and the pre-approval list is rendered
      // into one --allowedTools. The seed lists repository before observability because that is the
      // order this daemon's serversFor returns them in.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = seeded();
      String script =
          service
              .renderChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.EPIC_CHAT, pinned, AgentType.CLAUDE)
              .script();

      assertTrue(
          script.indexOf("\"repository\":") < script.indexOf("\"observability\":"), script);
    }

    @Test
    void aDispatchedRunIsFencedByItsConfigurationRatherThanOnlyByItsShape() {
      // ticket.dispatch is seeded read-only. The shape it launches with is the host's business, so
      // the fence has to be renderable from the row too — otherwise turning the store on would
      // depend on which of two call sites a dispatch happens to go through.
      AgentLaunchService service = service();
      AgentLaunchService.PinnedSession pinned = service.pinSession(null, false, AgentType.CLAUDE);

      configurations = seeded();
      String script =
          service
              .renderChat(
                  AgentMcpScope.REPOSITORY, AgentSurface.TICKET_DISPATCH, pinned, AgentType.CLAUDE)
              .script();

      assertEquals(
          2,
          script.split("agentReadOnly=true", -1).length - 1,
          "both servers fenced, the way an unattended run's are");
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
}
