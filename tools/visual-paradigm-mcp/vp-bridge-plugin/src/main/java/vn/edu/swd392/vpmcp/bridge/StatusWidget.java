package vn.edu.swd392.vpmcp.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.vp.plugin.ApplicationManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Bridge-status window for Visual Paradigm. Uses the native Windows title bar, so
 * minimize/maximize/close/drag/resize all behave like a normal window. The themed interior
 * shows a live pulse dot, throughput sparkline, WRITES/READS/ERRORS counters and a
 * colorized activity log. Double-click the header opens the web console; the ▫ button
 * opens a theme picker persisted to {@code ~/.vpmcp/widget.properties}. Closing hides the
 * window to the system tray (click the tray icon to reopen).
 */
final class StatusWidget {

  // ------------------------------------------------------------------ themes

  /** Color palette for the widget interior. */
  static final class Theme {
    final String name;
    final Color bgTop, bgBot, panel, border, fg, dim, ok, err, warn, accent, accent2, logBg, iconFg;
    final boolean round;

    Theme(
        String name, int bgTop, int bgBot, int panel, int border, int fg, int dim,
        int ok, int err, int warn, int accent, int accent2, int logBg, int iconFg,
        boolean round) {
      this.name = name;
      this.bgTop = new Color(bgTop);
      this.bgBot = new Color(bgBot);
      this.panel = new Color(panel);
      this.border = new Color(border);
      this.fg = new Color(fg);
      this.dim = new Color(dim);
      this.ok = new Color(ok);
      this.err = new Color(err);
      this.warn = new Color(warn);
      this.accent = new Color(accent);
      this.accent2 = new Color(accent2);
      this.logBg = new Color(logBg);
      this.iconFg = new Color(iconFg);
      this.round = round;
    }
  }

  static final Theme[] THEMES = {
    new Theme("Dark Nebula",
        0x1E2433, 0x151822, 0x232A3C, 0x394258, 0xEAEFF7, 0x8B94A8,
        0x4CD98A, 0xFF5C5C, 0xE8B43C, 0x7C8CFF, 0x4ED1C4, 0x131722, 0x0B0E16, true),
    new Theme("Light Mist",
        0xF5F7FB, 0xE9EDF5, 0xFFFFFF, 0xC9D2E0, 0x1D2433, 0x68748A,
        0x1F9E57, 0xD93636, 0xB8860B, 0x4F5FD8, 0x159E93, 0xF0F3F9, 0xFFFFFF, true),
    new Theme("Terminal",
        0x0B0F0A, 0x050805, 0x101812, 0x1E3A24, 0x9FFFB0, 0x4E7A57,
        0x4ADE80, 0xFF6B6B, 0xFFD166, 0x3ADE7E, 0x3ADE7E, 0x070B08, 0x050805, false),
    new Theme("Solar Slate",
        0x0F1B26, 0x0B1420, 0x17252F, 0x2C4255, 0xD8E7F0, 0x6E8798,
        0x5AD18B, 0xFF7A7A, 0xFFC24B, 0x57B8FF, 0xFFB86B, 0x0A1218, 0x06121B, true),
  };

  private static Theme themeByName(String name) {
    for (Theme t : THEMES) {
      if (t.name.equals(name)) {
        return t;
      }
    }
    return THEMES[0];
  }

  private static final Path PREFS =
      Path.of(System.getProperty("user.home"), ".vpmcp", "widget.properties");

