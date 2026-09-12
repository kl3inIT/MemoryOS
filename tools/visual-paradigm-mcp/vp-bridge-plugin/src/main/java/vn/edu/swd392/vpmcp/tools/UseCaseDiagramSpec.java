package vn.edu.swd392.vpmcp.tools;

import java.util.ArrayList;
import java.util.List;

public final class UseCaseDiagramSpec {
  public String operationId;
  public String diagramName;
  public String systemName;
  public List<FactSpec> facts = new ArrayList<>();
  public List<ActorSpec> actors = new ArrayList<>();
  public List<UseCaseSpec> useCases = new ArrayList<>();
  public List<AssociationSpec> associations = new ArrayList<>();
  public List<RelationshipSpec> relationships = new ArrayList<>();
  public LayoutSpec layout;

  public static final class FactSpec {
    public String id;
    public String text;
  }

  public static final class ActorSpec {
    public String id;
    public String name;
    public String side = "LEFT";
    public List<String> factIds = new ArrayList<>();
  }

  public static final class UseCaseSpec {
    public String id;
    public String name;
    public String description;
    public String lane = "LEFT";
    public String group;
    public boolean abstractUseCase;
    public List<String> factIds = new ArrayList<>();
  }

  public static final class AssociationSpec {
    public String id;
    public String actorId;
    public String useCaseId;
    public List<String> factIds = new ArrayList<>();
  }

  public static final class RelationshipSpec {
    public String id;
    public String type;
    public String from;
    public String to;
    public String condition;
    public String rationale;
    public List<String> factIds = new ArrayList<>();
  }

  public static final class LayoutSpec {
    public int boundaryX = 520;
    public int boundaryY = 80;
    public int boundaryWidth = 900;
    public int useCaseWidth = 250;
    public int useCaseHeight = 62;
    public int rowGap = 34;
    public int groupGap = 50;
    public int topPadding = 90;
    public int bottomPadding = 70;
    public int actorWidth = 170;
    public int actorHeight = 130;
    public int actorGap = 180;
    public String connectorStyle = "OBLIQUE";
  }
}
