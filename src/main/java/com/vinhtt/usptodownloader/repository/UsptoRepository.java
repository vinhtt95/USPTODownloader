package com.vinhtt.usptodownloader.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vinhtt.usptodownloader.model.PatentDoc;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
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
    // CookieManager để giữ session (JSESSIONID)
    private final CookieManager cookieManager = new CookieManager();
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    // Base URL
    private static final String BASE_URL = "https://ppubs.uspto.gov/dirsearch-public";

    // 1. URL lấy Session (Bắt buộc phải gọi trước)
    private static final String SESSION_URL = BASE_URL + "/users/me/session";

    // 2. URL Search (Dựa trên thông tin bạn cung cấp)
    // Lưu ý: Nếu server trả về 404, hãy thử bỏ chữ "/dirsearch-public" xem sao,
    // nhưng thường thì phải có nó.
    private static final String SEARCH_URL = BASE_URL + "/searches/counts";

    // 3. URL Download
    private static final String PDF_URL_TEMPLATE = "https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/%s";

    public UsptoRepository() {
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public CompletableFuture<List<PatentDoc>> searchPatents(String queryText) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("========== BẮT ĐẦU QUY TRÌNH MỚI ==========");

                // BƯỚC 1: LẤY SESSION (Handshake)
                // Phải gọi cái này để Server cấp Cookie và CaseId (dù mình đang fake CaseId)
                HttpRequest sessionReq = HttpRequest.newBuilder()
                        .uri(URI.create(SESSION_URL))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                        .header("Origin", "https://ppubs.uspto.gov")
                        .header("Referer", "https://ppubs.uspto.gov/pubwebapp/")
                        .POST(HttpRequest.BodyPublishers.ofString("12345")) // Payload fake session init
                        .build();

                System.out.println("DEBUG: 1. Đang Init Session...");
                HttpResponse<String> sessionRes = client.send(sessionReq, HttpResponse.BodyHandlers.ofString());
                System.out.println("DEBUG: Session Status: " + sessionRes.statusCode());
                // Không cần quan tâm body, chỉ cần lấy Cookie là được.

                // BƯỚC 2: SEARCH
                // Xử lý escape dấu ngoặc kép trong query của user
                String escapedQuery = queryText.replace("\"", "\\\"");

                // Payload JSON chính xác theo mẫu bạn cung cấp
                // Tôi đã thêm "pageCount": 500 để request trả về dữ liệu thay vì chỉ trả về số lượng đếm
                String searchPayload = """
                    {
                      "anchorDocIds": null,
                      "querySource": "brs",
                      "caseId": 202316993,
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
                    """.formatted(escapedQuery, escapedQuery, escapedQuery);

                System.out.println("DEBUG: 2. Payload Search: \n" + searchPayload);

                HttpRequest searchReq = HttpRequest.newBuilder()
                        .uri(URI.create(SEARCH_URL))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
                        .header("Origin", "https://ppubs.uspto.gov")
                        .header("Referer", "https://ppubs.uspto.gov/pubwebapp/")
                        .POST(HttpRequest.BodyPublishers.ofString(searchPayload))
                        .build();

                System.out.println("DEBUG: Đang gửi Search Request tới " + SEARCH_URL);
                HttpResponse<String> response = client.send(searchReq, HttpResponse.BodyHandlers.ofString());

                System.out.println("DEBUG: Search Status: " + response.statusCode());
                System.out.println("DEBUG: Search Body: " + response.body());

                List<PatentDoc> results = new ArrayList<>();
                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());

                    // USPTO có thể trả về "patents" hoặc "docs" hoặc nằm trong object khác
                    // Dựa trên endpoint "counts", có thể nó trả về số lượng trước.
                    // Nếu JSON trả về có mảng "patents", ta parse luôn.
                    JsonNode patentsNode = root.path("patents");

                    if (patentsNode.isMissingNode()) {
                        System.out.println("DEBUG: Không thấy trường 'patents'. Thử tìm trường 'docs'...");
                        // Thử dự phòng trường hợp cấu trúc khác
                        patentsNode = root.path("response").path("docs");
                    }

                    if (patentsNode.isArray()) {
                        System.out.println("DEBUG: Tìm thấy " + patentsNode.size() + " kết quả.");
                        for (JsonNode node : patentsNode) {
                            PatentDoc doc = new PatentDoc();
                            // Mapping linh hoạt các trường ID
                            if (node.has("documentId")) doc.setDocumentId(node.get("documentId").asText());
                            else if (node.has("id")) doc.setDocumentId(node.get("id").asText());
                            else if (node.has("displayId")) doc.setDocumentId(node.get("displayId").asText());

                            if (node.has("inventionTitle")) doc.setInventionTitle(node.get("inventionTitle").asText());
                            if (node.has("title")) doc.setInventionTitle(node.get("title").asText()); // Dự phòng

                            if (doc.getDocumentId() != null) {
                                results.add(doc);
                            }
                        }
                    } else {
                        System.out.println("DEBUG: CẢNH BÁO - Response không chứa danh sách patent. Có thể endpoint 'counts' chỉ trả về số lượng.");
                    }
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
                System.out.println("DEBUG: Downloading " + documentId);

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
                    System.err.println("DEBUG: Download fail code " + response.statusCode());
                }
            } catch (Exception e) {
                throw new RuntimeException("Download failed: " + documentId, e);
            }
        });
    }
}