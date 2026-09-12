package vn.edu.swd392.vpmcp.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.edu.swd392.vpmcp.bridge.BridgeException;

class UseCaseDiagramSpecValidatorTest {
  @Test
  void dryRunAddsExtraWhitespaceBetweenBusinessGroups() throws Exception {
    UseCaseDiagramSpec spec = validSpec();
    spec.useCases.get(1).group = "payment";
    spec.layout = new UseCaseDiagramSpec.LayoutSpec();
    spec.layout.rowGap = 20;
    spec.layout.groupGap = 80;

    Map<String, Object> result = new UseCaseDiagramTools().createUseCaseDiagram(spec, true);
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> plan =
        (Map<String, Map<String, Object>>) result.get("plan");
    int bookY = ((Number) plan.get("book").get("y")).intValue();
    int payY = ((Number) plan.get("pay").get("y")).intValue();

    assertTrue(payY - bookY >= spec.layout.useCaseHeight + 100);
  }

  @Test
  void dryRunAlignsRelatedUseCasesAcrossLanesWithinOneBusinessGroup() throws Exception {
    UseCaseDiagramSpec spec = validSpec();
    spec.useCases.get(1).lane = "RIGHT";

    Map<String, Object> result = new UseCaseDiagramTools().createUseCaseDiagram(spec, true);
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> plan =
        (Map<String, Map<String, Object>>) result.get("plan");

    assertEquals(plan.get("book").get("y"), plan.get("pay").get("y"));
  }

  @Test
  void dryRunSeparatesActorsThatTargetTheSameUseCase() throws Exception {
    UseCaseDiagramSpec spec = validSpec();
    UseCaseDiagramSpec.ActorSpec agent = actor("agent", "Booking Agent", "BOOKING");
    spec.actors.add(agent);
    spec.associations.add(association("agent-book", "agent", "book", "BOOKING"));

    Map<String, Object> result = new UseCaseDiagramTools().createUseCaseDiagram(spec, true);
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> plan =
        (Map<String, Map<String, Object>>) result.get("plan");
    int guestY = ((Number) plan.get("guest").get("y")).intValue();
    int agentY = ((Number) plan.get("agent").get("y")).intValue();

    assertTrue(
        Math.abs(agentY - guestY)
            >= spec.layout.actorHeight + 40);
  }

  @Test
  void acceptsTraceableConditionalExtendAndMandatoryInclude() {
    assertDoesNotThrow(() -> UseCaseDiagramSpecValidator.validate(validSpec()));
  }

  @Test
  void rejectsExtendWithoutCondition() {
    UseCaseDiagramSpec spec = validSpec();
    spec.relationships.get(0).condition = "";

    assertThrows(
        BridgeException.class, () -> UseCaseDiagramSpecValidator.validate(spec));
  }

  @Test
  void rejectsConditionalInclude() {
    UseCaseDiagramSpec spec = validSpec();
    spec.relationships.get(1).condition = "sometimes";

    assertThrows(
        BridgeException.class, () -> UseCaseDiagramSpecValidator.validate(spec));
  }

  @Test
  void rejectsRelationshipWithoutRationaleOrKnownEvidence() {
    UseCaseDiagramSpec missingRationale = validSpec();
    missingRationale.relationships.get(0).rationale = "";
    assertThrows(
        BridgeException.class,
        () -> UseCaseDiagramSpecValidator.validate(missingRationale));

    UseCaseDiagramSpec unknownFact = validSpec();
    unknownFact.relationships.get(0).factIds = new ArrayList<>(List.of("UNKNOWN"));
    assertThrows(
        BridgeException.class, () -> UseCaseDiagramSpecValidator.validate(unknownFact));
  }

  private static UseCaseDiagramSpec validSpec() {
    UseCaseDiagramSpec spec = new UseCaseDiagramSpec();
    spec.operationId = "use-case-semantic-test";
    spec.diagramName = "Booking Use Cases";
    spec.systemName = "Booking System";
    spec.facts =
        new ArrayList<>(
            List.of(
                fact("BOOKING", "Payment occurs only after a booking is approved."),
                fact("SUPPORT", "Resolving a dispute requires reviewing its records first.")));
    spec.actors = new ArrayList<>(List.of(actor("guest", "Guest", "BOOKING")));
    spec.useCases =
        new ArrayList<>(
            List.of(
                useCase("book", "Book Accommodation", "BOOKING"),
                useCase("pay", "Complete Payment", "BOOKING"),
                useCase("resolve", "Resolve Dispute", "SUPPORT"),
                useCase("review", "Review Case", "SUPPORT")));
    spec.associations =
        new ArrayList<>(
            List.of(
                association("guest-book", "guest", "book", "BOOKING"),
                association("guest-resolve", "guest", "resolve", "SUPPORT")));
    spec.relationships =
        new ArrayList<>(
            List.of(
                relationship(
                    "payment-extends-booking",
                    "EXTEND",
                    "pay",
                    "book",
                    "booking approved",
                    "Payment is inserted only after approval.",
                    "BOOKING"),
                relationship(
                    "resolve-includes-review",
                    "INCLUDE",
                    "resolve",
                    "review",
                    "",
                    "The case must be reviewed before it can be resolved.",
                    "SUPPORT")));
    return spec;
  }

  private static UseCaseDiagramSpec.FactSpec fact(String id, String text) {
    UseCaseDiagramSpec.FactSpec fact = new UseCaseDiagramSpec.FactSpec();
    fact.id = id;
    fact.text = text;
    return fact;
  }

  private static UseCaseDiagramSpec.ActorSpec actor(String id, String name, String factId) {
    UseCaseDiagramSpec.ActorSpec actor = new UseCaseDiagramSpec.ActorSpec();
    actor.id = id;
    actor.name = name;
    actor.factIds = new ArrayList<>(List.of(factId));
    return actor;
  }

  private static UseCaseDiagramSpec.UseCaseSpec useCase(
      String id, String name, String factId) {
    UseCaseDiagramSpec.UseCaseSpec useCase = new UseCaseDiagramSpec.UseCaseSpec();
    useCase.id = id;
    useCase.name = name;
    useCase.description = name;
    useCase.group = "booking";
    useCase.factIds = new ArrayList<>(List.of(factId));
    return useCase;
  }

  private static UseCaseDiagramSpec.AssociationSpec association(
      String id, String actorId, String useCaseId, String factId) {
    UseCaseDiagramSpec.AssociationSpec association = new UseCaseDiagramSpec.AssociationSpec();
    association.id = id;
    association.actorId = actorId;
    association.useCaseId = useCaseId;
    association.factIds = new ArrayList<>(List.of(factId));
    return association;
  }

  private static UseCaseDiagramSpec.RelationshipSpec relationship(
      String id,
      String type,
      String from,
      String to,
      String condition,
      String rationale,
      String factId) {
    UseCaseDiagramSpec.RelationshipSpec relationship =
        new UseCaseDiagramSpec.RelationshipSpec();
    relationship.id = id;
    relationship.type = type;
    relationship.from = from;
    relationship.to = to;
    relationship.condition = condition;
    relationship.rationale = rationale;
    relationship.factIds = new ArrayList<>(List.of(factId));
    return relationship;
  }
}
