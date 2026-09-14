package vn.edu.swd392.vpmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.edu.swd392.vpmcp.bridge.BridgeException;

class ContextDiagramToolsTest {
  @Test
  void dryRunBuildsCircularRadialOfficialStylePlanWithoutVisualParadigm() throws Exception {
    ContextDiagramSpec spec = validSpec();

    Map<String, Object> result = new ContextDiagramTools().createContextDiagram(spec, true);

    assertFalse((Boolean) result.get("applied"));
    assertEquals("DataFlowDiagram", result.get("notation"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> plan = (List<Map<String, Object>>) result.get("plan");
    assertEquals(5, plan.size());
    Map<String, Object> process = plan.get(0);
    assertEquals("PROCESS", process.get("kind"));
    assertEquals(process.get("width"), process.get("height"));

    Set<String> relativeSides = new HashSet<>();
    int centerX = ((Number) process.get("x")).intValue()
        + ((Number) process.get("width")).intValue() / 2;
    int centerY = ((Number) process.get("y")).intValue()
        + ((Number) process.get("height")).intValue() / 2;
    for (Map<String, Object> entity : plan.subList(1, plan.size())) {
      int entityCenterX =
          ((Number) entity.get("x")).intValue()
              + ((Number) entity.get("width")).intValue() / 2;
      int entityCenterY =
          ((Number) entity.get("y")).intValue()
              + ((Number) entity.get("height")).intValue() / 2;
      if (Math.abs(entityCenterX - centerX) > Math.abs(entityCenterY - centerY)) {
        relativeSides.add(entityCenterX < centerX ? "LEFT" : "RIGHT");
      } else {
        relativeSides.add(entityCenterY < centerY ? "TOP" : "BOTTOM");
      }
    }
    assertEquals(Set.of("TOP", "RIGHT", "BOTTOM", "LEFT"), relativeSides);
    @SuppressWarnings("unchecked")
    Map<String, Object> validation = (Map<String, Object>) result.get("validation");
    assertTrue((Boolean) validation.get("valid"));
  }

  @Test
  void rejectsInternalEntityToEntityFlowAndNonCircularProcess() {
    ContextDiagramSpec badFlow = validSpec();
    badFlow.flows.get(0).from = "customer";
    badFlow.flows.get(0).to = "bank";
    assertThrows(
        BridgeException.class,
        () -> new ContextDiagramTools().createContextDiagram(badFlow, true));

    ContextDiagramSpec badLayout = validSpec();
    badLayout.layout = new ContextDiagramSpec.LayoutSpec();
    badLayout.layout.processHeight = 260;
    assertThrows(
        BridgeException.class,
        () -> new ContextDiagramTools().createContextDiagram(badLayout, true));
  }

  private static ContextDiagramSpec validSpec() {
    ContextDiagramSpec spec = new ContextDiagramSpec();
    spec.operationId = "context-test-operation";
    spec.diagramName = "Context - Booking";
    spec.systemName = "Booking System";
    spec.entities =
        new ArrayList<>(
            List.of(
                entity("customer", "Customer"),
                entity("bank", "Bank"),
                entity("schedule", "Time / Schedule"),
                entity("reservation", "External Reservation System")));
    spec.flows =
        new ArrayList<>(
            List.of(
                flow("booking-request", "Booking request", "customer", "system"),
                flow("payment-validation", "Payment validation request", "system", "bank"),
                flow("current-time", "Current time", "schedule", "system"),
                flow(
                    "external-booking",
                    "Booking confirmation",
                    "reservation",
                    "system")));
    return spec;
  }

  private static ContextDiagramSpec.EntitySpec entity(String id, String name) {
    ContextDiagramSpec.EntitySpec entity = new ContextDiagramSpec.EntitySpec();
    entity.id = id;
    entity.name = name;
    return entity;
  }

  private static ContextDiagramSpec.FlowSpec flow(
      String id, String name, String from, String to) {
    ContextDiagramSpec.FlowSpec flow = new ContextDiagramSpec.FlowSpec();
    flow.id = id;
    flow.name = name;
    flow.from = from;
    flow.to = to;
    return flow;
  }
}
