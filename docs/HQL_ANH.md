# HQL.IMAGE — Mã hoá ảnh thành chuỗi ký tự để cất vào cơ sở dữ liệu

Ý tưởng rất đơn giản:

```
  logo.png  ──mã hoá──►  "iVBORw0KGgoAAAANSUhEUg..."  ──►  cột VARCHAR(MAX) trong CSDL
                                     │
                                     └──giải mã──►  ảnh PNG thật gửi về trình duyệt
```

Nhờ vậy **sao lưu cơ sở dữ liệu là đã sao lưu luôn toàn bộ ảnh**, không phải chép
thêm thư mục `uploads/` nào cả, và cũng không cần cấu hình multipart/form-data.

```java
use hql.image;
```

---

## 1. Bảng hàm

| Hàm | Công dụng |
|---|---|
| `anhTuFile("logo.png")` | Đọc file ảnh trên đĩa → chuỗi base64 |
| `anhRaFile(chuoi, "ra.png")` | Ghi chuỗi base64 ngược lại thành file ảnh, trả về số byte |
| `anhLamSach(chuoi)` | Bỏ phần đầu `data:image/png;base64,` và mọi khoảng trắng |
| `anhKieu(chuoi)` | Nhận dạng định dạng: `image/png`, `image/jpeg`, `image/gif`, `image/webp`, `image/bmp` |
| `anhDataUri(chuoi[, kieu])` | Trả về `data:image/png;base64,...` để dán thẳng vào `<img src>` |
| `anhDungLuong(chuoi)` | Số byte thật của ảnh |
| `anhHopLe(chuoi)` | `true` nếu giải mã ra được ảnh thật |
| `anhRong(chuoi)` / `anhCao(chuoi)` | Chiều rộng / chiều cao tính bằng điểm ảnh |
| `anhThongTin(chuoi)` | Bảng `{ rong, cao, kieu, dung_luong, hop_le }` |
| `anhThuNho(chuoi, rongToiDa, caoToiDa)` | Thu nhỏ giữ nguyên tỷ lệ (ảnh đã nhỏ thì giữ nguyên) |
| `anhCatVuong(chuoi, canh)` | Cắt giữa thành ảnh vuông — dùng cho ảnh đại diện |

Mỗi hàm đều có tên tiếng Anh tương đương nếu bạn quen viết kiểu quốc tế:
`imageFileToBase64`, `imageBase64ToFile`, `imageBase64Clean`, `imageBase64Mime`,
`imageBase64Uri`, `imageBase64Size`, `imageBase64Valid`, `imageBase64Width`,
`imageBase64Height`, `imageBase64Info`, `imageBase64Resize`, `imageBase64Square`.

---

## 2. Trả ảnh về trình duyệt (`hql.web`)

| Hàm | Công dụng |
|---|---|
| `webAnh(chuoi[, kieu])` | Giải mã chuỗi rồi gửi về trình duyệt dưới dạng **ảnh nhị phân thật** |
| `webNhiPhan(chuoi, kieu[, tenFile])` | Gửi về dạng tệp tải xuống (kèm `Content-Disposition`) |
| `webJsonText(chuoi)` | Trả về một chuỗi JSON tự dựng (không đụng tới `Content-Type` thủ công) |
| `jsonEscape(chuoi)` (`hql.util`) | Làm sạch chuỗi để nhét an toàn vào JSON |

---

## 3. Ví dụ đủ một vòng: tải lên → lưu → hiển thị

### 3.1 Trình duyệt đọc ảnh thành chuỗi ký tự

Máy chủ HQL nhận tham số dạng `application/x-www-form-urlencoded`, nên phía trình
duyệt ta đọc file bằng `FileReader` rồi nhét chuỗi vào một ô nhập ẩn
(xem sẵn trong `SmartFarm/web/anh.js`, hàm `gan0Anh(...)`):

```html
<form method="post" action="/SmartFarm/CaNhan">
    <input type="hidden" name="act" value="luuAnh">
    <input type="hidden" name="anh" id="chuoiAnh">
    <input type="file" id="fileAnh" accept="image/*" style="display:none">
    <div class="anh-tai-len" id="vungAnh">Bấm hoặc kéo thả ảnh vào đây</div>
    <div class="anh-xem-truoc" id="xemAnh"></div>
    <button class="btn btn-primary">Lưu ảnh</button>
</form>
<script>
    gan0Anh('vungAnh', 'fileAnh', 'chuoiAnh', 'xemAnh', false, 512);
</script>
```

