package eu.wohlben.qits.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The substitution behind a surface's initial prompt: what it fills in, and what it leaves alone. */
class AgentPromptTemplateTest {

  private static final Map<String, String> FACTS =
      Map.of(
          "project", "qits",
          "epic", "agent-configuration-system",
          "repository", "qits-coding-agents",
          "branch", "epic/agent-configuration-system");

  @Test
  void aNamedFactIsSubstituted() {
    assertEquals(
        "Continue agent-configuration-system in qits-coding-agents on"
            + " epic/agent-configuration-system.",
        AgentPromptTemplate.render(
            "Continue {{epic}} in {{repository}} on {{branch}}.", FACTS));
  }

  @Test
  void anUnresolvableNameIsLeftLiteral() {
    // Not "null", and not nothing. An agent reading {{ticket}} can see a fact was expected and is
    // missing, which is a sentence a human can act on; "null" looks like data and a hole reads like
    // a sentence that was always that way.
    assertEquals(
        "Fix {{ticket}} in qits.", AgentPromptTemplate.render("Fix {{ticket}} in {{project}}.", FACTS));
    assertEquals("{{nonesuch}}", AgentPromptTemplate.render("{{nonesuch}}", FACTS));
  }

  @Test
  void ablankFactCountsAsAbsent() {
    Map<String, String> facts = new HashMap<>();
    facts.put("ticket", "   ");
    assertEquals("{{ticket}}", AgentPromptTemplate.render("{{ticket}}", facts));
  }

  @Test
  void whitespaceInsideTheBracesIsTolerated() {
    assertEquals("qits", AgentPromptTemplate.render("{{ project }}", FACTS));
  }

  @Test
  void aPromptWithNoPlaceholdersIsItself() {
    assertEquals(
        "You are this project's tickets front desk.",
        AgentPromptTemplate.render("You are this project's tickets front desk.", FACTS));
    assertEquals("", AgentPromptTemplate.render(null, FACTS));
  }

  @Test
  void aSubstitutedValueIsNotItselfATemplate() {
    // A fact carrying braces or a backslash must not re-enter the substitution or corrupt it — the
    // values come from a container's own environment, but the rule is worth pinning.
    assertEquals(
        "{{epic}}", AgentPromptTemplate.render("{{project}}", Map.of("project", "{{epic}}")));
    assertEquals("a\\b", AgentPromptTemplate.render("{{project}}", Map.of("project", "a\\b")));
  }

  @Test
  void theNamesAreTheOnesTheEditorWillShow() {
    // The list is a contract with the editor: a template written against a name that does not exist
    // is a prompt that reads as configured and renders as a placeholder.
    assertEquals(
        List.of("project", "epic", "repository", "branch", "workspace", "ticket", "commit"),
        AgentPromptTemplate.NAMES);
    assertTrue(AgentPromptTemplate.NAMES.contains("workspace"));
  }
}
