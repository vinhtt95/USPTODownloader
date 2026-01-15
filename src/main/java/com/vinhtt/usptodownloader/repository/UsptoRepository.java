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
    // Endpoint download yêu cầu ID gọn (VD: 11223344 hoặc D654321)
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
                System.out.println("========== BẮT ĐẦU (FIX ID FORMAT FOR DOWNLOAD) ==========");

                // --- BƯỚC 1: INIT SESSION ---
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

                // --- BƯỚC 2: SEARCH (Payload chuẩn) ---
                String escapedQuery = queryText.replace("\"", "\\\"");

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
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Origin", "https://ppubs.uspto.gov")
                        .header("Referer", "https://ppubs.uspto.gov/pubwebapp/")
                        .POST(HttpRequest.BodyPublishers.ofString(searchPayload));

                if (authToken != null) reqBuilder.header("x-access-token", authToken);

                System.out.println("DEBUG: 2. Gửi request Search...");
                HttpResponse<String> searchRes = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

                List<PatentDoc> results = new ArrayList<>();
                if (searchRes.statusCode() == 200) {
                    JsonNode root = mapper.readTree(searchRes.body());
                    JsonNode patentsNode = root;
                    if (root.has("patents")) patentsNode = root.get("patents");
                    else if (root.has("docs")) patentsNode = root.get("docs");

                    if (patentsNode.isArray()) {
                        System.out.println("DEBUG: Đã tìm thấy " + patentsNode.size() + " bản ghi.");
                        for (JsonNode node : patentsNode) {
                            PatentDoc doc = new PatentDoc();

                            // --- FIX LOGIC ID: LẤY ID GỐC ---
                            String cleanId = null;

                            // Cách 1: Ưu tiên dùng patentNumber gốc (Ví dụ: "11223344" hoặc "D1108091")
                            if (node.has("patentNumber")) {
                                cleanId = node.get("patentNumber").asText();
                            }
                            // Cách 2: Nếu không có, parse từ displayId (Ví dụ: "US D1108091 S" -> "D1108091")
                            else if (node.has("displayId")) {
                                String displayId = node.get("displayId").asText();
                                String[] parts = displayId.split("\\s+");
                                // Logic: Tìm phần tử nào trông giống số patent nhất (bỏ qua US, S, B2...)
                                for (String part : parts) {
                                    // Nếu bắt đầu bằng số hoặc chữ D/RE/PP và dài > 4 ký tự
                                    if (part.matches("^(D|RE|PP|T)?\\d+$") && part.length() >= 5) {
                                        cleanId = part;
                                        break;
                                    }
                                }
                                // Fallback: Nếu không tìm thấy, lấy phần tử thứ 2 (index 1) vì thường là US [ID] KIND
                                if (cleanId == null && parts.length >= 2) {
                                    cleanId = parts[1];
                                }
                            }
                            // Cách 3: Lấy documentId và cố gắng làm sạch
                            else if (node.has("documentId")) {
                                cleanId = node.get("documentId").asText().replaceAll("^US", "").replaceAll("[A-Z]\\d*$", "");
                            }

                            if (cleanId != null) {
                                // Xóa mọi ký tự lạ còn sót lại (dấu phẩy, khoảng trắng)
                                cleanId = cleanId.replaceAll("[,\\s]", "");
                                doc.setDocumentId(cleanId);
                            } else {
                                System.err.println("DEBUG: Không tìm thấy ID hợp lệ cho node: " + node.toString().substring(0, 50));
                                continue;
                            }

                            if (node.has("inventionTitle")) doc.setInventionTitle(node.get("inventionTitle").asText());
                            else if (node.has("title")) doc.setInventionTitle(node.get("title").asText());

                            results.add(doc);
                        }
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
                // Đảm bảo ID sạch sẽ
                String cleanId = documentId.trim();
                String downloadUrl = String.format(PDF_URL_TEMPLATE, cleanId);

                // System.out.println("DEBUG: Downloading URL: " + downloadUrl);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "https://ppubs.uspto.gov/")
                        .GET();

                if (authToken != null) {
                    reqBuilder.header("x-access-token", authToken);
                }

                Path targetPath = outputDir.resolve(cleanId + ".pdf");
                HttpResponse<InputStream> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200) {
                    Files.copy(response.body(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("DEBUG: Download Success [" + cleanId + "]");
                } else {
                    System.err.println("Download Error [" + cleanId + "]: " + response.statusCode());
                }
            } catch (Exception e) {
                System.err.println("Exception download [" + documentId + "]: " + e.getMessage());
            }
        });
    }
}