### 3.2 Máy chủ HQL cất chuỗi vào bảng

```java
use hql.image;
use hql.database;

func luuAnh(doiTuong, doiTuongId, tenFile, chuoiVao, ghiChu) {
    var kq = 0;
    var b64 = anhLamSach(str(chuoiVao));          // bỏ "data:image/png;base64,"
    if (len(b64) > 0 && anhHopLe(b64)) {
        var nen = anhThuNho(b64, 1280, 1280);      // cho nhẹ cơ sở dữ liệu
        executeSafe("INSERT INTO KhoAnh (ma_anh, doi_tuong, doi_tuong_id, ten_file, kieu_mime, chieu_rong, chieu_cao, dung_luong, du_lieu, ngay_tai) VALUES (?,?,?,?,?,?,?,?,?,GETDATE())",
            [uuid(), doiTuong, num(doiTuongId), tenFile, anhKieu(nen),
             num(anhRong(nen)), num(anhCao(nen)), num(anhDungLuong(nen)), nen]);
        kq = num(identity());
    }
    return kq;
}
```

### 3.3 Giải mã ngược lại để hiển thị

`web/AnhServlet.hql` → truy cập bằng `/SmartFarm/Anh?id=12`:

```java
func doGet() {
    for r in querySafe("SELECT ten_file, kieu_mime, du_lieu FROM KhoAnh WHERE id = ?", [num(param("id","0"))]) {
        if (param("tai","") == "1") { webNhiPhan(r.du_lieu, r.kieu_mime, r.ten_file); }
        if (param("tai","") != "1") { webAnh(r.du_lieu, r.kieu_mime); }
    }
    return true;
}
```

Trong HTML chỉ cần:

```html
<img src="/SmartFarm/Anh?id=12" alt="ảnh hoá đơn">
<a href="/SmartFarm/Anh?id=12&tai=1">Tải ảnh gốc về máy</a>
```

### 3.4 Cách 2 — nhúng thẳng vào trang, không cần servlet

```java
printWeb("<img src=\"" + anhDataUri(r.du_lieu) + "\">");
```

Cách này tiện cho ảnh nhỏ (biểu tượng, chữ ký) nhưng làm trang HTML phình to và
trình duyệt không nhớ đệm được, nên ảnh lớn vẫn nên đi qua `/Anh?id=..`.

---

## 4. Bảng `KhoAnh` trong Smart Farm

```sql
CREATE TABLE KhoAnh (
    id            int IDENTITY(1,1) PRIMARY KEY,
    ma_anh        varchar(60)   NOT NULL UNIQUE,  -- mã ngẫu nhiên
    doi_tuong     varchar(40)   NOT NULL,         -- AVATAR | HOA_DON | TIN_NHAN | TRANG_TRAI
    doi_tuong_id  int           NULL,             -- id bản ghi gắn với ảnh
    ten_file      nvarchar(255) NOT NULL,
    kieu_mime     varchar(60)   NOT NULL,
    chieu_rong    int           NOT NULL,
    chieu_cao     int           NOT NULL,
    dung_luong    int           NOT NULL,         -- byte
    du_lieu       varchar(max)  NOT NULL,         -- <<< CHUỖI BASE64 CỦA ẢNH
    ghi_chu       nvarchar(255) NULL,
    tai_khoan_tai_len int       NULL,
    ngay_tai      datetime      NOT NULL
);
```

Dùng `varchar(max)` chứ **không** dùng `nvarchar(max)`: base64 chỉ gồm ký tự ASCII
nên `varchar` tiết kiệm đúng một nửa dung lượng.

---

## 5. Lưu ý khi dùng

- Chuỗi base64 **dài hơn ảnh gốc khoảng 33%**. Ảnh 1 MB → chuỗi ~1,37 triệu ký tự.
  Vì thế luôn gọi `anhThuNho()` trước khi cất vào cơ sở dữ liệu.
- Cách này hợp với ảnh vừa và nhỏ (ảnh đại diện, hoá đơn, ảnh hiện trường).
  Với kho ảnh hàng chục nghìn tấm độ phân giải cao thì nên cân nhắc lưu ra đĩa.
- `anhThuNho()` giữ nền trong suốt nếu ảnh gốc là PNG có kênh alpha, còn lại xuất JPEG.
- `anhCatVuong()` luôn xuất JPEG nền trắng vì ảnh đại diện không cần nền trong suốt.
- Ảnh hỏng / tệp không phải ảnh: `anhHopLe()` trả về `false`, hãy kiểm tra trước khi lưu.
