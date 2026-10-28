package com.mechmotor;

import com.mechmotor.service.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Native JavaFX user-directory, Google connection, and report delivery controls. */
public final class AdministrationDialogs {
    private AdministrationDialogs() {}

    private static <T> void async(Callable<T> operation, Consumer<T> success, Consumer<Throwable> error) {
        Task<T> task = new Task<>() { @Override protected T call() throws Exception { return operation.call(); } };
        task.setOnSucceeded(event -> success.accept(task.getValue()));
        task.setOnFailed(event -> error.accept(task.getException()));
        Thread thread = new Thread(task, "mechmotor-admin-operation");
        thread.setDaemon(true);
        thread.start();
    }

    public static void showUsers(Window owner, WorkspaceAdministrationService service) {
        StageGuard.assertFxThread();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Settings — Users & Roles");
        dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TableView<WorkspaceAdministrationService.WorkspaceUser> table = new TableView<>();
        table.setPrefSize(760, 370);
        table.getColumns().addAll(
                col("Name", WorkspaceAdministrationService.WorkspaceUser::name),
                col("Email", WorkspaceAdministrationService.WorkspaceUser::email),
                col("Directory role", WorkspaceAdministrationService.WorkspaceUser::role),
                col("Status", u -> u.active() ? "Active" : "Inactive"));
        Label note = new Label("These are local directory records, not application login accounts or enforced permissions.");
        note.setWrapText(true);
        Button add = new Button("+ Add User");
        Button edit = new Button("Update User");
        Button deactivate = new Button("Deactivate User");
        Button reactivate = new Button("Reactivate User");
        Runnable refresh = () -> async(service::users,
                users -> table.setItems(FXCollections.observableArrayList(users)), AdministrationDialogs::error);
        add.setOnAction(e -> editUser(owner, service, null, refresh));
        edit.setOnAction(e -> {
            var user = table.getSelectionModel().getSelectedItem();
            if (user != null) editUser(owner, service, user, refresh);
        });
        deactivate.setOnAction(e -> setUserActive(owner, service, table.getSelectionModel().getSelectedItem(), false, refresh));
        reactivate.setOnAction(e -> setUserActive(owner, service, table.getSelectionModel().getSelectedItem(), true, refresh));
        VBox content = new VBox(12, note, table, new FlowPane(9, 9, add, edit, deactivate, reactivate));
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        refresh.run();
        dialog.showAndWait();
    }

    private static TableColumn<WorkspaceAdministrationService.WorkspaceUser, String> col(
            String title, java.util.function.Function<WorkspaceAdministrationService.WorkspaceUser, String> getter) {
        TableColumn<WorkspaceAdministrationService.WorkspaceUser, String> column = new TableColumn<>(title);
        column.setCellValueFactory(v -> new SimpleStringProperty(getter.apply(v.getValue())));
        column.setPrefWidth(175);
        return column;
    }

    private static void editUser(Window owner, WorkspaceAdministrationService service,
                                 WorkspaceAdministrationService.WorkspaceUser current, Runnable refresh) {
        Dialog<ButtonType> editor = new Dialog<>();
        editor.initOwner(owner);
        editor.setTitle(current == null ? "Add User" : "Update User");
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        editor.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
        TextField name = new TextField(current == null ? "" : current.name());
        TextField email = new TextField(current == null ? "" : current.email());
        ComboBox<String> role = new ComboBox<>(FXCollections.observableArrayList("ADMIN","STAFF","VIEWER"));
        role.setValue(current == null ? "STAFF" : current.role());
        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(10);
        fields.addRow(0,new Label("Full name"),name);
        fields.addRow(1,new Label("Email"),email);
        fields.addRow(2,new Label("Directory role"),role);
        fields.setPadding(new Insets(18));
        editor.getDialogPane().setContent(fields);
        editor.setResultConverter(button -> button == save ? button : null);
        editor.showAndWait().ifPresent(button ->
                async(() -> { service.save(current == null ? null : current.id(),
                                name.getText(),email.getText(),role.getValue()); return true; },
                        ok -> refresh.run(), AdministrationDialogs::error));
    }

