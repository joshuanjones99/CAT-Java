// CAT's fallback for starting BTT's card simulation server: a java agent that
// runs inside the Brand Test Tool's own JVM and presses the menu item an operator
// would press, Actions -> Start Card Simulation Server.
//
// Why an agent: BTT runs on Fime's 32-bit Java 8 runtime (CRE 5.0.0), and the
// 64-bit Java Access Bridge CAT uses for the other simulators cannot reach a
// 32-bit JVM's windows ("HWND ... is not Java Window", measured 2026-10-06).
// Inside the JVM there is nothing to reach: the menu bar is a plain Swing tree.
//
// It presses only that item, only once, and only if BTT's own automation server
// (petrotest.devices.vats.btt, the preferred route) is not answering - so the
// two never both start the server. It touches no licensing and loads nothing.
//
// While it waits it reads the titles of BTT's open dialogs, and nothing else.
// One about a licence ("Invalid License", HANDOFF.md §1.133) is reported as
// `blocked:` with the dialog's text and ends the wait: BTT never builds its menu
// behind it. Any other dialog is named in the `waiting` line. It presses no
// button in either.
//
// Loaded through JAVA_TOOL_OPTIONS=-javaagent:<jar>=<options file>. Java 8
// splits that variable on whitespace with no quoting, so every option lives in
// the properties file, whose path has no spaces. Built with --release 8.

package cat.btt;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

public final class MenuAgent {
    private MenuAgent() {}

    public static void premain(String args) {
        final Properties options = new Properties();
        try (InputStream in = new FileInputStream(args)) {
            options.load(in);
        } catch (IOException e) {
            return; // no options, no agent: BTT starts exactly as without it
        }
        Thread thread = new Thread(() -> run(options), "cat-btt-menu-agent");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(Properties options) {
        // `|`-separated: BTT 5.9.0 says "Start Card Simulator Server", the AQA page
        // (an older BTT) "Start Card Simulation Server".
        String label = options.getProperty("label", "Start Card Simulator Server").trim();
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        Path status = Paths.get(options.getProperty("status", "cat-btt-agent-{pid}.txt").replace("{pid}", pid));
        int automationPort = Integer.parseInt(options.getProperty("automation_port", "0"));
        long timeoutMs = Long.parseLong(options.getProperty("timeout_ms", "120000"));
        long graceMs = Long.parseLong(options.getProperty("grace_ms", "5000"));
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            write(status, "waiting for " + label);
            String shown = "";
            JMenuItem item = null;
            while (System.currentTimeMillis() < deadline) {
                item = find(label);
                if (item != null && item.isEnabled()) {
                    break;
                }
                String licence = licenceDialog();
                if (licence != null) {
                    write(status, "blocked: " + licence);
                    return;
                }
                String dialogs = String.join("; ", dialogTitles());
                if (!dialogs.equals(shown)) {
                    shown = dialogs;
                    write(status, "waiting for " + label + (dialogs.isEmpty() ? "" : " (dialog open: " + dialogs + ")"));
                }
                Thread.sleep(500);
            }
            if (item == null) {
                String menus = menus();
                if (menus.isEmpty()) {
                    // A JVM with no menu bar at all: a child BTT spawned (the card
                    // simulator inherits JAVA_TOOL_OPTIONS). Not a verdict.
                    write(status, "idle: no BTT window in this JVM");
                } else {
                    write(status, "not-found: " + label + " (menus: " + menus + ")");
                }
                return;
            }
            if (!item.isEnabled()) {
                write(status, "disabled: " + label);
                return;
            }
            long graceEnd = System.currentTimeMillis() + graceMs;
            while (System.currentTimeMillis() < graceEnd) {
                if (automationPort > 0 && listening(automationPort)) {
                    write(status, "skipped: automation server answers on " + automationPort);
                    return;
                }
                Thread.sleep(250);
            }
            final JMenuItem target = item;
            SwingUtilities.invokeAndWait(target::doClick);
            write(status, "clicked: " + label);
        } catch (Exception e) {
            try {
                write(status, "error: " + e);
            } catch (IOException ignored) {
                // nothing left to tell
            }
        }
    }

