# Smart TV Remote – app Android (không cần Termux)

App Android tự nói chuyện với TV bằng giao thức Android TV Remote v2 (giống app Google TV): tìm TV bằng mDNS, ghép đôi bằng mã 6 ký tự, rồi điều khiển. Giao diện giữ nguyên từ bản web (`app/src/main/assets/www`), phần backend được viết lại bằng Kotlin.

## Tạo file APK (dùng GitHub, không cần Android Studio)
1. Tạo repo mới trên GitHub, đưa TOÀN BỘ nội dung thư mục này lên (kể cả thư mục ẩn `.github`).
2. Vào tab **Actions** > **Build APK** > **Run workflow** (hoặc tự chạy khi push).
3. Chạy xong, mở lần chạy đó > mục **Artifacts** > tải `SmartTvRemote-apk` > giải nén được `app-debug.apk`.
4. Cài APK (cho phép "cài từ nguồn không xác định").

Hoặc dùng Android Studio: mở thư mục này > Build > Build APK(s).

## Dùng
Mở app > tab TV > chạm tên TV > nhập mã hiện trên TV. Lần sau tự kết nối, không cần nhập lại.

## Giới hạn
- Chưa build/thử trên máy thật. Giao thức viết theo đặc tả Android TV Remote v2, có thể cần chỉnh nhỏ.
- Không có ADB và nhập văn bản (bản Node mới có ADB).
- Nút Netflix/Prime/Disney+ dùng deep link, cần xác minh trên TV.
