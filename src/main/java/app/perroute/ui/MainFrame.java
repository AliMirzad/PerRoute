package app.perroute.ui;

import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.ProxyNode;
import app.perroute.model.RoutingMode;
import app.perroute.parser.ParseResult;
import app.perroute.persistence.AppStateStore;
import app.perroute.service.AdminCheck;
import app.perroute.service.AppProxyLauncher;
import app.perroute.service.AppDirectories;
import app.perroute.service.CoreInstaller;
import app.perroute.service.ProcessDiscovery;
import app.perroute.service.ProxyHealthCheck;
import app.perroute.service.ProxiFyreManager;
import app.perroute.service.SubscriptionService;
import app.perroute.service.XrayConfigGenerator;
import app.perroute.service.XrayProcessManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("serial")
public final class MainFrame extends JFrame {
    private final AppState state;
    private final AppStateStore stateStore;
    private final NodeTableModel nodeModel;
    private final AppTableModel appModel;
    private final JTable nodeTable;
    private final JTable appTable;
    private final JComboBox<ConnectionMode> connectionMode;
    private final JComboBox<RoutingMode> routingMode;
    private final JTextField subscriptionUrl = new JTextField();
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("قطع");
    private final JLabel environmentLabel = new JLabel();
    private final JLabel routeActivityLabel = new JLabel("منتظر ترافیک برنامه");
    private final JLabel modeHelpLabel = new JLabel();
    private final JButton connectButton = Theme.primaryButton("اتصال");
    private final JButton installCoreButton = new JButton("نصب هسته");
    private final JButton installPerAppButton = new JButton("نصب موتور برنامه‌ها");
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final SubscriptionService subscriptionService = new SubscriptionService();
    private final XrayConfigGenerator configGenerator = new XrayConfigGenerator();
    private final XrayProcessManager xray = new XrayProcessManager();
    private final AppProxyLauncher appProxyLauncher = new AppProxyLauncher();
    private final ProxyHealthCheck proxyHealthCheck = new ProxyHealthCheck();
    private final CoreInstaller coreInstaller = new CoreInstaller();
    private final ProxiFyreManager proxiFyre = new ProxiFyreManager();
    private final List<Process> launchedApplications = new ArrayList<>();
    private volatile ConnectionMode activeConnectionMode;
    private volatile boolean connectionReady;

