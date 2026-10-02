package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalMcpTest {

  @Test
  void rendersAsAStdioServer() {
    assertEquals(
        "{\"type\":\"stdio\",\"command\":\"qits-browser-mcp\"}",
        McpServers.stdioMcp("qits-browser-mcp").encode());
  }

  @Test
  void refusesACommandTheShellWouldReadAsMore() {
    // The command lands inside a single-quoted shell argument and the renderer escapes nothing.
    for (String command : List.of("a b", "x'y", "a;b", "$(id)", "")) {
      assertThrows(
          IllegalArgumentException.class, () -> new LocalMcp("browser", command, List.of()), command);
    }
  }

  @Test
  void refusesAKeyOutsideTheServerKeyGrammar() {
    assertThrows(IllegalArgumentException.class, () -> new LocalMcp("Browser", "x", List.of()));
  }

  @Test
  void aHostThatDeclaresNoneAttachesNone() {
    AgentMcpServers none = scope -> List.of();

    assertEquals(List.of(), none.localServers());
  }
}
