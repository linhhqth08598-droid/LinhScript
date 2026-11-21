# HQL WEB — Viết Web bằng ngôn ngữ HQL

Java có **JSP + Servlet**, HQL giờ có **HQLSP + Servlet.hql**.

| Java | HQL |
|---|---|
| `index.jsp` | `index.hqlsp` |
| `BaoCaoServlet.java` | `BaoCaoServlet.hql` |
| `out.println("<h1>")` | `printWeb("<h1>")` |
| `<%= ... %>` | `<%= ... %>` (y hệt) |
| `request.getParameter("ten")` | `param("ten")` |
| `${pageContext.request.contextPath}` | `webContext()` |
| Text Block `"""..."""` | `"""..."""` (y hệt) |

---

## 1. File main — nơi đặt TÊN PROJECT

Ai cài thư viện HQL về máy khác cũng chỉ cần viết 1 file main như dưới đây.
**Bắt buộc phải có tên project** (giống `http://localhost:8080/TenProject` của Java web).

```java
// web.hql
use hql.web;
use hql.time;

var TEN_PROJECT = "/QuanLyKho";   // <-- TỰ ĐỔI TÊN Ở ĐÂY
var CONG        = 8080;
var THU_MUC_WEB = "web";          // Thư mục chứa .hqlsp / .html / <Ten>Servlet.hql

webStart(CONG, TEN_PROJECT, THU_MUC_WEB);
webWait();
```

Chạy `LinhScriptRunner web.hql`, cửa sổ Run sẽ **tự in ra toàn bộ đường dẫn**, bấm vào là mở được:

```
=========================================================
   HQL WEB SERVER - QuanLyKho
=========================================================
   Dia chi        : http://localhost:8080/QuanLyKho/
   Trong mang LAN : http://26.62.109.68:8080/QuanLyKho/
   Thu muc goc    : D:\...\web
---------------------------------------------------------
   [Servlet] http://localhost:8080/QuanLyKho/BaoCao        BaoCaoServlet.hql
   [Trang]   http://localhost:8080/QuanLyKho/              index.hqlsp
   [Route]   http://localhost:8080/QuanLyKho/api/sanpham   apiSanPham()
=========================================================
```

Mở `http://localhost:8080/` sẽ tự chuyển hướng về `/QuanLyKho/`.

---

## 2. HQLSP — viết HTML, chèn code HQL

File `web/index.hqlsp`:

```html
<!doctype html>
<html>
<head>
    <link rel="stylesheet" href="<%= webContext() %>/style.css">
</head>
<body>
    <h1>Bây giờ là <%= now() %></h1>

    <% var ten = param("ten", "khách"); %>
    <p>Xin chào <%= htmlEscape(ten) %></p>

    <ul>
    <% for m in ["Toán", "Lý", "Hóa"] { %>
        <li><%= m %></li>
    <% } %>
    </ul>
</body>
</html>
```

| Cú pháp | Ý nghĩa |
|---|---|
| `<% code %>` | Chạy code HQL (kể cả `if`, `for` bao quanh HTML) |
| `<%= biểu thức %>` | In giá trị ra trang |
| `<%-- ghi chú --%>` | Ghi chú, không hiện ra HTML |
| `<%@ page contentType="..." %>` | Đặt kiểu nội dung |
| `<%@ include file="header.hqlsp" %>` | Nhúng file khác |

---

## 3. Servlet — tên file khóa cứng `<Tên>Servlet.hql`

`web/BaoCaoServlet.hql` → truy cập bằng `/QuanLyKho/BaoCao`

```java
use hql.web;
use hql.database;

class SanPham {                       // OOP dùng y hệt Java
    var @ten;
    var @gia;
    func @init(t, g) { this.@ten = t; this.@gia = g; }
    func @moTa() { return this.@ten + " - " + this.@gia; }
}

func init()  { print("Servlet đã nạp"); }   // Chạy 1 lần lúc khởi động

func doGet() {
    printWeb("""
        <!doctype html>
        <html><body>
        <h1>Báo cáo kho</h1>
    """);

    if (connect("localhost", 1433, "DemoDB", "sa", "123")) {
        for dong in query("SELECT ProductName, Price FROM Products") {
            var sp = new SanPham(dong.ProductName, dong.Price);
            printWeb("<p>" + sp.@moTa() + "</p>");
        }
        closeDB();
    }

    printWeb("</body></html>");
}

func doPost() { printWeb("Đã nhận POST: " + param("ten")); }
```

Mỗi servlet có **vùng hàm/lớp riêng** — hai file servlet cùng đặt tên `doGet()` không đụng nhau.

---

## 4. Bảng hàm thư viện `hql.web`

**Máy chủ**
| Hàm | Công dụng |
|---|---|
| `webStart(cong, "/ten", "thuMuc")` | Khởi động máy chủ (tên project bắt buộc) |
| `webStop()` / `webWait()` | Dừng / giữ chương trình chạy |
| `webRoute("/duong-dan", "tenHam")` | Gán 1 hàm HQL cho 1 URL |
| `webServlet("/duong-dan", "X.hql")` | Gán servlet cho URL tùy ý |
| `webContext()` | Trả về tên project, vd `/QuanLyKho` |
| `webExportTomcat("Ten", "web.hql")` | Đóng gói ra thư mục chạy trên Tomcat |

**Đọc request**
`param(ten[, mặcĐịnh])`, `params()`, `webMethod()`, `webPath()`, `webQuery()`, `webBody()`, `webIP()`, `reqHeader(ten)`, `cookieGet(ten)`

**Trả response**
`printWeb(x)`, `webStatus(mã)`, `webType(kiểu)`, `resHeader(tên, giá trị)`, `webRedirect(url)`, `cookieSet(tên, giá trị[, giây])`, `webJson(dữLiệu)`, `webInclude(file)`, `webRender(file)`

**Session**
`sessionSet(k, v)`, `sessionGet(k)`, `sessionDel(k)`, `sessionId()`, `sessionKill()`

**Tiện ích**
`htmlEscape(s)`, `urlEncode(s)`, `urlDecode(s)`, `webMime(tênFile)`

---

## 5. Chạy trên Tomcat 10.1

```java
// deploy.hql
use hql.web;
webRoot("web");
webExportTomcat("QuanLyKho", "web.hql");
```

Chạy xong sẽ sinh ra thư mục:

```
QuanLyKho/
├── index.hqlsp, BaoCaoServlet.hql, style.css ...
└── WEB-INF/
    ├── web.xml        (đã khai báo sẵn me.hql.script.LinhScriptRunner$HqlServlet)
    ├── main.hql       (chính là file main của bạn)
    ├── classes/       (trình thông dịch HQL đã biên dịch)
    └── lib/           (các file .jar, vd driver SQL Server)
```

Chép thư mục `QuanLyKho` vào `<Tomcat>/webapps/`, khởi động Tomcat rồi mở
`http://localhost:8080/QuanLyKho`.

Khi chạy trong Tomcat, `webStart()` và `webWait()` **tự động vô hiệu** (Tomcat đã là máy chủ rồi),
tên project lấy theo tên thư mục trong `webapps/` — nên cùng một file main chạy được cả 2 nơi.

---

## 6. Bảo mật sẵn có

- Chặn `../../` thoát ra ngoài thư mục web.
- Không bao giờ trả mã nguồn `.hql` ra trình duyệt.
- `querySafe()` / `executeSafe()` dùng tham số `?` chống SQL Injection.
- `htmlEscape()` chống XSS khi in dữ liệu người dùng nhập.
