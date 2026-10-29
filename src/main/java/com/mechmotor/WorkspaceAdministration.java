package com.mechmotor;

import com.mechmotor.service.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Admin and Gmail report controls. All database/network work uses the supplied executor. */
public final class WorkspaceAdministration {
    private WorkspaceAdministration() {}
    private static void theme(Dialog<?> dialog) {
        var css=WorkspaceAdministration.class.getResource("/styles/app.css");
        if(css!=null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        dialog.getDialogPane().getStyleClass().add("premium-admin-dialog");
    }
    public static void users(Window owner,WorkspaceUserService service,Executor executor) {
        Dialog<ButtonType> dialog=new Dialog<>();
        dialog.initOwner(owner);
        theme(dialog);
        dialog.setTitle("MechMotor - Manage Users");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TableView<WorkspaceUserService.User> table=new TableView<>();
        table.setPrefSize(750,390);
        table.getColumns().addAll(column("Name",WorkspaceUserService.User::name),
                column("Email",WorkspaceUserService.User::email),
                column("Role",WorkspaceUserService.User::role),
                column("Status",u->u.active()?"Active":"Inactive"));
        Button add=new Button("+ Add User"),edit=new Button("Edit User"),
                remove=new Button("Deactivate User"),delete=new Button("Delete User");
        add.getStyleClass().add("admin-primary");
        delete.getStyleClass().add("admin-destructive");
        Label info=new Label("Local user directory only. Roles do not grant login permissions.");
        info.setWrapText(true);
        VBox root=new VBox(12,info,table,new HBox(10,add,edit,remove,delete));
        root.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(root);
        Runnable refresh=()->background(executor,service::list,data->table.setItems(FXCollections.observableArrayList(data)),error->failure(owner,error));
        add.setOnAction(e->editUser(owner,null,service,executor,refresh));
        edit.setOnAction(e->{
            var selection=table.getSelectionModel().getSelectedItem();
            if(selection!=null && selection.active()) editUser(owner,selection,service,executor,refresh);
        });
        remove.setOnAction(e->{
            var selection=table.getSelectionModel().getSelectedItem();
            if(selection==null || !selection.active()) return;
            Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,
                    "Deactivate "+selection.name()+"? The record is retained.",ButtonType.CANCEL,ButtonType.OK);
            confirm.initOwner(owner);
            if(confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
            background(executor,()->{service.deactivate(selection.id());return true;},
                    ignored->refresh.run(),error->failure(owner,error));
        });
        delete.setOnAction(e->{
            var selected=table.getSelectionModel().getSelectedItem();
            if(selected==null) return;
            Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,
                    "Permanently delete "+selected.email()+" from the local user directory? This cannot be undone.",
                    ButtonType.CANCEL,ButtonType.OK);
            confirm.initOwner(owner);
            if(confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
            background(executor,()->{service.delete(selected.id());return true;},
                    ignored->refresh.run(),error->failure(owner,error));
        });
        refresh.run();
        dialog.showAndWait();
    }
    private static void editUser(Window owner,WorkspaceUserService.User prior,
                                 WorkspaceUserService service,Executor executor,Runnable refresh) {
        Dialog<ButtonType> dialog=new Dialog<>();
        dialog.initOwner(owner);
        theme(dialog);
        dialog.setTitle(prior==null?"Add User":"Edit User");
        ButtonType save=new ButtonType("Save User",ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
        TextField name=new TextField(prior==null?"":prior.name());
        TextField email=new TextField(prior==null?"":prior.email());
        ComboBox<String> role=new ComboBox<>(FXCollections.observableArrayList("ADMIN","MANAGER","STAFF"));
        role.setValue(prior==null?"STAFF":prior.role());
        GridPane form=new GridPane();form.setHgap(12);form.setVgap(12);form.setPadding(new Insets(16));
        form.addRow(0,new Label("Name"),name);
        form.addRow(1,new Label("Email"),email);
        form.addRow(2,new Label("Role (directory label only)"),role);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION,event->{
            try { WorkspaceUserService.requireEmail(email.getText());
                if(name.getText()==null || name.getText().isBlank()) throw new IllegalArgumentException("Enter a name.");
            } catch(Exception invalid){event.consume();failure(owner,invalid);}
        });
        if(dialog.showAndWait().filter(save::equals).isPresent())
            background(executor,()->{
                if(prior==null) service.create(name.getText(),email.getText(),role.getValue());
                else service.update(prior.id(),name.getText(),email.getText(),role.getValue());
                return true;
            },ignored->refresh.run(),error->failure(owner,error));
    }
    public static void gmailSettings(Window owner,WorkspaceUserService users,
                                     GmailReportService gmail,Executor executor) {
        Dialog<ButtonType> dialog=new Dialog<>();
        dialog.initOwner(owner);
        theme(dialog);
        dialog.setTitle("MechMotor - Gmail Connection & Default Recipient");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Label connection=new Label(gmail.connected()?"Google account connected":"Google account not connected");
        Label directions=new Label("Google OAuth Desktop Client ID is read from MECHMOTOR_GOOGLE_CLIENT_ID. "
                +"A browser opens for Gmail send permission. Reconnect after restarting MechMotor.");
        directions.setWrapText(true);
        TextField defaultEmail=new TextField();
        defaultEmail.setPromptText("default@example.com");
        Button save=new Button("Save Default Email");
        Button connect=new Button("Connect Google Account");
        Button disconnect=new Button("Disconnect");
        connect.getStyleClass().add("admin-primary");
        disconnect.getStyleClass().add("admin-destructive");
        Label status=new Label("");
        Label googleHeading=new Label("Google Account (Gmail)");
        googleHeading.getStyleClass().add("admin-heading");
        Label emailHeading=new Label("Default Report Recipient");
        emailHeading.getStyleClass().add("admin-heading");
        VBox layout=new VBox(13,googleHeading,connection,new HBox(10,connect,disconnect),
                directions,emailHeading,defaultEmail,save,status);
        layout.getStyleClass().add("admin-content");
        save.getStyleClass().add("admin-success");
        layout.setPadding(new Insets(18));
        layout.setPrefWidth(560);
        dialog.getDialogPane().setContent(layout);
        background(executor,users::defaultRecipient,defaultEmail::setText,error->failure(owner,error));
        save.setOnAction(e->{
            try { WorkspaceUserService.requireEmail(defaultEmail.getText()); }
            catch(Exception invalid){failure(owner,invalid);return;}
            background(executor,()->{users.setDefaultRecipient(defaultEmail.getText());return true;},
                    ignored->status.setText("Default recipient saved."),
                    error->failure(owner,error));
        });
        connect.setOnAction(e->{
            connect.setDisable(true);
            status.setText("Complete Google authorization in your browser...");
            background(executor,()->{gmail.connect();return true;},
                    ok->{connect.setDisable(false);connection.setText("Google account connected");status.setText("Connected for this session.");},
                    error->{connect.setDisable(false);status.setText("Connection failed.");failure(owner,error);});
        });
        disconnect.setOnAction(e->{
            gmail.disconnect();
            connection.setText("Google account not connected");
            status.setText("Disconnected.");
        });
        dialog.showAndWait();
    }

    public static void reports(Window owner, OperationalReportsService reports,
                               WorkspaceUserService users,GmailReportService gmail,Executor executor) {
        Dialog<ButtonType> dialog=new Dialog<>();
        dialog.initOwner(owner);
        theme(dialog);dialog.setTitle("MechMotor - Generate and Email Report");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ComboBox<OperationalReportsService.ReportType> type=new ComboBox<>(
                FXCollections.observableArrayList(OperationalReportsService.ReportType.values()));
        type.setValue(OperationalReportsService.ReportType.SALES);
        DatePicker from=new DatePicker(LocalDate.now());
        DatePicker to=new DatePicker(LocalDate.now());
        CheckBox today=new CheckBox("Today (default)");
        today.setSelected(true);
        from.disableProperty().bind(today.selectedProperty());
        to.disableProperty().bind(today.selectedProperty());
        TextField recipient=new TextField();
        recipient.setPromptText("Optional recipient email (otherwise use default)");
        CheckBox both=new CheckBox("Send to both entered email and default email");
        Label defaultLabel=new Label("Default recipient: loading...");
        Label connected=new Label(gmail.connected()?"Gmail connected":"Gmail not connected - connect in Settings");
        Label status=new Label("");
        Button generate=new Button("Generate & Download");
        Button send=new Button("Send Report");
        Button generateAndSendBoth=new Button("Generate & Send Both");
        generate.getStyleClass().add("admin-primary");
        send.getStyleClass().add("admin-success");
        generateAndSendBoth.getStyleClass().add("admin-purple");
        Label heading=new Label("Generate, Download or Email Reports");
        heading.getStyleClass().add("admin-heading");
        VBox typeCard=new VBox(9,new Label("Report Type"),type);
        VBox dateCard=new VBox(9,new Label("Date Range"),today,new HBox(8,new Label("From"),from),
                new HBox(8,new Label("To"),to));
        VBox emailCard=new VBox(9,new Label("Email Options"),connected,defaultLabel,recipient,both);
        for(VBox card:List.of(typeCard,dateCard,emailCard))
            card.getStyleClass().add("report-control-card");
        HBox formRow=new HBox(13,typeCard,dateCard,emailCard);
        HBox.setHgrow(emailCard,Priority.ALWAYS);
        VBox layout=new VBox(15,heading,formRow,
                new HBox(12,generate,send,generateAndSendBoth),status);
        layout.getStyleClass().add("admin-content");
        layout.setPadding(new Insets(18));layout.setPrefWidth(900);
        dialog.getDialogPane().setContent(layout);
        final String[] defaultEmail={""};
        background(executor,users::defaultRecipient,email->{
            defaultEmail[0]=email;
            defaultLabel.setText("Default recipient: "+(email.isBlank()?"Not configured":email));
        },error->failure(owner,error));
        generate.setOnAction(e->{
            LocalDate reportStart=today.isSelected()?LocalDate.now():from.getValue();
            LocalDate reportEnd=today.isSelected()?LocalDate.now():to.getValue();
            if(!validDates(owner,reportStart,reportEnd)) return;
            if(both.isSelected() && !gmail.connected()) { failure(owner,new IllegalStateException("Connect Gmail in Settings to use both recipients."));return; }
            FileChooser chooser=new FileChooser();
            chooser.setTitle("Save MechMotor CSV report");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));
            chooser.setInitialFileName("mechmotor-"+type.getValue().name().toLowerCase(Locale.ROOT)+".csv");
            var file=chooser.showSaveDialog(owner);
            if(file==null) return;
            Path destination=file.toPath();
            OperationalReportsService.ReportType selectedType=type.getValue();
            List<String> copyTargets;
            try { copyTargets=both.isSelected()?recipients(defaultEmail[0],recipient.getText(),true):List.of(); }
            catch(Exception exception){failure(owner,exception);return;}
            generate.setDisable(true);status.setText("Generating report...");
            background(executor,()->{
                long count=reports.export(selectedType,destination,reportStart,reportEnd);
                if(!copyTargets.isEmpty()) gmail.sendReport(copyTargets,destination,selectedType.name());
                return count;
            },count->{generate.setDisable(false);status.setText("Generated "+count+" rows: "+destination.getFileName()
                    +(copyTargets.isEmpty()?"":" and sent to "+String.join(", ",copyTargets)));},
                    error->{generate.setDisable(false);failure(owner,error);status.setText("Generation failed.");});
        });
        generateAndSendBoth.setOnAction(e->{
            if(recipient.getText().isBlank()) {
                failure(owner,new IllegalArgumentException("Enter a recipient to send to both emails."));
                return;
            }
            both.setSelected(true);
            send.fire();
        });
        send.setOnAction(e->{
            LocalDate reportStart=today.isSelected()?LocalDate.now():from.getValue();
            LocalDate reportEnd=today.isSelected()?LocalDate.now():to.getValue();
            if(!validDates(owner,reportStart,reportEnd))return;
            OperationalReportsService.ReportType selectedType=type.getValue();
            List<String> targets;
            try {
                targets=recipients(defaultEmail[0],recipient.getText(),both.isSelected());
                if(!gmail.connected()) throw new IllegalStateException("Connect Gmail from Settings first.");
            }catch(Exception error){failure(owner,error);return;}
            Alert confirmation=new Alert(Alert.AlertType.CONFIRMATION,
                    "Generate and email "+type.getValue()+" report to: "+String.join(", ",targets),
                    ButtonType.CANCEL,ButtonType.OK);
            confirmation.initOwner(owner);
            if(confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
            send.setDisable(true);status.setText("Generating and sending report...");
            background(executor,()->{
                Path tempFolder=Files.createTempDirectory("mechmotor-email-report-");
                Path csv=tempFolder.resolve("report.csv");
                try {
                    reports.export(selectedType,csv,reportStart,reportEnd);
                    gmail.sendReport(targets,csv,selectedType.name());
                    return true;
                }finally {
                    Files.deleteIfExists(csv);
                    Files.deleteIfExists(tempFolder);
                }
            },ok->{send.setDisable(false);status.setText("Report sent to "+String.join(", ",targets));},
            error->{send.setDisable(false);status.setText("Send failed.");failure(owner,error);});
        });
        dialog.showAndWait();
    }
    public static List<String> recipients(String fallback,String entered,boolean both) {
        String recipient=entered==null?"":entered.trim();
        String defaultAddress=fallback==null?"":fallback.trim();
        List<String> destinations=new ArrayList<>();
        if(!recipient.isBlank()) {
            WorkspaceUserService.requireEmail(recipient);
            destinations.add(recipient);
            if(both) {
                if(defaultAddress.isBlank())throw new IllegalArgumentException("Set the default email in Settings first.");
                WorkspaceUserService.requireEmail(defaultAddress);
                destinations.add(defaultAddress);
            }
        }else{
            if(defaultAddress.isBlank())throw new IllegalArgumentException("Enter an email or set a default email in Settings.");
            WorkspaceUserService.requireEmail(defaultAddress);
            destinations.add(defaultAddress);
        }
        return destinations.stream().distinct().toList();
    }
    public static boolean validReportDates(LocalDate from, LocalDate to) {
        return from != null && to != null && !to.isBefore(from) && !from.plusDays(366).isBefore(to);
    }
    private static boolean validDates(Window owner,LocalDate from,LocalDate to) {
        if(!validReportDates(from,to)) {
            failure(owner,new IllegalArgumentException("Select a valid date range up to 366 days."));
            return false;
        }return true;
    }
    private static <T> TableColumn<WorkspaceUserService.User,String> column(
            String title,java.util.function.Function<WorkspaceUserService.User,String> f) {
        TableColumn<WorkspaceUserService.User,String> col=new TableColumn<>(title);
        col.setCellValueFactory(data->new javafx.beans.property.SimpleStringProperty(f.apply(data.getValue())));
        return col;
    }
    private static <T> void background(Executor executor,
            java.util.concurrent.Callable<T> task,Consumer<T> success,Consumer<Throwable> error) {
        executor.execute(()->{
            try{T data=task.call();Platform.runLater(()->success.accept(data));}
            catch(Throwable ex){Platform.runLater(()->error.accept(ex));}
        });
    }
    private static void failure(Window owner,Throwable error){
        Alert alert=new Alert(Alert.AlertType.ERROR,error.getMessage()==null?
                "Operation failed. Check the application log.":error.getMessage(),ButtonType.OK);
        alert.initOwner(owner);alert.setTitle("MechMotor");alert.setHeaderText("Unable to complete action");alert.show();
    }
}
