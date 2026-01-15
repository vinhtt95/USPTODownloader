package com.vinhtt.usptodownloader.viewmodel;

import com.vinhtt.usptodownloader.model.PatentDoc;
import com.vinhtt.usptodownloader.repository.IPatentRepository;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MainViewModel {
    // Dependencies
    private final IPatentRepository repository;

    // View State (Properties để Binding)
    private final StringProperty searchQuery = new SimpleStringProperty("slipper.ttl. AND s.kd. AND @PD>=\"20230101\"");
    private final StringProperty statusMessage = new SimpleStringProperty("Ready");
    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);
    private final BooleanProperty isBusy = new SimpleBooleanProperty(false);
    private final ObjectProperty<File> outputDirectory = new SimpleObjectProperty<>();

    // Data List
    private final ObservableList<PatentDoc> results = FXCollections.observableArrayList();

    public MainViewModel(IPatentRepository repository) {
        this.repository = repository;
    }

    // Command: Thực thi Search và Download
    public void executeSearchAndDownload() {
        if (outputDirectory.get() == null) {
            statusMessage.set("Please select an output directory first.");
            return;
        }

        isBusy.set(true);
        progress.set(-1); // Indeterminate progress
        statusMessage.set("Searching...");
        results.clear();

        repository.searchPatents(searchQuery.get())
                .thenAccept(docs -> {
                    Platform.runLater(() -> {
                        results.setAll(docs);
                        statusMessage.set("Found " + docs.size() + " patents. Starting download...");
                        startBatchDownload(docs);
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusMessage.set("Error: " + ex.getMessage());
                        isBusy.set(false);
                        progress.set(0);
                    });
                    return null;
                });
    }

    private void startBatchDownload(List<PatentDoc> docs) {
        int total = docs.size();
        AtomicInteger counter = new AtomicInteger(0);
        AtomicInteger success = new AtomicInteger(0);

        // Chạy từng cái một hoặc parallel tuỳ nhu cầu. Ở đây dùng chain đơn giản.
        // Để tránh block UI, logic download đã nằm trong thread pool của Repository.
        // Tuy nhiên để update UI mượt mà, ta có thể loop qua.

        // Lưu ý: USPTO có rate limit, nên download tuần tự là an toàn nhất.
        new Thread(() -> {
            for (PatentDoc doc : docs) {
                try {
                    Platform.runLater(() -> statusMessage.set("Downloading: " + doc.getDocumentId()));
                    repository.downloadPdf(doc.getDocumentId(), outputDirectory.get().toPath()).join();
                    success.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Failed: " + doc.getDocumentId());
                }

                int current = counter.incrementAndGet();
                Platform.runLater(() -> progress.set((double) current / total));

                // Nghỉ nhẹ tránh bị ban IP
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }

            Platform.runLater(() -> {
                statusMessage.set("Completed. Downloaded " + success.get() + "/" + total + " files.");
                isBusy.set(false);
            });
        }).start();
    }

    // Getters for Properties (cho View binding)
    public StringProperty searchQueryProperty() { return searchQuery; }
    public StringProperty statusMessageProperty() { return statusMessage; }
    public DoubleProperty progressProperty() { return progress; }
    public BooleanProperty isBusyProperty() { return isBusy; }
    public ObjectProperty<File> outputDirectoryProperty() { return outputDirectory; }
}