    public MainFrame(AppState state, AppStateStore stateStore) {
        super("PerRoute — VPN برای برنامه‌های انتخابی");
        this.state = state;
        this.stateStore = stateStore;
        this.nodeModel = new NodeTableModel(state.nodes());
        this.appModel = new AppTableModel(state.appPaths());
        this.nodeTable = new JTable(nodeModel);
        this.appTable = new JTable(appModel);
        this.connectionMode = new JComboBox<>(ConnectionMode.values());
        this.routingMode = new JComboBox<>(RoutingMode.values());

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1020, 680));
        setSize(1180, 780);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(12, 12));
        ((JPanel)getContentPane()).setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        add(sidebar(), BorderLayout.WEST);
        add(content(), BorderLayout.CENTER);
        configureTables();
        bindActions();
        restoreSelection();
        refreshEnvironment();
        updateConnectionState(false);
        applyOrientation(this);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });
    }

    private JPanel sidebar() {
        RoundedPanel panel = new RoundedPanel(24, Theme.SIDEBAR);
        panel.setPreferredSize(new Dimension(255, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(26, 22, 22, 22));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel brand = new JLabel("PerRoute");
        brand.setForeground(java.awt.Color.WHITE);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 27f));
        JLabel tagline = new JLabel("VPN هوشمند برای هر برنامه");
        tagline.setForeground(new java.awt.Color(0x91A0B8));
        tagline.setFont(tagline.getFont().deriveFont(12f));
        brand.setAlignmentX(Component.RIGHT_ALIGNMENT);
        tagline.setAlignmentX(Component.RIGHT_ALIGNMENT);
        panel.add(brand);
        panel.add(Box.createVerticalStrut(3));
        panel.add(tagline);
        panel.add(Box.createVerticalStrut(28));

        RoundedPanel statusCard = new RoundedPanel(18, Theme.SIDEBAR_CARD);
        statusCard.setLayout(new BoxLayout(statusCard, BoxLayout.Y_AXIS));
        statusCard.setBorder(BorderFactory.createEmptyBorder(17, 16, 17, 16));
        statusCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 118));
        JLabel caption = new JLabel("وضعیت اتصال");
        caption.setForeground(new java.awt.Color(0x91A0B8));
        caption.setAlignmentX(Component.RIGHT_ALIGNMENT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 19f));
        statusLabel.setForeground(new java.awt.Color(0xFF7081));
        statusLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        routeActivityLabel.setForeground(new java.awt.Color(0x91A0B8));
        routeActivityLabel.setFont(routeActivityLabel.getFont().deriveFont(11f));
        routeActivityLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        statusCard.add(caption);
        statusCard.add(Box.createVerticalStrut(5));
        statusCard.add(statusLabel);
        statusCard.add(Box.createVerticalStrut(5));
        statusCard.add(routeActivityLabel);
        panel.add(statusCard);
        panel.add(Box.createVerticalStrut(14));

        connectButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        connectButton.setPreferredSize(new Dimension(210, 46));
        connectButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
        panel.add(connectButton);
        panel.add(Box.createVerticalStrut(12));

        styleSidebarButton(installCoreButton);
        panel.add(installCoreButton);
        panel.add(Box.createVerticalStrut(8));
        styleSidebarButton(installPerAppButton);
        panel.add(installPerAppButton);
        panel.add(Box.createVerticalGlue());
        environmentLabel.setForeground(new java.awt.Color(0x91A0B8));
        environmentLabel.setFont(environmentLabel.getFont().deriveFont(11f));
        environmentLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        panel.add(environmentLabel);
        panel.add(Box.createVerticalStrut(8));
        JLabel version = new JLabel("نسخه 0.4 · Windows x64");
        version.setForeground(new java.awt.Color(0x63718A));
        version.setFont(version.getFont().deriveFont(10f));
        version.setAlignmentX(Component.RIGHT_ALIGNMENT);
        panel.add(version);
        return panel;
    }

    private static void styleSidebarButton(JButton button) {
        button.setForeground(new java.awt.Color(0xD9E1EF));
        button.setBackground(Theme.SIDEBAR_CARD);
        button.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        button.putClientProperty("FlatLaf.style", "arc: 14; borderWidth: 0; focusWidth: 0");
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        button.setAlignmentX(Component.RIGHT_ALIGNMENT);
    }

    private JPanel content() {
        JPanel workspace = new JPanel(new BorderLayout(0, 12));
        workspace.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0));
        JPanel titleArea = new JPanel();
        titleArea.setOpaque(false);
        titleArea.setLayout(new BoxLayout(titleArea, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("داشبورد اتصال");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        title.setForeground(Theme.TEXT);
        JLabel subtitle = new JLabel("سرور، برنامه و مسیر ترافیک را از یک‌جا کنترل کنید");
        subtitle.setForeground(Theme.MUTED);
        titleArea.add(title);
        titleArea.add(Box.createVerticalStrut(2));
        titleArea.add(subtitle);
        JTabbedPane tabs = new JTabbedPane();
        tabs.putClientProperty("FlatLaf.style", "tabType: underlined; showTabSeparators: false; tabHeight: 44; underlineHeight: 3");
        tabs.addTab("سرورها", serversPanel());
        tabs.addTab("برنامه‌ها و مسیریابی", applicationsPanel());
        tabs.addTab("گزارش", logsPanel());
        workspace.add(titleArea, BorderLayout.NORTH);
        workspace.add(tabs, BorderLayout.CENTER);
        return workspace;
    }

    private JPanel serversPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 4, 4, 4));
        JPanel input = new JPanel(new BorderLayout(8, 0));
        Theme.card(input);
        subscriptionUrl.setToolTipText("آدرس Subscription را اینجا وارد کنید");
        JButton fetch = Theme.primaryButton("دریافت اشتراک");
        fetch.setActionCommand("fetch-subscription");
        JButton paste = Theme.secondaryButton("افزودن لینک دستی");
        paste.setActionCommand("paste-links");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(paste);
        buttons.add(fetch);
        input.add(new JLabel("Subscription URL"), BorderLayout.EAST);
        input.add(subscriptionUrl, BorderLayout.CENTER);
        input.add(buttons, BorderLayout.WEST);

        JPanel tableCard = new JPanel(new BorderLayout(8, 8));
        Theme.card(tableCard);
        tableCard.add(new JScrollPane(nodeTable), BorderLayout.CENTER);
        JButton remove = Theme.secondaryButton("حذف سرور");
        remove.setActionCommand("remove-node");
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.setOpaque(false);
        bottom.add(remove);
        tableCard.add(bottom, BorderLayout.SOUTH);
        root.add(input, BorderLayout.NORTH);
        root.add(tableCard, BorderLayout.CENTER);
        return root;
    }

    private JPanel applicationsPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 4, 4, 4));
        JPanel mode = new JPanel(new GridLayout(2, 2, 10, 8));
        Theme.card(mode);
        connectionMode.setSelectedItem(state.connectionMode());
        routingMode.setSelectedItem(state.routingMode());
        routingMode.setEnabled(state.connectionMode() == ConnectionMode.TUN_COMPATIBILITY);
        mode.add(new JLabel("روش اتصال"));
        mode.add(connectionMode);
        mode.add(new JLabel("قانون TUN"));
        mode.add(routingMode);

        JPanel tableCard = new JPanel(new BorderLayout(8, 8));
        Theme.card(tableCard);
        tableCard.add(new JScrollPane(appTable), BorderLayout.CENTER);
        JButton running = Theme.primaryButton("انتخاب از برنامه‌های در حال اجرا");
        running.setActionCommand("running-apps");
        JButton file = Theme.secondaryButton("انتخاب فایل EXE");
        file.setActionCommand("choose-exe");
        JButton remove = Theme.secondaryButton("حذف");
        remove.setActionCommand("remove-app");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        actions.setOpaque(false);
        actions.add(remove);
        actions.add(file);
        actions.add(running);
        tableCard.add(actions, BorderLayout.SOUTH);

        updateModeHelp();
        modeHelpLabel.setForeground(Theme.MUTED);
        modeHelpLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));
        root.add(mode, BorderLayout.NORTH);
        root.add(tableCard, BorderLayout.CENTER);
        root.add(modeHelpLabel, BorderLayout.SOUTH);
        return root;
    }

    private JPanel logsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 4, 4, 4));
        Theme.card(panel);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setLineWrap(true);
        logArea.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private void configureTables() {
        for (JTable table : List.of(nodeTable, appTable)) {
            table.setRowHeight(29);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setFillsViewportHeight(true);
            table.setShowVerticalLines(false);
            table.setShowHorizontalLines(false);
            table.getTableHeader().setPreferredSize(new Dimension(0, 38));
            table.putClientProperty("FlatLaf.style", "rowHeight: 38; selectionArc: 10; showHorizontalLines: false; showVerticalLines: false");
        }
        nodeTable.getColumnModel().getColumn(0).setPreferredWidth(360);
        nodeTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        nodeTable.getColumnModel().getColumn(2).setPreferredWidth(220);
        appTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        appTable.getColumnModel().getColumn(1).setPreferredWidth(600);
    }

    private void bindActions() {
        findButton("fetch-subscription").addActionListener(e -> fetchSubscription());
        findButton("paste-links").addActionListener(e -> pasteLinks());
        findButton("remove-node").addActionListener(e -> removeNode());
        findButton("running-apps").addActionListener(e -> selectRunningApps());
        findButton("choose-exe").addActionListener(e -> chooseExecutable());
        findButton("remove-app").addActionListener(e -> removeApp());
        connectionMode.addActionListener(e -> {
            ConnectionMode selected = (ConnectionMode) connectionMode.getSelectedItem();
            state.setConnectionMode(selected);
            routingMode.setEnabled(selected == ConnectionMode.TUN_COMPATIBILITY);
            updateModeHelp();
            updateConnectionState(xray.isRunning());
            refreshEnvironment();
            saveState();
        });
        routingMode.addActionListener(e -> {
            state.setRoutingMode((RoutingMode) routingMode.getSelectedItem());
            saveState();
        });
        nodeTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && nodeTable.getSelectedRow() >= 0) {
                state.setSelectedNodeKey(nodeModel.get(nodeTable.convertRowIndexToModel(nodeTable.getSelectedRow())).key());
                saveState();
            }
        });
        connectButton.addActionListener(e -> toggleConnection());
        installCoreButton.addActionListener(e -> installCore());
        installPerAppButton.addActionListener(e -> installPerAppEngine());
        xray.addLogListener(this::handleXrayLog);
        xray.addStateListener(running -> {
            SwingUtilities.invokeLater(() -> updateConnectionState(running && connectionReady));
            if (!running && activeConnectionMode == ConnectionMode.NATIVE_PER_APP) {
                executor.submit(() -> {
                    try { proxiFyre.stopAndRestore(); }
                    catch (Exception e) { appendLog("پاک‌سازی موتور per-app ناموفق بود: " + message(e)); }
                    finally { activeConnectionMode = null; }
                });
            }
        });
    }

    private void updateModeHelp() {
        ConnectionMode mode = (ConnectionMode) connectionMode.getSelectedItem();
        String text = switch (mode == null ? ConnectionMode.NATIVE_PER_APP : mode) {
            case NATIVE_PER_APP -> "حالت عمومی: برنامه می‌تواند باز باشد؛ فقط همان پردازش با موتور شبکهٔ ویندوز وارد VPN می‌شود.";
            case BROWSER_PROXY -> "حالت مرورگر: مرورگر باید کاملاً بسته باشد؛ PerRoute آن را با پراکسی محلی اجرا می‌کند.";
            case SYSTEM_PROXY -> "حالت سیستم: برنامه‌هایی که تنظیم پراکسی ویندوز را می‌خوانند از مسیر VPN عبور می‌کنند.";
            case TUN_COMPATIBILITY -> "حالت پیشرفته: کل ترافیک ابتدا وارد TUN می‌شود و سپس بر اساس پردازش تفکیک می‌شود.";
        };
        modeHelpLabel.setText(text);
    }

    private JButton findButton(String actionCommand) {
        return findButton(getContentPane(), actionCommand);
    }

    private JButton findButton(Component parent, String command) {
        if (parent instanceof JButton button && command.equals(button.getActionCommand())) return button;
        if (parent instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButton(child, command);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void fetchSubscription() {
        String url = subscriptionUrl.getText().strip();
        if (url.isBlank()) { error("آدرس Subscription را وارد کنید."); return; }
        setBusy(true);
        executor.submit(() -> {
            try {
                ParseResult result = subscriptionService.fetch(url);
                SwingUtilities.invokeLater(() -> {
                    mergeNodes(result);
                    if (state.subscriptionUrls().stream().noneMatch(url::equalsIgnoreCase)) state.subscriptionUrls().add(url);
                    saveState();
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> error(message(e))); }
            finally { SwingUtilities.invokeLater(() -> setBusy(false)); }
        });
    }

    private void pasteLinks() {
        JTextArea input = new JTextArea(12, 64);
        input.setLineWrap(true);
        int answer = JOptionPane.showConfirmDialog(this, new JScrollPane(input),
                "لینک‌ها را وارد کنید — هر لینک در یک خط", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        mergeNodes(subscriptionService.parsePasted(input.getText()));
        saveState();
    }

    private void mergeNodes(ParseResult result) {
        LinkedHashMap<String, ProxyNode> merged = new LinkedHashMap<>();
        nodeModel.values().forEach(node -> merged.put(node.key(), node));
        result.nodes().forEach(node -> merged.put(node.key(), node));
        nodeModel.replace(merged.values());
        if (!result.nodes().isEmpty() && nodeTable.getSelectedRow() < 0) nodeTable.setRowSelectionInterval(0, 0);
        appendLog(result.nodes().size() + " سرور خوانده شد.");
        result.warnings().forEach(warning -> appendLog("هشدار: " + warning));
        if (result.nodes().isEmpty()) error("هیچ لینک قابل استفاده‌ای پیدا نشد. جزئیات در گزارش ثبت شد.");
    }

    private void removeNode() {
        int row = nodeTable.getSelectedRow();
        if (row < 0) return;
        nodeModel.remove(nodeTable.convertRowIndexToModel(row));
        state.setSelectedNodeKey("");
        saveState();
    }

    private void chooseExecutable() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("انتخاب فایل اجرایی برنامه");
        chooser.setFileFilter(new FileNameExtensionFilter("Windows executable (*.exe)", "exe"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            appModel.addAll(List.of(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString()));
            saveState();
        }
    }

    private void selectRunningApps() {
        setBusy(true);
        executor.submit(() -> {
            List<ProcessDiscovery.RunningApplication> applications = new ProcessDiscovery().list();
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                JList<ProcessDiscovery.RunningApplication> list = new JList<>(applications.toArray(ProcessDiscovery.RunningApplication[]::new));
                list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                list.setVisibleRowCount(16);
                int answer = JOptionPane.showConfirmDialog(this, new JScrollPane(list),
                        "برنامه‌های در حال اجرا", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                if (answer == JOptionPane.OK_OPTION) {
                    appModel.addAll(list.getSelectedValuesList().stream().map(ProcessDiscovery.RunningApplication::path).toList());
                    saveState();
                }
            });
        });
    }

    private void removeApp() {
        int row = appTable.getSelectedRow();
        if (row < 0) return;
        appModel.remove(appTable.convertRowIndexToModel(row));
        saveState();
    }

    private void installCore() {
        setBusy(true);
        executor.submit(() -> {
            try {
                coreInstaller.install(this::appendLog);
                SwingUtilities.invokeLater(() -> {
                    refreshEnvironment();
                    JOptionPane.showMessageDialog(this, "هسته رسمی Xray و Wintun نصب شد.", "آماده", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> error(message(e))); }
            finally { SwingUtilities.invokeLater(() -> setBusy(false)); }
        });
    }

    private void installPerAppEngine() {
        int answer = JOptionPane.showConfirmDialog(this,
                "موتور ProxiFyre و درایور Windows Packet Filter از انتشار رسمی دریافت می‌شوند. نصب‌کننده دسترسی Administrator می‌خواهد و ناشر آن امضای دیجیتال ندارد؛ checksum رسمی قبل از اجرا بررسی می‌شود. ادامه می‌دهید؟",
                "نصب موتور برنامه‌ها", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        setBusy(true);
        executor.submit(() -> {
            try {
                proxiFyre.downloadAndInstall(this::appendLog);
                SwingUtilities.invokeLater(() -> {
                    refreshEnvironment();
                    JOptionPane.showMessageDialog(this, "موتور عمومی per-app آماده است.", "آماده", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> error(message(e))); }
            finally { SwingUtilities.invokeLater(() -> setBusy(false)); }
        });
    }

    private void toggleConnection() {
        if (xray.isRunning()) {
            if (activeConnectionMode == ConnectionMode.BROWSER_PROXY &&
                    launchedApplications.stream().anyMatch(Process::isAlive)) {
                int answer = JOptionPane.showConfirmDialog(this,
                        "برنامه‌هایی که PerRoute اجرا کرده هنوز باز هستند. بعد از قطع، اینترنت آن‌ها تا زمان بستن و اجرای دوباره قطع می‌شود. ادامه می‌دهید؟",
                        "قطع اتصال", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
            }
            disconnect();
            return;
        }
        ConnectionMode mode = state.connectionMode();
        if (mode == ConnectionMode.TUN_COMPATIBILITY && !AdminCheck.isElevated()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "این روش اتصال به دسترسی Administrator نیاز دارد. برنامه با دسترسی مدیر دوباره اجرا شود؟",
                    "دسترسی Administrator", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.YES_OPTION && AdminCheck.relaunchPackagedAppElevated()) shutdown();
            else if (answer == JOptionPane.YES_OPTION) error("اجرای خودکار ممکن نبود؛ PerRoute.exe را با Run as administrator باز کنید.");
            return;
        }
        if (mode == ConnectionMode.NATIVE_PER_APP && !proxiFyre.inspect().ready()) {
            error("موتور عمومی per-app نصب نیست. دکمهٔ «نصب موتور برنامه‌ها» را بزنید.");
            return;
        }
        int row = nodeTable.getSelectedRow();
        if (row < 0) { error("ابتدا یک سرور انتخاب کنید."); return; }
        if (state.appPaths().isEmpty()) { error("حداقل یک برنامه را در بخش برنامه‌ها اضافه کنید."); return; }
        ProxyNode node = nodeModel.get(nodeTable.convertRowIndexToModel(row));

        if (mode == ConnectionMode.NATIVE_PER_APP) {
            List<String> selectedNames = state.appPaths().stream()
                    .map(path -> java.nio.file.Path.of(path).getFileName().toString())
                    .distinct().toList();
            List<String> runningSelected = appProxyLauncher.runningApplications(List.copyOf(state.appPaths()));
            if (!runningSelected.isEmpty()) {
                error("برای جلوگیری از باقی‌ماندن اتصال‌های مستقیم، ابتدا این برنامه‌ها را کاملاً ببندید: " +
                        String.join("، ", runningSelected) +
                        System.lineSeparator() +
                        "سپس اتصال را بزنید؛ PerRoute بعد از آماده‌شدن VPN برنامه را خودش اجرا می‌کند.");
                return;
            }
            appendLog("برنامه‌های انتخاب‌شده برای اجرای امن بعد از اتصال: " + String.join("، ", selectedNames));
        }

        if (mode == ConnectionMode.BROWSER_PROXY) {
            List<String> problems = appProxyLauncher.validate(List.copyOf(state.appPaths()));
            if (!problems.isEmpty()) {
                error(String.join(System.lineSeparator(), problems) + System.lineSeparator() +
                        "برای این برنامه حالت عمومی per-app را انتخاب کنید.");
                return;
            }
            List<String> running = appProxyLauncher.runningApplications(List.copyOf(state.appPaths()));
            if (!running.isEmpty()) {
                error("این برنامه هنوز در حال اجراست: " + String.join("، ", running) +
                        System.lineSeparator() + "آن را کاملاً ببندید (حتی پردازش پس‌زمینه) و دوباره اتصال را بزنید.");
                return;
            }
        }

        setBusy(true);
        executor.submit(() -> {
            try {
                boolean tun = mode == ConnectionMode.TUN_COMPATIBILITY;
                String config = tun
                        ? configGenerator.generate(node, List.copyOf(state.appPaths()), state.routingMode())
                        : configGenerator.generateApplicationProxy(node);
                xray.start(config, tun);
                if (!tun) {
                    Thread.sleep(350);
                    if (!xray.isRunning()) {
                        throw new IOException("هستهٔ Xray بلافاصله متوقف شد؛ احتمالاً پورت محلی 17890 یا 17891 اشغال است. جزئیات را در لاگ ببینید.");
                    }
                    appendLog("در حال آزمایش عبور واقعی اینترنت از سرور انتخابی…");
                    ProxyHealthCheck.Result health = proxyHealthCheck.check();
                    if (!health.success()) {
                        throw new IOException("سرور انتخابی تست واقعی اینترنت را پاس نکرد: " + health.detail() +
                                ". کانفیگ WS/TLS یا خود سرور ناسازگار یا از دسترس خارج است.");
                    }
                    appendLog("تست اینترنت موفق بود: " + health.detail());
                    if (mode == ConnectionMode.NATIVE_PER_APP) {
                        proxiFyre.applyAndStart(List.copyOf(state.appPaths()));
                        launchedApplications.removeIf(process -> !process.isAlive());
                        launchedApplications.addAll(appProxyLauncher.launchNormally(List.copyOf(state.appPaths())));
                    } else {
                        launchedApplications.removeIf(process -> !process.isAlive());
                        launchedApplications.addAll(appProxyLauncher.launch(List.copyOf(state.appPaths())));
                    }
                }
                activeConnectionMode = mode;
                connectionReady = true;
                SwingUtilities.invokeLater(() -> updateConnectionState(true));
                appendLog("اتصال به سرور «" + node.name() + "» برقرار شد.");
                if (tun) {
                    appendLog("TUN فعال شد؛ ترافیک برنامه‌ها داخل Xray تفکیک می‌شود.");
                } else if (mode == ConnectionMode.NATIVE_PER_APP) {
                    appendLog("موتور شبکه فعال شد؛ فقط پردازش‌های انتخابی وارد VPN هستند و بقیهٔ سیستم مستقیم است.");
                } else {
                    appendLog("فقط " + state.appPaths().stream()
                            .map(path -> java.nio.file.Path.of(path).getFileName().toString())
                            .distinct().toList() + " با پراکسی اجرا شد؛ مسیر اینترنت ویندوز تغییر نکرد.");
                }
            } catch (Exception e) {
                if (mode == ConnectionMode.NATIVE_PER_APP) {
                    try { proxiFyre.stopAndRestore(); }
                    catch (Exception restoreError) { appendLog("بازگردانی تنظیمات ProxiFyre ناموفق بود: " + message(restoreError)); }
                }
                xray.stop();
                activeConnectionMode = null;
                connectionReady = false;
                SwingUtilities.invokeLater(() -> error(message(e)));
            }
            finally { SwingUtilities.invokeLater(() -> setBusy(false)); }
        });
    }

    private void disconnect() {
        ConnectionMode mode = activeConnectionMode;
        setBusy(true);
        executor.submit(() -> {
            try {
                if (mode == ConnectionMode.NATIVE_PER_APP) proxiFyre.stopAndRestore();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> error("توقف موتور per-app ناموفق بود: " + message(e)));
            } finally {
                activeConnectionMode = null;
                connectionReady = false;
                xray.stop();
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        });
    }

    private void updateConnectionState(boolean running) {
        ConnectionMode mode = running && activeConnectionMode != null ? activeConnectionMode : state.connectionMode();
        connectButton.setText(running ? "قطع اتصال" : switch (mode) {
            case NATIVE_PER_APP -> "اتصال برنامه‌های انتخابی";
            case BROWSER_PROXY -> "اتصال و اجرای مرورگر";
            case SYSTEM_PROXY -> "اتصال پراکسی سیستم";
            case TUN_COMPATIBILITY -> "اتصال TUN";
        });
        statusLabel.setText(running ? "متصل" : "قطع");
        statusLabel.setForeground(running ? new java.awt.Color(0x4DE0A3) : new java.awt.Color(0xFF7081));
        routeActivityLabel.setText(running ? switch (mode) {
            case NATIVE_PER_APP -> "فقط پردازش‌های انتخابی داخل VPN هستند";
            case BROWSER_PROXY -> "مرورگر انتخابی داخل VPN است";
            case SYSTEM_PROXY -> "پراکسی کاربر ویندوز فعال است";
            case TUN_COMPATIBILITY -> "TUN فعال است";
        } : "اتصال غیرفعال است");
        installCoreButton.setEnabled(!running);
        installPerAppButton.setEnabled(!running);
        connectionMode.setEnabled(!running);
    }

    private void handleXrayLog(String line) {
        appendLog(line);
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("perroute-app-proxy") || lower.contains("perroute-selected-profile") || lower.contains("perroute-selected-applications") ||
                lower.contains("selected-applications")) {
            SwingUtilities.invokeLater(() -> {
                routeActivityLabel.setText("ترافیک برنامه از VPN عبور کرد ✓");
                routeActivityLabel.setForeground(new java.awt.Color(0x4DE0A3));
            });
        } else if (lower.contains("perroute-fallback-direct")) {
            SwingUtilities.invokeLater(() -> routeActivityLabel.setText("ترافیک غیرانتخابی مستقیم است"));
        }
    }

    private void refreshEnvironment() {
        CoreInstaller.CoreInfo core = coreInstaller.inspect();
        ProxiFyreManager.Inspection nativeEngine = proxiFyre.inspect();
        boolean admin = AdminCheck.isElevated();
        String privilege = state.connectionMode() != ConnectionMode.TUN_COMPATIBILITY
                ? "بدون نیاز به Admin" : (admin ? "Admin ✓" : "Admin -");
        environmentLabel.setText((core.ready() ? "Core ✓" : "Core —") + "   " +
                (nativeEngine.ready() ? "Per-app ✓" : "Per-app —") + "   " + privilege);
        installCoreButton.setText(core.ready() ? "بروزرسانی هسته" : "نصب هسته");
        installPerAppButton.setText(nativeEngine.ready() ? "بروزرسانی موتور برنامه‌ها" : "نصب موتور برنامه‌ها");
    }

    private void restoreSelection() {
        for (int i = 0; i < nodeModel.getRowCount(); i++) {
            if (nodeModel.get(i).key().equals(state.selectedNodeKey())) {
                nodeTable.setRowSelectionInterval(i, i);
                return;
            }
        }
        if (nodeModel.getRowCount() > 0) nodeTable.setRowSelectionInterval(0, 0);
    }

    private void saveState() {
        try { stateStore.save(state); }
        catch (IOException e) { appendLog("ذخیره تنظیمات ناموفق بود: " + message(e)); }
    }

    private void appendLog(String text) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.append("[" + time + "] " + text + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setBusy(boolean busy) {
        connectButton.setEnabled(!busy);
        installCoreButton.setEnabled(!busy && !xray.isRunning());
        installPerAppButton.setEnabled(!busy && !xray.isRunning());
        connectionMode.setEnabled(!busy && !xray.isRunning());
        setCursor(busy ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR) : java.awt.Cursor.getDefaultCursor());
    }

    private void error(String text) {
        appendLog("خطا: " + text);
        JOptionPane.showMessageDialog(this, text, "خطا", JOptionPane.ERROR_MESSAGE);
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static void applyOrientation(Component component) {
        if (!(component instanceof JTextArea)) component.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) applyOrientation(child);
        }
    }

    private void shutdown() {
        if (activeConnectionMode == ConnectionMode.NATIVE_PER_APP) {
            try { proxiFyre.stopAndRestore(); }
            catch (Exception e) { appendLog("پاک‌سازی موتور per-app ناموفق بود: " + message(e)); }
        }
        activeConnectionMode = null;
        xray.stop();
        saveState();
        executor.shutdownNow();
        dispose();
        System.exit(0);
    }
}
