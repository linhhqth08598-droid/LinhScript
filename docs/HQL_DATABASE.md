# 🗄️ HQL.DATABASE — SQL online + NoSQL (MongoDB)

Toàn bộ thư viện database nằm trong **một file duy nhất: `src/me/hql/script/HqlDbPool.java`**
(bể kết nối SQL, nối SQL online qua URL, JSON nhỏ, BSON, giao thức MongoDB tự viết, cửa ngõ hàm `nosql*`).
Không cần thêm file `.jar` cho MongoDB; SQL vẫn cần JDBC driver trong `lib/`.

```java
use hql.database;     // bật cả hàm SQL lẫn hàm nosqlXxx   (use hql.nosql; cũng được nếu chỉ dùng NoSQL)
```

---

## 1. Tệp cấu hình `db.henv`

Đặt `db.henv` cạnh `main.hql` (hoặc ở thư mục cha, tối đa 6 cấp). Sao chép từ `db.henv.example`.
`db.henv` nằm trong `.gitignore`. Cú pháp giống `.henv`: `khoá=giá trị`, `#` là chú thích.
Tệp này được nạp **tự động**, ưu tiên cao hơn `.henv` (xem [HQL_ENV.md](HQL_ENV.md)).

```
db.type=postgres          # sqlserver | mysql | postgres | sqlite   (mongodb → xem mục 4)
db.name=namedb            # tên database (sqlite: đường dẫn file)
db.host=ep-xxxx.us-east-2.aws.neon.tech
db.port=5432              # bỏ trống = cổng mặc định
db.user=neondb_owner
db.password=matkhau
db.ssl=auto               # auto | true | false
db.pool.max=10
db.pool.min=2
db.timeout=15000
# db.url=postgresql://user:matkhau@host/namedb?sslmode=require     # có db.url thì bỏ qua host/port/name/user/password
```

Các khoá viết hoa kiểu cũ (`DB_TYPE`, `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_URL`...) vẫn chạy bình thường.

### Quy tắc đặt tên khoá (áp dụng cho MỌI khoá, mọi tệp `.henv`)

* **Không phân biệt hoa thường và dấu `.` `_` `-`:** `db.host` = `DB_HOST` = `Db-Host` = `db_host`. Viết kiểu nào cũng nhận, đọc bằng kiểu nào cũng được (`env("db.host")` hay `env("DB_HOST")`).
* **Tệp theo nhóm** có tên `nhom.henv` (`db.henv`, `mail.henv`...) được tự nạp, ưu tiên cao hơn `.henv`. Trong tệp đó có thể **bỏ tiền tố**: trong `db.henv`, `HOST=...` hiểu là `db.host`; trong `mail.henv`, `EMAIL=...` hiểu là `mail.email`.
* **Viết đủ tiền tố thì thắng viết tắt** trong cùng tệp: có cả `HOST=a` và `db.host=b` thì `db.host` = `b`.
* `${TEN}` chỉ thấy các khoá khai báo trong cùng tệp hoặc tệp đã nạp trước.


---

## 2. Nối SQL online

Ngoài cách cũ (`connect("mysql", host, port, db, user, pw)`), `connect` nhận **địa chỉ URL do nhà cung cấp đưa**:

```java
connect("postgresql://user:matkhau@ep-xxx.neon.tech/neondb?sslmode=require");   // Neon, Supabase, Render, Railway...
connect("mysql://avnadmin:matkhau@mysql-xxx.aivencloud.com:12345/defaultdb");   // Aiven, PlanetScale...
connect("sqlserver://user:matkhau@ten.database.windows.net:1433/GearZone");      // Azure SQL, AWS RDS...
connect("postgresql://host/db", "user", "matkhau");                              // user/mật khẩu truyền riêng thì thắng cái trong URL
connectEnv();                                                                    // theo db.henv
```

* **SSL tự bật** khi máy chủ nằm trên mạng (không phải `localhost`, `127.x`, `10.x`, `192.168.x`, `172.16-31.x`, `*.local`).
  Ép bằng `db.ssl=true/false`.
* Mật khẩu có ký tự đặc biệt trong URL phải mã hoá `%`: `@` → `%40`, `:` → `%3A`, `/` → `%2F`.
* Tham số lạ của nhà cung cấp được xử lý sẵn: `channel_binding` (Neon) bị bỏ, `ssl-mode=REQUIRED` (Aiven) đổi thành `sslMode`.
* Cần JDBC driver tương ứng trong `lib/` (PostgreSQL, MySQL/MariaDB, mssql-jdbc).
* Điền `db.name` (hoặc `db.url`) trong `db.henv` mà chưa gọi `connect` → lệnh SQL đầu tiên **tự nối**.

