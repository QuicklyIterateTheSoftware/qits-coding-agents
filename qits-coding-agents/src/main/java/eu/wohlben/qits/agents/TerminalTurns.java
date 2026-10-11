package eu.wohlben.qits.agents;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * How a turn is typed into an interactive harness's PTY (qits-1152).
 *
 * <p>A TUI submits on Enter, and a real newline in typed text is an Enter: a multi-line turn would
 * arrive as several turns, the first cut off mid-sentence. So each newline ({@code \r\n}, {@code
 * \n} or a lone {@code \r}) becomes the two characters backslash and {@code n}, the whole turn is
 * one line, and one carriage return submits it.
 *
 * <p>The carriage return is a write of its own, {@link #SUBMIT_PAUSE} after the text. A TUI reads
 * text that arrives in one burst as a paste, and a carriage return inside a paste is part of it:
 * the turn would sit in the input line, not sent.
 *
 * <p>Every other control character is a keystroke too: ESC starts a terminal sequence, Ctrl-C
 * interrupts, Ctrl-U clears the line, Tab completes. A turn can carry text from outside (comments,
 * event data), so tabs become spaces and the remaining control characters (C0, DEL, C1) are removed.
 *
 * <p>This is for the PTY only. The turn keeps its real newlines everywhere else: in the host's
 * rows, in a chat's stdin, and in an argv seed, where a quoted argument carries them safely.
 */
public final class TerminalTurns {

  private static final Pattern NEWLINE = Pattern.compile("\r\n|\r|\n");
  private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f-\\x9f]");

  private TerminalTurns() {}

  /**
   * The turn on one line: every newline replaced by a literal backslash and {@code n}, tabs by
   * spaces, other control characters removed.
   */
  public static String oneLine(String text) {
    if (text == null) {
      return "";
    }
    String plain = CONTROL.matcher(text.replace('\t', ' ')).replaceAll("");
    return NEWLINE.matcher(plain).replaceAll("\\\\n");
  }

  /** The key that submits a turn: a carriage return, what a terminal sends for Enter. */
  public static byte[] enter() {
    return new byte[] {'\r'};
  }

  /** How long after the text the {@link #enter} is written, so it is not read as part of a paste. */
  public static final Duration SUBMIT_PAUSE = Duration.ofMillis(300);

  /** The text to write to the PTY: the turn on one line, without the {@link #enter}. */
  public static byte[] text(String text) {
    return oneLine(text).getBytes(StandardCharsets.UTF_8);
  }
}
