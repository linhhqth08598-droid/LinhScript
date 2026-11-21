# 🔐 HQL.ENV — Tệp cấu hình môi trường `.henv`

Định dạng tệp `.henv` được gọi chính thức là **Tệp cấu hình môi trường LinhScript**
(tiếng Anh: **HQL Environment Configuration File**).

Ý tưởng giống `.env` của Node.js và `.properties` của Java: cất mật khẩu, địa chỉ máy chủ, cổng...
ra khỏi mã nguồn để đổi giữa máy dev và máy chạy thật mà không phải sửa code.

```java
use hql.env;
```

---

## 1. Cú pháp tệp

```
# dòng chú thích (cũng nhận dấu ; ở đầu dòng)
DB_HOST=127.0.0.1                      # giá trị trần; " #" cắt chú thích cuối dòng
DB_NAME="Gear Zone"                    # nháy kép: hiểu \n \t \" \\
MAIL_BRAND='GEAR #ZONE'                # nháy đơn: giữ nguyên từng chữ
export DB_PORT=1433                    # chữ export ở đầu được bỏ qua
DB_URL=jdbc:sqlserver://${DB_HOST}:${DB_PORT};databaseName=${DB_NAME}
DB_USER=${DB_USER_MAC_DINH:-sa}        # giá trị dự phòng khi biến chưa có
db.hostname=localhost                  # khoá có dấu chấm (kiểu .properties) cũng được
GHI_CHU="dòng một
dòng hai"                              # nháy kép mở ở dòng này, đóng ở dòng sau
```

* Tệp lưu **UTF-8** (BOM của Notepad được tự bỏ).
* Dòng sai cú pháp bị bỏ qua, không làm hỏng cả tệp.
* Muốn ghi chữ `${...}` nguyên văn: viết `\${...}` trong nháy kép hoặc dùng nháy đơn.

## 2. Tự động nạp & thứ tự ưu tiên

Khi chạy `main.hql`, LinhScript **tự tìm `.henv`** ở thư mục của file main rồi ngược lên tối đa 6 cấp cha
(và thư mục đang chạy). Không cần gọi hàm nào cũng dùng được `connectEnv()`, `mailSend...()`.

Ưu tiên từ cao xuống thấp:

| # | Nguồn | Ghi chú |
|---|---|---|
| 1 | Biến môi trường thật của hệ điều hành | Giống dotenv: không bị tệp ghi đè → đặt mật khẩu thật trên máy chủ mà khỏi sửa tệp |
| 2 | `.henv.local` | Riêng từng máy, **không đưa lên git** |
| 3 | `.henv.<chế độ>` | Chọn bằng biến `HQL_ENV`, ví dụ `HQL_ENV=production` → `.henv.production` |
| 4 | `.henv` | Giá trị chung của dự án |

Tệp mẫu: `.henv.example` (sao chép thành `.henv`). `.henv` và `.henv.local` đã nằm trong `.gitignore`.

## 3. Hàm

| Hàm | Công dụng |
|---|---|
| `envGet("KHOA")` / `envGet("KHOA", macDinh)` | Lấy giá trị (không có → `null` hoặc giá trị mặc định) |
| `envInt("KHOA", macDinh)` | Lấy số nguyên |
| `envBool("KHOA", macDinh)` | `true` với `true / 1 / yes / on / y / co`, `false` với `false / 0 / no / off / n / khong` |
| `envHas("KHOA")` | Khoá có tồn tại không |
| `envSet("KHOA", giaTri)` | Đặt tạm trong bộ nhớ (không ghi ra tệp) |
| `envRequire("A", "B", ...)` | Thiếu khoá nào thì **dừng chương trình** và liệt kê đúng khoá thiếu |
| `envAll()` | Bảng toàn bộ khoá đọc từ tệp `.henv` |
| `envFiles()` | Danh sách tệp đã nạp |
| `envLoad("duong/dan/.henv" [, ghiDe])` | Nạp thêm một tệp; truyền thư mục thì tự dò `.henv` trong đó |

