package com.mechmotor;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import com.mechmotor.domain.Part;
import com.mechmotor.service.InventoryService;

@SpringBootApplication
public class MechMotorApplication extends Application {
    private ConfigurableApplicationContext context;

    public static void main(String[] args) { launch(args); }

    @Override public void init() {
        context = new SpringApplicationBuilder(MechMotorApplication.class)
                .web(WebApplicationType.NONE).run();
    }

    @Override public void start(Stage stage) {
        InventoryService inventory = context.getBean(InventoryService.class);
        Label brand = new Label("MECHMOTOR 1.0");
        brand.getStyleClass().add("brand");
        Label subtitle = new Label("Vehicle parts • Inventory workspace");
        TextField search = new TextField();
        search.setPromptText("Search SKU, barcode, part, brand, material, color, vehicle or supplier…");
        search.setPrefWidth(720);
        Button searchButton = new Button("Search");
        Button clearButton = new Button("Clear");
        Label status = new Label("Ready • Local database");
        TableView<Part> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No parts found. Add inventory or change your search."));
        addColumn(table, "SKU", "sku");
        addColumn(table, "Part name", "name");
        addColumn(table, "Brand / Company", "brand");
        addColumn(table, "Material", "material");
        addColumn(table, "Color", "color");
        addColumn(table, "Category", "category");
        addColumn(table, "Qty", "quantity");
        addColumn(table, "Selling price", "sellingPrice");
        Runnable runSearch = () -> {
            searchButton.setDisable(true);
            status.setText("Searching local inventory…");
            String query = search.getText();
            javafx.concurrent.Task<java.util.List<Part>> task = new javafx.concurrent.Task<>() {
                @Override protected java.util.List<Part> call() { return inventory.search(query); }
            };
            task.setOnSucceeded(e -> {
                table.setItems(FXCollections.observableArrayList(task.getValue()));
                status.setText(task.getValue().size() + " result(s) • Offline-ready");
                searchButton.setDisable(false);
            });
            task.setOnFailed(e -> {
                status.setText("Search failed. Check the local database and logs.");
                new Alert(Alert.AlertType.ERROR, "Could not search inventory: " +
                        task.getException().getMessage(), ButtonType.OK).show();
                searchButton.setDisable(false);
            });
            Thread worker = new Thread(task, "mechmotor-search");
            worker.setDaemon(true);
            worker.start();
        };
        searchButton.setOnAction(e -> runSearch.run());
        search.setOnAction(e -> runSearch.run());
        clearButton.setOnAction(e -> { search.clear(); runSearch.run(); });
        HBox searchBar = new HBox(10, search, searchButton, clearButton);
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox header = new VBox(5, brand, subtitle);
        header.setPadding(new Insets(20));
        header.getStyleClass().add("header");
        HBox toolbar = new HBox(10, searchBar);
        toolbar.setPadding(new Insets(16, 20, 10, 20));
        VBox root = new VBox(0, header, toolbar, table, status);
        root.setSpacing(0);
        VBox.setVgrow(table, Priority.ALWAYS);
        status.setPadding(new Insets(12, 20, 12, 20));
        Scene scene = new Scene(root, 1180, 720);
        var css = getClass().getResource("/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle("MechMotor 1.0 — Vehicle Parts Shop");
        stage.setMinWidth(850);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.show();
        runSearch.run();
    }

    private static void addColumn(TableView<Part> table, String title, String property) {
        TableColumn<Part, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().displayValue(property)));
        table.getColumns().add(column);
    }

    @Override public void stop() {
        if (context != null) context.close();
        Platform.exit();
    }
}
