package com.mechmotor;

import com.mechmotor.domain.*;
import com.mechmotor.service.InventoryService;
import com.mechmotor.service.DatabaseBackupService;
import com.mechmotor.service.InventoryCsvService;
import com.mechmotor.service.LicenseService;
import com.mechmotor.service.LicenseStatus;
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
        HBox actions = new HBox(8, addButton, editButton, deactivateButton, fitmentButton, categoryMenu, supplierMenu, backupButton, restoreButton, csvMenu,
                new Separator(), previousButton, nextButton, pageLabel);
        actions.setPadding(new Insets(8, 20, 8, 20));
        HBox.setHgrow(pageLabel, Priority.ALWAYS);
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