    private static void setUserActive(Window owner, WorkspaceAdministrationService service,
                                      WorkspaceAdministrationService.WorkspaceUser user,
                                      boolean active, Runnable refresh) {
        if (user == null) return;
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                (active ? "Reactivate " : "Deactivate ") + user.email() + "?",ButtonType.CANCEL,ButtonType.OK);
        confirmation.initOwner(owner);
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
        async(() -> { if (active) service.reactivate(user.id()); else service.deactivate(user.id()); return true; },
                ok -> refresh.run(), AdministrationDialogs::error);
    }

    public static void showGmail(Window owner, WorkspaceAdministrationService preferences,
                                 GmailReportSender sender) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Settings — Gmail & Report Delivery");
        dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TextField defaultRecipient = new TextField();
        defaultRecipient.setPromptText("Default recipient email address");
        Label connection = new Label(sender.connected() ? "Connected for this session" : "Not connected");
        Label instruction = new Label(
                "Google authorization uses Gmail send-only permission. Set the environment variable " +
                "MECHMOTOR_GOOGLE_CLIENT_ID to your OAuth Desktop client ID before launching MechMotor. " +
                "Tokens remain in memory and must be re-authorized after restart.");
        instruction.setWrapText(true);
        Button save = new Button("Save Default Email");
        Button connect = new Button("Connect Google / Gmail");
        Button disconnect = new Button("Disconnect");
        save.setOnAction(e -> async(() -> {
            preferences.saveDefaultRecipient(defaultRecipient.getText()); return true;
        }, ok -> info("Saved default report recipient."), AdministrationDialogs::error));
        connect.setOnAction(e -> {
            connect.setDisable(true);
            connection.setText("Opening Google sign-in...");
            async(sender::connect, message -> {
                connection.setText(message);
                connect.setDisable(false);
            }, throwable -> {
                connect.setDisable(false);
                connection.setText("Not connected");
                error(throwable);
            });
        });
        disconnect.setOnAction(e -> { sender.disconnect(); connection.setText("Disconnected"); });
        VBox content = new VBox(12, new Label("Default report recipient"),defaultRecipient,
                new Label("Google Gmail"),connection,connect,disconnect,save,instruction);
        content.setPadding(new Insets(18));
        content.setPrefWidth(550);
        dialog.getDialogPane().setContent(content);
        async(preferences::defaultRecipient,defaultRecipient::setText,AdministrationDialogs::error);
        dialog.showAndWait();
    }

    public static void showReports(Window owner, WorkspaceAdministrationService preferences,
                                   GmailReportSender sender, OperationalReportsService reports) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Reports — Generate, Download & Send");
        dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ComboBox<OperationalReportsService.ReportType> type = new ComboBox<>(
                FXCollections.observableArrayList(OperationalReportsService.ReportType.values()));
        type.setValue(OperationalReportsService.ReportType.SALES);
        RadioButton today = new RadioButton("Today (default)");
        RadioButton range = new RadioButton("Custom date range");
        ToggleGroup mode = new ToggleGroup();
        today.setToggleGroup(mode);
        range.setToggleGroup(mode);
        today.setSelected(true);
        DatePicker from = new DatePicker(LocalDate.now());
        DatePicker through = new DatePicker(LocalDate.now());
        from.disableProperty().bind(range.selectedProperty().not());
        through.disableProperty().bind(range.selectedProperty().not());
        TextField specific = new TextField();
        specific.setPromptText("Recipient email (optional when a default is configured)");
        CheckBox both = new CheckBox("Both: send the generated report to specified AND default email");
        Button generate = new Button("Generate & Download");
        Button send = new Button("Generate & Send Email");
        Label status = new Label("Defaults to today's report. No report will be sent without clicking an action.");
        status.setWrapText(true);
        GridPane fields = new GridPane();
        fields.setHgap(12);fields.setVgap(11);
        fields.addRow(0,new Label("Report type"),type);
        fields.addRow(1,new Label("Date"),new HBox(12,today,range));
        fields.addRow(2,new Label("From"),from);
        fields.addRow(3,new Label("To"),through);
        fields.addRow(4,new Label("Email"),specific);
        fields.add(both,1,5);
        fields.setPadding(new Insets(18));
        specific.setPrefWidth(355);
        Runnable[] runGenerate = new Runnable[1];
        java.util.function.Consumer<Boolean> execute = emailOnly -> {
            LocalDate start = range.isSelected() ? from.getValue() : LocalDate.now();
            LocalDate end = range.isSelected() ? through.getValue() : LocalDate.now();
            if (start == null || end == null || start.isAfter(end)) {
                error(new IllegalArgumentException("Choose a valid date range."));return;
            }
            if (!emailOnly && both.isSelected() && !sender.connected()) {
                error(new IllegalStateException("Connect Gmail in Settings before sending to both recipients."));
                return;
            }
            Path destination = null;
            if (!emailOnly) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Save generated MechMotor report");
                chooser.setInitialFileName("mechmotor-" + type.getValue().name().toLowerCase() +
                        "-" + start + "-to-" + end + ".csv");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV reports","*.csv"));
                java.io.File chosen = chooser.showSaveDialog(owner);
                if (chosen == null) return;
                destination = chosen.toPath();
            }
            final Path saveTo = destination;
            generate.setDisable(true);send.setDisable(true);
            status.setText(emailOnly ? "Generating and sending report..." : "Generating report...");
            async(() -> {
                Path file = saveTo == null ? Files.createTempFile("mechmotor-report-",".csv") : saveTo;
                boolean temp = saveTo == null;
                try {
                    long rows = reports.export(type.getValue(),file,start,end);
                    boolean mustSend = emailOnly || both.isSelected();
                    if (mustSend) {
                        List<String> recipients = preferences.recipients(specific.getText(),both.isSelected());
                        sender.send(file, recipients,"MechMotor " + type.getValue().name() +
                                " report: " + start + " to " + end);
                        return "Generated " + rows + " rows and Gmail accepted the report for "
                                + String.join(", ",recipients) +
                                (saveTo == null ? "." : ". Download: " + file);
                    }
                    return "Generated " + rows + " rows. Saved: " + file;
                } finally { if (temp) Files.deleteIfExists(file); }
            }, message -> {
                generate.setDisable(false);send.setDisable(false);
                status.setText(message);
                info(message);
            }, throwable -> {
                generate.setDisable(false);send.setDisable(false);
                status.setText("Operation failed; see error.");
                error(throwable);
            });
        };
        generate.setOnAction(e -> execute.accept(false));
        send.setOnAction(e -> execute.accept(true));
        HBox buttons = new HBox(12,generate,send);
        VBox content = new VBox(12,fields,buttons,status);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private static void info(String text) {
        new Alert(Alert.AlertType.INFORMATION,text,ButtonType.OK).show();
    }
    private static void error(Throwable cause) {
        new Alert(Alert.AlertType.ERROR,
                cause == null ? "Unexpected error." : cause.getMessage(),ButtonType.OK).show();
    }
    private static final class StageGuard {
        static void assertFxThread() {
            if (!Platform.isFxApplicationThread()) throw new IllegalStateException("JavaFX thread required.");
        }
    }
}