    private static JMenuItem find(String label) {
        for (Frame frame : Frame.getFrames()) {
            if (!(frame instanceof JFrame) || !frame.isDisplayable()) {
                continue;
            }
            JMenuBar bar = ((JFrame) frame).getJMenuBar();
            if (bar == null) {
                continue;
            }
            for (int i = 0; i < bar.getMenuCount(); i++) {
                JMenuItem hit = search(bar.getMenu(i), label);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static JMenuItem search(JMenu menu, String label) {
        if (menu == null) {
            return null;
        }
        for (Component child : menu.getMenuComponents()) {
            if (child instanceof JMenu) {
                JMenuItem hit = search((JMenu) child, label);
                if (hit != null) {
                    return hit;
                }
            } else if (child instanceof JMenuItem) {
                String text = ((JMenuItem) child).getText();
                if (text == null) {
                    continue;
                }
                for (String wanted : label.split(java.util.regex.Pattern.quote("|"))) {
                    if (text.trim().equalsIgnoreCase(wanted.trim())) {
                        return (JMenuItem) child;
                    }
                }
            }
        }
        return null;
    }

    /** The visible dialogs' titles, e.g. "Invalid License". */
    private static List<String> dialogTitles() {
        List<String> titles = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog && window.isShowing()) {
                titles.add("'" + String.valueOf(((Dialog) window).getTitle()) + "'");
            }
        }
        return titles;
    }

    /** "'<title>': <text>" for a visible dialog about a licence, else null. */
    private static String licenceDialog() {
        for (Window window : Window.getWindows()) {
            if (!(window instanceof Dialog) || !window.isShowing()) {
                continue;
            }
            String title = String.valueOf(((Dialog) window).getTitle());
            String lower = title.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("license") || lower.contains("licence")) {
                StringBuilder text = new StringBuilder();
                collectText(window, text);
                String body = text.toString().replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
                if (body.length() > 300) {
                    body = body.substring(0, 300) + "...";
                }
                return "'" + title + "'" + (body.isEmpty() ? "" : ": " + body);
            }
        }
        return null;
    }

    private static void collectText(Component component, StringBuilder into) {
        String text = null;
        if (component instanceof JOptionPane) {
            Object message = ((JOptionPane) component).getMessage();
            text = message instanceof String ? (String) message : null;
        } else if (component instanceof JLabel) {
            text = ((JLabel) component).getText();
        } else if (component instanceof JTextComponent) {
            text = ((JTextComponent) component).getText();
        }
        if (text != null && !text.trim().isEmpty()) {
            into.append(text).append(' ');
        }
        if (component instanceof Container && !(component instanceof JTextComponent)) {
            for (Component child : ((Container) component).getComponents()) {
                collectText(child, into);
            }
        }
    }

    private static String menus() {
        List<String> names = new ArrayList<>();
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof JFrame && ((JFrame) frame).getJMenuBar() != null) {
                JMenuBar bar = ((JFrame) frame).getJMenuBar();
                for (int i = 0; i < bar.getMenuCount(); i++) {
                    JMenu menu = bar.getMenu(i);
                    if (menu == null) {
                        continue;
                    }
                    List<String> items = new ArrayList<>();
                    for (Component child : menu.getMenuComponents()) {
                        if (child instanceof JMenuItem) {
                            items.add(String.valueOf(((JMenuItem) child).getText()));
                        }
                    }
                    names.add(menu.getText() + items);
                }
            }
        }
        return String.join(", ", names);
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void write(Path status, String text) throws IOException {
        Path staging = status.resolveSibling(status.getFileName() + ".tmp");
        Files.write(staging, text.getBytes(StandardCharsets.UTF_8));
        Files.move(staging, status, StandardCopyOption.REPLACE_EXISTING);
    }
}
