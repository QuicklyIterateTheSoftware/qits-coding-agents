package eu.wohlben.qits.agents;

import java.util.Locale;
import java.util.Optional;

/**
 * The one status palette a session name is coloured from: a coloured square per entity status,
 * placed in front of the qualified id so the claude.ai session list says which phase each session is
 * in without opening its ticket.
 *
 * <p><b>Its twin is the UI's.</b> qits-projects-frontend {@code src/app/project/entities-model.ts}
 * {@code STATUS_TONES} badges the same statuses in the same colours — REPORTED {@code neutral}
 * (grey), REFINED {@code highlight} (purple), IMPLEMENTING {@code info} (blue), IMPLEMENTED {@code
 * info} (blue, the same tone — IMPLEMENTING leads straight into it rather than being a different
 * kind of thing), VERIFYING {@code info} (blue, same reasoning as IMPLEMENTING — it is the
 * platform's own verify dispatch rather than a different kind of thing), VERIFIED {@code warning}
 * (yellow), DONE {@code success} (green), DROPPED {@code neutral} — and its {@code BLOCKED_BADGE} is
 * {@code danger}, the red this palette leaves to {@link AgentRemoteControl#BLOCKED_GLYPH} and to
 * nothing else. The two are hand-kept copies of one table (ticket qits-617); a change to either is a
 * change to both, or a person reads one colour on the board and another in the session list for the
 * same piece of work.
 *
 * <p>DROPPED shares REPORTED's grey on purpose: dropped work is neither a failure nor an
 * achievement, and the UI has always rendered it neutral.
 */
public enum EntityStatusSquare {
  REPORTED("⬜"),
  REFINED("🟪"),
  IMPLEMENTING("🟦"),
  IMPLEMENTED("🟦"),
  VERIFYING("🟦"),
  VERIFIED("🟨"),
  DONE("🟩"),
  DROPPED("⬜");

  private final String square;

  EntityStatusSquare(String square) {
    this.square = square;
  }

  /** The emoji square, alone — no space. */
  public String square() {
    return square;
  }

  /**
   * The square for a status word, case- and padding-insensitive; empty for a blank word and for one
   * this palette does not know, which a name renders as no square at all rather than a wrong one.
   */
  public static Optional<EntityStatusSquare> of(String status) {
    if (status == null || status.isBlank()) {
      return Optional.empty();
    }
    String word = status.trim().toUpperCase(Locale.ROOT);
    for (EntityStatusSquare value : values()) {
      if (value.name().equals(word)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
