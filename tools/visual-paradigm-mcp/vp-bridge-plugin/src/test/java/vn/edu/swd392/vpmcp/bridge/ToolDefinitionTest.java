package vn.edu.swd392.vpmcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.edu.swd392.vpmcp.tools.ContextDiagramTools;

class ToolDefinitionTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void scansSchemaAndExecutesAnnotatedMethod() throws Exception {
    List<ToolDefinition> tools = ToolDefinition.scan(new ExampleTools(), mapper);

    assertEquals(1, tools.size());
    ObjectNode json = tools.get(0).toJson(mapper);
    assertEquals("draw", json.path("name").asText());
    assertEquals("string", json.at("/inputSchema/properties/diagramName/type").asText());
    assertFalse(json.at("/inputSchema/properties/diagramName/description").asText().isBlank());

    ObjectNode arguments = mapper.createObjectNode();
    arguments.put("diagramName", "State - EMS");
    arguments.put("x", 120);
    assertEquals("State - EMS@120", tools.get(0).execute(arguments));
  }

  @Test
  void rejectsMissingWrongTypeAndUnknownArgumentsBeforeInvocation() {
    ToolDefinition tool = ToolDefinition.scan(new ExampleTools(), mapper).get(0);

    ObjectNode missing = mapper.createObjectNode();
    missing.put("diagramName", "State - EMS");
    assertThrows(BridgeException.class, () -> tool.execute(missing));

    ObjectNode wrongType = mapper.createObjectNode();
    wrongType.put("diagramName", "State - EMS");
    wrongType.put("x", "120");
    assertThrows(BridgeException.class, () -> tool.execute(wrongType));

    ObjectNode unknown = mapper.createObjectNode();
    unknown.put("diagramName", "State - EMS");
    unknown.put("x", 120);
    unknown.put("surprise", true);
    assertThrows(BridgeException.class, () -> tool.execute(unknown));
  }

  @Test
  void convertsAndDryRunsTheStrictDeclarativeContextSchema() throws Exception {
    ToolDefinition tool =
        ToolDefinition.scan(new ContextDiagramTools(), mapper).stream()
            .filter(candidate -> "createContextDiagram".equals(candidate.name()))
            .findFirst()
            .orElseThrow();
    ObjectNode arguments =
        (ObjectNode)
            mapper.readTree(
                "{"
                    + "\"spec\":{"
                    + "\"operationId\":\"context-contract-test\","
                    + "\"diagramName\":\"Context - Booking\","
                    + "\"systemName\":\"Booking System\","
                    + "\"entities\":[{\"id\":\"customer\",\"name\":\"Customer\"}],"
                    + "\"flows\":[{"
                    + "\"id\":\"request\","
                    + "\"name\":\"Booking request\","
                    + "\"from\":\"customer\","
                    + "\"to\":\"system\""
                    + "}]"
                    + "},"
                    + "\"dryRun\":true"
                    + "}");

    @SuppressWarnings("unchecked")
    var result = (java.util.Map<String, Object>) tool.execute(arguments);

    assertEquals(false, result.get("applied"));
    assertEquals("Context - Booking", result.get("diagramName"));
  }

  static final class ExampleTools {
    @Tool(name = "draw", description = "Draw an example")
    public String draw(String diagramName, int x) {
      return diagramName + "@" + x;
    }
  }
}
