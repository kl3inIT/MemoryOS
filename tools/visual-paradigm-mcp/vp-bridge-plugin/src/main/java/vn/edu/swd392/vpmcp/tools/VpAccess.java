package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.ApplicationManager;
import com.vp.plugin.DiagramManager;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IRelationship;
import com.vp.plugin.model.factory.IModelElementFactory;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import vn.edu.swd392.vpmcp.bridge.BridgeException;

abstract class VpAccess {
  protected final <T> T onEdt(Callable<T> operation) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return operation.call();
    }
    FutureTask<T> task = new FutureTask<>(operation);
    SwingUtilities.invokeAndWait(task);
    return task.get();
  }

  protected final ApplicationManager application() {
    return ApplicationManager.instance();
  }

  protected final IProject project() {
    IProject project = application().getProjectManager().getProject();
    if (project == null) {
      throw new IllegalStateException("Open or create a Visual Paradigm project first");
    }
    return project;
  }

  protected final DiagramManager diagrams() {
    return application().getDiagramManager();
  }

  protected final IModelElementFactory models() {
    return IModelElementFactory.instance();
  }

  protected final IDiagramUIModel createDiagram(String type, String name) {
    requireText(name, "diagramName");
    Iterator<?> existing = project().diagramIterator();
    while (existing.hasNext()) {
      Object item = existing.next();
      if (item instanceof IDiagramUIModel
          && name.equals(((IDiagramUIModel) item).getName())) {
        throw BridgeException.conflict(
            "DUPLICATE_DIAGRAM", "$.diagramName", "Diagram already exists: " + name);
      }
    }
    IDiagramUIModel diagram = diagrams().createDiagram(type);
    diagram.setName(name);
    diagram.setShowConnectorName(IDiagramUIModel.SHOW_CONNECTOR_NAME_YES);
    diagrams().openDiagram(diagram);
    return diagram;
  }

  protected final IDiagramUIModel findDiagram(String name) {
    requireText(name, "diagramName");
    IDiagramUIModel match = null;
    int count = 0;
    Iterator<?> iterator = project().diagramIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IDiagramUIModel) {
        IDiagramUIModel diagram = (IDiagramUIModel) item;
        if (name.equals(diagram.getName())) {
          match = diagram;
          count++;
        }
      }
    }
    if (match == null) {
      throw new IllegalArgumentException("Diagram not found: " + name);
    }
    if (count > 1) {
      throw BridgeException.conflict(
          "AMBIGUOUS_DIAGRAM",
          "$.diagramName",
          "More than one diagram uses the name: " + name);
    }
    return match;
  }

  protected final IDiagramElement findShape(
      IDiagramUIModel diagram, String name, Class<? extends IModelElement> type) {
    IDiagramElement match = null;
    int count = 0;
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IDiagramElement) {
        IDiagramElement element = (IDiagramElement) item;
        IModelElement model = element.getModelElement();
        if (type.isInstance(model) && name.equals(model.getName())) {
          match = element;
          count++;
        }
      }
    }
    if (count > 1) {
      throw BridgeException.conflict(
          "AMBIGUOUS_ELEMENT",
          "$",
          "More than one "
              + type.getSimpleName()
              + " uses the name '"
              + name
              + "' on diagram '"
              + diagram.getName()
              + "'");
    }
    if (match != null) {
      return match;
    }
    throw new IllegalArgumentException(
        type.getSimpleName() + " not found on diagram '" + diagram.getName() + "': " + name);
  }

  protected final <T extends IModelElement> T model(
      IDiagramUIModel diagram, String name, Class<T> type) {
    return type.cast(findShape(diagram, name, type).getModelElement());
  }

  protected final IDiagramElement addShape(
      IDiagramUIModel diagram,
      IModelElement model,
      String name,
      int x,
      int y,
      int width,
      int height) {
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof IDiagramElement)) {
        continue;
      }
      IModelElement existing = ((IDiagramElement) item).getModelElement();
      if (existing != null
          && model.getClass().isInstance(existing)
          && name.equals(existing.getName())) {
        model.delete();
        throw BridgeException.conflict(
            "DUPLICATE_ELEMENT",
            "$",
            "Element already exists on diagram '" + diagram.getName() + "': " + name);
      }
    }
    model.setName(name);
    IDiagramElement element = diagrams().createDiagramElement(diagram, model);
    element.setBounds(x, y, width, height);
    return element;
  }

  protected final IDiagramElement findDiagramElementById(
      IDiagramUIModel diagram, String elementId) {
    requireText(elementId, "elementId");
    Iterator<?> iterator = diagram.diagramElementIterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (item instanceof IDiagramElement
          && elementId.equals(((IDiagramElement) item).getId())) {
        return (IDiagramElement) item;
      }
    }
    throw new IllegalArgumentException(
        "Diagram element not found on '" + diagram.getName() + "': " + elementId);
  }

  protected final IDiagramElement addConnector(
      IDiagramUIModel diagram,
      IRelationship relationship,
      IDiagramElement from,
      IDiagramElement to) {
    relationship.setFrom(from.getModelElement());
    relationship.setTo(to.getModelElement());
    return diagrams().createConnector(diagram, relationship, from, to, null);
  }

  protected static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  protected static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
