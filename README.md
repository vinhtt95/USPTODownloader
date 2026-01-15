# USPTO Patent Downloader

Ứng dụng Desktop JavaFX giúp tìm kiếm và tải hàng loạt các bằng sáng chế (Patent) từ hệ thống P-PUBS của Văn phòng Sáng chế và Nhãn hiệu Hoa Kỳ (USPTO).

Ứng dụng được thiết kế để vượt qua các rào cản xác thực của Single Page Application (SPA), tự động xử lý Session, Token và tải file PDF chất lượng cao.

## 🚀 Tính năng chính

* **Tìm kiếm nâng cao:** Hỗ trợ cú pháp tìm kiếm đầy đủ của USPTO (Ví dụ: `slipper.ttl. AND @PD>="20230101"`).
* **Tải PDF hàng loạt:** Tự động tải về file PDF đầy đủ (Full Patent Image) thay vì từng trang ảnh rời rạc.
* **Cơ chế xác thực thông minh:** Tự động giả lập quy trình Handshake, lấy `caseId` và `x-access-token` để xác thực với API nội bộ.
* **Chuẩn hóa dữ liệu:** Tự động làm sạch Patent ID (loại bỏ khoảng trắng, mã loại dư thừa) để đảm bảo link tải hoạt động (Ví dụ: chuyển `US D1108091 S` thành `D1108091`).
* **Kiến trúc hiện đại:** Sử dụng mô hình MVVM (Model-View-ViewModel) và Asynchronous Programming giúp giao diện không bị treo khi tải nặng.

## 🛠 Công nghệ sử dụng

* **Ngôn ngữ:** Java 21
* **Build Tool:** Maven
* **Giao diện (GUI):** JavaFX 21 (Modular)
* **HTTP Client:** Java 11+ `java.net.http.HttpClient`
* **JSON Processing:** Jackson Library
* **Utilities:** Lombok (giảm boilerplate code)
* **Packaging:** Maven Shade Plugin (Tạo Fat Jar executable)

## 🏗 Kiến trúc dự án (MVVM)

Dự án tuân thủ nguyên lý SOLID và chia tách trách nhiệm rõ ràng:

1.  **Model (`PatentDoc.java`)**:
    * Chứa cấu trúc dữ liệu của một Patent (ID, Title, Date...).
    * Sử dụng Jackson để map JSON từ API.

2.  **Repository (`UsptoRepository.java`)**:
    * Lớp quan trọng nhất, chịu trách nhiệm giao tiếp với USPTO API.
    * Xử lý logic nghiệp vụ phức tạp:
        * **B1: Init Session:** Gọi `/users/me/session` để lấy `x-access-token` và `caseId`.
        * **B2: Search:** Gọi `/searches/searchWithBeFamily` với payload JSON đặc thù để lấy danh sách.
        * **B3: Download:** Chuẩn hóa ID và gọi `/print/downloadPdf/{id}` để tải file.

3.  **ViewModel (`MainViewModel.java`)**:
    * Cầu nối giữa View và Repository.
    * Quản lý trạng thái UI: `isBusy`, `progress`, `statusMessage`.
    * Chạy các tác vụ mạng trên Background Thread để không chặn JavaFX Application Thread.

4.  **View (`MainController.java` & `main-view.fxml`)**:
    * Chỉ chịu trách nhiệm hiển thị và binding dữ liệu từ ViewModel.

## ⚙️ Hướng dẫn cài đặt và chạy

### Yêu cầu tiên quyết
* **JDK 21** trở lên.
* **Maven** (đã cài đặt biến môi trường).

### Cách 1: Chạy trực tiếp từ IDE (IntelliJ, Eclipse)
1.  Mở dự án bằng IDE.
2.  Reload Maven để tải thư viện.
3.  Tìm file `src/main/java/com/vinhtt/usptodownloader/AppLauncher.java`.
4.  Chuột phải chọn **Run 'AppLauncher.main()'**.
    * *Lưu ý:* Không chạy trực tiếp `USPTODownloaderApp` để tránh lỗi JavaFX Module trên classpath.

### Cách 2: Build ra file EXE/JAR (Khuyên dùng)
Để đóng gói ứng dụng thành một file `.jar` duy nhất (có thể click đúp để chạy hoặc chạy dòng lệnh):

1.  Mở Terminal tại thư mục gốc dự án.
2.  Chạy lệnh build:
    ```bash
    mvn clean package
    ```
3.  Sau khi build thành công, file kết quả sẽ nằm trong thư mục `target/`. Chạy ứng dụng bằng lệnh:
    ```bash
    java -jar target/USPTODownloader-1.0-SNAPSHOT.jar
    ```

## 📝 Nhật ký kỹ thuật (Technical Notes)

Để ứng dụng hoạt động, chúng ta đã thực hiện Reverse Engineering hệ thống P-PUBS:

* **Endpoint:** Đã chuyển từ `/dirsearch-public` (cũ) sang `/api` (mới).
* **Authentication:** Hệ thống đã chuyển từ Cookie-based (`JSESSIONID`) sang Token-based (`x-access-token`). Tool tự động trích xuất token này từ Header của response khởi tạo.
* **Search Flow:** Sử dụng endpoint `/searchWithBeFamily` thay vì `/counts` + `/packets` để tối ưu hóa tốc độ và giảm số lượng request.
* **ID Formatting:** Hệ thống Download của USPTO rất kén định dạng ID. Tool có logic Regex để chuyển đổi các ID hiển thị (vd: `US 11,223,344 B2`) về ID tải về hợp lệ (vd: `11223344`).

## ⚠️ Lưu ý sử dụng
* Tool được viết cho mục đích học tập và nghiên cứu.
* Tránh spam request quá nhanh (tool đã tích hợp độ trễ nhỏ) để không bị chặn IP bởi USPTO.

---
**Author:** VinhTT