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
            tabRow(surface, List.of("Overview", "Inventory Reports", "Order Reports", "Supplier Reports"),
                    actions, "Generate Report", "reports");
            TableView<String> grid = new TableView<>();
            grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
            grid.getColumns().add(column("Report Type", x -> x));
            grid.setItems(FXCollections.observableArrayList(
                    "Sales", "Purchases", "Stock Movements", "Supplier Payables"));
            surface.getChildren().add(grid);
            Label caption = label("Choose Generate Report to select type, date range and CSV destination. "
                    + "Reports are generated on demand; no fictitious history is shown.", "detail-muted");
            caption.setWrapText(true);
            surface.getChildren().add(caption);
            rowActions(surface, actions, "Generate Report", "reports",
                    "Export Inventory CSV", "export", "Backup Database", "backup");
        } else if ("Settings".equals(page)) {
            tabRow(surface, List.of("General", "Licensing", "Security & Backup"), actions,
                    "Shop Profile", "shopProfile");
            GridPane settings = new GridPane();
            settings.setHgap(24);
            settings.setVgap(18);
            settings.addRow(0, label("General Settings", "details-heading"),
                    label("Users & Access", "details-heading"));
            settings.addRow(1, label("Currency: INR (₹)", "detail-name"),
                    label("Activation controls licensed features", "detail-muted"));
            settings.addRow(2, label("Time zone: Asia/Kolkata", "detail-name"),
                    label("Account roles and 2FA are not implemented", "detail-muted"));
            settings.addRow(3, action("Edit Shop Profile / Tax", actions.get("shopProfile")),
                    action("View Activation", actions.get("activation")));
            settings.addRow(4, label("Security & Backup", "details-heading"),
                    label("Data Management", "details-heading"));
            settings.addRow(5, action("Backup Database", actions.get("backup")),
                    action("Restore Backup", actions.get("restore")));
            surface.getChildren().add(settings);
        }
        VBox quick = new VBox(10);
        quick.getStyleClass().add("workspace-surface");
        quick.setPadding(new Insets(15));
        quick.getChildren().add(label("Quick Actions", "details-heading"));
        if ("Categories".equals(page)) rowActions(quick, actions, "Add Category", "addCategory",
                "Inventory", "inventory");
        else if ("Suppliers".equals(page)) rowActions(quick, actions, "Add Supplier", "addSupplier",
                "Purchase Orders", "purchaseOrders");
        else if ("Reports".equals(page)) rowActions(quick, actions, "View Reports", "reports",
                "Export CSV", "export");
        else if ("Orders".equals(page)) rowActions(quick, actions, "New Purchase Order", "purchaseOrders",
                "View Sales", "salesHistory");
        else rowActions(quick, actions, "Shop Settings", "shopProfile",
                "Back Up Now", "backup");
        root.getChildren().add(quick);
        return root;
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
