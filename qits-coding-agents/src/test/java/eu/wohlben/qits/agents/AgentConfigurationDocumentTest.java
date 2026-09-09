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

  private static String document(String... surfaces) {
    return "{\"version\":1,\"generatedAt\":\"2026-09-09T10:00:00Z\",\"surfaces\":["
        + String.join(",", surfaces)
        + "]}";
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
