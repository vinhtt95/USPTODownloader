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
    private static final String SEARCH_FAMILY_URL = BASE_URL + "/searches/searchWithBeFamily";
    private static final String PDF_URL_TEMPLATE = "https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/%s";

    // Cache
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
                System.out.println("========== BẮT ĐẦU (ENDPOINT: searchWithBeFamily - FULL PAYLOAD) ==========");

                // --- BƯỚC 1: INIT SESSION (Lấy Token & CaseId) ---
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

                // Lấy Token
                sessionRes.headers().firstValue("x-access-token").ifPresent(token -> {
                    this.authToken = token;
                    System.out.println("DEBUG: Đã lấy Token: " + token.substring(0, 15) + "...");
                });

                // Lấy CaseID
                if (sessionRes.statusCode() == 200) {
                    JsonNode sessionNode = mapper.readTree(sessionRes.body());
                    if (sessionNode.path("userCase").has("caseId")) {
                        this.currentCaseId = sessionNode.path("userCase").get("caseId").asText();
                    }
                }

                // --- BƯỚC 2: SEARCH TRỰC TIẾP (Không cần count trước) ---
                String escapedQuery = queryText.replace("\"", "\\\"");

                // Payload dựa chính xác theo mẫu bạn cung cấp
                String searchPayload = """
                    {
                        "start": 0,
                        "pageCount": 500,
                        "sort": "date_publ desc",
                        "docFamilyFiltering": "familyIdFiltering",
                        "searchType": 1,
                        "familyIdEnglishOnly": true,
                        "familyIdFirstPreferred": "US-PGPUB",
                        "familyIdSecondPreferred": "USPAT",
                        "familyIdThirdPreferred": "FPRS",
                        "showDocPerFamilyPref": "showEnglish",
                        "queryId": 0,
                        "tagDocSearch": false,
                        "query": {
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
                            "ignorePersist": true,
                            "userEnteredQuery": "%s"
                        }
                    }
                    """.formatted(currentCaseId, escapedQuery, escapedQuery, escapedQuery);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(SEARCH_FAMILY_URL))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                        .header("Origin", "https://ppubs.uspto.gov")
                        .header("Referer", "https://ppubs.uspto.gov/pubwebapp/")
                        .POST(HttpRequest.BodyPublishers.ofString(searchPayload));

                if (authToken != null) reqBuilder.header("x-access-token", authToken);

                System.out.println("DEBUG: 2. Gửi request Search (searchWithBeFamily)...");
                // System.out.println("DEBUG: Payload: " + searchPayload); // Uncomment nếu cần xem payload

                HttpResponse<String> searchRes = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

                List<PatentDoc> results = new ArrayList<>();
                if (searchRes.statusCode() == 200) {
                    JsonNode root = mapper.readTree(searchRes.body());

                    // Kiểm tra xem dữ liệu nằm ở đâu
                    JsonNode patentsNode = root;
                    if (root.has("patents")) patentsNode = root.get("patents");
                    else if (root.has("docs")) patentsNode = root.get("docs");

                    if (patentsNode.isArray()) {
                        System.out.println("DEBUG: Đã tìm thấy " + patentsNode.size() + " bản ghi.");
                        for (JsonNode node : patentsNode) {
                            PatentDoc doc = new PatentDoc();
                            // Mapping các trường ID
                            if (node.has("documentId")) doc.setDocumentId(node.get("documentId").asText());
                            else if (node.has("id")) doc.setDocumentId(node.get("id").asText());
                            else if (node.has("patentNumber")) doc.setDocumentId(node.get("patentNumber").asText());
                            else if (node.has("displayId")) doc.setDocumentId(node.get("displayId").asText());

                            // Mapping Title
                            if (node.has("inventionTitle")) doc.setInventionTitle(node.get("inventionTitle").asText());
                            else if (node.has("title")) doc.setInventionTitle(node.get("title").asText());

                            if (doc.getDocumentId() != null) results.add(doc);
                        }
                    } else {
                        System.out.println("DEBUG: JSON OK nhưng không có mảng data. Body: " + searchRes.body().substring(0, Math.min(searchRes.body().length(), 200)));
                    }
                } else {
                    System.err.println("DEBUG: Lỗi Search: " + searchRes.statusCode());
                    System.err.println("DEBUG: Error Body: " + searchRes.body());
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