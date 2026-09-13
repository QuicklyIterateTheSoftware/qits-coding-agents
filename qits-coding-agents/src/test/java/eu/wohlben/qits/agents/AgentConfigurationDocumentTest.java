package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mounted document: what a container is born with, read once at boot.
 *
 * <p>The JSON asserted here is the shape {@code AgentConfigurationDocumentDto} writes in
 * qits-projects-service (commit {@code 5520f1d}), field name for field name. The two repositories
 * share no type — the service depends on no daemon library and this library reads no service — so
 * the field names <em>are</em> the contract, and this suite is the only place either side can be
 * caught renaming one.
 */
class AgentConfigurationDocumentTest {

  @TempDir Path mount;

  /** One surface as the service writes it: every field present, the seeded workspace pair. */
  private static final String SEEDED_EPIC_CHAT =
      """
      {
        "surface": "epic.chat",
        "harness": "CLAUDE",
        "model": "",
        "effort": "",
        "remoteControl": true,
        "permissionMode": "SKIP_PERMISSIONS",
        "activityTracking": true,
        "systemPrompt": "",
        "initialPrompt": "",
        "mcpServers": [
          {
            "server": "repository",
            "narrowProject": true,
            "narrowRepository": true,
            "narrowWorkspace": true,
            "readOnly": false,
            "allowedTools": ["mcp__repository__listBranches"]
          },
          {
            "server": "observability",
            "narrowProject": false,
            "narrowRepository": true,
            "narrowWorkspace": true,
            "readOnly": false,
            "allowedTools": ["mcp__observability__telemetryErrors"]
          }
        ],
        "shipped": true
      }
      """;

  /**
   * A <b>version-1</b> document: the surface record flat in the array. Nothing writes this shape any
   * more — the service moved to version 2 when the external catalog landed — and it is still read,
   * because a container created between those two releases keeps what it was born with for its whole
   * life.
   */
  private static String document(String... surfaces) {
    return "{\"version\":1,\"generatedAt\":\"2026-09-09T10:00:00Z\",\"surfaces\":["
        + String.join(",", surfaces)
        + "]}";
  }

  /** A version-2 document: each entry is {@code {configuration, externalMcpServers}}. */
  private static String documentV2(String... entries) {
    return "{\"version\":2,\"generatedAt\":\"2026-09-09T10:00:00Z\",\"surfaces\":["
        + String.join(",", entries)
        + "]}";
  }

  /** One version-2 entry wrapping {@code configuration} beside its rendered external servers. */
  private static String entry(String configuration, String externalServers) {
    return "{\"configuration\":" + configuration + ",\"externalMcpServers\":[" + externalServers + "]}";
  }

  private static final String STRIPE_SERVER =
      """
      {
        "key": "stripe",
        "url": "https://mcp.stripe.com/v1",
        "headerName": "Authorization",
        "headerValue": "Bearer sk-live-not-a-real-token",
        "allowedTools": ["mcp__stripe__listCustomers"]
      }
      """;

  @Nested
  class TheExternalCatalog {

    @Test
    void aVersionTwoSurfaceCarriesItsServersFullyRendered() {
      // The one shape on this platform that carries a credential's value, which is why the document
      // is a mounted file rather than an environment variable.
      AgentConfigurationDocument document =
          AgentConfigurationDocument.parse(
              documentV2(entry(SEEDED_EPIC_CHAT, STRIPE_SERVER)), "test");

      AgentSurfaceConfiguration surface = document.surfaces().get("epic.chat");
      assertEquals(1, surface.externalMcpServers().size());
      AgentExternalMcpServer stripe = surface.externalMcpServers().get(0);
      assertEquals("stripe", stripe.key());
      assertEquals("https://mcp.stripe.com/v1", stripe.url());
      assertEquals("Authorization", stripe.headerName());
      assertEquals("Bearer sk-live-not-a-real-token", stripe.headerValue());
      assertEquals(List.of("mcp__stripe__listCustomers"), stripe.allowedTools());
      assertTrue(stripe.hasCredential());
      assertEquals(List.of("stripe"), surface.externalMcpServerKeys());
      // And the configuration inside the wrapper is read exactly as version 1 read it flat.
      assertEquals(2, surface.mcpServers().size());
      assertTrue(surface.remoteControl());
    }

