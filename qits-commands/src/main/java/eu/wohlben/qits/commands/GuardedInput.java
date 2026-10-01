package eu.wohlben.qits.commands;

/**
 * What became of keystrokes the server offered a terminal on the condition that the person has
 * nothing unsent in its input line — see {@link CommandRegistry#inputUnlessDraft}.
 */
public enum GuardedInput {
  /** Written to the terminal. */
  WRITTEN,
  /** Held back, nothing written: the person's browser terminal left a draft in the input line. */
  HELD_FOR_DRAFT,
  /** Nothing written: the command is not a running terminal session — it ended, or never was one. */
  NOT_RUNNING
}
