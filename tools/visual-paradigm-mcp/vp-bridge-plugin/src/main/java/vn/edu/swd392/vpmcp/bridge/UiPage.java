package vn.edu.swd392.vpmcp.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Management console markup loaded once from the bundled classpath resource. */
final class UiPage {
  private UiPage() {}

  static final String HTML = load();

  private static String load() {
    try (InputStream in = UiPage.class.getResourceAsStream("/vpmcp/ui.html")) {
      if (in == null) {
        throw new IllegalStateException("Missing bundled resource /vpmcp/ui.html");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot load console page", exception);
    }
  }
}