    @Test
    void theCredentialCannotLeakThroughAToString() {
      // A record's default toString prints every component, and this object travels a launch path
      // whose neighbours are logged as a matter of course.
      AgentExternalMcpServer stripe =
          AgentConfigurationDocument.parse(documentV2(entry(SEEDED_EPIC_CHAT, STRIPE_SERVER)), "test")
              .surfaces()
              .get("epic.chat")
              .externalMcpServers()
              .get(0);

      assertFalse(stripe.toString().contains("sk-live"), stripe.toString());
      assertTrue(stripe.toString().contains("<redacted>"), stripe.toString());
      assertFalse(
          AgentConfigurationDocument.parse(documentV2(entry(SEEDED_EPIC_CHAT, STRIPE_SERVER)), "test")
              .toString()
              .contains("sk-live"),
          "and not through the document that holds it either");
    }

    @Test
    void aReservedKeyIsRefusedAtBootAsWellAsOnWrite() {
      // The store validates this on write; a document can still reach a container from an older
      // service. An entry keyed 'repository' does not attach twice — both harnesses render one
      // key-to-config object, so it DISPLACES the platform's server and the session looks normal.
      InvalidAgentConfigurationException refused =
          assertThrows(
              InvalidAgentConfigurationException.class,
              () ->
                  AgentConfigurationDocument.parse(
                      documentV2(
                          entry(
                              SEEDED_EPIC_CHAT,
                              "{\"key\":\"repository\",\"url\":\"https://elsewhere.example\"}")),
                      "/etc/qits/agent-configuration.json"));

      assertTrue(refused.getMessage().contains("externalMcpServers[0].key"), refused.getMessage());
      assertTrue(refused.getMessage().contains("displace it silently"), refused.getMessage());
      assertEquals(
          List.of("repository", "actions", "observability"),
          AgentConfigurationDocument.RESERVED_SERVER_KEYS);
    }

    @Test
    void everyOtherWayAnExternalEntryCanBeWrongNamesItsKey() {
      assertTrue(
          refusalFor("{\"url\":\"https://a.example\"}").contains("externalMcpServers[0].key"));
      assertTrue(refusalFor("{\"key\":\"a\"}").contains("externalMcpServers[0].url"));
      assertTrue(
          refusalFor("{\"key\":\"a\",\"url\":\"https://a.example\"},{\"key\":\"a\",\"url\":\"https://b.example\"}")
              .contains("a second time"));
    }

    @Test
    void aHeaderWithoutAValueIsRefusedAndTheMessageQuotesNoValue() {
      // A server attached with half a credential 401s on the agent's first tool call, which surfaces
      // as a confused agent hours later rather than as an error anybody reads. And the refusal is
      // logged, so it names the shape and never the value.
      String refusal =
          refusalFor(
              "{\"key\":\"a\",\"url\":\"https://a.example\",\"headerName\":\"Authorization\"}");
      assertTrue(refusal.contains("presents both"), refusal);

      String reverse =
          refusalFor(
              "{\"key\":\"a\",\"url\":\"https://a.example\",\"headerValue\":\"sk-live-secret\"}");
      assertFalse(reverse.contains("sk-live-secret"), reverse);
    }

    @Test
    void aSurfaceThatAttachesNoneHasNoneRatherThanNull() {
      assertEquals(
          List.of(),
          AgentConfigurationDocument.parse(document(SEEDED_EPIC_CHAT), "test")
              .surfaces()
              .get("epic.chat")
              .externalMcpServers(),
          "a version-1 document is read as a document with no external servers");
    }

    private String refusalFor(String externalServers) {
      return assertThrows(
              InvalidAgentConfigurationException.class,
              () ->
                  AgentConfigurationDocument.parse(
                      documentV2(entry(SEEDED_EPIC_CHAT, externalServers)), "test"))
          .getMessage();
    }
  }

  private Path write(String json) throws IOException {
    Path file = mount.resolve("agent-configuration.json");
    Files.writeString(file, json);
    return file;
  }

  @Nested
  class AbsentIsNotBroken {

