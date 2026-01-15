package com.vinhtt.usptodownloader.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vinhtt.usptodownloader.model.PatentDoc;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class UsptoRepository implements IPatentRepository {
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    // URLs
    private static final String BASE_URL = "https://ppubs.uspto.gov/api";
    private static final String SESSION_URL = BASE_URL + "/users/me/session";
    private static final String SEARCH_COUNT_URL = BASE_URL + "/searches/counts";
    private static final String SEARCH_FAMILY_URL = BASE_URL + "/searches/searchWithBeFamily"; // Endpoint lấy data
    private static final String PDF_URL_TEMPLATE = "https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/%s";

    // Token & CaseId cache
    private String authToken = null;
    private String currentCaseId = "202316993";

    public UsptoRepository() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public CompletableFuture<List<PatentDoc>> searchPatents(String queryText) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("========== BẮT ĐẦU (FLOW: AUTH -> COUNTS -> SEARCH_FAMILY) ==========");

                // --- BƯỚC 1: INIT SESSION ---
                HttpRequest sessionReq = HttpRequest.newBuilder()
                        .uri(URI.create(SESSION_URL))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Origin", "https://ppubs.uspto.gov")
                        .header("Referer", "https://ppubs.uspto.gov/pubwebapp/")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();

                System.out.println("DEBUG: 1. Init Session...");
                HttpResponse<String> sessionRes = client.send(sessionReq, HttpResponse.BodyHandlers.ofString());

                // Lấy Token
                sessionRes.headers().firstValue("x-access-token").ifPresent(token -> {
                    this.authToken = token;
                    System.out.println("DEBUG: Đã lấy Token: " + token.substring(0, 10) + "...");
                });

                // Lấy CaseID
                if (sessionRes.statusCode() == 200) {
                    JsonNode sessionNode = mapper.readTree(sessionRes.body());
                    if (sessionNode.path("userCase").has("caseId")) {
                        this.currentCaseId = sessionNode.path("userCase").get("caseId").asText();
                    }
                }

                // --- BƯỚC 2: COUNTS (Lấy QueryID) ---
                String escapedQuery = queryText.replace("\"", "\\\"");
                String countPayload = """
                    {
                      "anchorDocIds": null,
                      "querySource": "brs",
                      "caseId": %s,
                      "hl_snippets": "2",
                      "op": "OR",
                      "q": "%s",
                      "queryName": "%s",
                      "highlights": "1",
                      "qt": "brs",
                      "spellCheck": false,
                      "viewName": "tile",
                      "plurals": true,
                      "britishEquivalents": true,
                      "databaseFilters": [
                        { "databaseName": "US-PGPUB", "countryCodes": [] },
                        { "databaseName": "USPAT", "countryCodes": [] },
                        { "databaseName": "USOCR", "countryCodes": [] }
                      ],
                      "searchType": 1,
                      "ignorePersist": false,
                      "userEnteredQuery": "%s",
                      "start": 0,
                      "pageCount": 500
                    }
                    """.formatted(currentCaseId, escapedQuery, escapedQuery, escapedQuery);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Origin", "https://ppubs.uspto.gov")
                        .header("Referer", "https://ppubs.uspto.gov/pubwebapp/");
                if (authToken != null) reqBuilder.header("x-access-token", authToken);

                System.out.println("DEBUG: 2. Gửi lệnh Count...");
                HttpResponse<String> countRes = client.send(reqBuilder.uri(URI.create(SEARCH_COUNT_URL))
                        .POST(HttpRequest.BodyPublishers.ofString(countPayload)).build(), HttpResponse.BodyHandlers.ofString());

                long queryId = 0;
                long numResults = 0;
                if (countRes.statusCode() == 200) {
                    JsonNode countNode = mapper.readTree(countRes.body());
                    numResults = countNode.path("numResults").asLong();
                    queryId = countNode.path("id").asLong();
                    System.out.println("DEBUG: Kết quả đếm: " + numResults + ". Query ID: " + queryId);
                } else {
                    throw new RuntimeException("Count Failed: " + countRes.statusCode());
                }

                if (numResults == 0) return new ArrayList<>();

                // --- BƯỚC 3: FETCH DATA (searchWithBeFamily) ---
                // FIX: Payload này dùng queryId từ bước 2, thay vì gửi lại query text
                // Nếu vẫn lỗi 400, bạn hãy mở Tab Network -> Copy Payload của request searchWithBeFamily và dán cho tôi xem
                String fetchPayload = """
                    {
                        "queryId": %d,
                        "start": 0,
                        "pageCount": %d,
                        "caseId": %s
                    }
                    """.formatted(queryId, Math.min(numResults, 500), currentCaseId);

                System.out.println("DEBUG: 3. Gửi lệnh Fetch Data (searchWithBeFamily)...");
                System.out.println("DEBUG: Fetch Payload: " + fetchPayload);

                HttpResponse<String> fetchRes = client.send(reqBuilder.uri(URI.create(SEARCH_FAMILY_URL))
                        .POST(HttpRequest.BodyPublishers.ofString(fetchPayload)).build(), HttpResponse.BodyHandlers.ofString());

                List<PatentDoc> results = new ArrayList<>();
                if (fetchRes.statusCode() == 200) {
                    JsonNode root = mapper.readTree(fetchRes.body());
                    // API này thường trả về trực tiếp mảng các bản ghi
                    JsonNode patentsNode = root;
                    if (root.has("patents")) patentsNode = root.get("patents");
                    else if (root.has("docs")) patentsNode = root.get("docs");

                    if (patentsNode.isArray()) {
                        System.out.println("DEBUG: Đã tải về " + patentsNode.size() + " patent.");
                        for (JsonNode node : patentsNode) {
                            PatentDoc doc = new PatentDoc();
                            if (node.has("documentId")) doc.setDocumentId(node.get("documentId").asText());
                            else if (node.has("id")) doc.setDocumentId(node.get("id").asText());
                            else if (node.has("patentNumber")) doc.setDocumentId(node.get("patentNumber").asText());
                            else if (node.has("displayId")) doc.setDocumentId(node.get("displayId").asText());

                            if (node.has("inventionTitle")) doc.setInventionTitle(node.get("inventionTitle").asText());
                            else if (node.has("title")) doc.setInventionTitle(node.get("title").asText());

                            if (doc.getDocumentId() != null) results.add(doc);
                        }
                    } else {
                        System.out.println("DEBUG: JSON OK nhưng không thấy mảng data. Body mẫu: " + fetchRes.body().substring(0, Math.min(fetchRes.body().length(), 200)));
                    }
                } else {
                    System.err.println("DEBUG: Lỗi Fetch: " + fetchRes.statusCode());
                    System.err.println("DEBUG: Error Body: " + fetchRes.body());
                }

                return results;

            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Search failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> downloadPdf(String documentId, Path outputDir) {
        return CompletableFuture.runAsync(() -> {
            try {
                String downloadUrl = String.format(PDF_URL_TEMPLATE, documentId);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();

                Path targetPath = outputDir.resolve(documentId + ".pdf");
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200) {
                    Files.copy(response.body(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                System.err.println("Exception download " + documentId);
            }
        });
    }
}