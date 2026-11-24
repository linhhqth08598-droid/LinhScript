# HQL.APP — Biến trang web HQL thành cửa sổ app, chạy tiến trình con

Một dự án HQL đã có máy chủ web (`hql.web`) thì gần như đã có sẵn giao diện app: chỉ thiếu một cái cửa sổ để bày trang ấy ra. Thư viện `hql.app` chính là cái cửa sổ đó, cộng vài việc lặt vặt quanh nó — chạy tiến trình con, chờ cổng, tắt tiến trình.

```java
use hql.app;
```

Giao diện app **chính là** trang web, nên sửa giao diện web là app đổi theo ngay, không phải dựng lại app.

> Cửa sổ là khung **Chromium nhúng (JCEF)**, cần thư viện `jcef-maven` trong `lib/`. Lần chạy đầu tiên sẽ tải Chromium (~150 MB) vào thư mục `hql_app_cef/`. Nếu không dùng được, `hql.app` tự lùi về Microsoft Edge / Google Chrome có sẵn trên máy (chế độ `--app`) và in ra một dòng thông báo.

---

## 1. Bảng hàm

### Cửa sổ app

| Hàm | Công dụng |
|---|---|
| `appHasBrowser()` | Máy có Edge/Chrome để làm khung dự phòng không |
| `appWindow(url[, title[, width[, height[, iconPath]]]])` | Mở cửa sổ app (mặc định `"App"`, 1400×900) → trả về **mã cửa sổ**. `iconPath` phải là PNG/GIF/JPG (không đọc được `.ico` / `.svg`) |
| `appIsOpen(id)` | Cửa sổ còn mở không |
| `appWait(id)` | Chặn tới khi người dùng đóng cửa sổ |
| `appClose(id)` | Đóng cửa sổ |
| `appTray(id[, iconPath[, openLabel[, exitLabel]]])` | Thu xuống khay hệ thống: bấm dấu X chỉ ẩn cửa sổ, muốn thoát thì chọn mục thoát trong menu khay. Trả `false` nếu máy không có khay (khi đó bấm X vẫn là thoát) |
| `appRootDir(marker)` | Tìm thư mục cha chứa tệp/thư mục `marker` (dò ngược từ vị trí file `.exe` và thư mục hiện hành). Không thấy thì trả chuỗi rỗng |

### Tiến trình con

| Hàm | Công dụng |
|---|---|
| `processRun(command[, workDir[, logFile]])` | Chạy chương trình ngoài → trả về **mã tiến trình**. `command` nên là **mảng** `["java", "-jar", "a.jar"]`; đầu ra ghi nối vào `logFile` (bỏ trống thì bỏ đầu ra) |
| `processAlive(id)` | Tiến trình còn chạy không |
| `processKill(id)` | Tắt tiến trình (xin tử tế trước, 5 giây không xong mới ép) |

`exec()` cắt chuỗi lệnh theo dấu cách nên hỏng với đường dẫn kiểu `C:\Program Files\...`. `processRun` nhận một **mảng** tham số nên không bị lỗi này.

### Cổng mạng và môi trường

| Hàm | Công dụng |
|---|---|
| `portIsOpen(port)` | Cổng TCP trên máy này đang có ai nghe không |
| `waitPort(port[, seconds])` | Chờ cổng mở (mặc định 30 giây) → `true` nếu mở kịp |
| `sysProp("name")` | Đọc thuộc tính hệ thống Java (ví dụ `java.home`); không có thì trả chuỗi rỗng |

---

## 2. Ví dụ: web + cửa sổ app

```java
use hql.app;
use hql.web;

webStart(8080, "/App", "web");

if (waitPort(8080, 10)) {
    var win = appWindow("http://localhost:8080/App/", "Ứng dụng của tôi", 1200, 800, "icon.png");
    appTray(win, "icon.png", "Mở ứng dụng", "Thoát");
    appWait(win);          // chờ tới khi người dùng thoát hẳn
}

webStop();
```

## 3. Ví dụ: chạy một tiến trình con rồi tắt

```java
use hql.app;

var p = processRun(["java", "-jar", "server.jar"], "", "server.log");
if (waitPort(9000, 20)) {
    print("Server đã sẵn sàng");
}
// ...
if (processAlive(p)) { processKill(p); }
```
