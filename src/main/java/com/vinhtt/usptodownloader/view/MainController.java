package com.vinhtt.usptodownloader.view;

import com.vinhtt.usptodownloader.repository.UsptoRepository;
import com.vinhtt.usptodownloader.viewmodel.MainViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;

import java.io.File;

public class MainController {
    @FXML private TextField txtQuery;
    @FXML private TextField txtDir;
    @FXML private Button btnRun;
    @FXML private ProgressBar progressBar;
    @FXML private Label lblStatus;

    private MainViewModel viewModel;

    public void initialize() {
        // Dependency Injection (Manual)
        // Trong app lớn bạn sẽ dùng Framework DI, ở đây ta new trực tiếp nhưng vẫn đảm bảo tách lớp
        this.viewModel = new MainViewModel(new UsptoRepository());

        // Binding: Nối ViewModel vào View
        txtQuery.textProperty().bindBidirectional(viewModel.searchQueryProperty());
        lblStatus.textProperty().bind(viewModel.statusMessageProperty());
        progressBar.progressProperty().bind(viewModel.progressProperty());
        btnRun.disableProperty().bind(viewModel.isBusyProperty());

        // Binding một chiều cho thư mục output
        viewModel.outputDirectoryProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) txtDir.setText(newVal.getAbsolutePath());
        });
    }

    @FXML
    public void onBrowseDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Download Folder");
        File file = chooser.showDialog(txtQuery.getScene().getWindow());
        if (file != null) {
            viewModel.outputDirectoryProperty().set(file);
        }
    }

    @FXML
    public void onRun() {
        viewModel.executeSearchAndDownload();
    }
}