  private static Theme loadTheme() {
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(PREFS)) {
      p.load(in);
      return themeByName(p.getProperty("theme", THEMES[0].name));
    } catch (Exception ignored) {
      return THEMES[0];
    }
  }

  private static void saveTheme(Theme t) {
    try {
      Files.createDirectories(PREFS.getParent());
      Properties p = new Properties();
      p.setProperty("theme", t.name);
      try (OutputStream out = Files.newOutputStream(PREFS)) {
        p.store(out, "vp-mcp widget");
      }
    } catch (Exception ignored) {
    }
  }

  // ------------------------------------------------------------------ state

  private static final Font MONO = new Font("Consolas", Font.PLAIN, 11);
  private static final Font MONO_B = new Font("Consolas", Font.BOLD, 13);
  private static final int WIDTH = 356;

  private final VpBridgeServer bridge;
  private final int port;
  private final JFrame frame = new JFrame();
  private final ObjectMapper mapper = new ObjectMapper();

  private Theme theme = loadTheme();

  private final PulseDot dot = new PulseDot();
  private final JLabel statusText = new JLabel("starting…");
  private final JLabel writesVal = new JLabel("0", SwingConstants.CENTER);
  private final JLabel readsVal = new JLabel("0", SwingConstants.CENTER);
  private final JLabel errorsVal = new JLabel("0", SwingConstants.CENTER);
  private final JTextPane log = new JTextPane();
  private final JScrollPane logScroll = new JScrollPane(log);
  private final Sparkline spark = new Sparkline();
  private final Deque<Long> callTimes = new ArrayDeque<>();
  private long lastSeq;
  private int writes;
  private int reads;
  private int errors;
  private JDialog themeDialog;
  private java.awt.TrayIcon trayIcon;

  private static StatusWidget current;

  private StatusWidget(VpBridgeServer bridge, int port) {
    this.bridge = bridge;
    this.port = port;
    build();
    anchor();
    refresh();
    new Timer(500, e -> refresh()).start();
    new Timer(80, e -> dot.tick()).start();
  }

  static void show(VpBridgeServer bridge, int port) {
    SwingUtilities.invokeLater(
        () -> {
          if (current == null) {
            current = new StatusWidget(bridge, port);
          } else {
            current.showWidget();
          }
        });
  }

  /** Returns the live widget instance; used by the plugin and preview harnesses. */
  static StatusWidget current() {
    return current;
  }

  // ------------------------------------------------------------------ build

  private void build() {
    frame.setTitle("VP MCP Bridge \u2014 127.0.0.1:" + port);
    frame.setAlwaysOnTop(true);
    frame.setResizable(true);
    frame.setMinimumSize(new Dimension(320, 300));
    frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            hideWidget();
          }

          @Override
          public void windowDeiconified(WindowEvent e) {
            // Swing loses the backbuffer on minimize/restore in some LAFs; force full repaint
            frame.validate();
            frame.getContentPane().revalidate();
            frame.repaint();
          }
        });
    rebuildContent();
    frame.pack();
    frame.setSize(WIDTH, 460);
    frame.setVisible(true);
  }

  private void rebuildContent() {
    JPanel root = new GradientRoot();
    root.setLayout(new BorderLayout(0, 0));

    JPanel center = new JPanel();
    center.setOpaque(false);
    center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
    center.add(vgap(8));
    center.add(fixed(statusBar()));
    center.add(vgap(10));
    center.add(fixed(statsRow()));
    center.add(vgap(10));
    center.add(fixed(sparkRow()));
    center.add(vgap(8));
    center.add(fixed(sectionLabel("ACTIVITY")));
    center.add(vgap(4));
    center.add(logPane());
    center.add(vgap(10));
    center.add(fixed(buttons()));
    center.add(vgap(10));

    root.add(header(), BorderLayout.NORTH);
    root.add(center, BorderLayout.CENTER);
    frame.setContentPane(root);
    frame.revalidate();
    frame.repaint();
  }

  private JPanel header() {
    JPanel h = new JPanel(new BorderLayout(10, 0));
    h.setOpaque(false);
    h.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 10));

    JPanel icon = new JPanel() {
      @Override
      protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setPaint(new GradientPaint(0, 0, theme.accent, getWidth(), getHeight(), theme.accent2));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 9, 9);
        g2.setColor(theme.iconFg);
        g2.setFont(MONO_B);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString("{/}", (getWidth() - fm.stringWidth("{/}")) / 2,
            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
        g2.dispose();
      }
    };
    icon.setOpaque(false);
    icon.setPreferredSize(new Dimension(30, 30));

    JPanel titles = new JPanel();
    titles.setOpaque(false);
    titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
    JLabel name = new JLabel("VP MCP Bridge");
    name.setForeground(theme.fg);
    name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
    JLabel sub = new JLabel("visual-paradigm \u00B7 dbl-click \u2192 console");
    sub.setForeground(theme.dim);
    sub.setFont(sub.getFont().deriveFont(10f));
    titles.add(name);
    titles.add(sub);

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 6));
    controls.setOpaque(false);
    controls.add(controlGlyph("\u25AB", "Theme", this::openThemePicker));

    // double-click anywhere on the header opens the web console
    h.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              openConsole();
            }
          }
        });

    h.add(icon, BorderLayout.WEST);
    h.add(titles, BorderLayout.CENTER);
    h.add(controls, BorderLayout.EAST);
    return h;
  }

  private JLabel controlGlyph(String glyph, String tip, Runnable onClick) {
    JLabel l = new JLabel(glyph);
    l.setForeground(theme.dim);
    l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
    l.setToolTipText(tip);
    l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    l.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            onClick.run();
          }

          @Override
          public void mouseEntered(MouseEvent e) {
            l.setForeground(theme.fg);
          }

          @Override
          public void mouseExited(MouseEvent e) {
            l.setForeground(theme.dim);
          }
        });
    return l;
  }

  private JPanel statusBar() {
    JPanel bar = new JPanel(new BorderLayout(8, 0)) {
      @Override
      protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int r = theme.round ? 12 : 3;
        g2.setColor(theme.panel);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), r, r);
        g2.setColor(theme.border);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, r, r);
        g2.dispose();
        super.paintComponent(g);
      }
    };
    bar.setOpaque(false);
    bar.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createEmptyBorder(0, 12, 0, 12),
        BorderFactory.createEmptyBorder(9, 12, 9, 12)));
    statusText.setForeground(theme.fg);
    statusText.setFont(statusText.getFont().deriveFont(12f));
    bar.add(dot, BorderLayout.WEST);
    bar.add(statusText, BorderLayout.CENTER);
    return wrap(bar);
  }

  private JPanel statsRow() {
    JPanel row = new JPanel(new java.awt.GridLayout(1, 3, 8, 0));
    row.setOpaque(false);
    row.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
    row.add(stat(writesVal, "WRITES", theme.accent));
    row.add(stat(readsVal, "READS", theme.accent2));
    row.add(stat(errorsVal, "ERRORS", theme.err));
    return row;
  }

  private JPanel stat(JLabel value, String name, Color color) {
    JPanel s = new JPanel() {
      @Override
      protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int r = theme.round ? 12 : 3;
        g2.setColor(theme.panel);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), r, r);
        g2.setColor(color);
        g2.fillRoundRect(0, 0, getWidth(), 3, 3, 3);
        g2.dispose();
        super.paintComponent(g);
      }
    };
    s.setOpaque(false);
    s.setLayout(new BoxLayout(s, BoxLayout.Y_AXIS));
    s.setBorder(BorderFactory.createEmptyBorder(9, 4, 8, 4));
    value.setForeground(color);
    value.setFont(MONO_B.deriveFont(17f));
    value.setAlignmentX(Component.CENTER_ALIGNMENT);
    JLabel lbl = new JLabel(name, SwingConstants.CENTER);
    lbl.setForeground(theme.dim);
    lbl.setFont(lbl.getFont().deriveFont(9f));
    lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
    s.add(value);
    s.add(Box.createVerticalStrut(2));
    s.add(lbl);
    return s;
  }

  private JPanel sparkRow() {
    JPanel p = new JPanel(new BorderLayout(8, 0));
    p.setOpaque(false);
    p.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
    JLabel lbl = new JLabel("throughput");
    lbl.setForeground(theme.dim);
    lbl.setFont(lbl.getFont().deriveFont(9f));
    p.add(lbl, BorderLayout.WEST);
    p.add(spark, BorderLayout.CENTER);
    return p;
  }

  private JPanel sectionLabel(String text) {
    JPanel p = new JPanel(new BorderLayout());
    p.setOpaque(false);
    p.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 12));
    JLabel l = new JLabel(text);
    l.setForeground(theme.dim);
    l.setFont(l.getFont().deriveFont(10f));
    p.add(l, BorderLayout.WEST);
    return p;
  }

  private JPanel logPane() {
    log.setEditable(false);
    log.setBackground(theme.logBg);
    log.setFont(MONO);
    logScroll.setBorder(BorderFactory.createEmptyBorder());
    logScroll.setOpaque(false);
    logScroll.getViewport().setOpaque(false);
    logScroll.setPreferredSize(new Dimension(WIDTH - 24, 160));
    JPanel box = new JPanel(new BorderLayout()) {
      @Override
      protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int r = theme.round ? 12 : 3;
        g2.setColor(theme.logBg);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), r, r);
        g2.setColor(theme.border);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, r, r);
        g2.dispose();
        super.paintComponent(g);
      }
    };
    box.setOpaque(false);
    box.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createEmptyBorder(0, 12, 0, 12),
        BorderFactory.createEmptyBorder(4, 8, 4, 8)));
    box.add(logScroll, BorderLayout.CENTER);
    return wrap(box);
  }

  private JPanel buttons() {
    JPanel row = new JPanel(new java.awt.GridLayout(1, 3, 8, 0));
    row.setOpaque(false);
    row.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
    row.add(button("Clear", theme.panel, theme.fg, () -> log.setText("")));
    row.add(button("Auto-detect", theme.panel, theme.fg, this::anchor));
    row.add(button("Console", theme.accent, theme.iconFg, this::openConsole));
    return row;
  }

  private JComponent button(String label, Color bg, Color fg, Runnable action) {
    JLabel b = new JLabel(label, SwingConstants.CENTER) {
      private Color bgNow = bg;
      {
        setForeground(fg);
        setFont(getFont().deriveFont(Font.BOLD, 11f));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
      }

      @Override
      protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int r = theme.round ? 10 : 3;
        g2.setColor(bgNow);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), r, r);
        g2.dispose();
        super.paintComponent(g);
      }

      @Override
      public void setBackground(Color c) {
        bgNow = c;
      }
    };
    b.setOpaque(false);
    Color hover =
        bg.equals(theme.accent)
            ? bg.brighter()
            : new Color(
                Math.min(255, bg.getRed() + 24),
                Math.min(255, bg.getGreen() + 24),
                Math.min(255, bg.getBlue() + 24));
    b.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            action.run();
          }

          @Override
          public void mouseEntered(MouseEvent e) {
            b.setBackground(hover);
            b.repaint();
          }

          @Override
          public void mouseExited(MouseEvent e) {
            b.setBackground(bg);
            b.repaint();
          }
        });
    return b;
  }

  /** Wraps a component pinned to its preferred height so BoxLayout never stretches it. */
  private JPanel fixed(JComponent c) {
    JPanel p = new JPanel(new BorderLayout());
    p.setOpaque(false);
    p.add(c, BorderLayout.CENTER);
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
    return p;
  }

  private JPanel wrap(JComponent c) {
    JPanel p = new JPanel(new BorderLayout());
    p.setOpaque(false);
    p.add(c, BorderLayout.CENTER);
    return p;
  }

  private Component vgap(int h) {
    return Box.createVerticalStrut(h);
  }

  // ------------------------------------------------------------------ hide/show

  private void hideWidget() {
    frame.setVisible(false);
    installTray();
  }

  private void showWidget() {
    if (trayIcon != null) {
      try {
        java.awt.SystemTray.getSystemTray().remove(trayIcon);
      } catch (Throwable ignored) {
      }
      trayIcon = null;
    }
    frame.setExtendedState(JFrame.NORMAL);
    frame.setVisible(true);
    frame.toFront();
  }

  private void installTray() {
    if (trayIcon != null || !java.awt.SystemTray.isSupported()) {
      return;
    }
    try {
      java.awt.Image img =
          new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = (Graphics2D) img.getGraphics();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setPaint(new GradientPaint(0, 0, theme.accent, 16, 16, theme.accent2));
      g.fillOval(0, 0, 16, 16);
      g.dispose();
      java.awt.TrayIcon icon = new java.awt.TrayIcon(img, "VP MCP Bridge (click to show)");
      icon.setImageAutoSize(true);
      icon.addActionListener(e -> showWidget());
      java.awt.SystemTray.getSystemTray().add(icon);
      trayIcon = icon;
    } catch (Throwable ignored) {
    }
  }

  /** Applies a theme by name and rebuilds the interior; used by the picker and tests. */
  void applyThemeByName(String name) {
    theme = themeByName(name);
    saveTheme(theme);
    rebuildContent();
  }

  // ------------------------------------------------------------------ theme picker

  private void openThemePicker() {
    if (themeDialog != null && themeDialog.isShowing()) {
      themeDialog.toFront();
      return;
    }
    JDialog d = new JDialog(frame, "Widget theme", false);
    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    list.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    ButtonGroup group = new ButtonGroup();
    for (Theme t : THEMES) {
      JRadioButton rb = new JRadioButton(t.name, t == theme);
      rb.setAlignmentX(Component.LEFT_ALIGNMENT);
      group.add(rb);
      JPanel card = themeCard(t);
      JPanel row = new JPanel(new BorderLayout(10, 0));
      row.setAlignmentX(Component.LEFT_ALIGNMENT);
      row.setOpaque(false);
      row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
      row.add(rb, BorderLayout.WEST);
      row.add(card, BorderLayout.CENTER);
      rb.addActionListener(
          e -> {
            theme = t;
            saveTheme(t);
            rebuildContent();
          });
      card.addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              rb.doClick();
            }
          });
      list.add(row);
    }
    d.setContentPane(list);
    d.pack();
    d.setLocationRelativeTo(frame);
    themeDialog = d;
    d.setVisible(true);
  }

  /** Miniature preview of a theme: swatch band + stat chips + log lines. */
  private JPanel themeCard(Theme t) {
    JPanel card = new JPanel() {
      @Override
      protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        int r = t.round ? 10 : 2;
        g2.setPaint(new GradientPaint(0, 0, t.bgTop, 0, h, t.bgBot));
        g2.fillRoundRect(0, 0, w, h, r, r);
        g2.setPaint(new GradientPaint(0, 0, t.accent, w / 3, 0, t.accent2));
        g2.fillRoundRect(0, 0, w, 3, 3, 3);
        g2.setPaint(new GradientPaint(10, 12, t.accent, 26, 28, t.accent2));
        g2.fillRoundRect(10, 12, 16, 16, t.round ? 6 : 2, t.round ? 6 : 2);
        g2.setColor(t.fg);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 8f));
        g2.drawString("VP", 34, 20);
        g2.setColor(t.dim);
        g2.drawString("bridge", 34, 30);
        String[] names = {"W", "R", "E"};
        Color[] cols = {t.accent, t.accent2, t.err};
        for (int i = 0; i < 3; i++) {
          int x = 10 + i * 42;
          g2.setColor(t.panel);
          g2.fillRoundRect(x, 38, 36, 22, t.round ? 8 : 2, t.round ? 8 : 2);
          g2.setColor(cols[i]);
          g2.fillRoundRect(x, 38, 36, 2, 2, 2);
          g2.drawString(names[i], x + 14, 53);
        }
        for (int i = 0; i < 2; i++) {
          int y = 66 + i * 9;
          g2.setColor(t.logBg);
          g2.fillRoundRect(10, y, w - 20, 7, t.round ? 4 : 1, t.round ? 4 : 1);
          g2.setColor(i == 0 ? t.ok : t.err);
          g2.fillRect(14, y + 2, 30 + i * 18, 3);
          g2.setColor(t.dim);
          g2.fillRect(48 + i * 18, y + 2, w - 70 - i * 18, 3);
        }
        g2.setColor(t.border);
        g2.drawRoundRect(0, 0, w - 1, h - 1, r, r);
        g2.dispose();
      }
    };
    card.setPreferredSize(new Dimension(160, 92));
    card.setOpaque(false);
    card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return card;
  }

  // ------------------------------------------------------------------ behavior

  private void refresh() {
    long now = System.currentTimeMillis();
    while (!callTimes.isEmpty() && now - callTimes.peekFirst() > 60_000) {
      callTimes.removeFirst();
    }
    long latest = bridge.activity().latest();
    if (latest > lastSeq) {
      ArrayNode news = bridge.activity().snapshot(lastSeq, mapper);
      lastSeq = latest;
      for (JsonNode e : news) {
        boolean ok = e.path("success").asBoolean();
        String tool = e.path("toolName").asText("");
        String code = e.path("code").asText("?");
        long ms = e.path("durationMs").asLong();
        if (ok) {
          if (e.path("readOnly").asBoolean() || e.path("cached").asBoolean()) {
            reads++;
          } else {
            writes++;
          }
        } else {
          errors++;
        }
        callTimes.addLast(e.path("ts").asLong(now));
        logEntry(e.path("seq").asLong(), tool.isEmpty() ? "(invalid)" : tool, code, ms, ok);
        dot.pulse(ok);
      }
      writesVal.setText(String.valueOf(writes));
      readsVal.setText(String.valueOf(reads));
      errorsVal.setText(String.valueOf(errors));
      spark.repaint();
    }
    statusText.setText(bridge.toolCount() + " tools \u00B7 " + callTimes.size() + " calls/min");
  }

  private void logEntry(long seq, String tool, String code, long ms, boolean ok) {
    StyledDocument doc = log.getStyledDocument();
    try {
      appendStyled(doc, "#" + seq + "  ", theme.dim);
      appendStyled(doc, pad(tool, 22), theme.accent);
      appendStyled(doc, pad(code, 14), ok ? theme.ok : theme.err);
      appendStyled(doc, ms + "ms\n", theme.warn);
      String t = log.getText();
      if (t.length() > 12000) {
        doc.remove(0, t.length() - 8000);
      }
      log.setCaretPosition(doc.getLength());
    } catch (Exception ignored) {
    }
  }

  private static void appendStyled(StyledDocument d, String s, Color c) throws Exception {
    SimpleAttributeSet a = new SimpleAttributeSet();
    StyleConstants.setForeground(a, c);
    StyleConstants.setFontFamily(a, "Consolas");
    StyleConstants.setFontSize(a, 11);
    d.insertString(d.getLength(), s, a);
  }

  private static String pad(String s, int n) {
    if (s.length() >= n) {
      return s.substring(0, n - 1) + " ";
    }
    StringBuilder sb = new StringBuilder(s);
    while (sb.length() < n) {
      sb.append(' ');
    }
    return sb.toString();
  }

  private void anchor() {
    Component root = null;
    try {
      root = ApplicationManager.instance().getViewManager().getRootFrame();
    } catch (Throwable ignored) {
    }
    if (root != null && root.isShowing()) {
      Point p = root.getLocationOnScreen();
      frame.setLocation(
          p.x + root.getWidth() - frame.getWidth() - 16,
          p.y + root.getHeight() - frame.getHeight() - 24);
    } else {
      Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
      frame.setLocation(
          screen.width - frame.getWidth() - 40, screen.height - frame.getHeight() - 80);
    }
  }

  private void openConsole() {
    if (!Desktop.isDesktopSupported()) {
      return;
    }
    try {
      Desktop.getDesktop().browse(new URI("http://127.0.0.1:" + port + "/"));
    } catch (Exception ignored) {
    }
  }

  // ------------------------------------------------------------------ parts

  /** Gradient interior background inside the native window chrome. */
  private final class GradientRoot extends JPanel {
    GradientRoot() {
      setOpaque(true);
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setPaint(new GradientPaint(0, 0, theme.bgTop, 0, getHeight(), theme.bgBot));
      g2.fillRect(0, 0, getWidth(), getHeight());
      g2.dispose();
    }
  }

  /** Dot that breathes slowly and pulses on each tool call. */
  private final class PulseDot extends JComponent {
    private float glow;
    private float phase;

    PulseDot() {
      setPreferredSize(new Dimension(12, 12));
    }

    void pulse(boolean ok) {
      glow = 1f;
    }

    void tick() {
      phase += 0.12f;
      if (glow > 0f) {
        glow = Math.max(0f, glow - 0.05f);
      }
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      float breathe = 0.55f + 0.25f * (float) Math.sin(phase);
      int halo = (int) (glow * 150 + breathe * 60);
      g2.setColor(new Color(
          theme.ok.getRed(), theme.ok.getGreen(), theme.ok.getBlue(), Math.min(200, halo)));
      g2.fillOval(0, 0, 12, 12);
      g2.setColor(theme.ok);
      g2.fillOval(3, 3, 6, 6);
      g2.dispose();
    }
  }

  /** Per-second call throughput over the last 30s. */
  private final class Sparkline extends JComponent {
    private static final int BUCKETS = 30;

    Sparkline() {
      setPreferredSize(new Dimension(160, 18));
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      long now = System.currentTimeMillis();
      int[] buckets = new int[BUCKETS];
      for (Long t : callTimes) {
        int idx = (int) ((t - (now - 60_000)) / 2000);
        if (idx >= 0 && idx < BUCKETS) {
          buckets[idx]++;
        }
      }
      int max = 1;
      for (int b : buckets) {
        max = Math.max(max, b);
      }
      int w = getWidth();
      int h = getHeight();
      int bw = Math.max(2, w / BUCKETS - 1);
      for (int i = 0; i < BUCKETS; i++) {
        int bh = buckets[i] == 0 ? 1 : Math.max(3, buckets[i] * (h - 2) / max);
        int x = i * (bw + 1);
        g2.setColor(buckets[i] == 0 ? theme.border : theme.accent2);
        g2.fillRoundRect(x, h - bh, bw, bh, 2, 2);
      }
      g2.dispose();
    }
  }
}
