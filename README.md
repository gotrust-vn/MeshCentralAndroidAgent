# MeshCentral Agent cho Android

Ứng dụng MeshCentral Agent dành cho Android, cho phép quản lý và điều khiển thiết bị từ xa thông qua máy chủ MeshCentral. Hỗ trợ chia sẻ màn hình, điều khiển chuột/bàn phím, quản lý file và nhiều tính năng khác.

---

## Tính năng chính

- **Kết nối server tự động** — tự động kết nối lại khi mất kết nối
- **Chia sẻ màn hình tự động** — tự động bắt đầu chia sẻ màn hình khi kết nối thành công
- **Tự động chấp nhận quyền** — Accessibility Service tự click dialog xác nhận ghi màn hình
- **Điều khiển từ xa** — hỗ trợ chuột, bàn phím, cuộn trang, nhấn giữ, vuốt
- **Tích hợp MDM (Headwind MDM)** — đọc link server từ managed configuration `link_setup_server`; tự động cập nhật khi admin đổi link (phát hiện trong ~10 giây)
- **Deep link** — nhận link server từ app ngoài qua scheme `mc://`
- **Hỗ trợ Android 14+** — xử lý crash, phát hiện camera không cần quyền
- **Phát hiện camera tự động** — dùng QR scan nếu có camera, nhập tay nếu không có
- **Hiển thị log crash** — lỗi được lưu và hiển thị khi mở lại app

---

## Yêu cầu

- **Android** 8.0 (API 26) trở lên
- **Android Studio** hoặc JDK 17 + Gradle
- **MeshCentral server** đã cài đặt

---

## Cách build

### 1. Cài đặt môi trường

Cài [JDK 17](https://adoptium.net/) (Eclipse Temurin khuyến nghị).

### 2. Clone source

```bash
git clone <repo-url>
cd MeshCentralAndroidAgent
```

### 3. Build APK debug

```bash
# Windows (PowerShell / Command Prompt)
gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

> **Lưu ý:** Nếu máy có JAVA_HOME trỏ sai JDK, hãy dùng:
> ```bash
> JAVA_HOME="" ./gradlew assembleDebug
> ```
> Gradle sẽ tự dùng đường dẫn trong `gradle.properties` (`org.gradle.java.home`).

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### 4. Build APK release

```bash
gradlew.bat assembleRelease   # Windows
./gradlew assembleRelease      # Linux / macOS
```

Cần cấu hình signing trong `app/build.gradle` trước khi build release.

### 5. Cài lên thiết bị qua ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Cấu hình

### Kết nối server thủ công

1. Mở app → nhấn **Setup Server**
2. Quét QR code từ trang MeshCentral, hoặc nhập link dạng:
   ```
   mc://your-server.com,<hash>,<meshid>
   ```

### Deep link từ app khác

App ngoài có thể truyền link server vào bằng Intent:

```kotlin
val uri = Uri.parse("mc://your-server.com,<hash>,<meshid>")
startActivity(Intent(Intent.ACTION_VIEW, uri))
```

### Tích hợp Headwind MDM

Trong Headwind MDM admin, vào **Applications → App Settings**, thêm attribute:

| Key | Type | Value |
|-----|------|-------|
| `link_setup_server` | String | `mc://your-server.com,<hash>,<meshid>` |

App sẽ tự đọc link này khi khởi động và kiểm tra mỗi ~10 giây trong lúc chạy.

---

## Bật Accessibility Service (bắt buộc để tự động chấp nhận quyền)

Accessibility Service (`MeshAccessibilityService`) giúp tự động click dialog "Bắt đầu" khi app xin quyền ghi màn hình.

**Bật thủ công:**

1. Vào **Cài đặt → Trợ năng → Ứng dụng đã cài đặt**
2. Tìm **MeshCentral Agent** → bật lên

**Bật qua ADB:**

```bash
adb shell settings put secure enabled_accessibility_services \
  com.meshcentral.agent2/com.meshcentral.agent.MeshAccessibilityService
```

**Bật qua Headwind MDM:**

Dùng tính năng tự động cấp quyền của MDM (Device Owner mode).

---

## Cấu trúc source chính

```
app/src/main/java/com/meshcentral/agent/
├── MainActivity.kt          # Activity chính, quản lý kết nối agent, auto-connect, MDM
├── MainFragment.kt          # UI màn hình chính (trạng thái, nút kết nối, share screen)
├── ScannerFragment.kt       # QR scanner để quét link server
├── MeshAgent.kt             # WebSocket agent kết nối MeshCentral server
├── MeshTunnel.kt            # Tunnel xử lý remote desktop (KVM), file transfer
├── ScreenCaptureService.kt  # Foreground service ghi màn hình (MediaProjection)
├── MeshAccessibilityService.kt  # Accessibility service: auto-accept dialog, inject input
└── ...
```

---

## Các biến toàn cục quan trọng

| Biến | Ý nghĩa |
|------|---------|
| `g_autoConnect` | Tự động kết nối server khi khởi động |
| `g_autoConsent` | Tự động chấp nhận dialog chia sẻ màn hình |
| `g_ScreenCaptureService` | Reference đến ScreenCaptureService đang chạy |
| `g_mainActivity` | Reference đến MainActivity hiện tại |
| `g_pendingProjectionRequest` | Đang chờ user xác nhận quyền ghi màn hình |

---

## Lưu ý quan trọng

- **Lần đầu dùng:** dialog xin quyền ghi màn hình sẽ xuất hiện 1 lần — Accessibility Service tự click nếu đã bật
- **Đổi server link:** app tự detect trong ~10 giây (qua MDM polling hoặc broadcast), dừng session cũ, kết nối server mới và hiện lại dialog
- **Kill app:** service ghi màn hình tự dừng theo app (`stopWithTask="true"`)
- **Headwind MDM black screen:** nếu MDM bật `FLAG_SECURE` trên các cửa sổ, màn hình sẽ bị đen — đây là giới hạn của Android, cần tắt trong cấu hình MDM

---

## Thông tin thêm

- [MeshCentral.com](https://www.meshcentral.com)
- [Reddit](https://www.reddit.com/r/MeshCentral/)
