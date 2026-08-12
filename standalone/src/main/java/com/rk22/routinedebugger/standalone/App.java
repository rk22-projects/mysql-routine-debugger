package com.rk22.routinedebugger.standalone;

import com.rk22.routinedebugger.core.PersistentLogging;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow();
        Scene scene = new Scene(window.getRoot(), 1280, 800);
        stage.setTitle("MySQL Routine Debugger");
        stage.setScene(scene);
        window.setStage(stage);
        window.initScene(scene);
        stage.setOnCloseRequest(e -> window.onClose());
        stage.show();
        Platform.runLater(window::promptInitialConnect);
    }

    public static void main(String[] args) {
        // Also covers IDE/javafx:run launches which bypass the fat-JAR Launcher.
        PersistentLogging.initialize("standalone");
        launch(args);
    }
}
