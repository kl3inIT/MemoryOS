package vn.edu.swd392.vpmcp.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import vn.edu.swd392.vpmcp.bridge.BridgeException;

final class UseCaseDiagramSpecValidator {
  private UseCaseDiagramSpecValidator() {}

  static UseCaseDiagramSpec validate(UseCaseDiagramSpec spec) {
    if (spec == null) {
      throw BridgeException.invalidArgument("$.spec", "Use Case Diagram specification is required");
    }
    text(spec.operationId, "$.spec.operationId");
    text(spec.diagramName, "$.spec.diagramName");
    text(spec.systemName, "$.spec.systemName");
    if (spec.facts == null || spec.facts.isEmpty()) {
      throw BridgeException.invalidArgument(
          "$.spec.facts", "At least one verified requirement fact is required");
    }
    if (spec.actors == null || spec.actors.isEmpty()) {
      throw BridgeException.invalidArgument("$.spec.actors", "At least one actor is required");
    }
    if (spec.useCases == null || spec.useCases.isEmpty()) {
      throw BridgeException.invalidArgument("$.spec.useCases", "At least one use case is required");
    }
    if (spec.associations == null || spec.associations.isEmpty()) {
      throw BridgeException.invalidArgument(
          "$.spec.associations", "At least one actor-to-use-case association is required");
    }
    if (spec.relationships == null) {
      spec.relationships = new java.util.ArrayList<>();
    }

    Set<String> allIds = new HashSet<>();
    Set<String> factIds = new HashSet<>();
    for (int i = 0; i < spec.facts.size(); i++) {
      UseCaseDiagramSpec.FactSpec fact = spec.facts.get(i);
      text(fact.id, "$.spec.facts[" + i + "].id");
      text(fact.text, "$.spec.facts[" + i + "].text");
      if (!factIds.add(fact.id)) {
        throw BridgeException.invalidArgument(
            "$.spec.facts[" + i + "].id", "Fact IDs must be unique");
      }
    }
    Set<String> actorIds = new HashSet<>();
    Map<String, String> actorSides = new HashMap<>();
    for (int i = 0; i < spec.actors.size(); i++) {
      UseCaseDiagramSpec.ActorSpec actor = spec.actors.get(i);
      text(actor.id, "$.spec.actors[" + i + "].id");
      text(actor.name, "$.spec.actors[" + i + "].name");
      unique(allIds, actor.id, "$.spec.actors[" + i + "].id");
      actorIds.add(actor.id);
      String side = upper(actor.side);
      if (!"LEFT".equals(side) && !"RIGHT".equals(side)) {
        throw BridgeException.invalidArgument(
            "$.spec.actors[" + i + "].side", "Actor side must be LEFT or RIGHT");
      }
      actor.side = side;
      actorSides.put(actor.id, side);
      verifiedFacts(actor.factIds, factIds, "$.spec.actors[" + i + "].factIds");
    }

    Set<String> useCaseIds = new HashSet<>();
    for (int i = 0; i < spec.useCases.size(); i++) {
      UseCaseDiagramSpec.UseCaseSpec useCase = spec.useCases.get(i);
      text(useCase.id, "$.spec.useCases[" + i + "].id");
      text(useCase.name, "$.spec.useCases[" + i + "].name");
      text(useCase.group, "$.spec.useCases[" + i + "].group");
      unique(allIds, useCase.id, "$.spec.useCases[" + i + "].id");
      useCaseIds.add(useCase.id);
      String lane = upper(useCase.lane);
      if (!"LEFT".equals(lane) && !"RIGHT".equals(lane)) {
        throw BridgeException.invalidArgument(
            "$.spec.useCases[" + i + "].lane", "Use-case lane must be LEFT or RIGHT");
      }
      useCase.lane = lane;
      verifiedFacts(useCase.factIds, factIds, "$.spec.useCases[" + i + "].factIds");
    }

    Set<String> connectedActors = new HashSet<>();
    Set<String> connectedUseCases = new HashSet<>();
    Set<String> pairs = new HashSet<>();
    for (int i = 0; i < spec.associations.size(); i++) {
      UseCaseDiagramSpec.AssociationSpec association = spec.associations.get(i);
      text(association.id, "$.spec.associations[" + i + "].id");
      unique(allIds, association.id, "$.spec.associations[" + i + "].id");
      if (!actorIds.contains(association.actorId)) {
        throw BridgeException.invalidArgument(
            "$.spec.associations[" + i + "].actorId", "Unknown actor ID");
      }
      if (!useCaseIds.contains(association.useCaseId)) {
        throw BridgeException.invalidArgument(
            "$.spec.associations[" + i + "].useCaseId", "Unknown use-case ID");
      }
      verifiedFacts(
          association.factIds, factIds, "$.spec.associations[" + i + "].factIds");
      if (!pairs.add(association.actorId + "\u0000" + association.useCaseId)) {
        throw BridgeException.invalidArgument(
            "$.spec.associations[" + i + "]", "Duplicate actor-to-use-case association");
      }
      connectedActors.add(association.actorId);
      connectedUseCases.add(association.useCaseId);
    }

    for (int i = 0; i < spec.relationships.size(); i++) {
      UseCaseDiagramSpec.RelationshipSpec relationship = spec.relationships.get(i);
      text(relationship.id, "$.spec.relationships[" + i + "].id");
      unique(allIds, relationship.id, "$.spec.relationships[" + i + "].id");
      String type = upper(relationship.type);
      if (!"INCLUDE".equals(type)
          && !"EXTEND".equals(type)
          && !"GENERALIZATION".equals(type)) {
        throw BridgeException.invalidArgument(
            "$.spec.relationships[" + i + "].type",
            "Relationship type must be INCLUDE, EXTEND, or GENERALIZATION");
      }
      relationship.type = type;
      text(relationship.rationale, "$.spec.relationships[" + i + "].rationale");
      verifiedFacts(
          relationship.factIds, factIds, "$.spec.relationships[" + i + "].factIds");
      if (relationship.from == null || relationship.from.equals(relationship.to)) {
        throw BridgeException.invalidArgument(
            "$.spec.relationships[" + i + "]", "Relationship endpoints must be different");
      }
      if ("GENERALIZATION".equals(type)) {
        boolean actorPair =
            actorIds.contains(relationship.from) && actorIds.contains(relationship.to);
        boolean useCasePair =
            useCaseIds.contains(relationship.from) && useCaseIds.contains(relationship.to);
        if (!actorPair && !useCasePair) {
          throw BridgeException.invalidArgument(
              "$.spec.relationships[" + i + "]",
              "Generalization endpoints must both be actors or both be use cases");
        }
      } else if (!useCaseIds.contains(relationship.from)
          || !useCaseIds.contains(relationship.to)) {
        throw BridgeException.invalidArgument(
            "$.spec.relationships[" + i + "]",
            "Include and extend endpoints must both be use cases");
      }
      String condition = relationship.condition == null ? "" : relationship.condition.trim();
      relationship.condition = condition;
      if ("INCLUDE".equals(type) && !condition.isEmpty()) {
        throw BridgeException.invalidArgument(
            "$.spec.relationships[" + i + "].condition",
            "INCLUDE is unconditional mandatory reuse; condition must be blank");
      }
      if ("EXTEND".equals(type) && condition.isEmpty()) {
        throw BridgeException.invalidArgument(
            "$.spec.relationships[" + i + "].condition",
            "EXTEND is conditional optional behavior and requires an explicit condition");
      }
      if ("GENERALIZATION".equals(type) && !condition.isEmpty()) {
        throw BridgeException.invalidArgument(
            "$.spec.relationships[" + i + "].condition",
            "GENERALIZATION does not accept an execution condition");
      }
      if (useCaseIds.contains(relationship.from)) {
        connectedUseCases.add(relationship.from);
      }
      if (useCaseIds.contains(relationship.to)) {
        connectedUseCases.add(relationship.to);
      }
    }

    if (!connectedActors.containsAll(actorIds)) {
      throw BridgeException.invalidArgument("$.spec.associations", "Every actor must participate");
    }
    if (!connectedUseCases.containsAll(useCaseIds)) {
      throw BridgeException.invalidArgument(
          "$.spec", "Every use case must be associated or related to another use case");
    }

    UseCaseDiagramSpec.LayoutSpec layout =
        spec.layout == null ? new UseCaseDiagramSpec.LayoutSpec() : spec.layout;
    spec.layout = layout;
    if (layout.boundaryWidth < 650
        || layout.useCaseWidth < 160
        || layout.useCaseHeight < 45
        || layout.rowGap < 15
        || layout.groupGap < 0
        || layout.actorWidth < 40
        || layout.actorHeight < 90) {
      throw BridgeException.invalidArgument("$.spec.layout", "Use Case layout is too compact");
    }
    String connectorStyle = upper(layout.connectorStyle);
    if (!"OBLIQUE".equals(connectorStyle) && !"RECTILINEAR".equals(connectorStyle)) {
      throw BridgeException.invalidArgument(
          "$.spec.layout.connectorStyle", "connectorStyle must be OBLIQUE or RECTILINEAR");
    }
    layout.connectorStyle = connectorStyle;
    return spec;
  }

  private static void text(String value, String path) {
    if (value == null || value.trim().isEmpty()) {
      throw BridgeException.invalidArgument(path, "Value must not be blank");
    }
  }

  private static void unique(Set<String> ids, String id, String path) {
    if (!ids.add(id)) {
      throw BridgeException.invalidArgument(path, "Stable IDs must be unique");
    }
  }

  private static void verifiedFacts(
      java.util.List<String> references, Set<String> knownFactIds, String path) {
    if (references == null || references.isEmpty()) {
      throw BridgeException.invalidArgument(
          path, "At least one verified requirement fact must support this element");
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < references.size(); i++) {
      String factId = references.get(i);
      text(factId, path + "[" + i + "]");
      if (!knownFactIds.contains(factId)) {
        throw BridgeException.invalidArgument(path + "[" + i + "]", "Unknown fact ID");
      }
      if (!seen.add(factId)) {
        throw BridgeException.invalidArgument(path + "[" + i + "]", "Duplicate fact reference");
      }
    }
  }

  private static String upper(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
