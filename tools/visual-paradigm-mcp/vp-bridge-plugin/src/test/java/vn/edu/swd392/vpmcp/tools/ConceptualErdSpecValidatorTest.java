package vn.edu.swd392.vpmcp.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.edu.swd392.vpmcp.bridge.BridgeException;

class ConceptualErdSpecValidatorTest {
  @Test
  void acceptsFactGroundedColumnFreeConceptualModel() {
    assertDoesNotThrow(() -> ConceptualErdSpecValidator.validate(validSpec()));
  }

  @Test
  void dryRunProducesDeterministicGridBounds() throws Exception {
    ConceptualErdSpec spec = validSpec();
    spec.layout = new ConceptualErdSpec.LayoutSpec();
    spec.layout.originX = 100;
    spec.layout.originY = 120;
    spec.layout.entityWidth = 200;
    spec.layout.entityHeight = 60;
    spec.layout.horizontalGap = 100;
    spec.layout.verticalGap = 90;

    Map<String, Object> result = new ConceptualErdTools().createConceptualErd(spec, true);
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> plan =
        (Map<String, Map<String, Object>>) result.get("plan");

    assertEquals(150, ((Number) plan.get("account").get("x")).intValue());
    assertEquals(120, ((Number) plan.get("account").get("y")).intValue());
    assertEquals(450, ((Number) plan.get("booking").get("x")).intValue());
    assertEquals(270, ((Number) plan.get("booking").get("y")).intValue());
  }

  @Test
  void rejectsUnknownFactAndMissingRelationships() {
    ConceptualErdSpec unknownFact = validSpec();
    unknownFact.entities.get(0).factIds = new ArrayList<>(List.of("UNKNOWN"));
    assertThrows(
        BridgeException.class, () -> ConceptualErdSpecValidator.validate(unknownFact));

    ConceptualErdSpec orphan = validSpec();
    orphan.relationships.clear();
    assertThrows(BridgeException.class, () -> ConceptualErdSpecValidator.validate(orphan));
  }

  @Test
  void rejectsDuplicateSlotAndInvalidCardinality() {
    ConceptualErdSpec duplicateSlot = validSpec();
    duplicateSlot.entities.get(1).column = duplicateSlot.entities.get(0).column;
    duplicateSlot.entities.get(1).row = duplicateSlot.entities.get(0).row;
    assertThrows(
        BridgeException.class, () -> ConceptualErdSpecValidator.validate(duplicateSlot));

    ConceptualErdSpec invalidCardinality = validSpec();
    invalidCardinality.relationships.get(0).toCardinality = "MANY";
    assertThrows(
        BridgeException.class,
        () -> ConceptualErdSpecValidator.validate(invalidCardinality));
  }

  private static ConceptualErdSpec validSpec() {
    ConceptualErdSpec spec = new ConceptualErdSpec();
    spec.operationId = "conceptual-erd-test";
    spec.diagramName = "Marketplace Conceptual ERD";
    spec.facts =
        new ArrayList<>(
            List.of(
                fact("ACCOUNT", "Guests place booking requests."),
                fact("BOOKING", "Bookings retain lifecycle status.")));
    spec.entities =
        new ArrayList<>(
            List.of(
                entity("account", "Account", "Marketplace user identity.", 0, 0, "ACCOUNT"),
                entity("booking", "Booking", "Accommodation reservation.", 1, 1, "BOOKING")));
    spec.relationships =
        new ArrayList<>(
            List.of(
                relationship(
                    "account-places-booking",
                    "places",
                    "account",
                    "booking",
                    "ONE",
                    "ZERO_OR_MANY",
                    "One account may place many bookings.",
                    "ACCOUNT")));
    return spec;
  }

  private static ConceptualErdSpec.FactSpec fact(String id, String text) {
    ConceptualErdSpec.FactSpec fact = new ConceptualErdSpec.FactSpec();
    fact.id = id;
    fact.text = text;
    return fact;
  }

  private static ConceptualErdSpec.EntitySpec entity(
      String id, String name, String description, int column, int row, String factId) {
    ConceptualErdSpec.EntitySpec entity = new ConceptualErdSpec.EntitySpec();
    entity.id = id;
    entity.name = name;
    entity.description = description;
    entity.column = column;
    entity.row = row;
    entity.factIds = new ArrayList<>(List.of(factId));
    return entity;
  }

  private static ConceptualErdSpec.RelationshipSpec relationship(
      String id,
      String name,
      String from,
      String to,
      String fromCardinality,
      String toCardinality,
      String rationale,
      String factId) {
    ConceptualErdSpec.RelationshipSpec relationship =
        new ConceptualErdSpec.RelationshipSpec();
    relationship.id = id;
    relationship.name = name;
    relationship.from = from;
    relationship.to = to;
    relationship.fromCardinality = fromCardinality;
    relationship.toCardinality = toCardinality;
    relationship.rationale = rationale;
    relationship.factIds = new ArrayList<>(List.of(factId));
    return relationship;
  }
}
