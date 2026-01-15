package com.vinhtt.usptodownloader.repository;

import com.vinhtt.usptodownloader.model.PatentDoc;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface IPatentRepository {
    CompletableFuture<List<PatentDoc>> searchPatents(String query);
    CompletableFuture<Void> downloadPdf(String documentId, Path outputDir);
}