    @Test
    void noPathAndNoFileBothReadAsNoDocument() {
      // A container created before this shipped. The estate's usual rule, and the reason the
      // shipped constants stay in the code rather than becoming rows and only rows.
      assertEquals(Optional.empty(), AgentConfigurationDocument.readFrom(null));
      assertEquals(Optional.empty(), AgentConfigurationDocument.readFrom("  "));
      assertEquals(
          Optional.empty(),
          AgentConfigurationDocument.readFrom(mount.resolve("nothing-here.json").toString()));
      assertFalse(
          AgentSurfaceConfigurations.readFrom(mount.resolve("nothing-here.json").toString())
              .documentPresent());
    }

    @Test
    void aPathThatIsADirectoryIsBrokenRatherThanAbsent() throws IOException {
      Path directory = Files.createDirectory(mount.resolve("mounted-wrong"));

      InvalidAgentConfigurationException failure =
          assertThrows(
              InvalidAgentConfigurationException.class,
              () -> AgentConfigurationDocument.readFrom(directory.toString()));

      assertTrue(failure.getMessage().contains("is a directory"), failure.getMessage());
    }
  }

  @Nested
  class Reading {

    @Test
    void aSeededSurfaceReadsBackFieldForField() throws IOException {
      AgentConfigurationDocument read =
          AgentConfigurationDocument.readFrom(write(document(SEEDED_EPIC_CHAT)).toString())
              .orElseThrow();

      assertEquals(1, read.version());
      assertEquals("2026-09-09T10:00:00Z", read.generatedAt());
      AgentSurfaceConfiguration chat = read.surfaces().get("epic.chat");
      assertEquals(AgentType.CLAUDE, chat.harness());
      assertEquals(AgentPermissionMode.SKIP_PERMISSIONS, chat.permissionMode());
      assertTrue(chat.activityTracking());
      assertTrue(chat.remoteControl(), "on for a chat surface — the SDK control channel, not a flag");
      assertEquals("", chat.systemPrompt());
      assertNull(chat.systemPromptAppendix(), "empty is a value: this surface steers with nothing");
      assertFalse(chat.shipped());
      assertEquals(
          List.of("repository", "observability"),
          chat.mcpServers().stream().map(AgentMcpAttachment::server).toList(),
          "order is load-bearing: it is rendered into one --allowedTools argument");
      assertTrue(chat.mcpServers().get(0).narrowWorkspace());
      assertFalse(chat.mcpServers().get(1).narrowProject(), "observability has no notion of one");
    }

    @Test
    void aSurfaceOutsideTheVocabularyIsCarriedRatherThanRefused() {
      // The store may learn a ninth surface before this library does. Failing to boot on it would
      // force the two repositories to release in lockstep, which is what the rollout order cannot
      // do; carrying a key nobody looks up costs nothing.
      AgentConfigurationDocument read =
          AgentConfigurationDocument.parse(
              document(
                  """
                  {"surface":"ticket.triage","harness":"KIMI","permissionMode":"PROMPT"}
                  """),
              "test");

      assertTrue(read.surfaces().containsKey("ticket.triage"));
      assertEquals(AgentType.KIMI, read.surfaces().get("ticket.triage").harness());
    }

    @Test
    void aSurfaceThatSaysNothingAboutMcpTakesTheHostsWholeMapping() {
      // Distinguishable from an explicit empty list, which is a surface that attaches nothing on
      // purpose. The difference matters: one is "unconfigured", the other is a decision.
      AgentConfigurationDocument read =
          AgentConfigurationDocument.parse(
              document(
                  """
                  {"surface":"epic.agent","harness":"CLAUDE","permissionMode":"PROMPT"}
                  """,
                  """
                  {"surface":"epic.chat","harness":"CLAUDE","permissionMode":"PROMPT",
                   "mcpServers":[]}
                  """),
              "test");

      assertFalse(read.surfaces().get("epic.agent").attachesConfiguredServers());
      assertTrue(read.surfaces().get("epic.chat").attachesConfiguredServers());
      assertEquals(List.of(), read.surfaces().get("epic.chat").mcpServers());
    }
  }

  @Nested
  class MalformedIsLoudAndNamesTheKey {

    private String reject(String json) {
      return assertThrows(
              InvalidAgentConfigurationException.class,
              () -> AgentConfigurationDocument.parse(json, "/etc/qits/agent-configuration.json"))
          .getMessage();
    }

