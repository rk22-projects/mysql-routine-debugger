package be.rk22.dbgplugin.standalone;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow();
        Scene scene = new Scene(window.getRoot(), 1280, 800);
        stage.setTitle("MariaDB Procedure Debugger");
        stage.setScene(scene);
        window.initScene(scene);
        stage.setOnCloseRequest(e -> window.onClose());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
