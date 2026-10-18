package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
  void theTextIsOneLineWithoutTheEnter() {
    assertArrayEquals(
        "first\\nsecond".getBytes(StandardCharsets.UTF_8), TerminalTurns.text("first\nsecond"));
    assertArrayEquals(new byte[] {'\r'}, TerminalTurns.enter());
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

  @Test
  void aTurnOfExactlyChunkCharsIsOneChunkEqualToText() {
    String text = "a".repeat(TerminalTurns.CHUNK_CHARS);
    List<byte[]> chunks = TerminalTurns.chunks(text);
    assertEquals(1, chunks.size());
    assertArrayEquals(TerminalTurns.text(text), chunks.get(0));
  }

  @Test
  void aTurnOneCharOverChunkCharsSplitsIntoTwoChunks() {
    String text = "a".repeat(TerminalTurns.CHUNK_CHARS + 1);
    List<byte[]> chunks = TerminalTurns.chunks(text);
    assertEquals(2, chunks.size());
    assertEquals(TerminalTurns.CHUNK_CHARS, chunks.get(0).length);
    assertEquals(1, chunks.get(1).length);
    assertEquals(text, decodeAll(chunks));
  }

  @Test
  void aSurrogatePairStraddlingTheChunkBoundaryStaysWhole() {
    String pair = "😀"; // a high/low surrogate pair, one astral character
    String text = "a".repeat(TerminalTurns.CHUNK_CHARS - 1) + pair + "b".repeat(50);
    List<byte[]> chunks = TerminalTurns.chunks(text);
    assertEquals(2, chunks.size());
    String first = new String(chunks.get(0), StandardCharsets.UTF_8);
    String second = new String(chunks.get(1), StandardCharsets.UTF_8);
    assertEquals(TerminalTurns.CHUNK_CHARS - 1, first.length(), "the cut moved one character earlier");
    assertTrue(second.startsWith(pair), "the pair should start the next chunk whole, got: " + second);
    assertEquals(text, first + second);
  }

  @Test
  void aMultiByteCharacterAtTheBoundaryDecodesBackWhole() {
    String text = "x".repeat(TerminalTurns.CHUNK_CHARS - 1) + "—" + "y".repeat(50);
    List<byte[]> chunks = TerminalTurns.chunks(text);
    assertEquals(2, chunks.size());
    assertEquals(text, decodeAll(chunks));
  }

  @Test
  void anEmptyOrNullTurnIsOneEmptyChunk() {
    assertEquals(1, TerminalTurns.chunks("").size());
    assertEquals(0, TerminalTurns.chunks("").get(0).length);
    assertEquals(1, TerminalTurns.chunks(null).size());
    assertEquals(0, TerminalTurns.chunks(null).get(0).length);
  }

  @Test
  void decodedChunksConcatenateBackToTheOneLineTurn() {
    String text =
        "line one\nline two\twith a tab, an emoji 😀 and a dash —".repeat(40);
    assertEquals(TerminalTurns.oneLine(text), decodeAll(TerminalTurns.chunks(text)));
  }

  private static String decodeAll(List<byte[]> chunks) {
    StringBuilder all = new StringBuilder();
    for (byte[] chunk : chunks) {
      all.append(new String(chunk, StandardCharsets.UTF_8));
    }
    return all.toString();
  }
}
