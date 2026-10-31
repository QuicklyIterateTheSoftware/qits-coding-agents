package eu.wohlben.qits.agents;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The debug log a Remote Control launch of {@code claude} writes ({@link
 * ClaudeCodeAgent#remoteControlLogPath}), read back at harness exit: did the session archive its
 * claude.ai listing, and under which {@code cse_…} id (qits-1779).
 *
 * <p>{@code claude --remote-control} archives its session on SIGTERM and says so only in its debug
 * log:
 *
 * <pre>
 * … [DEBUG] [remote-bridge] Created session cse_01CiRkJvCf85w2aC9w8b2LHS
 * … [DEBUG] [code-session] Archive cse_01CiRkJvCf85w2aC9w8b2LHS status=200
 * … [DEBUG] [remote-bridge] Torn down (archive=200)
 * </pre>
 *
 * A session left listed on claude.ai is then either an archive that answered something other than
 * 200, or no archive line at all — a harness that exited on EOF or a KILL without ever being asked.
 * Those are different bugs, and {@link Outcome} keeps them apart.
 *
 * <p>The file is the harness's whole debug output, hundreds of lines a minute while it is busy,
 * and it sits on the credential volume every container shares — so {@link #compact} cuts it down to
 * the bridge lines once the harness has exited, which is everything this class or a person
 * debugging an archive ever reads from it.
 *
 * <p>Static, framework-free and never throws, like {@link AgentTranscripts}: it runs on a harness's
 * exit path, where a failure to read a diagnostic must not become a failure to finish the exit.
 */
public final class RemoteControlLog {

  /** {@link Outcome#archive} when the log exists but records no archive attempt at all. */
  public static final String NO_ARCHIVE = "NONE";

  /** The bridge's own line prefixes — the lines {@link #compact} keeps. */
  static final List<String> BRIDGE_MARKERS =
      List.of("[remote-bridge]", "[code-session]", "[bridge:repl]", "[bridge]");

  /**
   * A remote session id where the bridge names one: on creation, on the archive call, and in the
   * repl's teardown ({@code Hook cleanup: starting teardown for session=cse_… reason=host_exit}).
   */
  private static final Pattern SESSION =
      Pattern.compile(
          "(?:Created session |\\[code-session] Archive |teardown for session=)(cse_[A-Za-z0-9_]+)");

  /** An archive's status: the call itself, or the bridge's summary of it at teardown. */
  private static final Pattern ARCHIVE =
      Pattern.compile(
          "\\[code-session] Archive cse_[A-Za-z0-9_]+ status=([^\\s)]+)"
              + "|Torn down \\(archive=([^\\s)]+)\\)");

  private RemoteControlLog() {}

  /**
   * What a Remote Control log says about the end of its session.
   *
   * @param session the last {@code cse_…} id the bridge named, or null when it named none (the
   *     bridge never got as far as creating a session)
   * @param archive the status of the last archive the log records, as written — {@code "200"},
   *     {@code "401"} — or {@link #NO_ARCHIVE} when it records none
   */
  public record Outcome(String session, String archive) {

    /** Whether the last archive the log records answered 200. */
    public boolean archived() {
      return "200".equals(archive);
    }
  }

  /**
   * The log of the claude harness that ran as {@code sessionId} under {@code cwd}: {@code
   * <configDir>/projects/<escaped-cwd>/<sessionId>.remote.log}, the path {@link
   * ClaudeCodeAgent#start()} passed to {@code --debug-file}.
   *
   * @param configDir the harness's config dir as an absolute path — its {@code CLAUDE_CONFIG_DIR},
   *     {@link AgentTranscripts#configDir} on the credential volume
   */
  public static Path resolve(Path configDir, String cwd, String sessionId) {
    return configDir.resolve(ClaudeCodeAgent.remoteControlLogPath(cwd, sessionId));
  }

  /**
   * {@link #resolve(Path, String, String)} under the cwd every launch renders its log path for, the
   * conventional container checkout ({@code /workspace}) the transcript lookups assume as well.
   */
  public static Path resolve(Path configDir, String sessionId) {
    return resolve(configDir, AgentTranscriptService.CONTAINER_CWD, sessionId);
  }

  /**
   * What {@code file} says about its session's end; empty when there is no such file or it cannot be
   * read. Read as UTF-8 with malformed bytes replaced, so a torn last line does not cost the rest.
   */
  public static Optional<Outcome> read(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    String session = null;
    String archive = NO_ARCHIVE;
    try (BufferedReader reader = reader(file)) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher named = SESSION.matcher(line);
        while (named.find()) {
          session = named.group(1);
        }
        Matcher archived = ARCHIVE.matcher(line);
        while (archived.find()) {
          archive = archived.group(1) != null ? archived.group(1) : archived.group(2);
        }
      }
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
    return Optional.of(new Outcome(session, archive));
  }

  /**
   * Rewrites {@code file} in place with only its bridge lines ({@link #BRIDGE_MARKERS}): through a
   * temporary sibling and an atomic move, so a reader never sees half a file and a failure leaves
   * the original as it was. Only once the harness has exited — a writer still appending would lose
   * whatever it wrote after the copy began.
   *
   * @return whether the file was compacted; false when it is absent or anything failed, which is
   *     never an error to the caller
   */
  public static boolean compact(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return false;
    }
    Path temporary = null;
    try {
      Path dir = file.toAbsolutePath().getParent();
      temporary = Files.createTempFile(dir, file.getFileName().toString(), ".compacting");
      try (BufferedReader reader = reader(file);
          BufferedWriter writer =
              new BufferedWriter(
                  new OutputStreamWriter(
                      Files.newOutputStream(temporary), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (isBridgeLine(line)) {
            writer.write(line);
            writer.write('\n');
          }
        }
      }
      try {
        Files.move(
            temporary,
            file,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
      return true;
    } catch (IOException | RuntimeException e) {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException ignored) {
          // Best effort: a stray temporary is untidy, not harmful.
        }
      }
      return false;
    }
  }

  static boolean isBridgeLine(String line) {
    for (String marker : BRIDGE_MARKERS) {
      if (line.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  /** A lenient UTF-8 reader: {@code Files.newBufferedReader} would throw on one bad byte. */
  private static BufferedReader reader(Path file) throws IOException {
    return new BufferedReader(
        new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
  }
}
