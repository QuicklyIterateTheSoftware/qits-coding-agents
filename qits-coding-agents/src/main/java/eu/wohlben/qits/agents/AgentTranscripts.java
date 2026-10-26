package eu.wohlben.qits.agents;

import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.Command;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Whether a session's transcript is on the shared credential volume — the one thing that outlives a
 * container, and so the one witness a resume can lean on once {@code CommandStore} has not.
 *
 * <p>The harness writes its transcripts under its config dir on that volume ({@code
 * $CLAUDE_CONFIG_DIR}, i.e. {@code <claudeMount>/.claude}; Kimi's {@code <claudeMount>/.kimi-code}),
 * so a session driven in an earlier container is still resumable here when its transcript is. The
 * lookup is the recovery half of {@link AgentTranscriptService#resolveTranscript}: Claude's {@code
 * projects/<escaped-cwd>/<sessionId>.jsonl}, Kimi's {@code sessions/<workDirKey>/<sessionId>}
 * directory, each found by name rather than by the escaping convention.
 *
 * <p>Static and framework-free on purpose: the launch path asks it before a resume, and a host that
 * keeps a session alive across container recreations (the projects daemon's front desk) asks the
 * very same question before it asks for one.
 */
public final class AgentTranscripts {

  private AgentTranscripts() {}

  /**
   * The harness's config dir on the credential volume: {@code <claudeMount>/.claude} or {@code
   * <claudeMount>/.kimi-code}.
   */
  public static Path configDir(String claudeMount, AgentType agentType) {
    return Path.of(
            claudeMount,
            switch (agentType) {
              case CLAUDE -> ".claude";
              case KIMI -> ".kimi-code";
            })
        .normalize();
  }

  /**
   * Whether {@code sessionId}'s transcript exists under {@code agentType}'s config dir: a search of
   * Claude's {@code projects/} for {@code <sessionId>.jsonl}, or of Kimi's {@code sessions/} for a
   * {@code <sessionId>} directory. False for a blank mount or a null id — nothing to look in, or
   * nothing to look for.
   */
  public static boolean exists(String claudeMount, AgentType agentType, String sessionId) {
    if (claudeMount == null || claudeMount.isBlank() || sessionId == null || agentType == null) {
      return false;
    }
    boolean kimi = agentType == AgentType.KIMI;
    Path root = configDir(claudeMount, agentType).resolve(kimi ? "sessions" : "projects");
    if (!Files.isDirectory(root)) {
      return false;
    }
    String name = kimi ? sessionId : sessionId + ".jsonl";
    // Claude's file sits at depth 2, Kimi's directory at depth 2; one spare level for safety.
    try (Stream<Path> walk = Files.walk(root, 3)) {
      return walk.anyMatch(
          path ->
              path.getFileName().toString().equals(name)
                  && (kimi ? Files.isDirectory(path) : Files.isRegularFile(path)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * {@link #exists(String, AgentType, String)} for a session {@code command} drove: the harness is
   * the command's recorded one (an unset or unknown value is {@link AgentType#CLAUDE}), and the
   * hook-reported transcript path of the matching session ref is taken first when it sits under that
   * harness's config dir and is a file. {@code command} may be null, which is the plain lookup under
   * Claude.
   */
  public static boolean exists(String claudeMount, Command command, String sessionId) {
    if (claudeMount == null || claudeMount.isBlank() || sessionId == null) {
      return false;
    }
    AgentType agentType =
        command == null
            ? AgentType.CLAUDE
            : AgentType.parse(command.agentType()).orElse(AgentType.CLAUDE);
    if (command != null) {
      Path configDir = configDir(claudeMount, agentType);
      for (AgentSessionRef ref : command.agentSessions()) {
        if (sessionId.equals(ref.sessionId())
            && ref.transcriptPath() != null
            && !ref.transcriptPath().isBlank()) {
          Path reported = Path.of(ref.transcriptPath()).normalize();
          if (reported.startsWith(configDir) && Files.isRegularFile(reported)) {
            return true;
          }
        }
      }
    }
    return exists(claudeMount, agentType, sessionId);
  }
}
