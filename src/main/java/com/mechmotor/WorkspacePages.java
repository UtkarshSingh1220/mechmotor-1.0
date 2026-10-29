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
            // Real keyboard-accessible toggles, not decorative labels. Selecting a
            // tab swaps its own content without recreating the Settings page.
            List<String> settingsTabs = List.of(
                    "General", "Email & Reports", "Users & Roles", "Notifications", "Security");
            ToggleGroup tabGroup = new ToggleGroup();
            HBox navigation = new HBox(8);
            navigation.getStyleClass().add("settings-tab-bar");
            VBox tabContent = new VBox(14);
            tabContent.getStyleClass().add("settings-tab-content");
            tabContent.setFillWidth(true);

            Map<String, VBox> panes = new LinkedHashMap<>();

            VBox profile = settingsCard("Company & Shop Profile",
                    "Configure your registered shop information, address and tax details.",
                    actions, "Edit Shop Profile", "shopProfile");
            VBox license = settingsCard("License & Activation",
                    "View your activation status and expiry date. The stored key is never shown.",
                    actions, "View License Expiry", "activation");
            panes.put("General", settingsGrid(profile, license));

            VBox google = settingsCard("Google Account (Gmail)",
                    "Authorize Gmail sending through Google OAuth. An internet connection is required.",
                    actions, "Connect / Disconnect Google", "gmail");
            VBox recipients = settingsCard("Default Report Recipient",
                    "Set the default email in Gmail settings. A typed recipient overrides it; the Both option sends to both.",
                    actions, "Configure Default Email", "gmail");
            VBox delivery = settingsCard("Generate & Send Reports",
                    "Select Today or a custom range, then generate/download, send, or send to both recipients.",
                    actions, "Open Report Generator", "emailReports");
            panes.put("Email & Reports", settingsGrid(google, recipients, delivery));

            VBox directory = settingsCard("User Management",
                    "Create, update, deactivate or permanently delete local directory users.",
                    actions, "Manage Users", "users");
            VBox permissions = infoCard("Role Permissions",
                    "Directory roles describe users but are not enforced as application login permissions. "
                    + "No additional authentication or access controls are represented here.");
            panes.put("Users & Roles", settingsGrid(directory, permissions));

            VBox alerts = infoCard("Low-stock Alerts",
                    "Low-stock and out-of-stock totals are available on the Dashboard and in Inventory. "
                    + "Automatic notifications are not implemented.");
            VBox orderNotifications = infoCard("Order and Email Notifications",
                    "Scheduled notifications and unattended email delivery are not implemented. "
                    + "Use Reports to explicitly generate and send a report.");
            panes.put("Notifications", settingsGrid(alerts, orderNotifications));

            VBox backup = settingsCard("Database Backup",
                    "Create an integrity-checked snapshot in a folder you select.",
                    actions, "Back Up Database", "backup");
            VBox restore = settingsCard("Restore Database",
                    "Restore a validated backup after reviewing the current database and preserving a pre-restore copy.",
                    actions, "Restore Backup", "restore");
            VBox security = infoCard("Session & Access Security",
                    "This desktop release does not currently implement enforced role-based access control, "
                    + "two-factor authentication or configurable session timeout.");
            panes.put("Security", settingsGrid(backup, restore, security));

            List<ToggleButton> buttons = new ArrayList<>();
            for (String name : settingsTabs) {
                ToggleButton button = new ToggleButton(name);
                button.setToggleGroup(tabGroup);
                button.getStyleClass().add("settings-tab");
                button.setFocusTraversable(true);
                buttons.add(button);
                navigation.getChildren().add(button);
                button.setOnAction(event -> showSettingsTab(name, panes, tabContent, buttons));
            }
            // An already selected tab stays selected when clicked again.
            tabGroup.selectedToggleProperty().addListener((obs, before, selected) -> {
                if (selected == null && before != null) before.setSelected(true);
            });
            showSettingsTab("General", panes, tabContent, buttons);
            VBox section = new VBox(17, navigation, tabContent);
            section.getStyleClass().add("settings-container");
            surface.getChildren().add(section);
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

    private static VBox settingsCard(String title, String description,
                                     Map<String, Runnable> actions, String buttonText, String actionId) {
        VBox card = infoCard(title, description);
        card.getChildren().add(action(buttonText, actions.get(actionId)));
        return card;
    }

    private static VBox infoCard(String title, String description) {
        Label heading = label(title, "details-heading");
        Label detail = label(description, "detail-muted");
        detail.setWrapText(true);
        VBox card = new VBox(13, heading, detail);
        card.getStyleClass().add("settings-feature-card");
        card.setFillWidth(true);
        return card;
    }

    private static VBox settingsGrid(VBox... cards) {
        FlowPane grid = new FlowPane(14, 14);
        grid.setPrefWrapLength(830);
        grid.getStyleClass().add("settings-card-grid");
        for (VBox card : cards) {
            card.setPrefWidth(370);
            grid.getChildren().add(card);
        }
        return new VBox(grid);
    }

    private static void showSettingsTab(String name, Map<String, VBox> panes,
                                        VBox content, List<ToggleButton> buttons) {
        VBox selected = panes.get(name);
        if (selected == null) throw new IllegalArgumentException("Unknown settings tab: " + name);
        content.getChildren().setAll(selected);
        for (ToggleButton button : buttons) {
            boolean active = name.equals(button.getText());
            button.setSelected(active);
            button.getStyleClass().remove("settings-tab-selected");
            if (active) button.getStyleClass().add("settings-tab-selected");
        }
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
