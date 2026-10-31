import javax.swing.*;
import java.awt.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * NiuPcApp (M4c, design §7) — the PC operator app for 牛备.
 *
 * Modes:
 *   (no args)          Swing operator window + system-tray icon.
 *   --serve <args...>  headless passthrough to BackupReceiver.main (used by
 *                      the systemd user unit / autostart).
 *   --login <args...>  passthrough to BackupReceiver.main's --login flow.
 *
 * The window covers the operator's whole loop: account login (the only
 * password moment — afterwards the PC works with its device token from
 * account.json), starting/stopping the embedded account-mode receiver
 * (which supervises the easytier sidecar itself), approving pending phone
 * devices of the same account (PC-approves-phone, §6), bound-device and
 * quota status, and the session log. Receiver stdout is mirrored into the
 * log pane.
 */
public class NiuPcApp {

    private Path dataDir;
    private BackupReceiver helper;          // own Store connection for reads/login
    private AccountLink.State state;       // loaded account state (null until login)
    private Thread serveThread;

    private JTextField serverField;
    private JTextField accountField;
    private JPasswordField passwordField;
    private JTextField targetField;
    private JTextField portField;
    private JLabel statusLabel;
    private JLabel quotaLabel;
    private DefaultListModel<String> pendingModel;
    private JTextArea devicesArea;
    private JTextArea sessionsArea;
    private JTextArea logArea;
    private JButton startButton;
    private JButton stopButton;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("--serve") || args[0].equals("--login"))) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            if (args[0].equals("--login")) {
                String[] withFlag = new String[rest.length + 1];
                withFlag[0] = "--login";
                System.arraycopy(rest, 0, withFlag, 1, rest.length);
                BackupReceiver.main(withFlag);
            } else {
                BackupReceiver.main(rest);
            }
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("no display: use --serve (headless receiver) or --login");
            System.exit(2);
        }
        System.setProperty("java.awt.headless", "false");
        Path dd = Paths.get(System.getProperty("user.home"), ".niubackup");
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--data-dir")) dd = Paths.get(args[i + 1]);
        }
        final Path dataDir = dd;
        SwingUtilities.invokeLater(() -> {
            try {
                new NiuPcApp(dataDir).show();
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "启动失败: " + e.getMessage());
            }
        });
    }

    NiuPcApp(Path dataDir) throws Exception {
        this.dataDir = dataDir;
        this.helper = new BackupReceiver(28741,
                Paths.get(System.getProperty("user.home"), "NiuBackup"), null, dataDir, false);
        this.helper.openStore();
        this.state = AccountLink.load(dataDir);
    }

    // ------------------------------------------------------------------ UI

    private void show() {
        JFrame frame = new JFrame("牛备 NiuBackup — 电脑端");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (SystemTray.isSupported()) {
                    frame.setVisible(false);
                } else {
                    quit();
                }
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        serverField = addField(form, c, row++, "账号服务",
                state != null ? state.server : "http://10.200.0.1:18090");
        accountField = addField(form, c, row++, "账号",
                state != null ? state.account : "");
        passwordField = new JPasswordField();
        addRow(form, c, row++, "密码（仅登录时用）", passwordField);
        targetField = addField(form, c, row++, "存储目录",
                Paths.get(System.getProperty("user.home"), "NiuBackup").toString());
        portField = addField(form, c, row++, "备份端口", "28741");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton loginButton = new JButton("登录");
        loginButton.addActionListener(e -> doLogin());
        startButton = new JButton("启动服务");
        startButton.addActionListener(e -> doStart());
        stopButton = new JButton("停止服务");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> doStop());
        JButton refreshButton = new JButton("刷新设备/会话");
        refreshButton.addActionListener(e -> refreshPanels());
        buttons.add(loginButton);
        buttons.add(startButton);
        buttons.add(stopButton);
        buttons.add(refreshButton);
        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        form.add(buttons, c);

        statusLabel = new JLabel(stateSummary());
        c.gridy = row++;
        form.add(statusLabel, c);
        quotaLabel = new JLabel("");
        c.gridy = row++;
        form.add(quotaLabel, c);

        pendingModel = new DefaultListModel<>();
        JList<String> pendingList = new JList<>(pendingModel);
        pendingList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton approveButton = new JButton("批准所选手机");
        approveButton.addActionListener(e -> {
            String sel = pendingList.getSelectedValue();
            if (sel == null) return;
            doApprove(sel.split(" ")[0].replace("#", ""));
        });
        JPanel pendingPanel = new JPanel(new BorderLayout());
        pendingPanel.setBorder(BorderFactory.createTitledBorder("待批准设备（手机在 App 登录后会出现在这里）"));
        pendingPanel.add(new JScrollPane(pendingList), BorderLayout.CENTER);
        pendingPanel.add(approveButton, BorderLayout.SOUTH);

        devicesArea = new JTextArea(6, 60);
        devicesArea.setEditable(false);
        JPanel devicesPanel = new JPanel(new BorderLayout());
        devicesPanel.setBorder(BorderFactory.createTitledBorder("已绑定设备"));
        devicesPanel.add(new JScrollPane(devicesArea), BorderLayout.CENTER);

        sessionsArea = new JTextArea(6, 60);
        sessionsArea.setEditable(false);
        JPanel sessionsPanel = new JPanel(new BorderLayout());
        sessionsPanel.setBorder(BorderFactory.createTitledBorder("备份会话"));
        sessionsPanel.add(new JScrollPane(sessionsArea), BorderLayout.CENTER);

        logArea = new JTextArea(10, 60);
        logArea.setEditable(false);
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("运行日志"));
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        installLogTee();

        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(form, BorderLayout.NORTH);
        JTabbedPane center = new JTabbedPane();
        center.add("待批准", pendingPanel);
        center.add("已绑定设备", devicesPanel);
        center.add("备份会话", sessionsPanel);
        center.add("运行日志", logPanel);
        rootPanel.add(center, BorderLayout.CENTER);
        frame.add(rootPanel, BorderLayout.CENTER);
        frame.setSize(760, 640);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        installTray(frame);
        refreshPanels();
    }

    private JTextField addField(JPanel p, GridBagConstraints c, int row, String label, String value) {
        JTextField f = new JTextField(value, 28);
        addRow(p, c, row, label, f);
        return f;
    }

    private void addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent comp) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        p.add(comp, c);
    }

    private String stateSummary() {
        if (state == null) return "未登录 — 先填写账号服务/账号/密码并点「登录」";
        return "已登录: " + state.account + "（user " + state.userId + "，网络 "
                + state.networkName + "，本机IP " + state.pcIp + "）"
                + (serveThread != null && serveThread.isAlive() ? " · 服务运行中" : " · 服务未启动");
    }

    // ------------------------------------------------------------- actions

    private void doLogin() {
        String server = serverField.getText().trim();
        String account = accountField.getText().trim();
        String password = new String(passwordField.getPassword());
        if (server.isEmpty() || account.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(null, "账号服务/账号/密码都要填");
            return;
        }
        new Thread(() -> {
            try {
                helper.loadOrCreateKeys();
                AccountLink.State st = AccountLink.loginFlow(dataDir, helper.store,
                        server, account, password, helper.pubkeyB64(), null);
                state = st;
                log("login ok: user_id=" + st.userId + " network=" + st.networkName);
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText(stateSummary());
                    refreshPanels();
                });
            } catch (Exception e) {
                log("login failed: " + e.getMessage());
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(null, "登录失败: " + e.getMessage()));
            }
        }, "login").start();
    }

    private void doStart() {
        if (state == null) {
            JOptionPane.showMessageDialog(null, "先登录");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "端口无效");
            return;
        }
        String target = targetField.getText().trim();
        serveThread = new Thread(() -> {
            try {
                BackupReceiver.main(new String[]{
                        "--account", "--port", String.valueOf(port),
                        "--target", target, "--data-dir", dataDir.toString()});
                log("receiver stopped");
            } catch (Exception e) {
                log("receiver error: " + e.getMessage());
            }
            SwingUtilities.invokeLater(() -> {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                statusLabel.setText(stateSummary());
            });
        }, "receiver-serve");
        serveThread.start();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusLabel.setText(stateSummary() + " · 启动中…");
    }

    private void doStop() {
        BackupReceiver.stopCurrentServer();
    }

    private void doApprove(String deviceId) {
        if (state == null) return;
        new Thread(() -> {
            try {
                String res = AccountLink.approveWithDevice(state, deviceId);
                log("approve device " + deviceId + ": " + res);
                log(AccountLink.syncOnce(state, helper.store));
            } catch (Exception e) {
                log("approve failed: " + e.getMessage());
            }
            SwingUtilities.invokeLater(this::refreshPanels);
        }, "approve").start();
    }

    private void refreshPanels() {
        if (state == null) return;
        new Thread(() -> {
            try {
                List<String[]> devices = AccountLink.listDevices(state, state.pcDeviceToken);
                StringBuilder bound = new StringBuilder();
                for (String[] d : devices) {
                    String line = "#" + d[0] + " " + d[1] + " " + d[2]
                            + " approved=" + d[3] + " revoked=" + d[4];
                    if ("false".equals(d[3]) && "false".equals(d[4])) {
                        bound.append(line).append("  ← 待批准\n");
                    } else {
                        bound.append(line).append('\n');
                    }
                }
                // Pending list model: one entry per pending device.
                java.util.List<String> pend = new java.util.ArrayList<>();
                for (String[] d : devices) {
                    if ("false".equals(d[3]) && "false".equals(d[4])) {
                        pend.add("#" + d[0] + " " + d[1] + " " + d[2]);
                    }
                }
                String boundText = bound.toString();
                List<String[]> sessions = helper.store.listSessions(50);
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                StringBuilder sess = new StringBuilder();
                for (String[] s : sessions) {
                    sess.append(f.format(new Date(Long.parseLong(s[0]))))
                            .append(" device=").append(s[1])
                            .append(" files=").append(s[2])
                            .append(" bytes=").append(s[3])
                            .append(" ").append(s[4]).append('\n');
                }
                long used = helper.store.filesBytesTotal(state.userId);
                String quotaMeta = helper.store.getMeta("quota_bytes");
                long quota = quotaMeta == null ? -1 : Long.parseLong(quotaMeta);
                SwingUtilities.invokeLater(() -> {
                    pendingModel.clear();
                    for (String p : pend) pendingModel.addElement(p);
                    devicesArea.setText(boundText);
                    sessionsArea.setText(sess.toString());
                    quotaLabel.setText("已备份占用: " + humanBytes(used)
                            + (quota > 0 ? " / 配额 " + humanBytes(quota) : "")
                            + " · 存储目录 " + targetField.getText().trim());
                    statusLabel.setText(stateSummary());
                });
            } catch (Exception e) {
                log("refresh failed: " + e.getMessage());
            }
        }, "refresh").start();
    }

    // ---------------------------------------------------------------- tray

    private void installTray(JFrame frame) {
        if (!SystemTray.isSupported()) return;
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(0x1E, 0x7A, 0x34));
            g.fillRoundRect(2, 2, 28, 28, 8, 8);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            g.drawString("牛", 8, 24);
            g.dispose();
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("打开牛备");
            open.addActionListener(e -> {
                frame.setVisible(true);
                frame.setState(Frame.NORMAL);
            });
            MenuItem quitItem = new MenuItem("退出");
            quitItem.addActionListener(e -> quit());
            menu.add(open);
            menu.add(quitItem);
            TrayIcon icon = new TrayIcon(img, "牛备 NiuBackup", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> {
                frame.setVisible(true);
                frame.setState(Frame.NORMAL);
            });
            SystemTray.getSystemTray().add(icon);
        } catch (Exception ignore) {
            // Tray is a nice-to-have; the window works without it.
        }
    }

    private void quit() {
        BackupReceiver.stopCurrentServer();
        System.exit(0);
    }

    // ------------------------------------------------------------------ log

    private void installLogTee() {
        PrintStream original = System.out;
        OutputStream tee = new OutputStream() {
            private final StringBuilder line = new StringBuilder();
            @Override public void write(int b) {
                original.write(b);
                if (b == '\n') {
                    String s = line.toString();
                    line.setLength(0);
                    SwingUtilities.invokeLater(() -> {
                        logArea.append(s + "\n");
                        logArea.setCaretPosition(logArea.getDocument().getLength());
                    });
                } else {
                    line.append((char) b);
                }
            }
            @Override public void write(byte[] b, int off, int len) {
                String s = new String(b, off, len, StandardCharsets.UTF_8);
                original.write(b, off, len);
                SwingUtilities.invokeLater(() -> {
                    logArea.append(s);
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                });
            }
        };
        System.setOut(new PrintStream(tee, true, StandardCharsets.UTF_8));
    }

    private static void log(String msg) {
        System.out.println("[pc-ui] " + msg);
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1024.0 / 1024);
        return String.format("%.2f GB", n / 1024.0 / 1024 / 1024);
    }
}
