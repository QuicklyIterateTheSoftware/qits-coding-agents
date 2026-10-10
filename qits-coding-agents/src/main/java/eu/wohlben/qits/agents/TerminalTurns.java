package eu.wohlben.qits.agents;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * How a turn is typed into an interactive harness's PTY (qits-1152).
 *
 * <p>A TUI submits on Enter, and a real newline in typed text is an Enter: a multi-line turn would
 * arrive as several turns, the first cut off mid-sentence. So each newline ({@code \r\n}, {@code
 * \n} or a lone {@code \r}) becomes the two characters backslash and {@code n}, the whole turn is
 * one line, and one carriage return submits it.
 *
 * <p>This is for the PTY only. The turn keeps its real newlines everywhere else: in the host's
 * rows, in a chat's stdin, and in an argv seed, where a quoted argument carries them safely.
 */
public final class TerminalTurns {

  private static final Pattern NEWLINE = Pattern.compile("\r\n|\r|\n");

  private TerminalTurns() {}

  /** The turn on one line: every newline replaced by a literal backslash and {@code n}. */
  public static String oneLine(String text) {
    return text == null ? "" : NEWLINE.matcher(text).replaceAll("\\\\n");
  }

  /** The bytes to write to the PTY: the turn on one line, then the carriage return that submits. */
  public static byte[] keystrokes(String text) {
    return (oneLine(text) + "\r").getBytes(StandardCharsets.UTF_8);
  }
}
