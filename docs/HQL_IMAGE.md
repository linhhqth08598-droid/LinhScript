# HQL.IMAGE — Xử lý ảnh và lưu ảnh vào cơ sở dữ liệu

Thư viện `hql.image` làm hai việc:

1. **Xử lý ảnh trong bộ nhớ** — nạp, đổi cỡ, cắt, xoay, lật, làm mờ, đổi sang ảnh xám, đọc/ghi từng điểm ảnh.
2. **Mã hoá ảnh thành chuỗi base64** để cất thẳng vào cơ sở dữ liệu, rồi giải mã ngược lại khi cần hiển thị.

```java
use hql.image;
```

Ý tưởng của phần 2 rất đơn giản:

```
  logo.png  ──mã hoá──►  "iVBORw0KGgoAAAANSUhEUg..."  ──►  cột VARCHAR(MAX) trong CSDL
                                   │
                                   └──giải mã──►  ảnh PNG thật gửi về trình duyệt
```

Nhờ vậy **sao lưu cơ sở dữ liệu là đã sao lưu luôn toàn bộ ảnh**, không phải chép thêm thư mục `uploads/` nào cả.

---

## 1. Xử lý ảnh trong bộ nhớ

`loadImage` trả về một `id` (ví dụ `@img1`); các hàm sau **sửa trực tiếp** ảnh mang `id` đó.

| Hàm | Công dụng |
|---|---|
| `loadImage(path)` → id | Nạp ảnh từ file |
| `saveImage(id, "png"/"jpg", path)` | Lưu ảnh ra file |
| `imageWidth(id)`, `imageHeight(id)` | Chiều rộng / chiều cao (pixel) |
| `resizeImage(id, width, height)` | Đổi kích thước |
| `cropImage(id, x, y, width, height)` | Cắt một vùng |
| `rotateImage(id, angle)` | Xoay theo góc |
| `flipImage(id, "h"/"v")` | Lật ngang / dọc |
| `grayscaleImage(id)` | Đổi sang ảnh xám |
| `blurImage(id, level)` | Làm mờ |
| `invertImage(id)` | Đảo màu |
| `getPixelColor(id, x, y)` → `#rrggbb` | Đọc màu một điểm ảnh |
| `setPixelColor(id, x, y, "#rrggbb")` | Đặt màu một điểm ảnh |
| `imageToSvg(id[, path])` → đường dẫn | Ảnh → SVG |
| `svgToImage(svgTextOrPath)` → id | SVG → ảnh |

```java
use hql.image;

var img = loadImage("logo.png");
resizeImage(img, 200, 200);
grayscaleImage(img);
saveImage(img, "png", "logo_gray.png");
```

---

## 2. Ảnh ⇄ chuỗi base64

| Hàm | Công dụng |
|---|---|
| `imageFileToBase64(path)` | Đọc file ảnh trên đĩa → chuỗi base64 |
| `imageBase64ToFile(b64, path)` | Ghi chuỗi base64 ngược lại thành file ảnh, trả về số byte |
| `imageBase64Clean(b64)` | Bỏ phần đầu `data:image/png;base64,` và mọi khoảng trắng |
| `imageBase64Mime(b64)` | Nhận dạng định dạng: `image/png`, `image/jpeg`, `image/gif`, `image/webp`, `image/bmp` |
| `imageBase64Uri(b64[, mime])` | Trả về `data:image/png;base64,...` để dán thẳng vào `<img src>` |
| `imageBase64Size(b64)` | Số byte thật của ảnh |
| `imageBase64Valid(b64)` | `true` nếu giải mã ra được ảnh thật |
| `imageBase64Width(b64)`, `imageBase64Height(b64)` | Chiều rộng / chiều cao tính bằng điểm ảnh |
| `imageBase64Info(b64)` | Map `{ width, height, mime, size, valid }` |
| `imageBase64Resize(b64, maxWidth, maxHeight)` | Thu nhỏ giữ nguyên tỉ lệ (ảnh đã nhỏ hơn thì giữ nguyên) |
| `imageBase64Square(b64, side)` | Cắt giữa thành ảnh vuông — dùng cho ảnh đại diện |

---

## 3. Trả ảnh về trình duyệt (`hql.web`)

| Hàm | Công dụng |
|---|---|
| `webImage(b64[, mime])` | Giải mã chuỗi rồi gửi về trình duyệt dưới dạng **ảnh nhị phân thật** |
| `webBinary(b64, mime[, fileName])` | Gửi về dạng tệp tải xuống (kèm `Content-Disposition`) |
| `webJsonText(text)` | Trả về một chuỗi JSON tự dựng |
| `jsonEscape(text)` (`hql.util`) | Làm sạch chuỗi để nhét an toàn vào JSON |

---

## 4. Ví dụ đủ một vòng: tải lên → lưu → hiển thị

### 4.1 Trình duyệt đọc ảnh thành chuỗi ký tự

