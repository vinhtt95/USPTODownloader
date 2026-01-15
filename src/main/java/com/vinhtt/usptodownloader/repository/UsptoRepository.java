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
    private static final String SEARCH_URL = BASE_URL + "/searches/counts";
    private static final String PDF_URL_TEMPLATE = "https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/%s";

    // Lưu trữ Token
    private String authToken = null;

    public UsptoRepository() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public CompletableFuture<List<PatentDoc>> searchPatents(String queryText) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("========== BẮT ĐẦU (TOKEN AUTH MODE) ==========");
                authToken = null; // Reset token cũ

                // --- BƯỚC 1: INIT SESSION & LẤY TOKEN ---
                HttpRequest sessionReq = HttpRequest.newBuilder()
                        .uri(URI.create(SESSION_URL))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                        .header("Origin", "https://ppubs.uspto.gov")
                        .header("Referer", "https://ppubs.uspto.gov/pubwebapp/")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();

                System.out.println("DEBUG: 1. Init Session...");
                HttpResponse<String> sessionRes = client.send(sessionReq, HttpResponse.BodyHandlers.ofString());

                // 1.1 BẮT TOKEN TỪ HEADER
                Optional<String> tokenHeader = sessionRes.headers().firstValue("x-access-token");
                if (tokenHeader.isPresent()) {
                    authToken = tokenHeader.get();
                    System.out.println("DEBUG: Đã lấy được x-access-token: " + authToken.substring(0, 15) + "...");
                } else {
                    System.err.println("DEBUG: CẢNH BÁO - Không thấy header x-access-token!");
                }

                // 1.2 LẤY CASE ID
                String currentCaseId = "202316993";
                if (sessionRes.statusCode() == 200) {
                    JsonNode sessionNode = mapper.readTree(sessionRes.body());
                    if (sessionNode.path("userCase").has("caseId")) {
                        currentCaseId = sessionNode.path("userCase").get("caseId").asText();
                        System.out.println("DEBUG: Got CaseID: " + currentCaseId);
                    }
                } else {
                    System.err.println("DEBUG: Session Fail: " + sessionRes.statusCode());
                }

                // --- BƯỚC 2: SEARCH VỚI TOKEN ---
                String escapedQuery = queryText.replace("\"", "\\\"");
                String searchPayload = """
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

                HttpRequest.Builder searchReqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(SEARCH_URL))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                        .header("Origin", "https://ppubs.uspto.gov")
                        .header("Referer", "https://ppubs.uspto.gov/pubwebapp/");

                // Gắn Token vào Header (Thường các app dùng header này sẽ yêu cầu gửi lại đúng tên header đó)
                if (authToken != null) {
                    searchReqBuilder.header("x-access-token", authToken);
                    // Dự phòng: Gắn thêm Authorization Bearer nếu server yêu cầu chuẩn OAuth
                    // searchReqBuilder.header("Authorization", "Bearer " + authToken);
                }

                HttpRequest searchReq = searchReqBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(searchPayload))
                        .build();

                System.out.println("DEBUG: 2. Sending Search Request...");
                HttpResponse<String> response = client.send(searchReq, HttpResponse.BodyHandlers.ofString());

                System.out.println("DEBUG: Search Code: " + response.statusCode());

                List<PatentDoc> results = new ArrayList<>();
                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    JsonNode patentsNode = root.path("patents");
                    if (patentsNode.isMissingNode()) patentsNode = root.path("docs");

                    if (patentsNode.isArray()) {
                        System.out.println("DEBUG: Found " + patentsNode.size() + " docs.");
                        for (JsonNode node : patentsNode) {
                            PatentDoc doc = new PatentDoc();
                            if (node.has("documentId")) doc.setDocumentId(node.get("documentId").asText());
                            else if (node.has("id")) doc.setDocumentId(node.get("id").asText());
                            else if (node.has("displayId")) doc.setDocumentId(node.get("displayId").asText());

                            if (node.has("inventionTitle")) doc.setInventionTitle(node.get("inventionTitle").asText());
                            else if (node.has("title")) doc.setInventionTitle(node.get("title").asText());

                            if (doc.getDocumentId() != null) results.add(doc);
                        }
                    } else {
                        // Nếu API counts chỉ trả về số lượng, ta cần logic gọi tiếp API details
                        // Nhưng thường với pageCount=500 nó sẽ trả data. Hãy check log body.
                        System.out.println("DEBUG: JSON OK nhưng không có mảng data. Body: " + response.body().substring(0, Math.min(response.body().length(), 200)));
                    }
                } else {
                    System.out.println("DEBUG: Search Error Body: " + response.body());
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
                } else {
                    System.err.println("Download error " + documentId + ": " + response.statusCode());
                }
            } catch (Exception e) {
                System.err.println("Exception download " + documentId);
            }
        });
    }
}