package eu.wohlben.qits.agents;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The surface's ambient facts, substituted into a configured initial prompt.
 *
 * <p>A template is written once, in the editor, for a surface that will run in many containers:
 * "Continue the work on {@code {{epic}}} in {@code {{repository}}}" is the same sentence in every
 * workspace and a different sentence in each. So the prompt is templated over what the container
 * knows about itself, and the names below are what it knows — {@link #NAMES} is exactly the list the
 * editor shows beside the field, because a template written against a name that does not exist is a
 * prompt that reads as configured and renders as a placeholder.
 *
 * <p><b>An unresolvable placeholder is left literal.</b> {@code {{ticket}}} in a workspace that was
 * not cut for a ticket renders as {@code {{ticket}}} and not as {@code null} or as nothing. The
 * agent reading it can see that a fact was expected and is missing, which is a sentence a human can
 * act on; "null" is a value that looks like data, and silence is a sentence with a hole in it that
 * nobody can tell from a sentence that was always that way.
 *
 * <p>The syntax is deliberately not a language. No conditionals, no loops, no expressions — a
 * placeholder is a name in double braces and everything else is text. A prompt is prose an operator
 * writes; anything that needed a conditional is two surfaces.
 */
public final class AgentPromptTemplate {

  private AgentPromptTemplate() {}

  /** {@code {{name}}}, where a name is a bare identifier — nothing else is special. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

  /**
   * The facts a template may name, in the order the editor lists them.
   *
   * <p>{@code branch} and {@code commit} are answered by the library from {@link
   * eu.wohlben.qits.commands.CheckoutContext}; the rest are the host's, through {@link
   * AgentDefaults#ambientFacts()}, because a project id, an epic slug, a workspace id and a ticket
   * key are things a daemon knows about itself and this library deliberately does not. A host that
   * answers nothing simply leaves those placeholders literal.
   */
  public static final List<String> NAMES =
      List.of("project", "epic", "repository", "branch", "workspace", "ticket", "commit");

  /**
   * {@code template} with every placeholder naming a non-blank fact replaced by it. A blank or
   * absent fact leaves its placeholder exactly as it was written.
   */
  public static String render(String template, Map<String, String> facts) {
    if (template == null || template.isBlank() || !template.contains("{{")) {
      return template == null ? "" : template;
    }
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder rendered = new StringBuilder();
    while (matcher.find()) {
      String value = facts == null ? null : facts.get(matcher.group(1));
      matcher.appendReplacement(
          rendered,
          Matcher.quoteReplacement(value == null || value.isBlank() ? matcher.group() : value));
    }
    matcher.appendTail(rendered);
    return rendered.toString();
  }
}