Máy chủ HQL nhận tham số dạng `application/x-www-form-urlencoded`, nên phía trình duyệt đọc file bằng `FileReader` rồi nhét chuỗi vào một ô nhập ẩn:

```html
<form method="post" action="<%= webContext() %>/Profile">
    <input type="hidden" name="act" value="saveImage">
    <input type="hidden" name="image" id="imageData">
    <input type="file" id="imageFile" accept="image/*">
    <button>Lưu ảnh</button>
</form>

<script>
document.getElementById('imageFile').addEventListener('change', function () {
    var reader = new FileReader();
    reader.onload = function () {
        document.getElementById('imageData').value = reader.result;   // "data:image/png;base64,...."
    };
    reader.readAsDataURL(this.files[0]);
});
</script>
```

### 4.2 Máy chủ HQL cất chuỗi vào bảng

```java
use hql.image;
use hql.database;
use hql.util;

func saveUploadedImage(ownerType, ownerId, fileName, rawData) {
    var id = 0;
    var b64 = imageBase64Clean(str(rawData));          // bỏ "data:image/png;base64,"
    if (len(b64) > 0 && imageBase64Valid(b64)) {
        var small = imageBase64Resize(b64, 1280, 1280);   // cho nhẹ cơ sở dữ liệu
        executeSafe("INSERT INTO ImageStore (image_code, owner_type, owner_id, file_name, mime_type, width, height, size_bytes, data, created_at) VALUES (?,?,?,?,?,?,?,?,?,GETDATE())",
            [uuid(), ownerType, num(ownerId), fileName, imageBase64Mime(small),
             num(imageBase64Width(small)), num(imageBase64Height(small)), num(imageBase64Size(small)), small]);
        id = num(identity());
    }
    return id;
}
```

### 4.3 Giải mã ngược lại để hiển thị

File `web/ImageServlet.hql` → truy cập bằng `/MyApp/Image?id=12`:

```java
func doGet() {
    for r in querySafe("SELECT file_name, mime_type, data FROM ImageStore WHERE id = ?", [num(param("id", "0"))]) {
        if (param("download", "") == "1") { webBinary(r.data, r.mime_type, r.file_name); }
        if (param("download", "") != "1") { webImage(r.data, r.mime_type); }
    }
    return true;
}
```

Trong HTML chỉ cần:

```html
<img src="/MyApp/Image?id=12" alt="Ảnh hoá đơn">
<a href="/MyApp/Image?id=12&download=1">Tải ảnh gốc về máy</a>
```

### 4.4 Cách 2 — nhúng thẳng vào trang, không cần servlet

```java
printWeb("<img src=\"" + imageBase64Uri(r.data) + "\">");
```

Cách này tiện cho ảnh nhỏ (biểu tượng, chữ ký) nhưng làm trang HTML phình to và trình duyệt không nhớ đệm được, nên ảnh lớn vẫn nên đi qua servlet.

---

## 5. Bảng mẫu `ImageStore`

```sql
CREATE TABLE ImageStore (
    id          int IDENTITY(1,1) PRIMARY KEY,
    image_code  varchar(60)   NOT NULL UNIQUE,   -- mã ngẫu nhiên
    owner_type  varchar(40)   NOT NULL,          -- AVATAR | INVOICE | MESSAGE ...
    owner_id    int           NULL,              -- id bản ghi gắn với ảnh
    file_name   nvarchar(255) NOT NULL,
    mime_type   varchar(60)   NOT NULL,
    width       int           NOT NULL,
    height      int           NOT NULL,
    size_bytes  int           NOT NULL,
    data        varchar(max)  NOT NULL,          -- chuỗi base64 của ảnh
    created_at  datetime      NOT NULL
);
```

Dùng `varchar(max)` chứ **không** dùng `nvarchar(max)`: base64 chỉ gồm ký tự ASCII nên `varchar` tiết kiệm đúng một nửa dung lượng.

---

## 6. Lưu ý khi dùng

- Chuỗi base64 **dài hơn ảnh gốc khoảng 33%**. Ảnh 1 MB → chuỗi ~1,37 triệu ký tự. Vì thế nên gọi `imageBase64Resize()` trước khi cất vào cơ sở dữ liệu.
- Cách này hợp với ảnh vừa và nhỏ (ảnh đại diện, hoá đơn, ảnh hiện trường). Với kho ảnh hàng chục nghìn tấm độ phân giải cao thì nên cân nhắc lưu ra đĩa.
- `imageBase64Resize()` giữ nền trong suốt nếu ảnh gốc là PNG có kênh alpha, còn lại xuất JPEG.
- `imageBase64Square()` luôn xuất JPEG nền trắng vì ảnh đại diện không cần nền trong suốt.
- Ảnh hỏng / tệp không phải ảnh: `imageBase64Valid()` trả về `false`, hãy kiểm tra trước khi lưu.
