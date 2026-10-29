package com.mechmotor;

import com.mechmotor.domain.LookupOption;
import com.mechmotor.domain.SupplierDetails;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * JavaFX pages based on the supplied six-screen MechMotor design reference.
 * All displayed records are provided by the application services; sample
 * mockup metrics and user/security capabilities are intentionally not invented.
 */
public final class WorkspacePages {
    private WorkspacePages() {}

    public static VBox create(String page, List<LookupOption> categories,
                              List<SupplierDetails> suppliers,
                              Map<String, Runnable> actions) {
        VBox root = new VBox(13);
        root.getStyleClass().add("workspace-page");
        root.setPadding(new Insets(10, 15, 18, 15));
        String description = switch (page) {
            case "Orders" -> "Track purchase orders, sales orders and fulfillment.";
            case "Suppliers" -> "Manage supplier relationships, contacts and purchasing performance.";
            case "Categories" -> "Organize inventory into clear, manageable product categories.";
            case "Reports" -> "Monitor inventory performance, order activity and business insights.";
            case "Settings" -> "Configure your workspace, licensing and backups.";
            default -> "View and manage your inventory items and stock levels.";
        };
        String title = switch (page) {
            case "Orders" -> "Order Management";
            case "Suppliers" -> "Supplier Management";
            case "Categories" -> "Category Management";
            case "Reports" -> "Reports & Analytics";
            case "Settings" -> "Settings & Preferences";
            default -> "Inventory Management";
        };
        VBox intro = new VBox(5, label(title, "welcome-title"), label(description, "welcome-subtitle"));
        BorderPane banner = new BorderPane();
        banner.setLeft(intro);
        banner.setRight(label(LocalDate.now().format(
                DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy")), "today-pill"));
        banner.setPadding(new Insets(16));
        banner.getStyleClass().add("welcome-banner");
        root.getChildren().add(banner);

        FlowPane metrics = new FlowPane(12, 12);
        metrics.getStyleClass().add("dashboard-metrics");
        if ("Suppliers".equals(page)) {
            metrics.getChildren().addAll(metric("Total Suppliers", String.valueOf(suppliers.size())),
                    metric("Supplier Directory", "Available"),
                    metric("Purchasing", "Enabled"),
                    metric("Supplier Payments", "Records"));
        } else if ("Categories".equals(page)) {
            metrics.getChildren().addAll(metric("Active Categories", String.valueOf(categories.size())),
                    metric("Category Hierarchy", "Available"),
                    metric("Inventory Items", "View"),
                    metric("Category Search", "Available"));
        } else if ("Reports".equals(page)) {
            metrics.getChildren().addAll(metric("Available Reports", "4"),
                    metric("Sales", "CSV"), metric("Stock Movements", "CSV"),
                    metric("Supplier Payables", "CSV"));
        } else if ("Orders".equals(page)) {
            metrics.getChildren().addAll(metric("Purchase Orders", "Manage"),
                    metric("Sales History", "View"), metric("Returns", "Manage"),
                    metric("Supplier Payments", "Manage"));
        } else if ("Settings".equals(page)) {
            metrics.getChildren().addAll(metric("Shop Profile", "Configure"),
                    metric("Activation", "View status"), metric("Database Backup", "Available"),
                    metric("Restore", "Available"));
        }
        root.getChildren().add(metrics);

        VBox surface = new VBox(14);
        surface.setPadding(new Insets(15));
        surface.getStyleClass().add("workspace-surface");
        root.getChildren().add(surface);

        if ("Suppliers".equals(page)) {
            tabRow(surface, List.of("All Suppliers", "Active", "Supplier Details"), actions, "Add Supplier", "addSupplier");
            TableView<SupplierDetails> grid = new TableView<>();
            grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
            TableColumn<SupplierDetails, String> id = column("Supplier ID", x -> "SUP-" + x.id());
            TableColumn<SupplierDetails, String> name = column("Supplier Name", SupplierDetails::name);
            TableColumn<SupplierDetails, String> code = column("Supplier Code", x -> safe(x.supplierCode()));
            TableColumn<SupplierDetails, String> phone = column("Phone", x -> safe(x.phone()));
            grid.getColumns().addAll(id, name, code, phone);
            grid.setItems(FXCollections.observableArrayList(suppliers));
            grid.setPlaceholder(label("No suppliers saved. Use Add Supplier to create one.", "detail-muted"));
            search(surface, grid, SupplierDetails::name, suppliers, "Search suppliers by name or code...");
            surface.getChildren().add(grid);
            rowActions(surface, actions, "Add Supplier", "addSupplier",
                    "Edit Supplier", "editSupplier", "Supplier Payments", "payments");
        } else if ("Categories".equals(page)) {
            tabRow(surface, List.of("All Categories", "Active Categories", "Category Overview"), actions,
                    "Add Category", "addCategory");
            TableView<LookupOption> grid = new TableView<>();
            grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
            grid.getColumns().addAll(column("Category ID", x -> "CAT-" + x.id()),
                    column("Category Name", LookupOption::name),
                    column("Status", x -> "Active"));
            grid.setItems(FXCollections.observableArrayList(categories));
            grid.setPlaceholder(label("No active categories saved.", "detail-muted"));
            search(surface, grid, LookupOption::name, categories, "Search categories by name...");
            surface.getChildren().add(grid);
            rowActions(surface, actions, "Add Category", "addCategory",
                    "Rename Category", "renameCategory", "Deactivate Category", "deactivateCategory");
        } else if ("Orders".equals(page)) {
            tabRow(surface, List.of("All Orders", "Purchase Orders", "Sales Orders"),
                    actions, "New Order", "purchaseOrders");
            Label info = label("Open purchase orders or sales history to view live records. "
                    + "The application does not yet expose a combined order-list service.", "detail-muted");
            info.setWrapText(true);
            surface.getChildren().add(info);
            rowActions(surface, actions, "Purchase Orders", "purchaseOrders",
                    "Sales History", "salesHistory", "Receive Stock", "receiveStock",
                    "Returns / Refunds", "returns");
        } else if ("Reports".equals(page)) {
            tabRow(surface, List.of("Inventory Reports", "Order Reports",
                    "Supplier Reports", "Sales Reports"), actions,
                    "Generate Report", "emailReports");

            Label reportTitle = label("Generate, Download or Email Reports", "details-heading");
            Label reportHelp = label(
                    "Select the report, date range and destination in the report window. "
                    + "Today is selected by default. Gmail connection is required before sending.",
                    "detail-muted");
            reportHelp.setWrapText(true);
            Button openGenerator = action("⬇  Generate & Download", actions.get("emailReports"));
            Button openSender = action("✉  Send Report", actions.get("emailReports"));
            Button bothSender = action("✉  Generate & Send Both", actions.get("emailReports"));
            openGenerator.getStyleClass().add("report-download");
            openSender.getStyleClass().add("report-send");
            bothSender.getStyleClass().add("report-both");
            HBox reportButtons = new HBox(11, openGenerator, openSender, bothSender);
            reportButtons.setAlignment(Pos.CENTER_LEFT);
            surface.getChildren().addAll(reportTitle, reportHelp, reportButtons);

            Label recentTitle = label("Available Report Types", "details-heading");
            TableView<String> grid = new TableView<>();
            grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
            grid.getColumns().add(column("Report Type", x -> x));
            grid.setItems(FXCollections.observableArrayList(
                    "Sales", "Purchases", "Stock Movements", "Supplier Payables"));
            grid.setPrefHeight(230);
            surface.getChildren().addAll(recentTitle, grid);
        } else if ("Settings".equals(page)) {
            buildSettingsTabs(surface, actions);
        }
        VBox quick = new VBox(10);
        quick.getStyleClass().add("workspace-surface");
        quick.setPadding(new Insets(15));
        quick.getChildren().add(label("Quick Actions", "details-heading"));
        if ("Categories".equals(page)) rowActions(quick, actions, "Add Category", "addCategory",
                "Inventory", "inventory");
        else if ("Suppliers".equals(page)) rowActions(quick, actions, "Add Supplier", "addSupplier",
                "Purchase Orders", "purchaseOrders");
        else if ("Reports".equals(page)) rowActions(quick, actions, "Generate / Email Report", "emailReports",
                "Export CSV", "export");
        else if ("Orders".equals(page)) rowActions(quick, actions, "New Purchase Order", "purchaseOrders",
                "View Sales", "salesHistory");
        else rowActions(quick, actions, "Manage Users", "users",
                "Connect Gmail", "gmail", "Shop Settings", "shopProfile",
                "Back Up Now", "backup");
        root.getChildren().add(quick);
        return root;
    }

    /**
     * Real clickable tabs for Settings. The selected tab replaces the content
     * area instead of presenting an inert label or all settings at once.
     * Unimplemented capabilities are explicitly described, never simulated.
     */
    static void buildSettingsTabs(VBox surface, Map<String, Runnable> actions) {
        List<String> names = List.of("General", "Email & Reports",
                "Users & Roles", "Notifications", "Security");
        HBox tabs = new HBox(10);
        tabs.setAlignment(Pos.CENTER_LEFT);
        tabs.getStyleClass().add("settings-tab-bar");

        VBox content = new VBox(14);
        content.getStyleClass().add("settings-tab-content");
        VBox.setVgrow(content, Priority.ALWAYS);
        List<Button> buttons = new ArrayList<>();

        for (String name : names) {
            Button tab = new Button(name);
            tab.setFocusTraversable(true);
            tab.setAccessibleText("Settings: " + name);
            tab.getStyleClass().add("settings-tab");
            buttons.add(tab);
            tabs.getChildren().add(tab);
            tab.setOnAction(event -> {
                for (Button other : buttons) {
                    other.getStyleClass().remove("settings-tab-selected");
                    other.setAccessibleHelp("Click to display " + other.getText() + " settings.");
                }
                tab.getStyleClass().add("settings-tab-selected");
                tab.setAccessibleHelp("Currently selected settings section.");
                showSettingsSection(content, name, actions);
            });
        }
        surface.getChildren().addAll(tabs, content);
        buttons.get(0).fire(); // Show General at first open.
    }

    private static void showSettingsSection(VBox target, String section,
                                           Map<String, Runnable> actions) {
        target.getChildren().clear();
        Label heading = label(section, "details-heading");
        target.getChildren().add(heading);
        GridPane cards = new GridPane();
        cards.setHgap(14);
        cards.setVgap(14);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(50);
        left.setHgrow(Priority.ALWAYS);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(50);
        right.setHgrow(Priority.ALWAYS);
        cards.getColumnConstraints().addAll(left, right);

        switch (section) {
            case "General" -> {
                cards.add(settingsCard("Company & Shop Profile",
                        "Configure your business information and tax details.",
                        actions, "Edit Shop Profile", "shopProfile"), 0, 0);
                cards.add(settingsCard("License",
                        "View your activation status and expiry date. Your key stays hidden.",
                        actions, "View License Expiry", "activation"), 1, 0);
                cards.add(settingsCard("Local Data",
                        "Inventory records are stored in the local SQLite database.",
                        actions, "Backup Database", "backup"), 0, 1);
                cards.add(settingsCard("Restore",
                        "Restore a validated backup using the existing recovery workflow.",
                        actions, "Restore Backup", "restore"), 1, 1);
            }
            case "Email & Reports" -> {
                cards.add(settingsCard("Google Account (Gmail)",
                        "Connect or disconnect your Google account for report delivery.",
                        actions, "Manage Gmail Connection", "gmail"), 0, 0);
                cards.add(settingsCard("Default Report Recipient",
                        "Save a default email address for sending reports.",
                        actions, "Set Default Email", "gmail"), 1, 0);
                cards.add(settingsCard("Generate & Send Reports",
                        "Choose today's report or a custom range, then download or send it.",
                        actions, "Open Report Generator", "emailReports"), 0, 1);
                cards.add(settingsCard("Report Export",
                        "Export inventory data to a local CSV file.",
                        actions, "Export Inventory CSV", "export"), 1, 1);
            }
            case "Users & Roles" -> {
                cards.add(settingsCard("User Management",
                        "Add, edit, deactivate or permanently delete local directory users.",
                        actions, "Manage Users", "users"), 0, 0);
                cards.add(settingsCard("Directory Roles",
                        "Admin, Manager and Staff are directory labels. Login permissions are not yet enforced.",
                        actions, "View Users & Roles", "users"), 1, 0);
            }
            case "Notifications" -> {
                cards.add(settingsCard("Low Stock Alerts",
                        "View parts that need replenishment. Background alerts are not yet available.",
                        actions, "View Low Stock", "lowStock"), 0, 0);
                cards.add(settingsCard("Email Notifications",
                        "On-demand report emailing is supported. Automatic email notifications are not yet implemented.",
                        actions, "Gmail & Report Preferences", "gmail"), 1, 0);
                Label notice = label("Automatic alerts, notification preferences and delivery schedules "
                        + "are planned features, not active notification services.", "detail-muted");
                notice.setWrapText(true);
                target.getChildren().add(notice);
            }
            case "Security" -> {
                cards.add(settingsCard("Activation & License",
                        "Review expiry information. The activation key is never displayed.",
                        actions, "View License Status", "activation"), 0, 0);
                cards.add(settingsCard("Backup & Recovery",
                        "Protect your shop records with independent database snapshots.",
                        actions, "Backup Database", "backup"), 1, 0);
                cards.add(settingsCard("Restore Database",
                        "Validate a backup before replacing the local database.",
                        actions, "Restore Backup", "restore"), 0, 1);
                cards.add(settingsCard("Account Security",
                        "Multi-user sign-in, two-factor authentication and session timeout are not implemented.",
                        actions), 1, 1);
            }
            default -> throw new IllegalArgumentException("Unsupported Settings tab: " + section);
        }
        target.getChildren().add(cards);
    }

    private static VBox settingsCard(String heading, String description,
                                     Map<String, Runnable> actions, String... commands) {
        Label title = label(heading, "details-heading");
        Label help = label(description, "detail-muted");
        help.setWrapText(true);
        VBox card = new VBox(12, title, help);
        card.getStyleClass().add("settings-feature-card");
        for (int i = 0; i + 1 < commands.length; i += 2) {
            card.getChildren().add(action(commands[i], actions.get(commands[i + 1])));
        }
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static <T> void search(VBox parent, TableView<T> grid,
                                   java.util.function.Function<T, String> display,
                                   List<T> source, String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.textProperty().addListener((obs, oldValue, value) -> grid.setItems(
                FXCollections.observableArrayList(source.stream().filter(x ->
                        display.apply(x).toLowerCase(Locale.ROOT).contains(
                            value.trim().toLowerCase(Locale.ROOT))).toList())));
        parent.getChildren().add(field);
    }

    private static <T> TableColumn<T, String> column(String name,
                                                      java.util.function.Function<T, String> display) {
        TableColumn<T, String> column = new TableColumn<>(name);
        column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                safe(display.apply(data.getValue()))));
        return column;
    }

    private static void tabRow(VBox target, List<String> tabs, Map<String, Runnable> actions,
                               String actionName, String actionId) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < tabs.size(); i++) {
            Label tab = label(tabs.get(i), i == 0 ? "page-tab-selected" : "page-tab");
            row.getChildren().add(tab);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);
        row.getChildren().add(action(actionName, actions.get(actionId)));
        target.getChildren().add(row);
    }

    private static void rowActions(VBox parent, Map<String, Runnable> actions, String... pairs) {
        FlowPane row = new FlowPane(9, 9);
        for (int i = 0; i < pairs.length; i += 2)
            row.getChildren().add(action(pairs[i], actions.get(pairs[i + 1])));
        parent.getChildren().add(row);
    }

    private static Button action(String label, Runnable action) {
        Button button = new Button(label);
        button.getStyleClass().add("primary-action");
        button.setDisable(action == null);
        if (action != null) button.setOnAction(event -> action.run());
        return button;
    }

    private static VBox metric(String title, String value) {
        VBox box = new VBox(7, label(title, "metric-label"), label(value, "metric-value"));
        box.getStyleClass().add("metric-card");
        return box;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
