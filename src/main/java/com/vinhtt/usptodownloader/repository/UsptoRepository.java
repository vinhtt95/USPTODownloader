package com.vinhtt.usptodownloader.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vinhtt.usptodownloader.model.PatentDoc;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class UsptoRepository implements IPatentRepository {
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SEARCH_URL = "https://ppubs.uspto.gov/dirsearch-public/users/guest/search";
    private static final String PDF_URL_TEMPLATE = "https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/%s";

    @Override
    public CompletableFuture<List<PatentDoc>> searchPatents(String queryText) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String jsonPayload = """
                    {
                        "start": 0,
                        "pageCount": 500,
                        "sort": "date_publ desc",
                        "searchText": "%s",
                        "searchType": 1
                    }
                    """.formatted(queryText.replace("\"", "\\\""));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(SEARCH_URL))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                List<PatentDoc> results = new ArrayList<>();
                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    JsonNode patentsNode = root.path("patents");
                    if (patentsNode.isArray()) {
                        for (JsonNode node : patentsNode) {
                            results.add(mapper.treeToValue(node, PatentDoc.class));
                        }
                    }
                }
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Search failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> downloadPdf(String documentId, Path outputDir) {
        return CompletableFuture.runAsync(() -> {
            try {
                String downloadUrl = String.format(PDF_URL_TEMPLATE, documentId);
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).GET().build();
                Path targetPath = outputDir.resolve(documentId + ".pdf");

                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    Files.copy(response.body(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    throw new IOException("HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                throw new RuntimeException("Download failed: " + documentId, e);
            }
        });
    }
}