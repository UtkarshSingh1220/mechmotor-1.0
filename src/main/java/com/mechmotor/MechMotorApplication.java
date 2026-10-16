package com.mechmotor;

import com.mechmotor.domain.*;
import com.mechmotor.service.InventoryService;
import com.mechmotor.service.DatabaseBackupService;
import com.mechmotor.service.InventoryCsvService;
import com.mechmotor.service.LicenseService;
import com.mechmotor.service.LicenseStatus;
import com.mechmotor.service.SalesService;
import com.mechmotor.service.PurchasingService;
import com.mechmotor.service.InvoiceService;
import com.mechmotor.service.OperationalReportsService;
import com.mechmotor.domain.SupplierDetails;
import com.mechmotor.domain.SupplierDraft;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import java.nio.file.Path;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
public class MechMotorApplication extends Application {
    private ConfigurableApplicationContext context;
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mechmotor-database");
        thread.setDaemon(true);
        return thread;
    });
    private InventoryService inventory;
    private DatabaseBackupService backupService;
    private InventoryCsvService csvService;
    private LicenseService licenseService;
    private SalesService salesService;
    private PurchasingService purchasingService;
    private InvoiceService invoiceService;
    private OperationalReportsService reportsService;
    private final List<LookupOption> noOptions = List.of();
    private List<LookupOption> categories = noOptions;
    private List<LookupOption> suppliers = noOptions;

    @Override public void init() {
        context = new SpringApplicationBuilder(MechMotorApplication.class)
                .web(WebApplicationType.NONE).run();
    }

    @Override public void start(Stage stage) {
        inventory = context.getBean(InventoryService.class);
        licenseService = context.getBean(LicenseService.class);
        salesService = context.getBean(SalesService.class);
        purchasingService = context.getBean(PurchasingService.class);
        invoiceService = context.getBean(InvoiceService.class);
        reportsService = context.getBean(OperationalReportsService.class);
        backupService = context.getBean(DatabaseBackupService.class);
        csvService = context.getBean(InventoryCsvService.class);
        Label brand = new Label("MECHMOTOR 1.0");
        brand.getStyleClass().add("brand");
        Label subtitle = new Label("Vehicle parts • Inventory workspace");
        Label licenseStatusLabel = new Label("License: checking…");
        licenseStatusLabel.getStyleClass().add("license-status");
        Button activationButton = new Button("Activation…");
        activationButton.setOnAction(e -> showActivationDialog(stage, false, licenseStatusLabel));
        VBox brandBlock = new VBox(5, brand, subtitle);
        HBox header = new HBox(16, brandBlock, licenseStatusLabel, activationButton);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(brandBlock, Priority.ALWAYS);
        Label status = new Label("Connecting to local inventory…");
        status.getStyleClass().add("status");
        Label activePartsValue = new Label("—"); activePartsValue.getStyleClass().add("metric-value");
        Label totalUnitsValue = new Label("—"); totalUnitsValue.getStyleClass().add("metric-value");
        Label lowStockValue = new Label("—"); lowStockValue.getStyleClass().add("metric-value");
        Label outOfStockValue = new Label("—"); outOfStockValue.getStyleClass().add("metric-value");
        Label retailValue = new Label("—"); retailValue.getStyleClass().add("metric-value");
        HBox dashboard = new HBox(12,
                metricCard("Active parts", activePartsValue),
                metricCard("Units on hand", totalUnitsValue),
                metricCard("Low-stock parts", lowStockValue),
                metricCard("Out of stock", outOfStockValue),
                metricCard("Retail value (est.)", retailValue));
        dashboard.setPadding(new Insets(0, 20, 8, 20));

        TextField query = new TextField();
        query.setPromptText("Search SKU, barcode, brand, material, color, category, supplier or vehicle make/model/engine");
        TextField brandFilter = new TextField(); brandFilter.setPromptText("Brand");
        TextField materialFilter = new TextField(); materialFilter.setPromptText("Material");
        TextField colorFilter = new TextField(); colorFilter.setPromptText("Color");
        ComboBox<LookupOption> categoryFilter = new ComboBox<>(); categoryFilter.setPromptText("Any category");
        ComboBox<LookupOption> supplierFilter = new ComboBox<>(); supplierFilter.setPromptText("Any supplier");
        ComboBox<InventoryFilter.StockStatus> stockFilter = new ComboBox<>(FXCollections.observableArrayList(InventoryFilter.StockStatus.values()));
        stockFilter.setValue(InventoryFilter.StockStatus.ANY);
        ComboBox<String> sort = new ComboBox<>(FXCollections.observableArrayList("name", "sku", "brand", "quantity", "sellingPrice", "updatedAt"));
        sort.setValue("name");
        TextField minQty = new TextField(); minQty.setPromptText("Min qty");
        TextField maxQty = new TextField(); maxQty.setPromptText("Max qty");
        TextField minPrice = new TextField(); minPrice.setPromptText("Min ₹");
        TextField maxPrice = new TextField(); maxPrice.setPromptText("Max ₹");
        Button searchButton = new Button("Apply filters");
        Button clearButton = new Button("Clear");
        Button addButton = new Button("＋ Add part");
        Button editButton = new Button("Edit selected");
        Button deactivateButton = new Button("Deactivate");
        Button sellButton = new Button("Sell selected");
        Button voidSaleButton = new Button("Void sale / refund…");
        Button returnsButton = new Button("Returns / refunds");
        Button invoiceButton = new Button("Sales invoice PDF…");
        Button salesHistoryButton = new Button("Sales history");
        Button receiveStockButton = new Button("Receive stock");
        Button receiveMultiStockButton = new Button("Receive multi-item");
        Button purchaseHistoryButton = new Button("Purchase history / cancel");
        Button reportsButton = new Button("Operational reports…");
        Button fitmentButton = new Button("Vehicle fitment");
        MenuItem addCategoryItem = new MenuItem("Add root / subcategory…");
        MenuItem renameCategoryItem = new MenuItem("Rename selected category…");
        MenuItem deactivateCategoryItem = new MenuItem("Deactivate selected category…");
        MenuButton categoryMenu = new MenuButton("Categories", null, addCategoryItem, renameCategoryItem, deactivateCategoryItem);
        MenuItem addSupplierItem = new MenuItem("Add supplier…");
        MenuItem editSupplierItem = new MenuItem("Edit selected supplier…");
        MenuButton supplierMenu = new MenuButton("Suppliers", null, addSupplierItem, editSupplierItem);
        Button backupButton = new Button("Backup database");
        Button restoreButton = new Button("Restore backup");
        MenuItem exportCsvItem = new MenuItem("Export inventory CSV…");
        MenuItem importCsvItem = new MenuItem("Import inventory CSV…");
        MenuButton csvMenu = new MenuButton("CSV", null, exportCsvItem, importCsvItem);
        ComboBox<LookupOption> savedViewSelector = new ComboBox<>();
        savedViewSelector.setPromptText("Choose saved view");
        savedViewSelector.setPrefWidth(230);
        MenuItem saveViewItem = new MenuItem("Save current filters…");
        MenuItem loadViewItem = new MenuItem("Load selected view");
        MenuItem deleteViewItem = new MenuItem("Delete selected view");
        MenuButton savedViewsMenu = new MenuButton("Saved views", null, saveViewItem, loadViewItem, deleteViewItem);
        Button previousButton = new Button("← Previous");
        Button nextButton = new Button("Next →");
        Label pageLabel = new Label("Page 1");
        TableView<Part> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No parts found. Change the filters or add a part."));
        addColumn(table, "SKU", "sku");
        addColumn(table, "Part name", "name");
        addColumn(table, "Brand / Company", "brand");
        addColumn(table, "Material", "material");
        addColumn(table, "Color", "color");
        addColumn(table, "Category", "category");
        addColumn(table, "Supplier", "supplier");
        addColumn(table, "Qty", "quantity");
        addColumn(table, "Selling price", "sellingPrice");

        int[] page = {0};
        InventoryFilter[] activeFilter = {InventoryFilter.defaults(null)};
        boolean[] suppressFilterEvents = {false};
        InventoryPage[] lastPage = {new InventoryPage(List.of(), 0, 0, 50)};
        Runnable loadPage = () -> {
            try {
                InventoryFilter filter = new InventoryFilter(query.getText(), brandFilter.getText(),
                        materialFilter.getText(), colorFilter.getText(),
                        categoryFilter.getValue() == null ? null : categoryFilter.getValue().id(),
                        supplierFilter.getValue() == null ? null : supplierFilter.getValue().id(),
                        stockFilter.getValue(), parseInteger(minQty.getText(), "Minimum quantity"),
                        parseInteger(maxQty.getText(), "Maximum quantity"),
                        parsePaise(minPrice.getText(), "Minimum price"),
                        parsePaise(maxPrice.getText(), "Maximum price"), page[0], 50,
                        sort.getValue(), true);
                activeFilter[0] = filter;
                searchButton.setDisable(true);
                status.setText("Searching local inventory…");
                Task<InventoryPage> task = new Task<>() {
                    @Override protected InventoryPage call() { return inventory.search(filter); }
                };
                task.setOnSucceeded(e -> {
                    lastPage[0] = task.getValue();
                    table.setItems(FXCollections.observableArrayList(lastPage[0].items()));
                    pageLabel.setText("Page " + (lastPage[0].page() + 1) + " of " + lastPage[0].totalPages()
                            + " • " + lastPage[0].totalItems() + " part(s)");
                    previousButton.setDisable(lastPage[0].page() <= 0);
                    nextButton.setDisable(lastPage[0].page() + 1 >= lastPage[0].totalPages());
                    status.setText("Local inventory • " + lastPage[0].items().size() + " shown • Offline-ready");
                    searchButton.setDisable(false);
                    refreshDashboard(activePartsValue, totalUnitsValue, lowStockValue, outOfStockValue, retailValue);
                });
                task.setOnFailed(e -> {
                    searchButton.setDisable(false);
                    status.setText("Could not load inventory.");
                    showError("Inventory search failed", task.getException());
                });
                databaseExecutor.execute(task);
            } catch (IllegalArgumentException ex) {
                showError("Check your filters", ex);
            }
        };

        searchButton.setOnAction(e -> { page[0] = 0; loadPage.run(); });
        query.setOnAction(e -> { page[0] = 0; loadPage.run(); });
        clearButton.setOnAction(e -> {
            query.clear(); brandFilter.clear(); materialFilter.clear(); colorFilter.clear();
            categoryFilter.setValue(null); supplierFilter.setValue(null);
            stockFilter.setValue(InventoryFilter.StockStatus.ANY);
            minQty.clear(); maxQty.clear(); minPrice.clear(); maxPrice.clear();
            sort.setValue("name"); page[0] = 0; loadPage.run();
        });
        previousButton.setDisable(true);
        nextButton.setDisable(true);
        previousButton.setOnAction(e -> { if (page[0] > 0) { page[0]--; loadPage.run(); } });
        nextButton.setOnAction(e -> { if (page[0] + 1 < lastPage[0].totalPages()) { page[0]++; loadPage.run(); } });
        sort.setOnAction(e -> { if (!suppressFilterEvents[0]) { page[0] = 0; loadPage.run(); } });
        saveViewItem.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Save inventory view");
            dialog.setHeaderText("Save the currently applied search, filters and sorting");
            dialog.setContentText("View name:");
            dialog.showAndWait().ifPresent(name -> runBackground(
                    () -> inventory.saveView(name, activeFilter[0]),
                    id -> refreshSavedViews(savedViewSelector, status, id),
                    error -> showError("Could not save inventory view", error)));
        });
        loadViewItem.setOnAction(e -> {
            LookupOption selected = savedViewSelector.getValue();
            if (selected == null) {
                showError("Select a saved view", new IllegalArgumentException("Choose a saved view first."));
                return;
            }
            runBackground(() -> inventory.getSavedView(selected.id()), saved -> {
                InventoryFilter filter = saved.filter();
                LookupOption category = filter.categoryId() == null ? null
                        : categories.stream().filter(o -> o.id() == filter.categoryId()).findFirst().orElse(null);
                LookupOption supplier = filter.supplierId() == null ? null
                        : suppliers.stream().filter(o -> o.id() == filter.supplierId()).findFirst().orElse(null);
                if (filter.categoryId() != null && category == null) {
                    showError("Saved view needs attention", new IllegalArgumentException(
                            "Its category is no longer active. Delete or replace this saved view."));
                    return;
                }
                if (filter.supplierId() != null && supplier == null) {
                    showError("Saved view needs attention", new IllegalArgumentException(
                            "Its supplier no longer exists. Delete or replace this saved view."));
                    return;
                }
                suppressFilterEvents[0] = true;
                query.setText(filter.query());
                brandFilter.setText(filter.brand());
                materialFilter.setText(filter.material());
                colorFilter.setText(filter.color());
                categoryFilter.setValue(category);
                supplierFilter.setValue(supplier);
                stockFilter.setValue(filter.stockStatus());
                minQty.setText(filter.minQuantity() == null ? "" : filter.minQuantity().toString());
                maxQty.setText(filter.maxQuantity() == null ? "" : filter.maxQuantity().toString());
                minPrice.setText(filter.minSellingPricePaise() == null ? "" : rupees(filter.minSellingPricePaise()));
                maxPrice.setText(filter.maxSellingPricePaise() == null ? "" : rupees(filter.maxSellingPricePaise()));
                sort.setValue(filter.sortBy());
                suppressFilterEvents[0] = false;
                page[0] = 0;
                status.setText("Loaded saved view: " + saved.name());
                loadPage.run();
            }, error -> showError("Could not load saved view", error));
        });
        deleteViewItem.setOnAction(e -> {
            LookupOption selected = savedViewSelector.getValue();
            if (selected == null) {
                showError("Select a saved view", new IllegalArgumentException("Choose a saved view first."));
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete saved view '" + selected.name() + "'? Inventory data will not be changed.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setTitle("Delete saved view");
            confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(button ->
                    runBackground(() -> { inventory.deleteSavedView(selected.id()); return Boolean.TRUE; }, ignored -> {
                        savedViewSelector.setValue(null);
                        refreshSavedViews(savedViewSelector, status, null);
                    }, error -> showError("Could not delete saved view", error)));
        });

        importCsvItem.setOnAction(e -> {
            if (!licenseService.status().features().contains("CSV")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include CSV import/export."));
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Import inventory from CSV");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
            java.io.File selected = chooser.showOpenDialog(stage);
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Import creates new inventory items only. Existing SKUs are never overwritten. If any row is invalid or conflicts with an existing SKU, the entire import is rolled back. Vehicle fitments are managed separately.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setTitle("Confirm inventory import");
            confirm.setHeaderText("Import CSV inventory?");
            confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(button ->
                    runBackground(() -> csvService.importFrom(selected.toPath()), count -> {
                        page[0] = 0;
                        status.setText("Imported " + count + " inventory item(s) from " + selected.getName());
                        loadPage.run();
                        Alert info = new Alert(Alert.AlertType.INFORMATION,
                                "Imported " + count + " new inventory item(s). No existing items were overwritten.", ButtonType.OK);
                        info.setTitle("CSV import complete"); info.setHeaderText("Inventory imported"); info.show();
                    }, error -> showError("Inventory CSV import failed", error)));
        });

        exportCsvItem.setOnAction(e -> {
            if (!licenseService.status().features().contains("CSV")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include CSV import/export."));
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export filtered inventory to CSV");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
            chooser.setInitialFileName("mechmotor-inventory.csv");
            java.io.File target = chooser.showSaveDialog(stage);
            if (target == null) return;
            runBackground(() -> csvService.export(target.toPath(), activeFilter[0]), count -> {
                status.setText("Exported " + count + " inventory item(s) to " + target.getName());
                Alert info = new Alert(Alert.AlertType.INFORMATION,
                        "Exported " + count + " matching active inventory item(s) to:\n" + target, ButtonType.OK);
                info.setTitle("CSV export complete"); info.setHeaderText("Inventory export ready"); info.show();
            }, error -> showError("Could not export inventory", error));
        });

        reportsButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("CSV")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include CSV/report export."));
                return;
            }
            ChoiceDialog<OperationalReportsService.ReportType> typeDialog = new ChoiceDialog<>(
                    OperationalReportsService.ReportType.SALES,
                    OperationalReportsService.ReportType.values());
            typeDialog.setTitle("Operational reports");
            typeDialog.setHeaderText("Choose a CSV report");
            typeDialog.setContentText("Report type:");
            var chosenType = typeDialog.showAndWait();
            if (chosenType.isEmpty()) return;
            java.time.LocalDate defaultEnd = java.time.LocalDate.now();
            java.time.LocalDate defaultStart = defaultEnd.minusDays(6);
            TextInputDialog startDialog = new TextInputDialog(defaultStart.toString());
            startDialog.setTitle("Operational reports");
            startDialog.setHeaderText("Start date (inclusive)");
            startDialog.setContentText("Use YYYY-MM-DD:");
            var startText = startDialog.showAndWait();
            if (startText.isEmpty()) return;
            TextInputDialog endDialog = new TextInputDialog(defaultEnd.toString());
            endDialog.setTitle("Operational reports");
            endDialog.setHeaderText("End date (inclusive)");
            endDialog.setContentText("Use YYYY-MM-DD:");
            var endText = endDialog.showAndWait();
            if (endText.isEmpty()) return;
            final java.time.LocalDate from;
            final java.time.LocalDate through;
            try {
                from = java.time.LocalDate.parse(startText.get().strip());
                through = java.time.LocalDate.parse(endText.get().strip());
            } catch (java.time.DateTimeException invalidDate) {
                showError("Invalid report dates", new IllegalArgumentException("Enter dates in YYYY-MM-DD format.", invalidDate));
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save operational report");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
            chooser.setInitialFileName("mechmotor-" + chosenType.get().name().toLowerCase(java.util.Locale.ROOT).replace('_','-')
                    + "-" + from + "-to-" + through + ".csv");
            java.io.File target = chooser.showSaveDialog(stage);
            if (target == null) return;
            runBackground(() -> reportsService.export(chosenType.get(), target.toPath(), from, through), count -> {
                status.setText("Exported " + count + " report row(s) to " + target.getName());
                Alert info = new Alert(Alert.AlertType.INFORMATION,
                        "Exported " + count + " row(s) to:\n" + target, ButtonType.OK);
                info.setTitle("Report export complete"); info.setHeaderText("Operational report ready"); info.show();
            }, error -> showError("Could not export report", error));
        });

        addButton.setOnAction(e -> showPartDialog(stage, null, () -> { page[0] = 0; loadPage.run(); }, status));
        editButton.setOnAction(e -> {
            Part selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) { status.setText("Select a part to edit."); return; }
            runBackground(() -> inventory.getDetails(selected.id()), details ->
                    showPartDialog(stage, details, () -> loadPage.run(), status),
                    error -> showError("Could not load part details", error));
        });
        deactivateButton.setOnAction(e -> {
            Part selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) { status.setText("Select a part to deactivate."); return; }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Deactivate " + selected.name() + " (" + selected.sku() + ")? It will be hidden from active inventory but retained in the database.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setHeaderText("Deactivate inventory item");
            confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(button ->
                    runBackground(() -> { inventory.deactivate(selected.id()); return Boolean.TRUE; },
                            ignored -> { status.setText("Part deactivated."); loadPage.run(); },
                            error -> showError("Could not deactivate part", error)));
        });

        sellButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("SALES")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include SALES."));
                return;
            }
            Part selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a part", new IllegalArgumentException("Choose an inventory item to sell."));
                return;
            }
            if (selected.quantity() <= 0) {
                showError("Out of stock", new IllegalArgumentException("This item has no available stock."));
                return;
            }
            showSaleDialog(stage, selected, status, loadPage);
        });

        voidSaleButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("SALES")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include SALES."));
                return;
            }
            showVoidSaleDialog(stage, status, loadPage);
        });


        returnsButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("SALES")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include SALES."));
                return;
            }
            showReturnsDialog(stage, status, loadPage);
        });


        invoiceButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("SALES")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include SALES."));
                return;
            }
            TextInputDialog saleDialog = new TextInputDialog();
            saleDialog.setTitle("Sales invoice PDF");
            saleDialog.setHeaderText("Generate a printable invoice from the saved sale snapshots.");
            saleDialog.setContentText("Original sale number:");
            saleDialog.showAndWait().ifPresent(number -> {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Save sales invoice PDF");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
                chooser.setInitialFileName("mechmotor-invoice.pdf");
                java.io.File target = chooser.showSaveDialog(stage);
                if (target == null) return;
                Path selectedPath = target.toPath();
                if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                    selectedPath = target.toPath().resolveSibling(target.getName() + ".pdf");
                }
                final Path destination = selectedPath;
                runBackground(() -> invoiceService.exportSaleInvoice(number, destination), saved -> {
                    status.setText("Invoice PDF created: " + saved.getFileName());
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            "Invoice PDF saved to:\n" + saved, ButtonType.OK);
                    info.setTitle("Invoice exported");
                    info.setHeaderText("Sales invoice ready");
                    info.show();
                }, error -> showError("Could not create sales invoice", error));
            });
        });

        salesHistoryButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("SALES")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include SALES."));
                return;
            }
            showSalesHistoryDialog(stage, status);
        });

        receiveStockButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("PURCHASING")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include PURCHASING."));
                return;
            }
            Part selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a part", new IllegalArgumentException("Choose an inventory item to receive."));
                return;
            }
            showPurchaseDialog(stage, selected, supplierFilter.getValue(), status, loadPage);
        });

        receiveMultiStockButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("PURCHASING")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include PURCHASING."));
                return;
            }
            showMultiPurchaseDialog(stage, supplierFilter.getValue(), status, loadPage);
        });

        purchaseHistoryButton.setOnAction(e -> {
            if (!licenseService.status().features().contains("PURCHASING")) {
                showError("Feature not licensed", new IllegalArgumentException("Your activation key does not include PURCHASING."));
                return;
            }
            showPurchaseHistoryDialog(stage, status, loadPage);
        });

        fitmentButton.setOnAction(e -> {
            Part selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a part", new IllegalArgumentException("Choose an inventory item before managing vehicle fitment."));
                return;
            }
            runBackground(() -> inventory.fitments(selected.id()),
                    fitments -> showFitmentDialog(stage, selected, fitments, status),
                    error -> showError("Could not load vehicle fitments", error));
        });

        addCategoryItem.setOnAction(e -> {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(stage);
            dialog.setTitle("Add category");
            dialog.setHeaderText("Create a root category or a nested subcategory");
            ButtonType createType = new ButtonType("Create category", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);
            TextField categoryName = new TextField();
            categoryName.setPromptText("Category name");
            ComboBox<LookupOption> parent = new ComboBox<>(FXCollections.observableArrayList(categories));
            parent.setPromptText("Root category (no parent)");
            parent.setMaxWidth(Double.MAX_VALUE);
            GridPane form = new GridPane();
            form.setHgap(10); form.setVgap(10); form.setPadding(new Insets(12));
            form.add(new Label("Name"), 0, 0); form.add(categoryName, 1, 0);
            form.add(new Label("Parent"), 0, 1); form.add(parent, 1, 1);
            GridPane.setHgrow(categoryName, Priority.ALWAYS); GridPane.setHgrow(parent, Priority.ALWAYS);
            dialog.getDialogPane().setContent(form);
            Button create = (Button) dialog.getDialogPane().lookupButton(createType);
            create.addEventFilter(ActionEvent.ACTION, event -> {
                if (categoryName.getText() == null || categoryName.getText().isBlank()) {
                    event.consume(); showError("Category name required", new IllegalArgumentException("Enter a category name."));
                }
            });
            dialog.showAndWait().filter(createType::equals).ifPresent(button ->
                runBackground(() -> inventory.createCategory(categoryName.getText(), parent.getValue() == null ? null : parent.getValue().id()), id -> {
                    status.setText("Category created."); refreshLookups(categoryFilter, supplierFilter, loadPage, status);
                }, error -> showError("Could not create category", error)));
        });
        renameCategoryItem.setOnAction(e -> {
            LookupOption selected = categoryFilter.getValue();
            if (selected == null) { showError("Select a category", new IllegalArgumentException("Choose a category in the category filter first.")); return; }
            int separator = selected.name().lastIndexOf(" / ");
            String currentName = separator >= 0 ? selected.name().substring(separator + 3) : selected.name();
            TextInputDialog dialog = new TextInputDialog(currentName);
            dialog.setTitle("Rename category"); dialog.setHeaderText("Rename " + selected.name());
            dialog.setContentText("New category name:");
            dialog.showAndWait().ifPresent(name -> runBackground(() -> { inventory.renameCategory(selected.id(), name); return Boolean.TRUE; }, ignored -> {
                categoryFilter.setValue(null); status.setText("Category renamed."); refreshLookups(categoryFilter, supplierFilter, loadPage, status);
            }, error -> showError("Could not rename category", error)));
        });
        deactivateCategoryItem.setOnAction(e -> {
            LookupOption selected = categoryFilter.getValue();
            if (selected == null) { showError("Select a category", new IllegalArgumentException("Choose a category in the category filter first.")); return; }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Deactivate '" + selected.name() + "'? Categories with active subcategories or active inventory items cannot be deactivated.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setTitle("Deactivate category"); confirm.setHeaderText("Confirm category deactivation");
            confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(button ->
                runBackground(() -> { inventory.deactivateCategory(selected.id()); return Boolean.TRUE; }, ignored -> {
                    categoryFilter.setValue(null); status.setText("Category deactivated."); refreshLookups(categoryFilter, supplierFilter, loadPage, status);
                }, error -> showError("Could not deactivate category", error)));
        });
        backupButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose backup folder");
            java.io.File directory = chooser.showDialog(stage);
            if (directory != null) runBackground(() -> backupService.backupToDirectory(directory.toPath()), saved -> {
                status.setText("Verified backup created: " + saved.getFileName());
                Alert info = new Alert(Alert.AlertType.INFORMATION, "Backup created and integrity-checked at:\n" + saved, ButtonType.OK);
                info.setTitle("Backup complete"); info.setHeaderText("Your inventory backup is ready"); info.show();
            }, error -> showError("Backup failed", error));
        });
        restoreButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose MechMotor backup to restore");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite database backup", "*.db"));
            java.io.File selected = chooser.showOpenDialog(stage);
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Restoring replaces the current inventory with the selected backup. A verified pre-restore backup will be created first. Close other MechMotor instances before continuing.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setTitle("Confirm database restore"); confirm.setHeaderText("Replace current inventory?");
            confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(button ->
                runBackground(() -> backupService.restoreFrom(selected.toPath()), safetyBackup -> {
                    categoryFilter.setValue(null); supplierFilter.setValue(null); page[0] = 0;
                    status.setText("Restore complete. Safety backup: " + safetyBackup.getFileName());
                    refreshLookups(categoryFilter, supplierFilter, loadPage, status);
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            "Restore completed. Your previous database was saved to:\n" + safetyBackup, ButtonType.OK);
                    info.setTitle("Restore complete"); info.setHeaderText("Inventory restored"); info.show();
                }, error -> showError("Restore failed", error)));
        });

        addSupplierItem.setOnAction(e -> showSupplierDialog(stage, null, categoryFilter, supplierFilter, loadPage, status));
        editSupplierItem.setOnAction(e -> {
            LookupOption selected = supplierFilter.getValue();
            if (selected == null) {
                showError("Select a supplier", new IllegalArgumentException("Choose a supplier in the supplier filter first."));
                return;
            }
            runBackground(() -> inventory.getSupplier(selected.id()),
                    details -> showSupplierDialog(stage, details, categoryFilter, supplierFilter, loadPage, status),
                    error -> showError("Could not load supplier", error));
        });

        GridPane filters = new GridPane();
        filters.setHgap(8); filters.setVgap(8); filters.setPadding(new Insets(12, 20, 12, 20));
        filters.add(query, 0, 0, 4, 1); filters.add(searchButton, 4, 0); filters.add(clearButton, 5, 0);
        filters.add(brandFilter, 0, 1); filters.add(materialFilter, 1, 1); filters.add(colorFilter, 2, 1);
        filters.add(categoryFilter, 3, 1); filters.add(supplierFilter, 4, 1); filters.add(stockFilter, 5, 1);
        filters.add(minQty, 0, 2); filters.add(maxQty, 1, 2); filters.add(minPrice, 2, 2);
        filters.add(maxPrice, 3, 2); filters.add(new Label("Sort:"), 4, 2); filters.add(sort, 5, 2);
        filters.add(new Label("Saved view:"), 0, 3);
        filters.add(savedViewSelector, 1, 3, 2, 1);
        filters.add(savedViewsMenu, 3, 3, 3, 1);
        GridPane.setHgrow(savedViewSelector, Priority.ALWAYS);
        GridPane.setHgrow(savedViewsMenu, Priority.ALWAYS);
        for (int i = 0; i < 6; i++) GridPane.setHgrow(filters.getChildren().get(i), Priority.ALWAYS);
        FlowPane actions = new FlowPane(8, 8, addButton, editButton, deactivateButton, sellButton,
                voidSaleButton, returnsButton, invoiceButton, salesHistoryButton, receiveStockButton, receiveMultiStockButton, purchaseHistoryButton, fitmentButton, reportsButton,
                categoryMenu, supplierMenu, backupButton, restoreButton, csvMenu,
                previousButton, nextButton, pageLabel);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPrefWrapLength(1320);
        actions.setPadding(new Insets(8, 20, 8, 20));
        header.setPadding(new Insets(20)); header.getStyleClass().add("header");
        VBox root = new VBox(0, header, dashboard, filters, actions, table, status);
        VBox.setVgrow(table, Priority.ALWAYS);
        status.setPadding(new Insets(12, 20, 12, 20));
        Scene scene = new Scene(root, 1380, 820);
        var css = getClass().getResource("/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle("MechMotor 1.0 — Vehicle Parts Shop");
        stage.setMinWidth(1000); stage.setMinHeight(650); stage.setScene(scene);
        stage.show();
        LicenseStatus currentLicense = licenseService.status();
        updateLicenseLabel(currentLicense, licenseStatusLabel);
        if (!currentLicense.active() && !showActivationDialog(stage, true, licenseStatusLabel)) {
            Platform.exit();
            return;
        }

        runBackground(() -> new Lookups(inventory.categories(), inventory.suppliers()), lookups -> {
            categories = lookups.categories(); suppliers = lookups.suppliers();
            categoryFilter.setItems(FXCollections.observableArrayList(categories));
            supplierFilter.setItems(FXCollections.observableArrayList(suppliers));
            refreshSavedViews(savedViewSelector, status, null);
            loadPage.run();
        }, error -> { status.setText("Could not load categories and suppliers."); showError("Database initialization failed", error); });
    }


    private void showReturnsDialog(Stage owner, Label status, Runnable refreshInventory) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Sales returns and refunds");
        dialog.setHeaderText("Find a posted sale, select an item line, then return eligible quantity.");
        ButtonType processType = new ButtonType("Process return / refund", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(processType, ButtonType.CANCEL);

        TextField saleNumber = new TextField();
        saleNumber.setPromptText("MM-YYYYMMDD-XXXXXXXX");
        Button findSale = new Button("Find sale");
        ComboBox<SaleReturnable> linePicker = new ComboBox<>();
        linePicker.setPromptText("Select item from sale");
        linePicker.setMaxWidth(Double.MAX_VALUE);
        linePicker.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(SaleReturnable item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.sku() + " — " + item.partName()
                        + " • remaining " + item.remainingQuantity());
            }
        });
        linePicker.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(SaleReturnable item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.sku() + " — " + item.partName()
                        + " • remaining " + item.remainingQuantity());
            }
        });
        Label saleSummary = new Label("Enter the sale number shown on the original receipt.");
        saleSummary.setWrapText(true);
        TextField quantity = new TextField("1");
        ComboBox<String> refundMethod = new ComboBox<>(FXCollections.observableArrayList("CASH", "UPI", "CARD", "BANK_TRANSFER"));
        refundMethod.setValue("CASH");
        refundMethod.setMaxWidth(Double.MAX_VALUE);
        TextField refundReference = new TextField();
        refundReference.setPromptText("Optional refund transaction reference");
        TextField reason = new TextField();
        reason.setPromptText("Optional reason");
        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(9); form.setPadding(new Insets(12));
        form.add(new Label("Sale number *"), 0, 0); form.add(saleNumber, 1, 0); form.add(findSale, 2, 0);
        form.add(new Label("Sale item *"), 0, 1); form.add(linePicker, 1, 1, 2, 1);
        form.add(new Label("Sale details"), 0, 2); form.add(saleSummary, 1, 2, 2, 1);
        form.add(new Label("Quantity *"), 0, 3); form.add(quantity, 1, 3, 2, 1);
        form.add(new Label("Refund method *"), 0, 4); form.add(refundMethod, 1, 4, 2, 1);
        form.add(new Label("Refund reference"), 0, 5); form.add(refundReference, 1, 5, 2, 1);
        form.add(new Label("Reason"), 0, 6); form.add(reason, 1, 6, 2, 1);
        GridPane.setHgrow(saleNumber, Priority.ALWAYS);
        GridPane.setHgrow(linePicker, Priority.ALWAYS);
        GridPane.setHgrow(saleSummary, Priority.ALWAYS);
        GridPane.setHgrow(refundMethod, Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(700);
        Button process = (Button) dialog.getDialogPane().lookupButton(processType);
        process.setDisable(true);

        linePicker.valueProperty().addListener((obs, oldValue, selected) -> {
            process.setDisable(selected == null || selected.remainingQuantity() <= 0);
            if (selected == null) {
                saleSummary.setText("Choose an item line from the sale.");
            } else {
                saleSummary.setText("Customer: " + (selected.customerName() == null ? "Walk-in" : selected.customerName())
                        + " | Sold: " + selected.soldQuantity()
                        + " | Returned: " + selected.returnedQuantity()
                        + " | Remaining: " + selected.remainingQuantity()
                        + " | Refundable for this line: ₹" + rupees(selected.remainingRefundPaise()));
            }
        });
        saleNumber.textProperty().addListener((obs, oldValue, newValue) -> {
            linePicker.getItems().clear();
            linePicker.setValue(null);
            process.setDisable(true);
            saleSummary.setText("Sale number changed. Find the sale again.");
        });
        findSale.setOnAction(e -> {
            String number = saleNumber.getText() == null ? "" : saleNumber.getText().strip();
            if (number.isBlank()) {
                showError("Sale number required", new IllegalArgumentException("Enter the sale number from the original receipt."));
                return;
            }
            findSale.setDisable(true);
            runBackground(() -> salesService.returnableSale(number), found -> {
                findSale.setDisable(false);
                linePicker.getItems().setAll(found);
                SaleReturnable eligible = found.stream().filter(line -> line.remainingQuantity() > 0).findFirst()
                        .orElse(found.get(0));
                linePicker.setValue(eligible);
            }, error -> {
                findSale.setDisable(false);
                showError("Could not find sale", error);
            });
        });
        process.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            SaleReturnable selected = linePicker.getValue();
            if (selected == null || !selected.saleNumber().equalsIgnoreCase(saleNumber.getText().strip())) {
                showError("Find the sale first", new IllegalArgumentException("Load sale details and select a sale item first."));
                return;
            }
            try {
                SaleReturnDraft draft = new SaleReturnDraft(selected.saleNumber(), selected.saleItemId(),
                        parseRequiredInteger(quantity.getText(), "Return quantity"),
                        refundMethod.getValue(), refundReference.getText(), reason.getText());
                if (draft.quantity() > selected.remainingQuantity()) {
                    throw new IllegalArgumentException("Only " + selected.remainingQuantity() + " unit(s) remain eligible for this item.");
                }
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Return " + draft.quantity() + " x " + selected.sku() + " (" + selected.partName()
                                + ")? Refund amount is calculated from the original sale discount and previous returns. "
                                + "This records a refund but does not transfer money through a payment provider.",
                        ButtonType.CANCEL, ButtonType.OK);
                confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
                confirm.setTitle("Confirm return / refund");
                confirm.setHeaderText("Post inventory return and refund");
                if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
                process.setDisable(true);
                findSale.setDisable(true);
                runBackground(() -> salesService.returnSale(draft), receipt -> {
                    dialog.close();
                    status.setText("Return posted: " + receipt.returnNumber() + " • refund ₹" + rupees(receipt.refundPaise()));
                    refreshInventory.run();
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            "Return receipt: " + receipt.returnNumber()
                                    + "\nSale: " + receipt.saleNumber()
                                    + "\nPart: " + receipt.sku() + " — " + receipt.partName()
                                    + "\nQuantity: " + receipt.quantity()
                                    + "\nRefund recorded: ₹" + rupees(receipt.refundPaise())
                                    + "\nMethod: " + receipt.refundMethod()
                                    + "\n\nProcess the actual refund separately and retain its reference.", ButtonType.OK);
                    info.setTitle("Return completed");
                    info.setHeaderText("Return and refund recorded");
                    info.show();
                }, error -> {
                    process.setDisable(false);
                    findSale.setDisable(false);
                    showError("Could not process return", error);
                });
            } catch (IllegalArgumentException ex) {
                showError("Check return details", ex);
            }
        });
        dialog.showAndWait();
    }

    private void showSalesHistoryDialog(Stage owner, Label status) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Sales history");
        dialog.setHeaderText("Search recent sales, payment methods and recorded refunds. Showing at most 500 records.");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextField filter = new TextField();
        filter.setPromptText("Search sale number or customer");
        Button search = new Button("Search");
        Button refresh = new Button("Refresh");
        TableView<SaleHistoryRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No sales match this search."));
        table.setPrefSize(1050, 430);

        addHistoryColumn(table, "Sale number", row -> row.saleNumber());
        addHistoryColumn(table, "Customer", row -> row.customerName());
        addHistoryColumn(table, "Created at", row -> row.createdAt());
        addHistoryColumn(table, "Status", row -> row.status());
        addHistoryColumn(table, "Payment", row -> row.paymentMethods());
        addHistoryColumn(table, "Units", row -> Integer.toString(row.units()));
        addHistoryColumn(table, "Subtotal", row -> "₹" + rupees(row.subtotalPaise()));
        addHistoryColumn(table, "Discount", row -> "₹" + rupees(row.discountPaise()));
        addHistoryColumn(table, "Total", row -> "₹" + rupees(row.totalPaise()));
        addHistoryColumn(table, "Refund recorded", row -> "₹" + rupees(row.refundedPaise()));

        Runnable load = () -> {
            search.setDisable(true);
            refresh.setDisable(true);
            runBackground(() -> salesService.history(filter.getText(), 500), rows -> {
                table.setItems(FXCollections.observableArrayList(rows));
                search.setDisable(false);
                refresh.setDisable(false);
                status.setText("Sales history • " + rows.size() + " record(s) shown");
            }, error -> {
                search.setDisable(false);
                refresh.setDisable(false);
                showError("Could not load sales history", error);
            });
        };
        search.setOnAction(e -> load.run());
        refresh.setOnAction(e -> load.run());
        filter.setOnAction(e -> load.run());
        HBox controls = new HBox(8, filter, search, refresh);
        HBox.setHgrow(filter, Priority.ALWAYS);
        VBox content = new VBox(10, controls, table);
        content.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(1120, 540);
        dialog.show();
        load.run();
    }

    private static void addHistoryColumn(TableView<SaleHistoryRow> table, String title,
                                         java.util.function.Function<SaleHistoryRow, String> value) {
        TableColumn<SaleHistoryRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    private void showPurchaseHistoryDialog(Stage owner, Label status, Runnable refreshInventory) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Purchase history");
        dialog.setHeaderText("Search supplier receipts and review cancellation history. A receipt can only be cancelled when current stock covers the full reversal.");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextField filter = new TextField();
        filter.setPromptText("Search receipt, supplier, invoice reference or part");
        Button search = new Button("Search");
        Button cancelReceipt = new Button("Cancel selected receipt…");
        cancelReceipt.setDisable(true);
        TableView<PurchaseHistoryRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No purchase receipts match this search."));
        table.setPrefSize(1050, 420);
        addPurchaseHistoryColumn(table, "Receipt", row -> row.purchaseNumber());
        addPurchaseHistoryColumn(table, "Supplier", row -> row.supplierName());
        addPurchaseHistoryColumn(table, "Supplier invoice", row -> row.supplierInvoiceReference());
        addPurchaseHistoryColumn(table, "Received at", row -> row.createdAt());
        addPurchaseHistoryColumn(table, "Status", row -> row.status());
        addPurchaseHistoryColumn(table, "Items", row -> row.itemSummary());
        addPurchaseHistoryColumn(table, "Units", row -> Integer.toString(row.units()));
        addPurchaseHistoryColumn(table, "Total", row -> "₹" + rupees(row.totalPaise()));
        addPurchaseHistoryColumn(table, "Cancellation reason", row -> row.cancellationReason());

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) ->
                cancelReceipt.setDisable(selected == null || !"RECEIVED".equals(selected.status())));
        Runnable load = () -> {
            search.setDisable(true);
            runBackground(() -> purchasingService.history(filter.getText(), 500), rows -> {
                table.setItems(FXCollections.observableArrayList(rows));
                search.setDisable(false);
                status.setText("Purchase history • " + rows.size() + " receipt(s) shown");
            }, error -> {
                search.setDisable(false);
                showError("Could not load purchase history", error);
            });
        };
        search.setOnAction(e -> load.run());
        filter.setOnAction(e -> load.run());
        cancelReceipt.setOnAction(e -> {
            PurchaseHistoryRow selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || !"RECEIVED".equals(selected.status())) {
                showError("Select a received receipt", new IllegalArgumentException("Only a RECEIVED receipt can be cancelled."));
                return;
            }
            TextInputDialog reasonDialog = new TextInputDialog();
            reasonDialog.initOwner(owner);
            reasonDialog.setTitle("Purchase cancellation reason");
            reasonDialog.setHeaderText("Record why receipt " + selected.purchaseNumber() + " is being cancelled.");
            reasonDialog.setContentText("Reason:");
            reasonDialog.showAndWait().ifPresent(reason -> {
                if (reason.isBlank()) {
                    showError("Reason required", new IllegalArgumentException("Enter a cancellation reason."));
                    return;
                }
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "This marks the receipt CANCELLED, reverses its received stock and writes an audit movement. It is allowed only if current on-hand stock covers the entire receipt. It does not process supplier refunds or accounts payable. Continue?",
                        ButtonType.CANCEL, ButtonType.OK);
                confirm.initOwner(owner);
                confirm.setTitle("Confirm purchase cancellation");
                confirm.setHeaderText("Reverse received stock?");
                confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(button ->
                    runBackground(() -> purchasingService.cancelPurchase(selected.purchaseNumber(), reason), receipt -> {
                        status.setText("Purchase cancelled: " + receipt.purchaseNumber());
                        refreshInventory.run();
                        load.run();
                        Alert info = new Alert(Alert.AlertType.INFORMATION,
                                "Receipt: " + receipt.purchaseNumber()
                                        + "\nLines reversed: " + receipt.lineCount()
                                        + "\nUnits reversed: " + receipt.unitsReversed()
                                        + "\nReceipt total: ₹" + rupees(receipt.totalPaise())
                                        + "\nReason: " + receipt.reason()
                                        + "\n\nNo supplier refund or payment transfer was performed.", ButtonType.OK);
                        info.setTitle("Purchase cancelled");
                        info.setHeaderText("Stock receipt reversed");
                        info.show();
                    }, error -> showError("Could not cancel purchase receipt", error)));
            });
        });
        HBox controls = new HBox(8, filter, search, cancelReceipt);
        HBox.setHgrow(filter, Priority.ALWAYS);
        VBox content = new VBox(10, controls, table);
        content.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(1120, 530);
        dialog.show();
        load.run();
    }

    private static void addPurchaseHistoryColumn(TableView<PurchaseHistoryRow> table, String title,
                                                  java.util.function.Function<PurchaseHistoryRow, String> value) {
        TableColumn<PurchaseHistoryRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    private void showPurchaseDialog(Stage owner, Part part, LookupOption defaultSupplier,
                                    Label status, Runnable refreshInventory) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Receive supplier stock");
        dialog.setHeaderText(part.name() + " (" + part.sku() + ") — current stock: " + part.quantity());
        ButtonType receiveType = new ButtonType("Receive stock", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(receiveType, ButtonType.CANCEL);

        ComboBox<LookupOption> supplier = new ComboBox<>(FXCollections.observableArrayList(suppliers));
        supplier.setPromptText("Select supplier");
        supplier.setMaxWidth(Double.MAX_VALUE);
        if (defaultSupplier != null) supplier.setValue(suppliers.stream()
                .filter(option -> option.id() == defaultSupplier.id()).findFirst().orElse(null));
        TextField quantity = new TextField("1");
        TextField unitCost = new TextField(rupees(part.purchasePricePaise()));
        TextField invoiceReference = new TextField();
        invoiceReference.setPromptText("Optional supplier invoice / delivery note");
        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(10); form.setPadding(new Insets(12));
        form.add(new Label("Supplier"), 0, 0); form.add(supplier, 1, 0);
        form.add(new Label("Quantity received"), 0, 1); form.add(quantity, 1, 1);
        form.add(new Label("Unit cost (₹)"), 0, 2); form.add(unitCost, 1, 2);
        form.add(new Label("Invoice reference"), 0, 3); form.add(invoiceReference, 1, 3);
        GridPane.setHgrow(supplier, Priority.ALWAYS);
        GridPane.setHgrow(invoiceReference, Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(520);

        Button receive = (Button) dialog.getDialogPane().lookupButton(receiveType);
        receive.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            try {
                LookupOption selectedSupplier = supplier.getValue();
                if (selectedSupplier == null) throw new IllegalArgumentException("Choose a supplier.");
                PurchaseDraft draft = new PurchaseDraft(part.id(),
                        parseRequiredInteger(quantity.getText(), "Quantity received"),
                        parsePaise(unitCost.getText(), "Unit cost"),
                        selectedSupplier.id(), invoiceReference.getText());
                receive.setDisable(true);
                runBackground(() -> purchasingService.receive(draft), receipt -> {
                    dialog.close();
                    status.setText("Stock received: " + receipt.purchaseNumber() + " • " + receipt.quantity() + " unit(s)");
                    refreshInventory.run();
                    Alert confirmation = new Alert(Alert.AlertType.INFORMATION,
                            "Purchase receipt: " + receipt.purchaseNumber()
                                    + "\nSupplier: " + receipt.supplierName()
                                    + "\nPart: " + receipt.sku() + " — " + receipt.partName()
                                    + "\nQuantity received: " + receipt.quantity()
                                    + "\nUnit cost: ₹" + rupees(receipt.unitCostPaise())
                                    + "\nReceipt total: ₹" + rupees(receipt.totalPaise()), ButtonType.OK);
                    confirmation.setTitle("Stock received");
                    confirmation.setHeaderText("Supplier receipt posted");
                    confirmation.show();
                }, error -> {
                    receive.setDisable(false);
                    showError("Could not receive stock", error);
                });
            } catch (IllegalArgumentException ex) {
                showError("Check receipt details", ex);
            }
        });
        dialog.showAndWait();
    }


    private record PurchaseCartLine(Part part, int quantity, long unitCostPaise) {
        long lineTotalPaise() { return Math.multiplyExact((long) quantity, unitCostPaise); }
    }

    private void showMultiPurchaseDialog(Stage owner, LookupOption defaultSupplier,
                                         Label status, Runnable refreshInventory) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Receive multi-item supplier receipt");
        dialog.setHeaderText("Add up to 100 distinct parts. All stock, costs, receipt lines and ledger movements commit atomically.");
        ButtonType receiveType = new ButtonType("Post receipt", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(receiveType, ButtonType.CANCEL);

        ComboBox<LookupOption> supplier = new ComboBox<>(FXCollections.observableArrayList(suppliers));
        supplier.setPromptText("Supplier");
        if (defaultSupplier != null) supplier.setValue(suppliers.stream().filter(s -> s.id() == defaultSupplier.id()).findFirst().orElse(null));
        supplier.setMaxWidth(Double.MAX_VALUE);
        TextField invoice = new TextField();
        invoice.setPromptText("Optional supplier invoice / delivery note");

        TextField partQuery = new TextField();
        partQuery.setPromptText("Search SKU, barcode or part name");
        Button searchParts = new Button("Find parts");
        ComboBox<Part> partPicker = new ComboBox<>();
        partPicker.setPromptText("Select part");
        partPicker.setMaxWidth(Double.MAX_VALUE);
        partPicker.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Part part) { return part == null ? "" : part.sku() + " — " + part.name() + " (stock " + part.quantity() + ")"; }
            @Override public Part fromString(String value) { return null; }
        });
        TextField quantity = new TextField("1");
        quantity.setPromptText("Quantity");
        TextField unitCost = new TextField();
        unitCost.setPromptText("Unit cost ₹");
        Button addLine = new Button("Add line");
        Button removeLine = new Button("Remove selected");

        TableView<PurchaseCartLine> cart = new TableView<>();
        cart.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        cart.setPrefHeight(270);
        TableColumn<PurchaseCartLine, String> skuCol = new TableColumn<>("SKU / Part");
        skuCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().part().sku() + " — " + c.getValue().part().name()));
        TableColumn<PurchaseCartLine, String> qtyCol = new TableColumn<>("Qty");
        qtyCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(Integer.toString(c.getValue().quantity())));
        TableColumn<PurchaseCartLine, String> costCol = new TableColumn<>("Unit cost");
        costCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper("₹" + rupees(c.getValue().unitCostPaise())));
        TableColumn<PurchaseCartLine, String> totalCol = new TableColumn<>("Line total");
        totalCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper("₹" + rupees(c.getValue().lineTotalPaise())));
        cart.getColumns().addAll(skuCol, qtyCol, costCol, totalCol);
        Label totalLabel = new Label("0 line(s) • Total ₹0.00");
        Label searchStatus = new Label("Search for a part to add it to this receipt.");

        Runnable findParts = () -> {
            searchParts.setDisable(true);
            runBackground(() -> inventory.search(InventoryFilter.defaults(partQuery.getText())),
                    page -> {
                        partPicker.setItems(FXCollections.observableArrayList(page.items()));
                        searchStatus.setText(page.totalItems() + " matching part(s); first " + page.items().size() + " shown.");
                        searchParts.setDisable(false);
                    }, error -> {
                        searchParts.setDisable(false);
                        showError("Could not search inventory", error);
                    });
        };
        searchParts.setOnAction(e -> findParts.run());
        partQuery.setOnAction(e -> findParts.run());
        partPicker.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) unitCost.setText(rupees(selected.purchasePricePaise()));
        });
        addLine.setOnAction(e -> {
            try {
                Part selected = partPicker.getValue();
                if (selected == null) throw new IllegalArgumentException("Search and select a part.");
                if (cart.getItems().size() >= 100) throw new IllegalArgumentException("A receipt can contain at most 100 distinct parts.");
                if (cart.getItems().stream().anyMatch(line -> line.part().id() == selected.id())) {
                    throw new IllegalArgumentException("This part is already on the receipt. Remove it before adding it again.");
                }
                PurchaseCartLine line = new PurchaseCartLine(selected,
                        parseRequiredInteger(quantity.getText(), "Quantity received"),
                        parsePaise(unitCost.getText(), "Unit cost"));
                if (line.quantity() > 100_000) throw new IllegalArgumentException("Quantity cannot exceed 100,000 per part.");
                line.lineTotalPaise();
                cart.getItems().add(line);
                updatePurchaseCartTotal(cart, totalLabel);
            } catch (IllegalArgumentException | ArithmeticException ex) {
                showError("Check purchase line", ex);
            }
        });
        removeLine.setOnAction(e -> {
            PurchaseCartLine selected = cart.getSelectionModel().getSelectedItem();
            if (selected != null) cart.getItems().remove(selected);
            updatePurchaseCartTotal(cart, totalLabel);
        });

        GridPane form = new GridPane();
        form.setHgap(8); form.setVgap(8); form.setPadding(new Insets(12));
        form.add(new Label("Supplier"), 0, 0); form.add(supplier, 1, 0);
        form.add(new Label("Invoice reference"), 0, 1); form.add(invoice, 1, 1);
        form.add(new Label("Find part"), 0, 2); form.add(new HBox(8, partQuery, searchParts), 1, 2);
        form.add(new Label("Part"), 0, 3); form.add(partPicker, 1, 3);
        form.add(new Label("Quantity"), 0, 4); form.add(quantity, 1, 4);
        form.add(new Label("Unit cost ₹"), 0, 5); form.add(unitCost, 1, 5);
        form.add(new HBox(8, addLine, removeLine), 1, 6);
        form.add(searchStatus, 0, 7, 2, 1);
        form.add(cart, 0, 8, 2, 1);
        form.add(totalLabel, 0, 9, 2, 1);
        GridPane.setHgrow(supplier, Priority.ALWAYS); GridPane.setHgrow(invoice, Priority.ALWAYS);
        GridPane.setHgrow(partPicker, Priority.ALWAYS); GridPane.setHgrow(partQuery, Priority.ALWAYS);
        GridPane.setHgrow(cart, Priority.ALWAYS); GridPane.setVgrow(cart, Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefSize(860, 720);

        Button post = (Button) dialog.getDialogPane().lookupButton(receiveType);
        post.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            try {
                if (supplier.getValue() == null) throw new IllegalArgumentException("Choose a supplier.");
                if (cart.getItems().isEmpty()) throw new IllegalArgumentException("Add at least one part.");
                List<PurchaseLineDraft> lines = cart.getItems().stream()
                        .map(line -> new PurchaseLineDraft(line.part().id(), line.quantity(), line.unitCostPaise())).toList();
                PurchaseOrderDraft draft = new PurchaseOrderDraft(supplier.getValue().id(), invoice.getText(), lines);
                post.setDisable(true);
                runBackground(() -> purchasingService.receive(draft), receipt -> {
                    dialog.close();
                    status.setText("Receipt posted: " + receipt.purchaseNumber() + " • " + receipt.lineCount() + " line(s)");
                    refreshInventory.run();
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            "Receipt: " + receipt.purchaseNumber() + "\nSupplier: " + receipt.supplierName()
                                    + "\nDistinct parts: " + receipt.lineCount() + "\nUnits received: " + receipt.units()
                                    + "\nTotal: ₹" + rupees(receipt.totalPaise())
                                    + "\nAll lines, stock and ledger movements were committed together.", ButtonType.OK);
                    info.setTitle("Supplier receipt posted"); info.setHeaderText("Multi-item receipt completed"); info.show();
                }, error -> { post.setDisable(false); showError("Could not post supplier receipt", error); });
            } catch (IllegalArgumentException ex) {
                showError("Check receipt details", ex);
            }
        });
        dialog.show();
        findParts.run();
    }

    private static void updatePurchaseCartTotal(TableView<PurchaseCartLine> cart, Label label) {
        long total = 0;
        int units = 0;
        try {
            for (PurchaseCartLine line : cart.getItems()) {
                total = Math.addExact(total, line.lineTotalPaise());
                units = Math.addExact(units, line.quantity());
            }
            label.setText(cart.getItems().size() + " line(s) • " + units + " unit(s) • Total ₹" + rupees(total));
        } catch (ArithmeticException ex) {
            label.setText("Total exceeds supported amount");
        }
    }

    private record CartLine(Part part, int quantity, long unitPricePaise) {
        @Override public String toString() {
            String lineTotal;
            try { lineTotal = rupees(Math.multiplyExact((long) quantity, unitPricePaise)); }
            catch (ArithmeticException ex) { lineTotal = "amount too large"; }
            return part.sku() + " — " + part.name() + " • qty " + quantity
                    + " × ₹" + rupees(unitPricePaise) + " = ₹" + lineTotal;
        }
    }

    private void showVoidSaleDialog(Stage owner, Label status, Runnable refreshInventory) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Void sale and record refund");
        dialog.setHeaderText("Enter the original sale number. Only posted sales can be voided.");
        ButtonType voidType = new ButtonType("Void sale", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(voidType, ButtonType.CANCEL);

        TextField saleNumber = new TextField();
        saleNumber.setPromptText("e.g. MM-20261016-AB12CD34");
        TextField reason = new TextField();
        reason.setPromptText("Required audit reason");
        TextField refundReference = new TextField();
        refundReference.setPromptText("Optional cash voucher / UPI / card refund reference");
        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(10); form.setPadding(new Insets(12));
        form.add(new Label("Sale number"), 0, 0); form.add(saleNumber, 1, 0);
        form.add(new Label("Reason"), 0, 1); form.add(reason, 1, 1);
        form.add(new Label("Refund reference"), 0, 2); form.add(refundReference, 1, 2);
        GridPane.setHgrow(saleNumber, Priority.ALWAYS);
        GridPane.setHgrow(reason, Priority.ALWAYS);
        GridPane.setHgrow(refundReference, Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(560);

        Button voidButton = (Button) dialog.getDialogPane().lookupButton(voidType);
        voidButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (saleNumber.getText() == null || saleNumber.getText().isBlank()
                    || reason.getText() == null || reason.getText().isBlank()) {
                showError("Required fields missing", new IllegalArgumentException("Enter the sale number and a reason."));
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "This marks the sale VOIDED, restores its stock and records a full refund audit. It does NOT send money through a bank, UPI app or card terminal; process the actual refund separately. Continue?",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.initOwner(owner);
            confirm.setTitle("Confirm sale void");
            confirm.setHeaderText("This operation changes inventory and sales records");
            confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
                voidButton.setDisable(true);
                runBackground(() -> salesService.voidSale(saleNumber.getText(), reason.getText(), refundReference.getText()),
                        result -> {
                            dialog.close();
                            status.setText("Sale voided: " + result.saleNumber() + " • " + result.unitsRestored() + " unit(s) restored");
                            refreshInventory.run();
                            Alert info = new Alert(Alert.AlertType.INFORMATION,
                                    "Sale: " + result.saleNumber()
                                            + "\nUnits restored: " + result.unitsRestored()
                                            + "\nRefund recorded: ₹" + rupees(result.amountRefundedPaise())
                                            + "\nOriginal payment method: " + (result.refundMethod() == null ? "No payment recorded" : result.refundMethod())
                                            + "\nReason: " + result.reason()
                                            + "\n\nComplete the actual payment refund separately and retain its reference.", ButtonType.OK);
                            info.setTitle("Sale void recorded");
                            info.setHeaderText("Inventory and audit updated");
                            info.show();
                        }, error -> {
                            voidButton.setDisable(false);
                            showError("Could not void sale", error);
                        });
            });
        });
        dialog.showAndWait();
    }

    private void showSaleDialog(Stage owner, Part initialPart, Label status, Runnable refreshInventory) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Checkout sale");
        dialog.setHeaderText("Build a multi-item cart, review the total, then post the sale atomically.");
        ButtonType checkoutType = new ButtonType("Complete sale", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(checkoutType, ButtonType.CANCEL);

        var cart = FXCollections.<CartLine>observableArrayList(
                new CartLine(initialPart, 1, initialPart.sellingPricePaise()));
        ListView<CartLine> cartList = new ListView<>(cart);
        cartList.setPrefHeight(190);
        cartList.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(CartLine item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
        Button addItem = new Button("Add another part…");
        Button removeItem = new Button("Remove selected");
        TextField quantity = new TextField("1");
        TextField unitPrice = new TextField(rupees(initialPart.sellingPricePaise()));
        Button updateLine = new Button("Update selected line");
        TextField discount = new TextField("0.00");
        TextField customer = new TextField();
        customer.setPromptText("Optional customer name");
        ComboBox<String> paymentMethod = new ComboBox<>(FXCollections.observableArrayList("CASH", "UPI", "CARD", "BANK_TRANSFER"));
        paymentMethod.setValue("CASH");
        TextField reference = new TextField();
        reference.setPromptText("Optional payment reference");
        Label summary = new Label();
        summary.setWrapText(true);
        updateCartSummary(cart, discount.getText(), summary);

        cartList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            if (selected != null) {
                quantity.setText(Integer.toString(selected.quantity()));
                unitPrice.setText(rupees(selected.unitPricePaise()));
            }
        });
        addItem.setOnAction(e -> showPartPicker(owner,
                cart.stream().map(line -> line.part().id()).toList()).ifPresent(selected -> {
            cart.add(new CartLine(selected, 1, selected.sellingPricePaise()));
            cartList.getSelectionModel().select(cart.size() - 1);
            updateCartSummary(cart, discount.getText(), summary);
        }));
        removeItem.setOnAction(e -> {
            int selected = cartList.getSelectionModel().getSelectedIndex();
            if (selected >= 0) cart.remove(selected);
            updateCartSummary(cart, discount.getText(), summary);
        });
        updateLine.setOnAction(e -> {
            int selected = cartList.getSelectionModel().getSelectedIndex();
            if (selected < 0) { showError("Select a cart item", new IllegalArgumentException("Choose an item to update.")); return; }
            try {
                int qty = parseRequiredInteger(quantity.getText(), "Quantity");
                Long parsedPrice = parsePaise(unitPrice.getText(), "Unit price");
                if (parsedPrice == null) throw new IllegalArgumentException("Unit price is required.");
                long price = parsedPrice;
                CartLine existing = cart.get(selected);
                cart.set(selected, new CartLine(existing.part(), qty, price));
                updateCartSummary(cart, discount.getText(), summary);
            } catch (IllegalArgumentException ex) { showError("Check item details", ex); }
        });
        discount.textProperty().addListener((obs, oldValue, newValue) ->
                updateCartSummary(cart, newValue, summary));

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8); form.setPadding(new Insets(12));
        form.add(new Label("Items in cart"), 0, 0, 2, 1);
        form.add(cartList, 0, 1, 2, 1);
        HBox cartActions = new HBox(8, addItem, removeItem);
        form.add(cartActions, 0, 2, 2, 1);
        form.add(new Label("Selected quantity"), 0, 3); form.add(quantity, 1, 3);
        form.add(new Label("Unit price (₹)"), 0, 4); form.add(unitPrice, 1, 4);
        form.add(updateLine, 1, 5);
        form.add(new Label("Sale discount (₹)"), 0, 6); form.add(discount, 1, 6);
        form.add(new Label("Customer"), 0, 7); form.add(customer, 1, 7);
        form.add(new Label("Payment method"), 0, 8); form.add(paymentMethod, 1, 8);
        form.add(new Label("Payment reference"), 0, 9); form.add(reference, 1, 9);
        form.add(summary, 0, 10, 2, 1);
        GridPane.setHgrow(cartList, Priority.ALWAYS);
        GridPane.setHgrow(unitPrice, Priority.ALWAYS);
        GridPane.setHgrow(customer, Priority.ALWAYS);
        GridPane.setHgrow(paymentMethod, Priority.ALWAYS);
        GridPane.setHgrow(reference, Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(620);
        dialog.getDialogPane().setPrefHeight(610);

        Button checkout = (Button) dialog.getDialogPane().lookupButton(checkoutType);
        checkout.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            try {
                List<SaleLineDraft> lines = cart.stream()
                        .map(line -> new SaleLineDraft(line.part().id(), line.quantity(), line.unitPricePaise()))
                        .toList();
                Long parsedDiscount = parsePaise(discount.getText(), "Sale discount");
                CartSaleDraft draft = new CartSaleDraft(lines, parsedDiscount == null ? 0L : parsedDiscount,
                        customer.getText(), paymentMethod.getValue(), reference.getText());
                checkout.setDisable(true);
                runBackground(() -> salesService.checkout(draft), receipt -> {
                    dialog.close();
                    status.setText("Sale posted: " + receipt.saleNumber() + " • total ₹" + rupees(receipt.totalPaise()));
                    refreshInventory.run();
                    StringBuilder details = new StringBuilder("Sale: ").append(receipt.saleNumber()).append("\n");
                    for (SaleLineReceipt line : receipt.lines()) {
                        details.append(line.sku()).append(" — ").append(line.partName())
                                .append(" • ").append(line.quantity()).append(" × ₹")
                                .append(rupees(line.unitPricePaise())).append(" = ₹")
                                .append(rupees(line.lineTotalPaise())).append("\n");
                    }
                    details.append("Subtotal: ₹").append(rupees(receipt.subtotalPaise()))
                            .append("\nDiscount: ₹").append(rupees(receipt.discountPaise()))
                            .append("\nTotal paid: ₹").append(rupees(receipt.totalPaise()))
                            .append("\nPayment: ").append(receipt.paymentMethod());
                    Alert confirmation = new Alert(Alert.AlertType.INFORMATION, details.toString(), ButtonType.OK);
                    confirmation.setTitle("Sale completed");
                    confirmation.setHeaderText("Checkout successful");
                    confirmation.show();
                }, error -> {
                    checkout.setDisable(false);
                    showError("Checkout failed", error);
                });
            } catch (IllegalArgumentException ex) {
                showError("Check sale details", ex);
            }
        });
        dialog.showAndWait();
    }

    private java.util.Optional<Part> showPartPicker(Stage owner, List<Long> excludedPartIds) {
        Dialog<Part> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Add item to sale");
        dialog.setHeaderText("Search inventory and select a part to add to the cart.");
        ButtonType addType = new ButtonType("Add selected item", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);
        TextField search = new TextField();
        search.setPromptText("Search SKU, barcode, part name, brand or vehicle fitment");
        Button searchButton = new Button("Search");
        ListView<Part> results = new ListView<>(FXCollections.observableArrayList());
        results.setPrefSize(560, 300);
        results.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Part item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.sku() + " — " + item.name()
                        + " • stock " + item.quantity() + " • ₹" + rupees(item.sellingPricePaise()));
            }
        });
        Runnable find = () -> runBackground(() -> inventory.search(search.getText()), found -> {
            results.getItems().setAll(found.stream()
                    .filter(part -> part.quantity() > 0 && !excludedPartIds.contains(part.id())).toList());
            if (results.getItems().isEmpty()) showError("No matching stock", new IllegalArgumentException(
                    "No available inventory items matched. Try a more specific search or check stock levels."));
        }, error -> showError("Inventory search failed", error));
        searchButton.setOnAction(e -> find.run());
        search.setOnAction(e -> find.run());
        VBox content = new VBox(8, new HBox(8, search, searchButton), results);
        VBox.setVgrow(results, Priority.ALWAYS);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(640, 430);
        Button add = (Button) dialog.getDialogPane().lookupButton(addType);
        add.addEventFilter(ActionEvent.ACTION, event -> {
            Part selected = results.getSelectionModel().getSelectedItem();
            if (selected == null) {
                event.consume();
                showError("Select an item", new IllegalArgumentException("Choose an available item from the search results."));
                return;
            }
            dialog.setResult(selected);
            dialog.close();
        });
        find.run();
        return dialog.showAndWait();
    }

    private static void updateCartSummary(List<CartLine> cart, String discountText, Label summary) {
        try {
            long subtotal = 0;
            for (CartLine line : cart) {
                subtotal = Math.addExact(subtotal,
                        Math.multiplyExact((long) line.quantity(), line.unitPricePaise()));
            }
            Long parsedDiscount = parsePaise(discountText, "Sale discount");
            long discount = parsedDiscount == null ? 0L : parsedDiscount;
            long total = Math.max(0, subtotal - discount);
            summary.setText("Subtotal: ₹" + rupees(subtotal) + " • Discount: ₹" + rupees(discount)
                    + " • Total: ₹" + rupees(total) + (discount > subtotal ? " (discount exceeds subtotal)" : ""));
        } catch (RuntimeException ex) {
            summary.setText("Enter valid quantities, prices and discount to calculate the total.");
        }
    }

    private boolean showActivationDialog(Stage owner, boolean required, Label licenseStatusLabel) {
        LicenseStatus initial = licenseService.status();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("MechMotor activation");
        dialog.setHeaderText(required ? "Activation is required to use MechMotor." : "License and activation details");
        ButtonType activateType = new ButtonType("Activate", ButtonBar.ButtonData.OK_DONE);
        ButtonType closeType = new ButtonType(required ? "Exit" : "Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(activateType, closeType);

        Label status = new Label(initial.active()
                ? "Status: Active — " + initial.message()
                : "Status: Not active — " + initial.message());
        Label path = new Label("Activation key file: " + initial.keyFile());
        path.setWrapText(true);
        Label licenseDetails = new Label(initial.active()
                ? "License ID: " + initial.licenseId() + "\nCustomer: " + initial.customer()
                    + "\nExpires: " + (initial.expiresOn().equals(LocalDate.MAX) ? "Never" : initial.expiresOn())
                    + "\nFeatures: " + String.join(", ", initial.features().stream().sorted().toList())
                : "No valid license details are available.");
        TextArea storedKey = new TextArea();
        storedKey.setEditable(false);
        storedKey.setWrapText(true);
        storedKey.setPrefRowCount(2);
        String currentKey = initial.activationKey();
        storedKey.setText(maskActivationKey(currentKey));
        CheckBox reveal = new CheckBox("Reveal stored activation key");
        reveal.setOnAction(e -> storedKey.setText(reveal.isSelected() ? currentKey : maskActivationKey(currentKey)));
        TextArea newKey = new TextArea();
        newKey.setPromptText("Paste the signed MM1 activation key here");
        newKey.setWrapText(true);
        newKey.setPrefRowCount(3);

        VBox content = new VBox(8, status, licenseDetails, path,
                new Label("Stored activation key"), storedKey, reveal,
                new Label("Enter a new activation key"), newKey);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(680);
        Button activate = (Button) dialog.getDialogPane().lookupButton(activateType);
        activate.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                LicenseStatus activated = licenseService.activate(newKey.getText());
                updateLicenseLabel(activated, licenseStatusLabel);
            } catch (Exception ex) {
                event.consume();
                showError("Activation failed", ex);
            }
        });
        dialog.showAndWait();
        LicenseStatus after = licenseService.status();
        updateLicenseLabel(after, licenseStatusLabel);
        return after.active() || !required;
    }

    private static String maskActivationKey(String key) {
        if (key == null || key.isBlank()) return "(no activation key installed)";
        if (key.length() <= 20) return "•".repeat(key.length());
        return key.substring(0, 10) + "…" + key.substring(key.length() - 8);
    }

    private static void updateLicenseLabel(LicenseStatus status, Label label) {
        if (!status.active()) {
            label.setText("License: Not activated");
            label.getStyleClass().remove("license-active");
            if (!label.getStyleClass().contains("license-inactive")) label.getStyleClass().add("license-inactive");
            return;
        }
        String expiry = status.expiresOn().equals(LocalDate.MAX) ? "Never" : status.expiresOn().toString();
        label.setText("License: Active · " + status.licenseId() + " · Expires " + expiry);
        label.getStyleClass().remove("license-inactive");
        if (!label.getStyleClass().contains("license-active")) label.getStyleClass().add("license-active");
    }

    private VBox metricCard(String title, Label value) {
        Label label = new Label(title);
        label.getStyleClass().add("metric-label");
        VBox card = new VBox(5, label, value);
        card.getStyleClass().add("metric-card");
        card.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void refreshDashboard(Label activeParts, Label totalUnits, Label lowStock,
                                  Label outOfStock, Label retailValue) {
        runBackground(() -> inventory.dashboardMetrics(), metrics -> {
            activeParts.setText(String.format("%,d", metrics.activeParts()));
            totalUnits.setText(String.format("%,d", metrics.totalUnits()));
            lowStock.setText(String.format("%,d", metrics.lowStockParts()));
            outOfStock.setText(String.format("%,d", metrics.outOfStockParts()));
            retailValue.setText("₹" + rupees(metrics.retailStockValuePaise()));
        }, error -> showError("Could not refresh dashboard metrics", error));
    }

    private void refreshSavedViews(ComboBox<LookupOption> selector, Label status, Long selectId) {
        runBackground(() -> inventory.savedViews(), views -> {
            selector.setItems(FXCollections.observableArrayList(views));
            if (selectId != null) {
                selector.setValue(views.stream().filter(view -> view.id() == selectId).findFirst().orElse(null));
            }
            status.setText(views.size() + " saved inventory view(s) available.");
        }, error -> showError("Could not refresh saved views", error));
    }

    private void refreshLookups(ComboBox<LookupOption> categoryFilter,
                                ComboBox<LookupOption> supplierFilter, Runnable reload, Label status) {
        runBackground(() -> new Lookups(inventory.categories(), inventory.suppliers()), lookups -> {
            categories = lookups.categories(); suppliers = lookups.suppliers();
            categoryFilter.setItems(FXCollections.observableArrayList(categories));
            supplierFilter.setItems(FXCollections.observableArrayList(suppliers));
            status.setText("Categories and suppliers refreshed.");
            reload.run();
        }, error -> showError("Could not refresh categories and suppliers", error));
    }

    private void showFitmentDialog(Stage owner, Part part, List<VehicleFitment> initial, Label status) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Vehicle fitment — " + part.sku());
        dialog.setHeaderText("Compatible vehicles for " + part.name());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<VehicleFitment> fitmentList = new ListView<>(FXCollections.observableArrayList(initial));
        fitmentList.setPrefHeight(160);
        TextField make = field("", "e.g. Toyota");
        TextField model = field("", "e.g. Corolla");
        TextField yearFrom = field("", "e.g. 2018");
        TextField yearTo = field("", "e.g. 2024");
        TextField engine = field("", "Optional engine");
        TextField trim = field("", "Optional trim");
        TextField notes = field("", "Optional notes");
        GridPane form = new GridPane();
        form.setHgap(8); form.setVgap(8); form.setPadding(new Insets(10));
        addFormRow(form, 0, "Make *", make, "Model *", model);
        addFormRow(form, 1, "Year from *", yearFrom, "Year to *", yearTo);
        addFormRow(form, 2, "Engine", engine, "Trim", trim);
        form.add(new Label("Notes"), 0, 3); form.add(notes, 1, 3, 3, 1);
        GridPane.setHgrow(notes, Priority.ALWAYS);
        Button add = new Button("Add fitment");
        Button remove = new Button("Remove selected");
        HBox buttons = new HBox(8, add, remove);
        VBox content = new VBox(10, new Label("Existing fitments"), fitmentList, form, buttons);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(720);

        add.setOnAction(e -> {
            try {
                VehicleFitmentDraft draft = new VehicleFitmentDraft(make.getText(), model.getText(),
                        parseRequiredInteger(yearFrom.getText(), "Start year"),
                        parseRequiredInteger(yearTo.getText(), "End year"), engine.getText(), trim.getText(), notes.getText());
                add.setDisable(true);
                runBackground(() -> inventory.addFitment(part.id(), draft), id -> {
                    make.clear(); model.clear(); yearFrom.clear(); yearTo.clear(); engine.clear(); trim.clear(); notes.clear();
                    status.setText("Vehicle fitment added.");
                    refreshFitmentList(part.id(), fitmentList, add, status);
                }, error -> { add.setDisable(false); showError("Could not add vehicle fitment", error); });
            } catch (IllegalArgumentException ex) {
                showError("Check vehicle fitment details", ex);
            }
        });
        remove.setOnAction(e -> {
            VehicleFitment selected = fitmentList.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a fitment", new IllegalArgumentException("Choose a fitment to remove."));
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Remove compatibility with " + selected + "?", ButtonType.CANCEL, ButtonType.OK);
            confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
            confirm.setTitle("Remove vehicle fitment");
            confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(button ->
                    runBackground(() -> { inventory.removeFitment(part.id(), selected.id()); return Boolean.TRUE; }, ignored -> {
                        status.setText("Vehicle fitment removed.");
                        refreshFitmentList(part.id(), fitmentList, add, status);
                    }, error -> showError("Could not remove vehicle fitment", error)));
        });
        dialog.showAndWait();
    }

    private void refreshFitmentList(long partId, ListView<VehicleFitment> list, Button add, Label status) {
        runBackground(() -> inventory.fitments(partId), fitments -> {
            list.setItems(FXCollections.observableArrayList(fitments));
            add.setDisable(false);
        }, error -> { add.setDisable(false); showError("Could not refresh vehicle fitments", error); });
    }

    private void showSupplierDialog(Stage owner, SupplierDetails existing,
                                    ComboBox<LookupOption> categoryFilter, ComboBox<LookupOption> supplierFilter,
                                    Runnable reload, Label status) {
        Dialog<SupplierDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Add supplier" : "Edit supplier");
        dialog.setHeaderText(existing == null ? "Create a supplier record" : "Update supplier contact details");
        ButtonType saveType = new ButtonType(existing == null ? "Create supplier" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        TextField name = field(existing == null ? "" : existing.name(), "Required supplier name");
        TextField phone = field(existing == null ? "" : existing.phone(), "Phone / contact number");
        TextField code = field(existing == null ? "" : existing.supplierCode(), "Optional unique supplier code");
        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(9); form.setPadding(new Insets(12));
        form.add(new Label("Supplier name *"), 0, 0); form.add(name, 1, 0);
        form.add(new Label("Phone"), 0, 1); form.add(phone, 1, 1);
        form.add(new Label("Supplier code"), 0, 2); form.add(code, 1, 2);
        GridPane.setHgrow(name, Priority.ALWAYS); GridPane.setHgrow(phone, Priority.ALWAYS); GridPane.setHgrow(code, Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(500);
        java.util.function.Supplier<SupplierDraft> draftSupplier =
                () -> new SupplierDraft(name.getText(), phone.getText(), code.getText());
        Button save = (Button) dialog.getDialogPane().lookupButton(saveType);
        save.addEventFilter(ActionEvent.ACTION, event -> {
            try { draftSupplier.get().validate(); }
            catch (IllegalArgumentException ex) {
                event.consume();
                showError("Please correct supplier details", ex);
            }
        });
        dialog.setResultConverter(button -> button == saveType ? draftSupplier.get() : null);
        dialog.showAndWait().ifPresent(draft -> {
            if (existing == null) {
                runBackground(() -> inventory.createSupplier(draft), id -> {
                    status.setText("Supplier saved."); refreshLookups(categoryFilter, supplierFilter, reload, status);
                }, error -> showError("Could not save supplier", error));
            } else {
                runBackground(() -> { inventory.updateSupplier(existing.id(), draft); return Boolean.TRUE; }, ignored -> {
                    supplierFilter.setValue(null);
                    status.setText("Supplier updated."); refreshLookups(categoryFilter, supplierFilter, reload, status);
                }, error -> showError("Could not update supplier", error));
            }
        });
    }

    private void showPartDialog(Stage owner, PartDetails existing, Runnable onSaved, Label status) {
        Dialog<PartDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(existing == null ? "Add inventory part" : "Edit inventory part");
        dialog.setHeaderText(existing == null ? "Enter part details" : "Update part details");
        ButtonType saveType = new ButtonType(existing == null ? "Create part" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField sku = field(existing == null ? "" : existing.sku(), "Required, unique SKU");
        TextField barcode = field(existing == null ? "" : existing.barcode(), "Optional, unique barcode");
        TextField partNumber = field(existing == null ? "" : existing.partNumber(), "Part number");
        TextField name = field(existing == null ? "" : existing.name(), "Required part name");
        TextField brand = field(existing == null ? "" : existing.brand(), "Brand");
        TextField company = field(existing == null ? "" : existing.company(), "Company / manufacturer");
        TextField material = field(existing == null ? "" : existing.material(), "Material");
        TextField color = field(existing == null ? "" : existing.color(), "Color");
        TextField purchasePrice = field(existing == null ? "0.00" : rupees(existing.purchasePricePaise()), "₹");
        TextField sellingPrice = field(existing == null ? "0.00" : rupees(existing.sellingPricePaise()), "₹");
        TextField quantity = field(existing == null ? "0" : Integer.toString(existing.quantity()), "Whole number");
        TextField reorder = field(existing == null ? "0" : Integer.toString(existing.reorderLevel()), "Low-stock threshold");
        ComboBox<LookupOption> category = new ComboBox<>(FXCollections.observableArrayList(categories));
        category.setPromptText("No category"); category.setMaxWidth(Double.MAX_VALUE);
        ComboBox<LookupOption> supplier = new ComboBox<>(FXCollections.observableArrayList(suppliers));
        supplier.setPromptText("No supplier"); supplier.setMaxWidth(Double.MAX_VALUE);
        if (existing != null) {
            category.setValue(categories.stream().filter(o -> existing.categoryId() != null && o.id() == existing.categoryId()).findFirst().orElse(null));
            supplier.setValue(suppliers.stream().filter(o -> existing.supplierId() != null && o.id() == existing.supplierId()).findFirst().orElse(null));
        }
        GridPane form = new GridPane(); form.setHgap(10); form.setVgap(9); form.setPadding(new Insets(10));
        int row = 0;
        row = addFormRow(form, row, "SKU *", sku, "Barcode", barcode);
        row = addFormRow(form, row, "Part number", partNumber, "Part name *", name);
        row = addFormRow(form, row, "Brand", brand, "Company", company);
        row = addFormRow(form, row, "Material", material, "Color", color);
        row = addFormRow(form, row, "Category", category, "Supplier", supplier);
        row = addFormRow(form, row, "Purchase price (₹)", purchasePrice, "Selling price (₹)", sellingPrice);
        addFormRow(form, row, "Opening / current quantity", quantity, "Reorder level", reorder);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(760);
        java.util.function.Supplier<PartDraft> draftSupplier = () -> {
            PartDraft draft = new PartDraft(sku.getText(), barcode.getText(), partNumber.getText(), name.getText(),
                    brand.getText(), company.getText(), material.getText(), color.getText(),
                    category.getValue() == null ? null : category.getValue().id(),
                    supplier.getValue() == null ? null : supplier.getValue().id(),
                    purchasePrice.getText(), sellingPrice.getText(),
                    parseRequiredInteger(quantity.getText(), "Quantity"),
                    parseRequiredInteger(reorder.getText(), "Reorder level"));
            draft.purchasePricePaise();
            draft.sellingPricePaise();
            return draft;
        };
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                draftSupplier.get();
            } catch (IllegalArgumentException ex) {
                event.consume();
                showError("Please correct the part details", ex);
            }
        });
        dialog.setResultConverter(button -> button == saveType ? draftSupplier.get() : null);
        dialog.showAndWait().ifPresent(draft -> {
            if (existing == null) {
                runBackground(() -> inventory.create(draft), id -> {
                    status.setText("Part created (ID " + id + ")."); onSaved.run();
                }, error -> showError("Could not create part", error));
            } else {
                runBackground(() -> { inventory.update(existing.id(), draft); return Boolean.TRUE; }, ignored -> {
                    status.setText("Part updated."); onSaved.run();
                }, error -> showError("Could not update part", error));
            }
        });
    }

    private static int addFormRow(GridPane grid, int row, String label1, Control field1, String label2, Control field2) {
        grid.add(new Label(label1), 0, row); grid.add(field1, 1, row);
        grid.add(new Label(label2), 2, row); grid.add(field2, 3, row);
        GridPane.setHgrow(field1, Priority.ALWAYS); GridPane.setHgrow(field2, Priority.ALWAYS);
        return row + 1;
    }

    private <T> void runBackground(CheckedSupplier<T> work, java.util.function.Consumer<T> success,
                                   java.util.function.Consumer<Throwable> failure) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return work.get(); }
        };
        task.setOnSucceeded(e -> success.accept(task.getValue()));
        task.setOnFailed(e -> failure.accept(task.getException()));
        databaseExecutor.execute(task);
    }

    private static TextField field(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt); return field;
    }

    private static void addColumn(TableView<Part> table, String title, String property) {
        TableColumn<Part, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().displayValue(property)));
        table.getColumns().add(column);
    }

    private static Integer parseInteger(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        return parseRequiredInteger(raw, label);
    }
    private static int parseRequiredInteger(String raw, String label) {
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < 0) throw new IllegalArgumentException(label + " cannot be negative.");
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
    }
    private static Long parsePaise(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal amount = new BigDecimal(raw.strip());
            if (amount.signum() < 0 || amount.scale() > 2) throw new IllegalArgumentException(label + " must be non-negative with at most two decimal places.");
            return amount.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException(label + " must be a valid amount with at most two decimal places.");
        }
    }
    private static String rupees(long paise) { return BigDecimal.valueOf(paise, 2).toPlainString(); }
    private static void showError(String title, Throwable error) {
        String message = error == null || error.getMessage() == null ? "Unexpected error." : error.getMessage();
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title); alert.setHeaderText(title); alert.show();
    }

    @Override public void stop() {
        databaseExecutor.shutdownNow();
        if (context != null) context.close();
        Platform.exit();
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    private record Lookups(List<LookupOption> categories, List<LookupOption> suppliers) { }
}
