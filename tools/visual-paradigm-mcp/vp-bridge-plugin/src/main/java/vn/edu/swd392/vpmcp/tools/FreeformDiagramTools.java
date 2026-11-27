package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.connector.IArrowHead;
import com.vp.plugin.diagram.connector.IGenericConnectorUIModel;
import com.vp.plugin.diagram.format.IFillColor;
import com.vp.plugin.diagram.format.LineStyle;
import com.vp.plugin.diagram.shape.ICaptionConfigurableShapeUIModel;
import com.vp.plugin.diagram.shape.IRectangleUIModel;
import com.vp.plugin.model.IGenericConnector;
import com.vp.plugin.model.IModelElement;
import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.font.FontRenderContext;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import vn.edu.swd392.vpmcp.bridge.Tool;

/**
 * Generic ovals, rectangles and connectors that every Visual Paradigm edition can draw on a UML
 * diagram. They carry no UML or DFD semantics, so use them only when the native notation is not
 * available in the installed edition.
 */
public final class FreeformDiagramTools extends VpAccess {
  private static final int ROUNDED_CORNER = 16;

  @Tool(
      description =
          "Add a generic shape to an existing diagram. shapeType is oval, rectangle, or "
              + "rounded-rectangle. fillColor and lineColor are #RRGGBB or empty for the default. "
              + "fontSize 0 keeps the default font size. Returns the element ID used by the other "
              + "freeform tools.")
  public Map<String, Object> addFreeformShape(
      String diagramName,
      String name,
      String shapeType,
      int x,
      int y,
      int width,
      int height,
      String fillColor,
      String lineColor,
      int fontSize,
      boolean bold)
      throws Exception {
    requireText(name, "name");
    String type = clean(shapeType).toLowerCase(Locale.ROOT);
    if (!type.equals("oval") && !type.equals("rectangle") && !type.equals("rounded-rectangle")) {
      throw new IllegalArgumentException(
          "shapeType must be oval, rectangle, or rounded-rectangle");
    }
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("width and height must be positive");
    }
    Color fill = parseColor(fillColor, "fillColor");
    Color line = parseColor(lineColor, "lineColor");
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IModelElement model =
              type.equals("oval") ? models().createOval() : models().createRectangle();
          model.setName(name);
          IDiagramElement element = diagrams().createDiagramElement(diagram, model);
          element.setBounds(x, y, width, height);
          if (element instanceof IRectangleUIModel && type.equals("rounded-rectangle")) {
            ((IRectangleUIModel) element).setRoundedValue(ROUNDED_CORNER);
          }
          if (element instanceof ICaptionConfigurableShapeUIModel) {
            ((ICaptionConfigurableShapeUIModel) element)
                .setCaptionPlacement(ICaptionConfigurableShapeUIModel.CENTER);
          }
          if (fill != null && element instanceof IShapeUIModel) {
            IShapeUIModel shape = (IShapeUIModel) element;
            shape.getFillColor().setType(IFillColor.TYPE_SOLID);
            shape.getFillColor().setColor1(fill);
            shape.getFillColor().setColor2(fill);
            shape.getFillColor().setTransparency(IFillColor.OPAQUE);
            shape.getFillColor().applySetting();
          }
          if (line != null) {
            element.getLineModel().setColor(line);
            element.getLineModel().applySetting();
          }
          if (fontSize > 0) {
            element.getElementFont().setSize(fontSize);
          }
          element.getElementFont().setBold(bold);
          fitCaptionToBounds(element);
          return elementResult(diagram, element);
        });
  }

  @Tool(
      description =
          "Connect two freeform shapes with a named generic connector that carries one solid "
              + "arrowhead, at the target or, with arrowAtSource, at the source. pointsCsv is ordered x:y pairs including both endpoints, or empty "
              + "to let Visual Paradigm route it. lineStyle is a Visual Paradigm LineStyle name such as None or "
              + "Style2, or empty for a solid line.")
  public Map<String, Object> addFreeformConnector(
      String diagramName,
      String name,
      String fromElementId,
      String toElementId,
      String pointsCsv,
      String lineStyle,
      boolean arrowAtSource)
      throws Exception {
    Point[] points = parsePoints(pointsCsv);
    LineStyle style = parseLineStyle(lineStyle);
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement from = findDiagramElementById(diagram, fromElementId);
          IDiagramElement to = findDiagramElementById(diagram, toElementId);
          IGenericConnector model = models().createGenericConnector();
          model.setName(clean(name));
          model.setFrom(from.getModelElement());
          model.setTo(to.getModelElement());
          IDiagramElement element = diagrams().createConnector(diagram, model, from, to, new Point[0]);
          if (!(element instanceof IConnectorUIModel)) {
            model.delete();
            throw new IllegalStateException("Visual Paradigm did not create a connector view");
          }
          IConnectorUIModel connector = (IConnectorUIModel) element;
          diagram.setShowConnectorName(IDiagramUIModel.SHOW_CONNECTOR_NAME_YES);
          connector.setConnectorLabelOrientation(IConnectorUIModel.CLO_HORIZONTAL_ONLY);
          connector.setPaintThroughLabel(IDiagramUIModel.PAINT_CONNECTOR_THROUGH_LABEL_NO);
          route(connector, points);
          if (connector instanceof IGenericConnectorUIModel) {
            IGenericConnectorUIModel generic = (IGenericConnectorUIModel) connector;
            generic
                .getBeginArrowHead()
                .setType(arrowAtSource ? IArrowHead.ARROW_SOLID_ARROW1 : IArrowHead.ARROW_NONE);
            generic
                .getEndArrowHead()
                .setType(arrowAtSource ? IArrowHead.ARROW_NONE : IArrowHead.ARROW_SOLID_ARROW1);
          }
          if (style != null) {
            connector.getLineModel().setLineStyle(style);
            connector.getLineModel().applySetting();
          }
          return elementResult(diagram, element);
        });
  }

  @Tool(
      description =
          "Inspect the freeform shapes and connectors of one diagram: IDs, names, bounds, "
              + "connector points and caption bounds.",
      readOnly = true,
      idempotent = true)
  public List<Map<String, Object>> inspectFreeformDiagram(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          List<Map<String, Object>> elements = new ArrayList<>();
          for (IDiagramElement element : diagram.toDiagramElementArray()) {
            if (!isFreeform(element.getModelElement())) {
              continue;
            }
            Map<String, Object> item = elementResult(diagram, element);
            item.remove("diagramName");
            item.put("modelType", element.getModelElement().getModelType());
            if (element instanceof IConnectorUIModel) {
              List<String> points = new ArrayList<>();
              for (Point point : ((IConnectorUIModel) element).getPoints()) {
                points.add(point.x + ":" + point.y);
              }
              item.put("points", String.join(",", points));
              IConnectorUIModel connector = (IConnectorUIModel) element;
              item.put("fromPin", pinText(connector.getFromPinType(), connector.getFromPinRatio()));
              item.put("toPin", pinText(connector.getToPinType(), connector.getToPinRatio()));
              ICaptionUIModel caption = element.getCaptionUIModel();
              if (caption != null) {
                item.put(
                    "caption",
                    caption.getX()
                        + ":"
                        + caption.getY()
                        + " "
                        + caption.getWidth()
                        + "x"
                        + caption.getHeight());
              }
            }
            elements.add(item);
          }
          return elements;
        });
  }

  @Tool(
      description =
          "Route one freeform connector through explicit x:y points, including both endpoints, as "
              + "straight oblique segments. Each end is pinned to its shape at the given endpoint so "
              + "Visual Paradigm does not move it to the shape centre line.",
      idempotent = true)
  public Map<String, Object> routeFreeformConnector(
      String diagramName, String elementId, String pointsCsv) throws Exception {
    Point[] points = parsePoints(pointsCsv);
    if (points.length < 2) {
      throw new IllegalArgumentException("pointsCsv needs at least both endpoints");
    }
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement element = findDiagramElementById(diagram, elementId);
          if (!(element instanceof IConnectorUIModel)) {
            throw new IllegalArgumentException("Element is not a connector: " + elementId);
          }
          route((IConnectorUIModel) element, points);
          return elementResult(diagram, element);
        });
  }

  @Tool(
      description =
          "Change the line of one freeform shape or connector. lineStyle is a Visual Paradigm "
              + "LineStyle name (None, Style1 .. Style22) or empty to keep it; lineColor is #RRGGBB "
              + "or empty to keep it; lineWeight 0 keeps the current weight.",
      idempotent = true)
  public Map<String, Object> setFreeformLine(
      String diagramName, String elementId, String lineStyle, String lineColor, int lineWeight)
      throws Exception {
    LineStyle style = parseLineStyle(lineStyle);
    Color color = parseColor(lineColor, "lineColor");
    if (lineWeight < 0 || lineWeight > 12) {
      throw new IllegalArgumentException("lineWeight must be between 0 and 12");
    }
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement element = findDiagramElementById(diagram, elementId);
          if (style != null) {
            element.getLineModel().setLineStyle(style);
          }
          if (color != null) {
            element.getLineModel().setColor(color);
          }
          if (lineWeight > 0) {
            element.getLineModel().setWeight(lineWeight);
          }
          element.getLineModel().applySetting();
          return elementResult(diagram, element);
        });
  }

  @Tool(
      description =
          "Move the caption of one freeform connector to an explicit top-left position after "
              + "inspecting an export. width 0 fits the name on one line; a smaller width wraps it. "
              + "The caption stays attached to its connector.",
      idempotent = true)
  public Map<String, Object> layoutFreeformConnectorLabel(
      String diagramName, String elementId, int x, int y, int width) throws Exception {
    if (width < 0) {
      throw new IllegalArgumentException("width must not be negative");
    }
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement element = findDiagramElementById(diagram, elementId);
          if (!(element instanceof IConnectorUIModel)) {
            throw new IllegalArgumentException("Element is not a connector: " + elementId);
          }
          ICaptionUIModel caption = element.getCaptionUIModel();
          caption.setVisible(true);
          caption.setSide(ICaptionUIModel.SIDE_FREEMOVE);
          Font font = new Font("Arial", Font.PLAIN, element.getElementFont().getSize());
          FontRenderContext metrics = new FontRenderContext(null, true, true);
          int textWidth =
              (int)
                      Math.ceil(
                          font.getStringBounds(
                                  clean(element.getModelElement().getName()), metrics)
                              .getWidth())
                  + 24;
          int lineHeight = (int) Math.ceil(font.getLineMetrics("Ag", metrics).getHeight());
          int captionWidth = width == 0 ? textWidth : width;
          int lines = (textWidth + captionWidth - 1) / captionWidth;
          caption.setBounds(x, y, captionWidth, lines * lineHeight + 12);
          return elementResult(diagram, element);
        });
  }

  @Tool(
      description =
          "Delete one freeform shape or connector together with its model. Deleting a shape also "
              + "removes the connectors attached to it.",
      destructive = true)
  public Map<String, Object> deleteFreeformElement(String diagramName, String elementId)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement element = findDiagramElementById(diagram, elementId);
          IModelElement model = element.getModelElement();
          if (!isFreeform(model)) {
            throw new IllegalArgumentException("Element is not a freeform element: " + elementId);
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("diagramName", diagram.getName());
          result.put("deletedElementId", elementId);
          element.deleteModel();
          return result;
        });
  }

  @Tool(
      description =
          "Delete one diagram that holds only freeform shapes and connectors, together with their "
              + "models. Refuses a diagram that contains any other element.",
      destructive = true)
  public Map<String, Object> deleteFreeformDiagram(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          IDiagramElement[] elements = diagram.toDiagramElementArray();
          for (IDiagramElement element : elements) {
            if (!isFreeform(element.getModelElement())) {
              throw new IllegalArgumentException(
                  "Diagram contains an element that is not freeform: " + diagramName);
            }
          }
          for (IDiagramElement element : elements) {
            IModelElement model = element.getModelElement();
            if (model != null) {
              model.delete();
            }
          }
          diagram.delete();
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("deletedDiagramName", diagramName);
          result.put("deletedElements", elements.length);
          return result;
        });
  }

  private static void route(IConnectorUIModel connector, Point[] points) {
    connector.setConnectorStyle(IConnectorUIModel.CS_OBLIQUE);
    if (points.length == 0) {
      return;
    }
    connector.setFromPinType(IConnectorUIModel.PINTYPE_RATIO);
    connector.setFromPinRatio(pinRatio(connector.getFromShape(), points[0]));
    connector.setToPinType(IConnectorUIModel.PINTYPE_RATIO);
    connector.setToPinRatio(pinRatio(connector.getToShape(), points[points.length - 1]));
    connector.clearPoints();
    for (Point point : points) {
      connector.addPoint(point);
    }
  }

  private static Point2D pinRatio(IShapeUIModel shape, Point point) {
    return new Point2D.Double(
        (point.x - shape.getX()) / (double) shape.getWidth(),
        (point.y - shape.getY()) / (double) shape.getHeight());
  }

  private static String pinText(int type, Point2D ratio) {
    return type + (ratio == null ? "" : " " + ratio.getX() + ":" + ratio.getY());
  }

  private static boolean isFreeform(IModelElement model) {
    if (model == null) {
      return false;
    }
    String type = model.getModelType();
    return "Oval".equals(type) || "Rectangle".equals(type) || "GenericConnector".equals(type);
  }

  private static Map<String, Object> elementResult(
      IDiagramUIModel diagram, IDiagramElement element) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("diagramName", diagram.getName());
    result.put("elementId", element.getId());
    result.put("name", clean(element.getModelElement().getName()));
    result.put("x", element.getX());
    result.put("y", element.getY());
    result.put("width", element.getWidth());
    result.put("height", element.getHeight());
    return result;
  }

  private static Color parseColor(String value, String field) {
    String text = clean(value);
    if (text.isEmpty()) {
      return null;
    }
    if (!text.matches("#[0-9A-Fa-f]{6}")) {
      throw new IllegalArgumentException(field + " must be #RRGGBB or empty");
    }
    return Color.decode(text);
  }

  private static LineStyle parseLineStyle(String value) {
    String text = clean(value);
    if (text.isEmpty()) {
      return null;
    }
    for (LineStyle style : LineStyle.values()) {
      if (style.name().equalsIgnoreCase(text)) {
        return style;
      }
    }
    throw new IllegalArgumentException("Unknown lineStyle: " + text);
  }

  private static Point[] parsePoints(String pointsCsv) {
    String text = clean(pointsCsv);
    if (text.isEmpty()) {
      return new Point[0];
    }
    List<Point> points = new ArrayList<>();
    for (String pair : text.split(",")) {
      String[] parts = pair.trim().split(":");
      if (parts.length != 2) {
        throw new IllegalArgumentException("pointsCsv must use x:y pairs separated by commas");
      }
      try {
        points.add(new Point(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("pointsCsv must use integer x:y pairs");
      }
    }
    return points.toArray(new Point[0]);
  }
}