Hàm `env("KHOA")` của `hql.sys` cũng đọc được `.henv` (thêm tham số mặc định: `env("KHOA", macDinh)`).

```java
use hql.env;
use hql.database;
use hql.mail;

envRequire("DB_NAME", "DB_PASSWORD");          // thiếu là dừng ngay, báo rõ thiếu gì

if (connectEnv() == false) {
    print("Không nối được database: " + dbError());
}

mailWarmUp();                                   // mở sẵn kết nối SMTP khi khởi động
```

---

## 4. Database: bể kết nối (thay cho một kết nối dùng chung)

Trước đây `Interpreter` giữ **một** `Connection` duy nhất. Giờ dùng **bể kết nối** `HqlDbPool`
(tự viết, không cần thêm thư viện như HikariCP):

* Mượn / trả tự động: mỗi lệnh `query`, `execute`... mượn một kết nối rồi trả ngay.
* Kết nối chết (SQL Server cắt vì để lâu) được **tự phát hiện và thay mới**, không còn lỗi "connection closed" ở truy vấn đầu sau khi để máy chủ nghỉ.
* Tự dọn kết nối nhàn rỗi, tự giữ sẵn `DB_POOL_MIN` kết nối ở luồng nền.
* **Giao dịch riêng cho từng luồng**: `beginTransaction()` giữ một kết nối riêng tới `commit()` / `rollback()`; luồng khác không bị kéo vào.
* Request web kết thúc mà quên `commit` / `rollback` → tự rollback và in cảnh báo (luồng web không kẹt trong giao dịch cũ).
* `connect(...)` mở thử kết nối ngay → sai mật khẩu/máy chủ tắt thì trả `false` và `dbError()` nói rõ lý do.

| Hàm | Công dụng |
|---|---|
| `connectEnv()` | Nối theo `.henv` (`DB_URL`, hoặc `DB_TYPE`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`) |
| `dbPoolSize(toiDa [, giuSan])` | Đặt cỡ bể, gọi **trước** `connect` (mặc định 10 / 2) |
| `dbPoolStats()` | Chuỗi thống kê: đang dùng / nhàn rỗi / tổng / tối đa |
| `executeBatch(sql, [[...], [...]])` | Chèn/sửa nhiều dòng trong **một** giao dịch — nhanh hơn nhiều so với gọi `execute` trong vòng lặp |

Nếu `.henv` có cấu hình DB mà script chưa gọi `connect`, lần chạy SQL đầu tiên sẽ **tự kết nối theo `.henv`**.

### Lưu ý về `identity()`

Mỗi lệnh có thể chạy trên một kết nối khác nhau của bể, mà `SCOPE_IDENTITY()` chỉ đúng trong cùng một kết nối.
Vì vậy `execute("INSERT ...")` giờ **xin luôn khoá tự tăng** và ghi nhớ cho luồng hiện tại; `identity()` trả đúng giá trị đó.
Cách dùng không đổi:

```java
execute("INSERT INTO KhachHang(ten) VALUES (?)", ["Linh"]);
var id = identity();
```

---

## 5. Lỗi thường gặp

| Lỗi | Cách xử lý |
|---|---|
| `Chưa kết nối Database!` | Gọi `connect(...)` / `connectEnv()` hoặc điền `DB_NAME`, `DB_HOST`... vào `.henv` |
| `Hết kết nối trong bể ...` | Có giao dịch bị bỏ quên chưa `commit` / `rollback`, hoặc tăng `DB_POOL_MAX` |
| `Thiếu cấu hình bắt buộc trong tệp .henv: ...` | Điền các khoá được liệt kê |
| Đổi `.henv` mà không thấy tác dụng | Biến môi trường hệ điều hành cùng tên đang được ưu tiên hơn tệp |