    @Test
    void everyRefusalNamesTheFileAndTheOffendingKey() {
      // The whole reason the document is a mounted file rather than an environment variable: a bad
      // edit is discovered at boot, by key, instead of as a weird agent three hours later.
      assertTrue(reject("not json at all").contains("is not a JSON object"));
      assertTrue(reject("{\"surfaces\":[]}").contains("version must be a number"));
      assertTrue(reject("{\"version\":1}").contains("surfaces must be an array"));
      assertTrue(
          reject(document("{\"harness\":\"CLAUDE\",\"permissionMode\":\"PROMPT\"}"))
              .contains("surfaces[0].surface must name a surface"));
      assertTrue(
          reject(document("{\"surface\":\"epic.chat\",\"harness\":\"COPILOT\"}"))
              .contains("surfaces[0].harness is not a known harness: COPILOT"));
      assertTrue(
          reject(
                  document(
                      "{\"surface\":\"epic.chat\",\"harness\":\"CLAUDE\",\"permissionMode\":\"ASK_NICELY\"}"))
              .contains("surfaces[0].permissionMode is not a known permission mode: ASK_NICELY"));
      assertTrue(
          reject("{\"version\":99,\"surfaces\":[]}").contains("newer than this library understands"),
          "a shape this daemon does not understand must refuse, not read what it can");

      String message = reject(document("{\"surface\":\"epic.chat\",\"harness\":\"CLAUDE\"}"));
      assertTrue(message.startsWith("/etc/qits/agent-configuration.json:"), message);
    }

    @Test
    void aServerAttachedTwiceIsRefusedRatherThanMerged() {
      // Both harnesses render one key-to-config object, so a repeated key does not attach twice —
      // it silently displaces, and a session talking to somebody else's 'repository' server looks
      // entirely normal. The reserved-key check the external catalog needs is this same rule.
      String message =
          reject(
              document(
                  """
                  {"surface":"epic.chat","harness":"CLAUDE","permissionMode":"SKIP_PERMISSIONS",
                   "mcpServers":[{"server":"repository"},{"server":"repository"}]}
                  """));

      assertTrue(
          message.contains("surfaces[0].mcpServers[1].server attaches 'repository' a second time"),
          message);
    }

    @Test
    void aMalformedAttachmentNamesItsIndex() {
      assertTrue(
          reject(
                  document(
                      """
                      {"surface":"epic.chat","harness":"CLAUDE","permissionMode":"PROMPT",
                       "mcpServers":{"repository":true}}
                      """))
              .contains("surfaces[0].mcpServers must be an array"));
      assertTrue(
          reject(
                  document(
                      """
                      {"surface":"epic.chat","harness":"CLAUDE","permissionMode":"PROMPT",
                       "mcpServers":[{"server":"repository","allowedTools":[7]}]}
                      """))
              .contains("surfaces[0].mcpServers[0].allowedTools must hold tool ids"));
      assertTrue(
          reject(
                  document(
                      """
                      {"surface":"epic.chat","harness":"CLAUDE","permissionMode":"PROMPT"}
                      """,
                      """
                      {"surface":"epic.chat","harness":"CLAUDE","permissionMode":"PROMPT"}
                      """))
              .contains("is configured twice: epic.chat"));
    }
  }

  @Nested
  class Resolution {

    @Test
    void aSurfaceTheDocumentDoesNotMentionAnswersTheShippedDefault() {
      // Never a failure and never a 404, the same rule the store applies on its own side: a daemon
      // that knows a surface nobody has configured still has to launch it.
      AgentSurfaceConfigurations configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(document(SEEDED_EPIC_CHAT), "test"));

      AgentSurfaceConfiguration tickets =
          configurations.resolve(AgentSurface.PROJECT_TICKETS, AgentType.CLAUDE, true);

      assertTrue(tickets.shipped());
      assertEquals(AgentLaunchService.TICKETS_DESK_PROMPT, tickets.systemPrompt());
      assertNull(tickets.mcpServers(), "shipped means: whatever this host attaches for the scope");
    }

