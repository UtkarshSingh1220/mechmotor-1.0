package com.mechmotor;

import com.mechmotor.domain.*;
import com.mechmotor.service.InventoryService;
import com.mechmotor.service.DatabaseBackupService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
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
    private final List<LookupOption> noOptions = List.of();
    private List<LookupOption> categories = noOptions;
    private List<LookupOption> suppliers = noOptions;

    @Override public void init() {
        context = new SpringApplicationBuilder(MechMotorApplication.class)
                .web(WebApplicationType.NONE).run();
    }

    @Override public void start(Stage stage) {
        inventory = context.getBean(InventoryService.class);
        backupService = context.getBean(DatabaseBackupService.class);
        Label brand = new Label("MECHMOTOR 1.0");
        brand.getStyleClass().add("brand");
        Label subtitle = new Label("Vehicle parts • Inventory workspace");
        Label status = new Label("Connecting to local inventory…");
        status.getStyleClass().add("status");

        TextField query = new TextField();
        query.setPromptText("Search SKU, barcode, part, brand, company, material, color, category or supplier");
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
        Button addCategoryButton = new Button("+ Category");
        Button addSupplierButton = new Button("+ Supplier");
        Button backupButton = new Button("Backup database");
        Button restoreButton = new Button("Restore backup");
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
        sort.setOnAction(e -> { page[0] = 0; loadPage.run(); });

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

        addCategoryButton.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Add category"); dialog.setHeaderText("Create inventory category");
            dialog.setContentText("Category name:");
            dialog.showAndWait().ifPresent(name -> runBackground(() -> inventory.createCategory(name), id -> {
                status.setText("Category created."); refreshLookups(categoryFilter, supplierFilter, loadPage, status);
            }, error -> showError("Could not create category", error)));
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

        addSupplierButton.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Add supplier"); dialog.setHeaderText("Create supplier");
            dialog.setContentText("Supplier name:");
            dialog.showAndWait().ifPresent(name -> runBackground(() -> inventory.createSupplier(name), id -> {
                status.setText("Supplier created."); refreshLookups(categoryFilter, supplierFilter, loadPage, status);
            }, error -> showError("Could not create supplier", error)));
        });

        GridPane filters = new GridPane();
        filters.setHgap(8); filters.setVgap(8); filters.setPadding(new Insets(12, 20, 12, 20));
        filters.add(query, 0, 0, 4, 1); filters.add(searchButton, 4, 0); filters.add(clearButton, 5, 0);
        filters.add(brandFilter, 0, 1); filters.add(materialFilter, 1, 1); filters.add(colorFilter, 2, 1);
        filters.add(categoryFilter, 3, 1); filters.add(supplierFilter, 4, 1); filters.add(stockFilter, 5, 1);
        filters.add(minQty, 0, 2); filters.add(maxQty, 1, 2); filters.add(minPrice, 2, 2);
        filters.add(maxPrice, 3, 2); filters.add(new Label("Sort:"), 4, 2); filters.add(sort, 5, 2);
        for (int i = 0; i < 6; i++) GridPane.setHgrow(filters.getChildren().get(i), Priority.ALWAYS);
        HBox actions = new HBox(8, addButton, editButton, deactivateButton, addCategoryButton, addSupplierButton, backupButton, restoreButton,
                new Separator(), previousButton, nextButton, pageLabel);
        actions.setPadding(new Insets(8, 20, 8, 20));
        HBox.setHgrow(pageLabel, Priority.ALWAYS);
        VBox header = new VBox(5, brand, subtitle);
        header.setPadding(new Insets(20)); header.getStyleClass().add("header");
        VBox root = new VBox(0, header, filters, actions, table, status);
        VBox.setVgrow(table, Priority.ALWAYS);
        status.setPadding(new Insets(12, 20, 12, 20));
        Scene scene = new Scene(root, 1380, 820);
        var css = getClass().getResource("/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle("MechMotor 1.0 — Vehicle Parts Shop");
        stage.setMinWidth(1000); stage.setMinHeight(650); stage.setScene(scene); stage.show();

        runBackground(() -> new Lookups(inventory.categories(), inventory.suppliers()), lookups -> {
            categories = lookups.categories(); suppliers = lookups.suppliers();
            categoryFilter.setItems(FXCollections.observableArrayList(categories));
            supplierFilter.setItems(FXCollections.observableArrayList(suppliers));
            loadPage.run();
        }, error -> { status.setText("Could not load categories and suppliers."); showError("Database initialization failed", error); });
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