---

## 3. NoSQL = MongoDB (MongoDB Atlas, MongoDB tự dựng)

Chỉ lấy một NoSQL nổi tiếng nhất. Giao thức MongoDB (OP_MSG + BSON, đăng nhập SCRAM-SHA-256/SCRAM-SHA-1, TLS,
`mongodb+srv://`, tự tìm node chính của replica set) **tự viết bằng JDK**. Yêu cầu MongoDB 3.6 trở lên.

### 3.1 Kết nối

```java
use hql.database;

// Cách 1: dán chuỗi Atlas (Connect → Drivers). Database mặc định = phần sau dấu / hoặc tham số thứ 2
if (nosqlConnect("mongodb+srv://user:matkhau@cluster0.abcde.mongodb.net/", "namedb") == false) {
    print("Lỗi: " + nosqlError());
}

// Cách 2: theo db.henv  (nosql.url + nosql.name)
nosqlConnectEnv();         // hoặc khỏi gọi: hàm nosql* đầu tiên sẽ tự nối
```

`db.henv`:

```
nosql.url=mongodb+srv://user:matkhau@cluster0.abcde.mongodb.net/?retryWrites=true&w=majority
nosql.name=namedb
```

Hoặc chỉ dùng MongoDB (không có SQL):

```
db.type=mongodb
db.name=namedb
db.url=mongodb+srv://user:matkhau@cluster0.abcde.mongodb.net/
```

Hoặc tách rời: `nosql.host`, `nosql.port`, `nosql.user`, `nosql.password`, `nosql.ssl` (host `*.mongodb.net` tự dùng `mongodb+srv://`).
Khi `db.type=mongodb`, các hàm SQL **không** tự nối.

Tuỳ chọn trong địa chỉ được hiểu: `tls`, `authSource`, `authMechanism`, `directConnection`, `maxPoolSize`,
`connectTimeoutMS`, `socketTimeoutMS`, `tlsAllowInvalidCertificates` (chỉ dùng khi thử nghiệm, tắt kiểm tra chứng chỉ).

> Atlas: vào **Network Access** và thêm IP máy chạy script, nếu không sẽ không nối được.

### 3.2 Hàm

Bộ lọc / tài liệu là **map** `{ ... }` hoặc **chuỗi JSON** (cho phép khoá không nháy và nháy đơn: `"{tuoi: {$gt: 18}}"`).

| Hàm | Công dụng |
|---|---|
| `nosqlConnect(url[, tenDB])` / `nosqlConnectEnv()` | Nối → `true/false`; lý do lỗi: `nosqlError()` |
| `nosqlClose()` / `nosqlIsOpen()` / `nosqlPing()` | Đóng / còn mở? / kiểm tra máy chủ |
| `nosqlDatabase([ten])` | Xem hoặc đổi database mặc định |
| `nosqlDatabases()` / `nosqlCollections()` | Danh sách database / collection |
| `nosqlInsert(col, doc)` | Thêm 1 tài liệu → trả `_id` (chuỗi 24 ký tự) |
| `nosqlInsertMany(col, [doc, ...])` | Thêm nhiều → mảng `_id` |
| `nosqlFind(col[, loc[, tuyChon]])` | Tìm → mảng map. `tuyChon`: `{ "sort": {"tuoi": -1}, "limit": 10, "skip": 0, "fields": {"ten": 1} }` |
| `nosqlFindOne(col[, loc[, tuyChon]])` | 1 tài liệu hoặc `null` |
| `nosqlUpdate(col, loc, capNhat[, upsert])` | Sửa 1 → số tài liệu khớp. `upsert` = `true` thì chưa có sẽ tạo mới |
| `nosqlUpdateMany(col, loc, capNhat)` | Sửa tất cả khớp |
| `nosqlDelete(col, loc)` / `nosqlDeleteMany(col, loc)` | Xoá 1 / tất cả khớp (bắt buộc truyền bộ lọc; xoá hết thì truyền `{}`) |
| `nosqlCount(col[, loc])` | Đếm |
| `nosqlAggregate(col, [buoc, ...])` | Pipeline tổng hợp |
| `nosqlIndex(col, {"email": 1}[, true])` | Tạo chỉ mục (tham số 3 = `unique`) |
| `nosqlDrop(col)` | Xoá collection → `true/false` |
| `nosqlCommand(lenh[, tenDB])` | Lệnh MongoDB thô, trả map kết quả |
| `nosqlDoc("{ten: ?}", [gia_tri])` | Dựng map/mảng từ chuỗi JSON, `?` thay bằng giá trị trong mảng |
| `nosqlDate([iso])` | Giá trị ngày giờ để ghi vào tài liệu (mặc định: bây giờ) |