    @Test
    void theTwoComposedRunsShipTheOrchestrationPromptAndNobodyElseMoves() {
      // Three surfaces carry steering and five carry none. The composed runs are the two nobody
      // talks to turn by turn, so "orchestrate and delegate" has to arrive in the shipped prompt or
      // it never arrives at all; the other six render exactly what they rendered before.
      assertEquals(
          AgentLaunchService.COMPOSED_RUN_PROMPT,
          AgentSurfaceConfigurations.shippedSystemPrompt(AgentSurface.EPIC_AUTONOMOUS));
      assertEquals(
          AgentLaunchService.COMPOSED_RUN_PROMPT,
          AgentSurfaceConfigurations.shippedSystemPrompt(AgentSurface.TICKET_DISPATCH));
      assertEquals(
          AgentLaunchService.TICKETS_DESK_PROMPT,
          AgentSurfaceConfigurations.shippedSystemPrompt(AgentSurface.PROJECT_TICKETS));

      for (AgentSurface surface :
          List.of(
              AgentSurface.PROJECT_EPICS,
              AgentSurface.EPIC_CHAT,
              AgentSurface.EPIC_AGENT,
              AgentSurface.WORKSPACE_CHAT,
              AgentSurface.WORKSPACE_AGENT)) {
        assertEquals(
            "",
            AgentSurfaceConfigurations.shippedSystemPrompt(surface),
            surface + " steers with nothing, and this task did not change that");
      }
    }

    @Test
    void theComposedRunPromptSaysTheThreeThingsItIsFor() {
      // An identical copy is seeded by qits-projects and compared against this literal there. These
      // are the three instructions the ticket asked for, asserted so a reflow cannot drop one.
      String prompt = AgentLaunchService.COMPOSED_RUN_PROMPT;

      assertTrue(prompt.startsWith("You are orchestrating this run rather than typing it."), prompt);
      assertTrue(prompt.contains("hand each one to a subagent"), prompt);
      assertTrue(prompt.contains("Sonnet for mechanical, narrow, well-specified edits"), prompt);
      assertTrue(prompt.contains("Opus for anything wide, ambiguous or architecturally"), prompt);
      assertTrue(prompt.contains("Delegating the work does not delegate the verification."), prompt);
      assertFalse(prompt.endsWith("\n"), "a text block appendix ends where the sentence does");
    }

    @Test
    void anOperatorWhoClearsTheBoxGetsAnUnsteeredComposedRun() {
      // Data, not behaviour: the prompt is the fallback a container born without a document takes,
      // and an emptied row wins over it the same way it does on every other surface.
      AgentSurfaceConfigurations configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(
                  document(
                      """
                      {
                        "surface": "epic.autonomous",
                        "harness": "CLAUDE",
                        "model": "",
                        "effort": "",
                        "remoteControl": true,
                        "permissionMode": "SKIP_PERMISSIONS",
                        "activityTracking": true,
                        "systemPrompt": "",
                        "initialPrompt": "",
                        "mcpServers": []
                      }"""),
                  "test"));

      AgentSurfaceConfiguration autonomous =
          configurations.resolve(AgentSurface.EPIC_AUTONOMOUS, AgentType.CLAUDE, true);

      assertFalse(autonomous.shipped());
      assertEquals("", autonomous.systemPrompt());
    }

    @Test
    void theShippedFallbackTakesTheHostsOwnDefaults() {
      // A library that shipped an attachment table would be a library that knows which product it
      // is inside; a library that shipped an activity-tracking boolean would override a daemon
      // setting somebody chose. Both come from the host.
      AgentSurfaceConfiguration shipped =
          AgentSurfaceConfigurations.shipped()
              .resolve(AgentSurface.WORKSPACE_AGENT, AgentType.KIMI, false);

      assertEquals(AgentType.KIMI, shipped.harness());
      assertFalse(shipped.activityTracking());
      assertEquals(AgentPermissionMode.SKIP_PERMISSIONS, shipped.permissionMode());
      assertEquals("", shipped.systemPrompt());
      assertEquals("", shipped.model());
      assertEquals("", shipped.effort());
      assertNull(shipped.mcpServers());
    }

    @Test
    void remoteControlOnAChatSurfaceIsReadAndNotRefused() {
      // The owner's rule: when the knob is on, the flag is set. There is no validation here and
      // there must not be — the mechanism differs per launch shape (a chat enables remote control
      // over the SDK control channel, because --remote-control is dropped under --print), and the
      // library's job is to render the knob, not to have an opinion about the pairing.
      AgentSurfaceConfigurations configurations =
          AgentSurfaceConfigurations.of(
              AgentConfigurationDocument.parse(document(SEEDED_EPIC_CHAT), "test"));

      assertTrue(
          configurations.resolve(AgentSurface.EPIC_CHAT, AgentType.CLAUDE, true).remoteControl());
    }
  }
}
