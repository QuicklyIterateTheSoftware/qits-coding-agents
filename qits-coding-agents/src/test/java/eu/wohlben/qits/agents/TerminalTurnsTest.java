package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TerminalTurnsTest {

  @Test
  void everyNewlineBecomesALiteralBackslashN() {
    assertEquals("one\\ntwo\\nthree\\nfour", TerminalTurns.oneLine("one\ntwo\r\nthree\rfour"));
  }

  @Test
  void aTurnWithNoNewlineIsTypedAsItIs() {
    assertEquals("carry on", TerminalTurns.oneLine("carry on"));
  }

  @Test
  void theKeystrokesAreOneLineAndOneCarriageReturn() {
    assertArrayEquals(
        "first\\nsecond\r".getBytes(StandardCharsets.UTF_8),
        TerminalTurns.keystrokes("first\nsecond"));
  }

  @Test
  void aLiteralBackslashNTheTurnAlreadyHadIsKept() {
    assertEquals("say \\n here\\nnext", TerminalTurns.oneLine("say \\n here\nnext"));
  }

  @Test
  void controlCharactersAreNotTypedAsKeystrokes() {
    assertEquals(
        "a b[31mred c d", TerminalTurns.oneLine("a\tb\u001b[31mred\u0003 c\u0015 d\u007f\u009b"));
  }
}
