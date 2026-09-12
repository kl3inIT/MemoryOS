package vn.edu.swd392.vpmcp.tools;

import com.vp.plugin.ExportDiagramAsImageOption;
import com.vp.plugin.ExportDiagramImageMargin;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IModelElement;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import vn.edu.swd392.vpmcp.bridge.Tool;

public final class CommonDiagramTools extends VpAccess {
  @Tool(
      description =
          "List diagrams in the open Visual Paradigm project. Use an empty typeFilter for all diagrams.",
      readOnly = true,
      idempotent = true)
  public String listDiagrams(String typeFilter) throws Exception {
    return onEdt(
        () -> {
          String filter = clean(typeFilter).toLowerCase();
          StringBuilder result = new StringBuilder();
          Iterator<?> iterator = project().diagramIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (item instanceof IDiagramUIModel) {
              IDiagramUIModel diagram = (IDiagramUIModel) item;
              if (filter.isEmpty() || diagram.getType().toLowerCase().contains(filter)) {
                result
                    .append(diagram.getName())
                    .append(" | ")
                    .append(diagram.getType())
                    .append('\n');
              }
            }
          }
          return result.length() == 0 ? "No matching diagrams" : result.toString();
        });
  }

  @Tool(
      description =
          "Rename one existing diagram while preserving all UML elements and layout. Both the current and new names must be exact and unique.")
  public String renameDiagram(String diagramName, String newDiagramName) throws Exception {
    return onEdt(
        () -> {
          requireText(newDiagramName, "newDiagramName");
          String targetName = clean(newDiagramName);
          IDiagramUIModel diagram = findDiagram(diagramName);
          Iterator<?> iterator = project().diagramIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (item instanceof IDiagramUIModel) {
              IDiagramUIModel candidate = (IDiagramUIModel) item;
              if (candidate != diagram && targetName.equals(candidate.getName())) {
                throw new IllegalArgumentException(
                    "Another diagram already uses the name: " + targetName);
              }
            }
          }
          diagram.setName(targetName);
          return "Renamed diagram " + diagramName + " -> " + targetName;
        });
  }

  @Tool(
      description = "Inspect element names, model types, and positions on a diagram.",
      readOnly = true,
      idempotent = true)
  public String getDiagramElements(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          StringBuilder result = new StringBuilder();
          Iterator<?> iterator = diagram.diagramElementIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (item instanceof IDiagramElement) {
              IDiagramElement element = (IDiagramElement) item;
              IModelElement model = element.getModelElement();
              if (model != null) {
                result
                    .append(model.getClass().getSimpleName())
                    .append(" | ")
                    .append(model.getName())
                    .append(" | ")
                    .append(element.getX())
                    .append(',')
                    .append(element.getY())
                    .append(' ')
                    .append(element.getWidth())
                    .append('x')
                    .append(element.getHeight())
                    .append('\n');
              }
            }
          }
          return result.length() == 0 ? "Diagram has no elements" : result.toString();
        });
  }

  @Tool(description = "Apply Visual Paradigm automatic layout after all elements are added.")
  public String autoLayoutDiagram(String diagramName) throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          diagram.setShowConnectorName(IDiagramUIModel.SHOW_CONNECTOR_NAME_YES);
          diagrams().autoLayout(diagram);
          return "Auto-layout applied to " + diagramName;
        });
  }

  @Tool(
      description =
          "Export one complete diagram to PNG, transparent PNG, JPG, SVG, PDF, or TIFF. "
              + "The output path must be absolute and its extension must match the format.")
  public String exportDiagramImage(
      String diagramName,
      String outputPath,
      String imageFormat,
      int scalePercent,
      boolean overwrite)
      throws Exception {
    return onEdt(
        () -> {
          IDiagramUIModel diagram = findDiagram(diagramName);
          ExportTarget target =
              prepareFile(outputPath, imageFormat, scalePercent, overwrite);
          export(diagram, target.file, target.imageType, scalePercent);
          return exportResult(diagram, target.file);
        });
  }

  @Tool(
      description =
          "Export every diagram in the open project into one directory. "
              + "Files are named from their diagram names and duplicate names receive a numeric suffix.")
  public String exportAllDiagrams(
      String outputDirectory, String imageFormat, int scalePercent, boolean overwrite)
      throws Exception {
    return onEdt(
        () -> {
          Path directory = absolutePath(outputDirectory, "outputDirectory");
          Files.createDirectories(directory);
          if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("outputDirectory is not a directory: " + directory);
          }

          String extension = extensionFor(imageFormat);
          int imageType = imageTypeFor(imageFormat);
          Set<String> allocatedNames = new HashSet<>();
          StringBuilder result = new StringBuilder();
          int count = 0;
          Iterator<?> iterator = project().diagramIterator();
          while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof IDiagramUIModel)) {
              continue;
            }
            IDiagramUIModel diagram = (IDiagramUIModel) item;
            String baseName = safeFileName(diagram.getName());
            String fileName = uniqueFileName(baseName, extension, allocatedNames);
            Path path = directory.resolve(fileName);
            requireWritableTarget(path, overwrite);
            File file = path.toFile();
            export(diagram, file, imageType, scalePercent);
            result.append(exportResult(diagram, file)).append('\n');
            count++;
          }
          return count == 0 ? "Project has no diagrams to export" : result.toString();
        });
  }

  @Tool(description = "Save the currently open Visual Paradigm project.")
  public String saveProject() throws Exception {
    return onEdt(
        () -> {
          boolean saved = application().getProjectManager().saveProject();
          if (!saved) {
            throw new IllegalStateException("Visual Paradigm did not save the project");
          }
          return "Project saved";
        });
  }

  @Tool(
      description =
          "Create a new empty Visual Paradigm project and save it to an absolute .vpp path. "
              + "Use only when starting a clean workspace because it replaces the currently open project.",
      destructive = true)
  public String createProjectFile(String outputPath, boolean overwrite) throws Exception {
    return onEdt(
        () -> {
          Path path = absolutePath(outputPath, "outputPath");
          if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vpp")) {
            throw new IllegalArgumentException("outputPath must end with .vpp");
          }
          if (Files.exists(path) && !overwrite) {
            throw new IllegalArgumentException("Project already exists: " + path);
          }
          Path parent = path.getParent();
          if (parent != null) {
            Files.createDirectories(parent);
          }
          if (!application().getProjectManager().newProject()) {
            throw new IllegalStateException("Visual Paradigm did not create a new project");
          }
          if (!application().getProjectManager().saveProjectAs(path.toFile())) {
            throw new IllegalStateException("Visual Paradigm did not save the new project: " + path);
          }
          return "Created empty Visual Paradigm project: " + path;
        });
  }

  private void export(IDiagramUIModel diagram, File file, int imageType, int scalePercent)
      throws Exception {
    validateScale(scalePercent);
    ExportDiagramAsImageOption option = new ExportDiagramAsImageOption(imageType);
    option.setScale(scalePercent / 100.0f);
    option.setTextAntiAliasing(true);
    option.setGraphicAntiAliasing(true);
    option.setShowIndicatorsOnShapes(false);
    option.setImageMargin(ExportDiagramImageMargin.Default);
    application().getModelConvertionManager().exportDiagramAsImage(diagram, file, option);
    if (!file.isFile() || file.length() == 0) {
      throw new IllegalStateException("Visual Paradigm did not create the export: " + file);
    }
  }

  private static ExportTarget prepareFile(
      String outputPath, String imageFormat, int scalePercent, boolean overwrite)
      throws Exception {
    validateScale(scalePercent);
    Path path = absolutePath(outputPath, "outputPath");
    String extension = extensionFor(imageFormat);
    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (!fileName.endsWith("." + extension)) {
      throw new IllegalArgumentException(
          "outputPath extension must be ." + extension + " for format " + imageFormat);
    }
    Path parent = path.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("outputPath must have a parent directory");
    }
    Files.createDirectories(parent);
    requireWritableTarget(path, overwrite);
    return new ExportTarget(path.toFile(), imageTypeFor(imageFormat));
  }

  private static Path absolutePath(String value, String field) {
    requireText(value, field);
    Path path = Paths.get(value).normalize();
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(field + " must be an absolute path");
    }
    return path;
  }

  private static void requireWritableTarget(Path path, boolean overwrite) {
    if (Files.exists(path) && !overwrite) {
      throw new IllegalArgumentException(
          "Output already exists; set overwrite=true to replace it: " + path);
    }
    if (Files.exists(path) && Files.isDirectory(path)) {
      throw new IllegalArgumentException("Output path is a directory: " + path);
    }
  }

  private static void validateScale(int scalePercent) {
    if (scalePercent < 25 || scalePercent > 400) {
      throw new IllegalArgumentException("scalePercent must be between 25 and 400");
    }
  }

  private static int imageTypeFor(String value) {
    switch (normalizedFormat(value)) {
      case "png":
        return ExportDiagramAsImageOption.IMAGE_TYPE_PNG;
      case "png-transparent":
        return ExportDiagramAsImageOption.IMAGE_TYPE_PNG_WITHOUT_BACKGROUND;
      case "jpg":
        return ExportDiagramAsImageOption.IMAGE_TYPE_JPG;
      case "svg":
        return ExportDiagramAsImageOption.IMAGE_TYPE_SVG;
      case "pdf":
        return ExportDiagramAsImageOption.IMAGE_TYPE_PDF;
      case "tiff":
        return ExportDiagramAsImageOption.IMAGE_TYPE_TIFF;
      case "tiff-transparent":
        return ExportDiagramAsImageOption.IMAGE_TYPE_TIFF_WITHOUT_BACKGROUND;
      default:
        throw new IllegalArgumentException("Unsupported imageFormat: " + value);
    }
  }

  private static String extensionFor(String value) {
    switch (normalizedFormat(value)) {
      case "png":
      case "png-transparent":
        return "png";
      case "jpg":
        return "jpg";
      case "svg":
        return "svg";
      case "pdf":
        return "pdf";
      case "tiff":
      case "tiff-transparent":
        return "tiff";
      default:
        throw new IllegalArgumentException("Unsupported imageFormat: " + value);
    }
  }

  private static String normalizedFormat(String value) {
    requireText(value, "imageFormat");
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static String safeFileName(String value) {
    String safe = clean(value).replaceAll("[\\\\/:*?\"<>|]+", "-").replaceAll("\\s+", " ").trim();
    return safe.isEmpty() ? "diagram" : safe;
  }

  private static String uniqueFileName(
      String baseName, String extension, Set<String> allocatedNames) {
    String candidate = baseName + "." + extension;
    int suffix = 2;
    while (!allocatedNames.add(candidate.toLowerCase(Locale.ROOT))) {
      candidate = baseName + "-" + suffix + "." + extension;
      suffix++;
    }
    return candidate;
  }

  private static String exportResult(IDiagramUIModel diagram, File file) {
    return "Exported "
        + diagram.getName()
        + " -> "
        + file.getAbsolutePath()
        + " ("
        + file.length()
        + " bytes)";
  }

  private static final class ExportTarget {
    private final File file;
    private final int imageType;

    private ExportTarget(File file, int imageType) {
      this.file = file;
      this.imageType = imageType;
    }
  }
}
