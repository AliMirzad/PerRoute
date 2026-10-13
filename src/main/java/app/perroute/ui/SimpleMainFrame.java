package app.perroute.ui;

import app.perroute.model.AppState;
import app.perroute.model.ConnectionMode;
import app.perroute.model.ProxyNode;
import app.perroute.model.RoutingMode;
import app.perroute.parser.ParseResult;
import app.perroute.persistence.AppStateStore;
import app.perroute.service.AdminCheck;
import app.perroute.service.AppProxyLauncher;
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
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
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
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("serial")
public final class SimpleMainFrame extends JFrame {
    private final AppState state;
    private final AppStateStore stateStore;
    private final NodeTableModel nodeModel;
    private final JTable nodeTable;
    private final JComboBox<ConnectionMode> connectionMode = new JComboBox<>(ConnectionMode.values());
    private final JComboBox<RoutingMode> routingMode = new JComboBox<>(RoutingMode.values());
    private final JTextField subscriptionUrl = new JTextField();
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("قطع");
    private final JLabel environmentLabel = new JLabel();
    private final JLabel routeActivityLabel = new JLabel("آماده برای اتصال");
    private final JLabel modeHelpLabel = new JLabel();
    private final JLabel selectedServerLabel = new JLabel("هنوز سروری انتخاب نشده");
    private final JLabel appSummaryLabel = new JLabel();
    private final JPanel appChips = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 5));
    private final JButton connectButton = Theme.primaryButton("اتصال امن");
    private final JButton chooseAppsButton = Theme.primaryButton("+ انتخاب برنامه");
    private final JButton serversButton = Theme.secondaryButton("انتخاب یا افزودن سرور");
    private final JButton advancedButton = Theme.secondaryButton("تنظیمات");
    private final JButton installCoreButton = Theme.secondaryButton("نصب هسته");
    private final JButton installPerAppButton = Theme.secondaryButton("نصب موتور برنامه‌ها");

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

    public SimpleMainFrame(AppState state, AppStateStore stateStore) {
        super("PerRoute — اتصال انتخابی");
        this.state = state;
        this.stateStore = stateStore;
        this.nodeModel = new NodeTableModel(state.nodes());
        this.nodeTable = new JTable(nodeModel);
        connectionMode.setSelectedItem(state.connectionMode());
        routingMode.setSelectedItem(state.routingMode());
        routingMode.setEnabled(state.connectionMode() == ConnectionMode.TUN_COMPATIBILITY);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(880, 620));
        setSize(1020, 720);
        setLocationRelativeTo(null);
        setContentPane(buildDashboard());
        configureTable();
        bindActions();
        restoreSelection();
        refreshSelectedServer();
        refreshAppChips();
        refreshEnvironment();
        updateModeHelp();
        updateConnectionState(false);
        applyOrientation(this);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });
    }

    private JPanel buildDashboard() {
        GlassBackgroundPanel root = new GlassBackgroundPanel();
        root.setLayout(new BorderLayout(0, 18));
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 22, 28));
        root.add(header(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 16));
        center.setOpaque(false);
        center.add(connectionCard(), BorderLayout.NORTH);
        JPanel cards = new JPanel(new GridLayout(1, 2, 16, 0));
        cards.setOpaque(false);
        cards.add(applicationsCard());
        cards.add(serverCard());
        center.add(cards, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel brand = new JLabel("PerRoute");
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 29f));
        brand.setForeground(Theme.TEXT);
        brand.setAlignmentX(Component.RIGHT_ALIGNMENT);
        JLabel tagline = new JLabel("VPN فقط برای برنامه‌هایی که خودت انتخاب می‌کنی");
        tagline.setForeground(Theme.MUTED);
        tagline.setFont(tagline.getFont().deriveFont(13f));
        tagline.setAlignmentX(Component.RIGHT_ALIGNMENT);
        copy.add(brand);
        copy.add(Box.createVerticalStrut(3));
        copy.add(tagline);
        panel.add(copy, BorderLayout.EAST);
        panel.add(advancedButton, BorderLayout.WEST);
        return panel;
    }

    private JPanel connectionCard() {
        GlassPanel card = new GlassPanel(30, new Color(255, 255, 255, 190));
        card.setLayout(new BorderLayout(20, 0));
        card.setBorder(BorderFactory.createEmptyBorder(25, 28, 25, 28));
        JPanel status = new JPanel();
        status.setOpaque(false);
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        JLabel caption = new JLabel("وضعیت اتصال");
        caption.setForeground(Theme.MUTED);
        caption.setFont(caption.getFont().deriveFont(12f));
        caption.setAlignmentX(Component.RIGHT_ALIGNMENT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 28f));
        statusLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        routeActivityLabel.setForeground(Theme.MUTED);
        routeActivityLabel.setFont(routeActivityLabel.getFont().deriveFont(12f));
        routeActivityLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        status.add(caption);
        status.add(Box.createVerticalStrut(4));
        status.add(statusLabel);
        status.add(Box.createVerticalStrut(4));
        status.add(routeActivityLabel);
        connectButton.setPreferredSize(new Dimension(230, 62));
        connectButton.setFont(connectButton.getFont().deriveFont(Font.BOLD, 17f));
        card.add(status, BorderLayout.EAST);
        card.add(connectButton, BorderLayout.WEST);
        return card;
    }

    private JPanel applicationsCard() {
        GlassPanel card = new GlassPanel();
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(20, 20, 18, 20));
        JPanel title = new JPanel(new BorderLayout());
        title.setOpaque(false);
        title.add(cardTitle("برنامه‌های تحت VPN", "۱"), BorderLayout.EAST);
        appSummaryLabel.setForeground(Theme.MUTED);
        title.add(appSummaryLabel, BorderLayout.WEST);
        appChips.setOpaque(false);
        JScrollPane scroll = new JScrollPane(appChips);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        card.add(title, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        card.add(chooseAppsButton, BorderLayout.SOUTH);
        return card;
    }

    private JPanel serverCard() {
        GlassPanel card = new GlassPanel();
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(20, 20, 18, 20));
        card.add(cardTitle("سرور فعال", "۲"), BorderLayout.NORTH);
        JPanel server = new JPanel();
        server.setOpaque(false);
        server.setLayout(new BoxLayout(server, BoxLayout.Y_AXIS));
        selectedServerLabel.setFont(selectedServerLabel.getFont().deriveFont(Font.BOLD, 16f));
        selectedServerLabel.setForeground(Theme.TEXT);
        selectedServerLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        JLabel hint = new JLabel("برای تغییر سرور یا افزودن اشتراک کلیک کن");
        hint.setForeground(Theme.MUTED);
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setAlignmentX(Component.RIGHT_ALIGNMENT);
        server.add(Box.createVerticalGlue());
        server.add(selectedServerLabel);
        server.add(Box.createVerticalStrut(7));
        server.add(hint);
        server.add(Box.createVerticalGlue());
        card.add(server, BorderLayout.CENTER);
        card.add(serversButton, BorderLayout.SOUTH);
        return card;
    }

    private JLabel cardTitle(String text, String step) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        label.setForeground(Theme.TEXT);
        return label;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        environmentLabel.setForeground(Theme.MUTED);
        environmentLabel.setFont(environmentLabel.getFont().deriveFont(11f));
        JLabel privacy = new JLabel("فقط برنامه‌های انتخاب‌شده از VPN عبور می‌کنند");
        privacy.setForeground(Theme.MUTED);
        privacy.setFont(privacy.getFont().deriveFont(11f));
        footer.add(privacy, BorderLayout.EAST);
        footer.add(environmentLabel, BorderLayout.WEST);
        return footer;
    }

    private void configureTable() {
        nodeTable.setRowHeight(40);
        nodeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nodeTable.setFillsViewportHeight(true);
        nodeTable.setShowVerticalLines(false);
        nodeTable.setShowHorizontalLines(false);
        nodeTable.getTableHeader().setPreferredSize(new Dimension(0, 40));
        nodeTable.putClientProperty("FlatLaf.style", "rowHeight: 40; selectionArc: 12; showHorizontalLines: false; showVerticalLines: false");
        nodeTable.getColumnModel().getColumn(0).setPreferredWidth(360);
        nodeTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        nodeTable.getColumnModel().getColumn(2).setPreferredWidth(210);
    }

    private void bindActions() {
        chooseAppsButton.addActionListener(e -> selectRunningApps());
        serversButton.addActionListener(e -> showServersDialog());
        advancedButton.addActionListener(e -> showAdvancedDialog());
        connectButton.addActionListener(e -> toggleConnection());
        installCoreButton.addActionListener(e -> installCore());
        installPerAppButton.addActionListener(e -> installPerAppEngine());
        connectionMode.addActionListener(e -> {
            ConnectionMode selected = (ConnectionMode) connectionMode.getSelectedItem();
            state.setConnectionMode(selected);
            routingMode.setEnabled(selected == ConnectionMode.TUN_COMPATIBILITY);
            updateModeHelp();
            updateConnectionState(xray.isRunning() && connectionReady);
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
                refreshSelectedServer();
            }
        });
        xray.addLogListener(this::handleXrayLog);
        xray.addStateListener(running -> {
            SwingUtilities.invokeLater(() -> updateConnectionState(running && connectionReady));
            if (!running && activeConnectionMode == ConnectionMode.NATIVE_PER_APP) {
                executor.submit(() -> {
                    try { proxiFyre.stopAndRestore(); }
                    catch (Exception e) { appendLog("بازیابی موتور برنامه‌ها ناموفق بود: " + message(e)); }
                    finally { activeConnectionMode = null; }
                });
            }
        });
    }

    private void showServersDialog() {
        JDialog dialog = new JDialog(this, "سرورها", true);
        dialog.setSize(820, 590);
        dialog.setLocationRelativeTo(this);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel subscription = new JPanel(new BorderLayout(8, 0));
        subscriptionUrl.putClientProperty("JTextField.placeholderText", "لینک Subscription را اینجا بچسبان");
        JButton fetch = Theme.primaryButton("دریافت");
        fetch.addActionListener(e -> fetchSubscription());
        JButton paste = Theme.secondaryButton("لینک دستی");
        paste.addActionListener(e -> pasteLinks());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.add(paste);
        actions.add(fetch);
        subscription.add(subscriptionUrl, BorderLayout.CENTER);
        subscription.add(actions, BorderLayout.WEST);

        TableRowSorter<NodeTableModel> sorter = new TableRowSorter<>(nodeModel);
        nodeTable.setRowSorter(sorter);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "جستجوی سرور...");
        search.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            String value = search.getText().strip();
            sorter.setRowFilter(value.isBlank() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(value)));
        }));
        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.add(subscription, BorderLayout.NORTH);
        top.add(search, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        root.add(new JScrollPane(nodeTable), BorderLayout.CENTER);

        JButton remove = Theme.secondaryButton("حذف سرور");
        remove.addActionListener(e -> removeNode());
        JButton done = Theme.primaryButton("انتخاب شد");
        done.addActionListener(e -> dialog.dispose());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(remove, BorderLayout.WEST);
        bottom.add(done, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        applyOrientation(dialog);
        dialog.setVisible(true);
        nodeTable.setRowSorter(null);
        refreshSelectedServer();
    }

    private void showAdvancedDialog() {
        JDialog dialog = new JDialog(this, "تنظیمات و گزارش", true);
        dialog.setSize(720, 560);
        dialog.setLocationRelativeTo(this);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JPanel settings = new JPanel(new GridLayout(3, 2, 10, 10));
        settings.add(new JLabel("روش اتصال"));
        settings.add(connectionMode);
        settings.add(new JLabel("سیاست TUN"));
        settings.add(routingMode);
        settings.add(installCoreButton);
        settings.add(installPerAppButton);
        modeHelpLabel.setForeground(Theme.MUTED);
        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.add(settings, BorderLayout.CENTER);
        north.add(modeHelpLabel, BorderLayout.SOUTH);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setLineWrap(true);
        logArea.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        root.add(north, BorderLayout.NORTH);
        root.add(new JScrollPane(logArea), BorderLayout.CENTER);
        JButton close = Theme.primaryButton("بستن");
        close.addActionListener(e -> dialog.dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(close);
        root.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        applyOrientation(dialog);
        dialog.setVisible(true);
    }

    private void selectRunningApps() {
        setBusy(true);
        executor.submit(() -> {
            List<ProcessDiscovery.RunningApplication> applications = new ProcessDiscovery().list().stream()
                    .filter(SimpleMainFrame::isUserFacingApplication)
                    .toList();
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                showApplicationPicker(applications);
            });
        });
    }

    private void showApplicationPicker(List<ProcessDiscovery.RunningApplication> applications) {
        JDialog dialog = new JDialog(this, "انتخاب برنامه‌ها", true);
        dialog.setSize(680, 570);
        dialog.setLocationRelativeTo(this);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("کدام برنامه‌ها از VPN استفاده کنند؟");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        JLabel subtitle = new JLabel("فقط برنامه‌های باز نمایش داده می‌شوند");
        subtitle.setForeground(Theme.MUTED);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "نام برنامه را جستجو کن...");
        JPanel top = new JPanel(new BorderLayout(0, 9));
        JPanel headings = new JPanel();
        headings.setOpaque(false);
        headings.setLayout(new BoxLayout(headings, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.RIGHT_ALIGNMENT);
        subtitle.setAlignmentX(Component.RIGHT_ALIGNMENT);
        headings.add(title);
        headings.add(Box.createVerticalStrut(3));
        headings.add(subtitle);
        top.add(headings, BorderLayout.NORTH);
        top.add(search, BorderLayout.SOUTH);

        DefaultListModel<ProcessDiscovery.RunningApplication> model = new DefaultListModel<>();
        applications.forEach(model::addElement);
        JList<ProcessDiscovery.RunningApplication> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setFixedCellHeight(54);
        list.setCellRenderer(new ApplicationRenderer());
        selectSavedApplications(list, applications);
        search.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            model.clear();
            applications.stream()
                    .filter(app -> query.isBlank() || app.name().toLowerCase(Locale.ROOT).contains(query) ||
                            app.path().toLowerCase(Locale.ROOT).contains(query))
                    .forEach(model::addElement);
        }));

        JButton browse = Theme.secondaryButton("انتخاب فایل EXE");
        browse.addActionListener(e -> {
            dialog.dispose();
            chooseExecutable();
        });
        JButton apply = Theme.primaryButton("ذخیره انتخاب‌ها");
        apply.addActionListener(e -> {
            List<String> selected = list.getSelectedValuesList().stream().map(ProcessDiscovery.RunningApplication::path).toList();
            state.appPaths().clear();
            state.appPaths().addAll(selected);
            saveState();
            refreshAppChips();
            dialog.dispose();
        });
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(browse, BorderLayout.WEST);
        buttons.add(apply, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        applyOrientation(dialog);
        dialog.setVisible(true);
    }

    private void selectSavedApplications(JList<ProcessDiscovery.RunningApplication> list,
                                         List<ProcessDiscovery.RunningApplication> applications) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < applications.size(); i++) {
            String path = applications.get(i).path();
            if (state.appPaths().stream().anyMatch(path::equalsIgnoreCase)) indexes.add(i);
        }
        list.setSelectedIndices(indexes.stream().mapToInt(Integer::intValue).toArray());
    }

    private void refreshAppChips() {
        appChips.removeAll();
        if (state.appPaths().isEmpty()) {
            JLabel empty = new JLabel("هنوز برنامه‌ای انتخاب نشده");
            empty.setForeground(Theme.MUTED);
            appChips.add(empty);
        } else {
            for (String raw : List.copyOf(state.appPaths())) {
                Path path = Path.of(raw);
                JButton chip = new JButton(path.getFileName() + "   ×");
                chip.setToolTipText(raw + " — برای حذف کلیک کن");
                chip.setForeground(Theme.PRIMARY_DARK);
                chip.setBackground(new Color(232, 235, 255));
                chip.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
                chip.putClientProperty("FlatLaf.style", "arc: 999; borderWidth: 0; focusWidth: 0");
                chip.addActionListener(e -> {
                    state.appPaths().removeIf(raw::equalsIgnoreCase);
                    saveState();
                    refreshAppChips();
                });
                appChips.add(chip);
            }
        }
        appSummaryLabel.setText(state.appPaths().isEmpty() ? "خالی" : state.appPaths().size() + " برنامه");
        appChips.revalidate();
        appChips.repaint();
    }

    private void refreshSelectedServer() {
        ProxyNode selected = selectedNode();
        selectedServerLabel.setText(selected == null ? "هنوز سروری انتخاب نشده" : friendlyName(selected.name()));
        selectedServerLabel.setToolTipText(selected == null ? null : selected.endpoint());
    }

    private ProxyNode selectedNode() {
        return nodeModel.values().stream().filter(node -> node.key().equals(state.selectedNodeKey())).findFirst()
                .orElseGet(() -> nodeModel.getRowCount() == 0 ? null : nodeModel.get(0));
    }

    private static boolean isUserFacingApplication(ProcessDiscovery.RunningApplication app) {
        String name = app.name().toLowerCase(Locale.ROOT);
        String path = app.path().toLowerCase(Locale.ROOT);
        String windows = System.getenv("SystemRoot");
        if (windows != null && path.startsWith(windows.toLowerCase(Locale.ROOT) + "\\")) return false;
        if (path.contains("\\common files\\")) return false;
        if (name.equals("perroute.exe") || name.equals("xray.exe") || name.equals("proxifyre.exe") ||
                name.equals("java.exe") || name.equals("javaw.exe")) return false;
        return !(name.contains("crashpad") || name.contains("updater") || name.contains("update.exe") ||
                name.endsWith("service.exe") || name.endsWith("helper.exe") || name.endsWith("notify.exe"));
    }

    private static String friendlyName(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(codePoint -> codePoint <= Character.MAX_VALUE && codePoint != 0xFE0F)
                .forEach(result::appendCodePoint);
        return result.toString().replace("□", "").replace("→", "").replace("←", "")
                .replaceAll("\\s+", " ").strip();
    }

    private void fetchSubscription() {
        String url = subscriptionUrl.getText().strip();
        if (url.isBlank()) { error("لینک اشتراک را وارد کنید."); return; }
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
        appendLog(result.nodes().size() + " سرور دریافت شد.");
        result.warnings().forEach(warning -> appendLog("هشدار: " + warning));
        if (result.nodes().isEmpty()) error("هیچ سرور قابل استفاده‌ای پیدا نشد.");
        refreshSelectedServer();
    }

    private void removeNode() {
        int row = nodeTable.getSelectedRow();
        if (row < 0) return;
        nodeModel.remove(nodeTable.convertRowIndexToModel(row));
        state.setSelectedNodeKey("");
        if (nodeModel.getRowCount() > 0) nodeTable.setRowSelectionInterval(0, 0);
        saveState();
        refreshSelectedServer();
    }

    private void chooseExecutable() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("انتخاب فایل برنامه");
        chooser.setFileFilter(new FileNameExtensionFilter("Windows executable (*.exe)", "exe"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString();
            if (state.appPaths().stream().noneMatch(path::equalsIgnoreCase)) state.appPaths().add(path);
            saveState();
            refreshAppChips();
        }
    }

    private void installCore() {
        setBusy(true);
        executor.submit(() -> {
            try {
                coreInstaller.install(this::appendLog);
                SwingUtilities.invokeLater(() -> {
                    refreshEnvironment();
                    JOptionPane.showMessageDialog(this, "هسته Xray و Wintun آماده شد.", "انجام شد", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> error(message(e))); }
            finally { SwingUtilities.invokeLater(() -> setBusy(false)); }
        });
    }

    private void installPerAppEngine() {
        int answer = JOptionPane.showConfirmDialog(this,
                "موتور per-app و درایور Windows Packet Filter نصب شود؟ این نصب فقط یک‌بار دسترسی Administrator می‌خواهد.",
                "نصب موتور برنامه‌ها", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        setBusy(true);
        executor.submit(() -> {
            try {
                proxiFyre.downloadAndInstall(this::appendLog);
                SwingUtilities.invokeLater(() -> {
                    refreshEnvironment();
                    JOptionPane.showMessageDialog(this, "موتور برنامه‌ها آماده شد.", "انجام شد", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> error(message(e))); }
            finally { SwingUtilities.invokeLater(() -> setBusy(false)); }
        });
    }

    private void toggleConnection() {
        if (xray.isRunning()) { disconnect(); return; }
        ConnectionMode mode = state.connectionMode();
        if (mode == ConnectionMode.TUN_COMPATIBILITY && !AdminCheck.isElevated()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "حالت TUN به دسترسی Administrator نیاز دارد. برنامه با دسترسی مدیر دوباره اجرا شود؟",
                    "دسترسی Administrator", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.YES_OPTION && AdminCheck.relaunchPackagedAppElevated()) shutdown();
            else if (answer == JOptionPane.YES_OPTION) error("PerRoute.exe را با Run as administrator اجرا کنید.");
            return;
        }
        if (mode == ConnectionMode.NATIVE_PER_APP && !proxiFyre.inspect().ready()) {
            error("موتور برنامه‌ها نصب نشده است. از تنظیمات، «نصب موتور برنامه‌ها» را بزنید.");
            return;
        }
        ProxyNode node = selectedNode();
        if (node == null) { error("ابتدا یک سرور انتخاب کنید."); return; }
        if (state.appPaths().isEmpty()) { error("ابتدا برنامه‌هایی را که باید از VPN عبور کنند انتخاب کنید."); return; }

        if (mode == ConnectionMode.NATIVE_PER_APP) {
            List<String> running = appProxyLauncher.runningApplications(List.copyOf(state.appPaths()));
            if (!running.isEmpty()) {
                error("برای جلوگیری از اتصال مستقیم قدیمی، ابتدا این برنامه‌ها را کامل ببندید: " + String.join("، ", running) +
                        System.lineSeparator() + "بعد دوباره اتصال را بزنید؛ PerRoute خودش آن‌ها را اجرا می‌کند.");
                return;
            }
        }
        if (mode == ConnectionMode.BROWSER_PROXY) {
            List<String> problems = appProxyLauncher.validate(List.copyOf(state.appPaths()));
            if (!problems.isEmpty()) { error(String.join(System.lineSeparator(), problems)); return; }
            List<String> running = appProxyLauncher.runningApplications(List.copyOf(state.appPaths()));
            if (!running.isEmpty()) {
                error("این برنامه‌ها را کامل ببندید: " + String.join("، ", running));
                return;
            }
        }

        setBusy(true);
        routeActivityLabel.setText("در حال بررسی سرور...");
        executor.submit(() -> {
            try {
                boolean tun = mode == ConnectionMode.TUN_COMPATIBILITY;
                String config = tun
                        ? configGenerator.generate(node, List.copyOf(state.appPaths()), state.routingMode())
                        : configGenerator.generateApplicationProxy(node);
                xray.start(config, tun);
                if (!tun) {
                    Thread.sleep(350);
                    if (!xray.isRunning()) throw new IOException("هسته Xray متوقف شد؛ پورت محلی درگیر است.");
                    ProxyHealthCheck.Result health = proxyHealthCheck.check();
                    if (!health.success()) throw new IOException("سرور انتخابی تست اینترنت را پاس نکرد: " + health.detail());
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
                appendLog("اتصال به «" + node.name() + "» برقرار شد.");
                SwingUtilities.invokeLater(() -> updateConnectionState(true));
            } catch (Exception e) {
                if (mode == ConnectionMode.NATIVE_PER_APP) {
                    try { proxiFyre.stopAndRestore(); } catch (Exception ignored) {}
                }
                xray.stop();
                activeConnectionMode = null;
                connectionReady = false;
                SwingUtilities.invokeLater(() -> error(message(e)));
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        });
    }

    private void disconnect() {
        ConnectionMode mode = activeConnectionMode;
        setBusy(true);
        executor.submit(() -> {
            try {
                if (mode == ConnectionMode.NATIVE_PER_APP) proxiFyre.stopAndRestore();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> error("قطع موتور برنامه‌ها ناموفق بود: " + message(e)));
            } finally {
                activeConnectionMode = null;
                connectionReady = false;
                xray.stop();
                SwingUtilities.invokeLater(() -> {
                    updateConnectionState(false);
                    setBusy(false);
                });
            }
        });
    }

    private void updateConnectionState(boolean running) {
        connectButton.setText(running ? "قطع اتصال" : "اتصال امن");
        statusLabel.setText(running ? "متصل" : "قطع");
        statusLabel.setForeground(running ? new Color(0x159A68) : new Color(0xD34A62));
        routeActivityLabel.setText(running ? "برنامه‌های انتخابی از VPN عبور می‌کنند" : "آماده برای اتصال");
        connectButton.setBackground(running ? new Color(0xD34A62) : Theme.PRIMARY);
        installCoreButton.setEnabled(!running);
        installPerAppButton.setEnabled(!running);
        connectionMode.setEnabled(!running);
        chooseAppsButton.setEnabled(!running);
        serversButton.setEnabled(!running);
    }

    private void handleXrayLog(String line) {
        appendLog(line);
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("perroute-app-proxy") || lower.contains("selected-applications")) {
            SwingUtilities.invokeLater(() -> routeActivityLabel.setText("ترافیک برنامه از VPN عبور کرد"));
        }
    }

    private void updateModeHelp() {
        ConnectionMode mode = (ConnectionMode) connectionMode.getSelectedItem();
        modeHelpLabel.setText(switch (mode == null ? ConnectionMode.NATIVE_PER_APP : mode) {
            case NATIVE_PER_APP -> "پیشنهادی: فقط برنامه‌های انتخاب‌شده از VPN عبور می‌کنند.";
            case BROWSER_PROXY -> "فقط برای مرورگرهای Chromium؛ بدون موتور packet filter.";
            case SYSTEM_PROXY -> "پراکسی کاربر ویندوز؛ بدون Administrator.";
            case TUN_COMPATIBILITY -> "حالت سازگاری برای برنامه‌های خاص؛ نیازمند Administrator.";
        });
    }

    private void refreshEnvironment() {
        CoreInstaller.CoreInfo core = coreInstaller.inspect();
        ProxiFyreManager.Inspection engine = proxiFyre.inspect();
        environmentLabel.setText((core.ready() ? "هسته آماده" : "هسته نصب نیست") + "   •   " +
                (engine.ready() ? "موتور برنامه‌ها آماده" : "موتور نصب نیست") + "   •   نسخه 0.6");
        installCoreButton.setText(core.ready() ? "به‌روزرسانی هسته" : "نصب هسته");
        installPerAppButton.setText(engine.ready() ? "به‌روزرسانی موتور" : "نصب موتور برنامه‌ها");
    }

    private void restoreSelection() {
        for (int i = 0; i < nodeModel.getRowCount(); i++) {
            if (nodeModel.get(i).key().equals(state.selectedNodeKey())) {
                nodeTable.setRowSelectionInterval(i, i);
                return;
            }
        }
        if (nodeModel.getRowCount() > 0) {
            nodeTable.setRowSelectionInterval(0, 0);
            state.setSelectedNodeKey(nodeModel.get(0).key());
        }
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
        advancedButton.setEnabled(!busy);
        chooseAppsButton.setEnabled(!busy && !xray.isRunning());
        serversButton.setEnabled(!busy && !xray.isRunning());
        setCursor(busy ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR) :
                java.awt.Cursor.getDefaultCursor());
    }

    private void error(String text) {
        appendLog("خطا: " + text);
        JOptionPane.showMessageDialog(this, text, "خطا", JOptionPane.ERROR_MESSAGE);
        updateConnectionState(false);
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
            try { proxiFyre.stopAndRestore(); } catch (Exception ignored) {}
        }
        activeConnectionMode = null;
        xray.stop();
        saveState();
        executor.shutdownNow();
        dispose();
        System.exit(0);
    }

    private static final class SimpleDocumentListener implements DocumentListener {
        private final Runnable action;
        private SimpleDocumentListener(Runnable action) { this.action = action; }
        @Override public void insertUpdate(DocumentEvent e) { action.run(); }
        @Override public void removeUpdate(DocumentEvent e) { action.run(); }
        @Override public void changedUpdate(DocumentEvent e) { action.run(); }
    }

    private static final class ApplicationRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            ProcessDiscovery.RunningApplication app = (ProcessDiscovery.RunningApplication) value;
            label.setText("<html><b>" + escape(app.name()) + "</b><br><span style='color:#778198;font-size:9px'>" +
                    escape(compactPath(app.path())) + "</span></html>");
            try { label.setIcon(FileSystemView.getFileSystemView().getSystemIcon(new File(app.path()))); }
            catch (RuntimeException ignored) {}
            label.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
            return label;
        }

        private static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private static String compactPath(String value) {
            if (value.length() <= 72) return value;
            return value.substring(0, 16) + "…" + value.substring(value.length() - 52);
        }
    }
}
