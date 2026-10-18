package eu.wohlben.qits.agents;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Claude Code's TUI additionally treats any single stdin write of more than 800 characters as
 * a paste: it wraps the turn in {@code <pasted_content>} tags, and a model reads a pasted turn as
 * data to consult rather than an instruction to act on. Measured: a write of 800 characters or
 * fewer arrives plain, 801 or more arrives wrapped, and writes made back to back with no gap
 * between them are merged by the terminal into one burst before the TUI ever sees it — so cutting
 * the bytes is not enough, the writes also have to be spaced out. {@link #chunks} does both: it
 * splits a turn into pieces of at most {@link #CHUNK_CHARS} characters, comfortably under the
 * threshold, each written on its own and {@link #CHUNK_PAUSE} after the one before it, far enough
 * apart that the terminal never merges two chunks back into a single paste-sized burst (qits-1272).
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

  /** The largest piece {@link #chunks} writes in one go, measured in UTF-16 units. */
  public static final int CHUNK_CHARS = 512;

  /** How long {@link #chunks} pauses between two consecutive chunks of the same turn. */
  public static final Duration CHUNK_PAUSE = Duration.ofMillis(30);

  /**
   * {@link #text}, split into pieces of at most {@link #CHUNK_CHARS} UTF-16 units each, so a long
   * turn can be written to the PTY as several small writes rather than one that crosses Claude
   * Code's paste threshold. A cut never falls between the two halves of a surrogate pair — the
   * piece before it ends one character short instead, and the pair starts the next piece whole.
   * Each piece is UTF-8 encoded on its own, so a multi-byte character is never split either.
   *
   * <p>A turn of {@link #CHUNK_CHARS} characters or fewer comes back as exactly one chunk, equal
   * to {@link #text}. An empty or null turn comes back as a single empty {@code byte[]}, so a
   * caller that always writes every returned chunk reproduces today's one write of nothing.
   */
  public static List<byte[]> chunks(String text) {
    String line = oneLine(text);
    if (line.isEmpty()) {
      return List.of(new byte[0]);
    }
    List<byte[]> chunks = new ArrayList<>();
    int start = 0;
    while (start < line.length()) {
      int end = Math.min(start + CHUNK_CHARS, line.length());
      if (end < line.length()
          && Character.isHighSurrogate(line.charAt(end - 1))
          && Character.isLowSurrogate(line.charAt(end))) {
        end--;
      }
      chunks.add(line.substring(start, end).getBytes(StandardCharsets.UTF_8));
      start = end;
    }
    return chunks;
  }
}