### 3.3 Ví dụ

```java
use hql.database;

nosqlConnectEnv();

var id = nosqlInsert("sinhvien", { "ten": "Linh", "tuoi": 17, "dc": { "tp": "Hà Nội" }, "tags": ["a", "b"] });
nosqlInsertMany("sinhvien", [ { "ten": "An", "tuoi": 20 }, { "ten": "Bình", "tuoi": 22 } ]);

for sv in nosqlFind("sinhvien", { "tuoi": { "$gt": 18 } }, { "sort": { "tuoi": -1 }, "limit": 10 }) {
    print(sv.ten + " - " + sv.tuoi + " - " + sv._id);
}

var linh = nosqlFindOne("sinhvien", { "_id": id });          // chuỗi 24 ký tự hex ở khoá _id tự đổi thành ObjectId
nosqlUpdate("sinhvien", { "ten": "Linh" }, { "tuoi": 18 });     // không có toán tử → tự bọc $set
nosqlUpdate("sinhvien", { "ten": "Linh" }, { "$inc": { "tuoi": 1 } });
nosqlDelete("sinhvien", { "ten": "An" });

nosqlInsert("nhatky", { "noidung": "xin chao", "luc": nosqlDate() });    // lưu kiểu ngày giờ thật

nosqlClose();
```

### 3.4 Quy ước kiểu dữ liệu

| Trong script | Trong MongoDB |
|---|---|
| Số nguyên (`17`) | int32 / int64 |
| Số thực (`3.5`) | double |
| Chuỗi 24 ký tự hex ở khoá `_id` (hoặc `$in`/`$eq`... của `_id`) | ObjectId (đọc ra lại thành chuỗi hex) |
| `nosqlDate()`, `{ "$date": "2026-11-23T10:00:00Z" }` | Date (đọc ra thành chuỗi ISO-8601) |
| `{ "$oid": "..." }`, `{ "$numberLong": "123" }` | ObjectId, int64 |
| map / mảng | document / array |
| Đọc ra: mọi số | số thực của script (int64 vượt 2^53 thành chuỗi) |

### 3.4b Dữ liệu từ biến một cách an toàn

```java
var ten = nhapTuNguoiDung;
var loc = nosqlDoc("{ten: ?, tuoi: {$gt: ?}}", [ten, 18]);   // không cần tự escape nháy
```

### 3.5 Lỗi thường gặp

| Lỗi | Cách xử lý |
|---|---|
| `Chưa kết nối NoSQL!` | Gọi `nosqlConnect(...)` hoặc điền `nosql.url` vào `db.henv` |
| `Sai tên đăng nhập hoặc mật khẩu MongoDB` | Kiểm tra user (Atlas: *Database Access*); mật khẩu có ký tự đặc biệt phải `%`-encode |
| `Không nối được MongoDB ...` (Atlas) | Thêm IP vào *Network Access* |
| `Không tra được DNS của ...` | Mạng/DNS chặn SRV; dùng chuỗi `mongodb://host1:27017,host2:27017/...` |
| `MongoDB báo lỗi [11000]` | Trùng `_id` / khoá `unique` |
| `Thiếu bộ lọc` (khi `nosqlDeleteMany`) | Truyền `{}` nếu thật sự muốn xoá hết |

Mọi lỗi NoSQL là lỗi script thường: bắt bằng `try { ... } catch (e) { ... }`.

### 3.6 Giới hạn hiện tại

* Mật khẩu có ký tự không phải ASCII chưa qua chuẩn SASLprep.
* Chưa hỗ trợ đăng nhập X.509 / AWS / Kerberos, chưa có transaction nhiều lệnh, chưa nén dữ liệu.
* `nosqlFind` không giới hạn sẽ nạp **toàn bộ** kết quả vào bộ nhớ → dùng `limit` cho collection lớn.
* Phần MongoDB được kiểm thử với máy chủ MongoDB **giả lập** (đăng nhập SCRAM, TLS, getMore, nhiều luồng); chưa thử trên Atlas thật.
