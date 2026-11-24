# 📊 HQL.EXPORT — Xuất XLSX / DOCX / PDF / CSV có màu sắc, tự cấu hình

Thư viện thứ 22 của HQL. Không cần thư viện ngoài (không Apache POI, không iText): tự ghép tệp
zip + XML cho XLSX/DOCX, tự ghi cú pháp PDF và **nhúng font của máy để hiện đúng tiếng Việt**.
Động cơ nằm ở `HqlExport.java`.

```java
use hql.export;
```

> **Một bước là xong:** không truyền tuỳ chọn vẫn ra bảng đẹp (tiêu đề xanh, viền xám, đóng băng dòng đầu).
> Muốn đổi gì thì chỉ ghi đúng thứ đó vào `tuỳ_chọn`, phần còn lại giữ mặc định.

---

## 1. Các hàm

| Hàm | Công dụng |
|---|---|
| `exportXlsx(dữ_liệu, "file.xlsx"[, tuỳ_chọn])` | Xuất Excel |
| `exportDocx(dữ_liệu, "file.docx"[, tuỳ_chọn])` | Xuất Word (bảng) |
| `exportPdf(dữ_liệu, "file.pdf"[, tuỳ_chọn])` | Xuất PDF (tự sang trang, lặp tiêu đề, số trang) |
| `exportFile(dữ_liệu, "file.xlsx"[, tuỳ_chọn])` | Tự chọn định dạng theo đuôi tệp: `.xlsx` `.docx` `.pdf` `.csv` |
| `webExport(dữ_liệu, "bao-cao.xlsx"[, tuỳ_chọn])` | Trong web (`hql.web`): trả file về trình duyệt để **tải xuống** |

Tất cả trả `true` (riêng `webExport` trả số byte). Tên tệp chưa có đuôi thì tự thêm đúng đuôi.
`exportCSV("SELECT...", "file.csv")` của `hql.database` vẫn giữ nguyên.

### `dữ_liệu` có thể là

* **Chuỗi SQL** — chạy trên Database đang kết nối (giống `exportCSV`), bảng rỗng vẫn giữ dòng tiêu đề:
  `exportXlsx("SELECT * FROM SanPham", "sp.xlsx");`
* **Mảng bản ghi** (kết quả của `query(...)`, `readCsvRecords(...)`...): tên cột lấy từ khoá của bản ghi.
* **Mảng các mảng**: dòng đầu là tiêu đề (hoặc đặt `headers` trong tuỳ chọn, khi đó dòng đầu là dữ liệu).
* **Một bản ghi** duy nhất.

> Bản ghi dạng `HashMap` (ví dụ từ `readCsvRecords`) không có thứ tự cột cố định —
> hãy thêm `only: ['Tên', 'Điểm']` để chọn và sắp xếp cột. Kết quả `query(...)` thì đã đúng thứ tự.

### `tuỳ_chọn` là

Một Map **hoặc một chuỗi JSON**. Chuỗi JSON cho phép nháy đơn `'...'` và khoá viết trần (`bold:true`)
nên rất tiện khi viết trong mã HQL:

```java
use hql.export;

var tuyChon = "{ title:'BÁO CÁO DOANH THU', subtitle:'Tháng 11/2026', header:{bg:'#7C3AED', color:'white'} }";
exportXlsx(query("SELECT * FROM DonHang"), "donhang.xlsx", tuyChon);
```

Hoặc viết thẳng bằng map literal (kết quả file giống hệt):

```java
var tuyChon = {
    "title": "BÁO CÁO DOANH THU",
    "subtitle": "Tháng 11/2026",
    "zebra": true,
    "header": { "bg": "#7C3AED", "color": "white" }
};
exportXlsx(query("SELECT * FROM DonHang"), "donhang.xlsx", tuyChon);
```

---

## 2. Kiểu chữ / màu của MỘT phần tử

Mọi khoá đều **không bắt buộc**. Dùng được cho `header`, `body`, `title`, `subtitle`, từng cột, từng ô, và luật.

| Khoá | Ý nghĩa | Ví dụ |
|---|---|---|
| `font` | Phông chữ | `'Arial'`, `'Times New Roman'` |
| `size` | Cỡ chữ | `12` |
| `bold` | **In đậm** | `true` |
| `italic` | *In nghiêng* | `true` |
| `underline` | Gạch chân | `true` |
| `strike` | Gạch ngang | `true` |
| `color` | Màu chữ | `'#FF0000'` |
| `bg` | Màu nền ô | `'#FEF3C7'` |
| `align` | Căn lề ngang: `left` / `center` / `right` | `'center'` |
| `valign` | Căn dọc: `top` / `middle` / `bottom` | `'middle'` |
| `wrap` | Tự xuống dòng trong ô | `true` |
| `border` | `true` / `false` / một màu viền | `'#999999'` |
| `format` | Định dạng số kiểu Excel | `'#,##0'`, `'#,##0.00'`, `'0.0%'` |

**Màu** viết được là: `'#RRGGBB'`, `'#RGB'`, `'rgb(r,g,b)'` hoặc tên:
`black white red green blue navy cyan yellow orange purple pink gray lightgray brown gold teal lime`
và tên tiếng Việt `den trang do xanhla xanhduong vang cam tim hong xam nau`.

Cũng nhận khoá tiếng Việt: `phongchu`, `codechu`, `dam`, `nghieng`, `gachchan`, `gachngang`, `mauchu`, `maunen`, `canle`, `vien`.

---

## 3. Tuỳ chọn cấp cao nhất

| Khoá | Công dụng |
|---|---|
| `sheet` | Tên sheet (XLSX) |
| `title`, `subtitle` | Tiêu đề / phụ đề phía trên bảng. Là chuỗi, hoặc `{text:'...', color:..., size:...}` |
| `titleStyle`, `subtitleStyle` | Kiểu riêng cho tiêu đề / phụ đề |
| `font`, `size` | Phông và cỡ chữ **toàn bảng** |
| `header` | Kiểu dòng tiêu đề cột (mặc định: chữ trắng đậm, nền xanh `#1D4ED8`) |
| `body` | Kiểu chung cho dữ liệu |
| `zebra` | Dòng xen kẽ: `true`, một màu `'#F3F4F6'`, hoặc `{bg:..., color:...}` |
| `columns` | Kiểu theo cột (mục 4) |
| `rules` | Tô màu theo điều kiện (mục 5) |
| `cells` | Tô riêng từng ô / dòng / cột (mục 6) |
| `only` | Chỉ lấy và sắp xếp lại các cột này: `['Tên','Điểm']` |
| `labels` | Đổi tên hiển thị của cột: `{ten:'Họ tên', diem:'Điểm'}` |
| `headers` | Danh sách tên cột (cho mảng các mảng không có dòng tiêu đề) |
| `freeze` | XLSX: đóng băng dòng tiêu đề (mặc định `true`) |
| `filter` | XLSX: bật bộ lọc trên dòng tiêu đề (mặc định `false`) |
| `gridlines` | XLSX: hiện đường lưới (mặc định `true`) |
| `orientation` | XLSX/DOCX/PDF: `'landscape'` (mặc định, giấy ngang) hoặc `'portrait'` |
| `pageSize` | PDF: `a4` (mặc định) `a3` `a5` `letter` `legal` |
| `margin` | PDF: lề giấy (pt, mặc định 36) |
| `pageNumbers` | PDF: in số trang (mặc định `true`) |
| `footer` | PDF: chữ ở chân trang |
| `fontFile` | PDF: đường dẫn tệp `.ttf` muốn nhúng (khi máy không có phông cần dùng) |
| `numberLocale` | DOCX/PDF: `'vi'` (mặc định, `1.234.567,50`) hoặc `'us'` (`1,234,567.50`). XLSX luôn theo máy người mở |
| `delimiter`, `bom` | Chỉ CSV: ký tự ngăn cột (mặc định `,`) và có ghi BOM cho Excel không (mặc định `true`) |

---

## 4. Theo cột — `columns`

Khoá là **tên cột** hoặc **số thứ tự** (1 = cột đầu). Thêm `width` (đơn vị ký tự) để chỉnh độ rộng.

```java
columns: {
  'Doanh thu': { format:'#,##0', color:'#065F46', bold:true, width:18 },
  'Họ tên':    { italic:true, underline:true },
  3:           { align:'center' }
}
```

## 5. Tô màu theo điều kiện — `rules`

Mỗi luật: `col` (tên/số cột, bỏ trống hoặc `'*'` = mọi cột), `op`, `value`, tuỳ chọn `scope:'row'`
(tô **cả dòng**) và các khoá kiểu chữ.

`op`: `<` `<=` `>` `>=` `==` `!=` `contains` `startsWith` `endsWith` `empty` `notEmpty`
(tiếng Việt: `chua`, `trong`, `khongtrong`).

```java
rules: [
  { col:'Tồn kho', op:'<', value:10, bg:'#FEE2E2', color:'#B91C1C', bold:true },
  { col:'Ghi chú', op:'contains', value:'Nợ', scope:'row', bg:'#FEF3C7' }
]
```

## 6. Tô riêng từng ô / dòng / cột — `cells`

`row`: `0` = dòng tiêu đề, `1` = dòng dữ liệu đầu tiên... (bỏ trống hoặc `'*'` = mọi dòng).
`col`: tên hoặc số thứ tự cột (bỏ trống hoặc `'*'` = mọi cột).

```java
cells: [
  { row:0, bg:'#111827' },                       // cả dòng tiêu đề
  { row:2, col:'Họ tên', bg:'#DBEAFE', bold:true }, // 1 ô
  { col:'Mã', color:'gray' }                      // cả cột
]
```

Thứ tự ưu tiên (cái sau ghi đè cái trước): mặc định → `body` → `zebra` → `columns` → `rules` → `cells`.

---

## 7. Ví dụ đầy đủ

Dùng chuỗi nhiều dòng `"""..."""` cho tuỳ chọn dài (nháy đơn bên trong không cần thoát):

```java
use hql.database;
use hql.export;

connectEnv();

var tuyChon = """
{
  sheet: 'Doanh thu',
  title: 'BÁO CÁO DOANH THU',
  subtitle: 'Tháng 11/2026',
  font: 'Arial',
  zebra: '#F8FAFC',
  labels: { TenKH:'Họ tên', SoLuong:'Số lượng', TongTien:'Doanh thu', GhiChu:'Ghi chú' },
  header: { bg:'#7C3AED', color:'white', bold:true },
  columns: {
    'Doanh thu': { format:'#,##0', bold:true, color:'#065F46', width:18 }
  },
  rules: [
    { col:'Số lượng', op:'<', value:10, bg:'#FEE2E2', color:'#B91C1C', strike:true },
    { col:'Ghi chú',  op:'contains', value:'Nợ', scope:'row', bg:'#FEF3C7' }
  ],
  filter: true,
  footer: 'My Shop'
}
""";

var sql = "SELECT TenKH, SoLuong, TongTien, GhiChu FROM DonHang";

exportXlsx(sql, "baocao.xlsx", tuyChon);
exportDocx(sql, "baocao.docx", tuyChon);
exportPdf (sql, "baocao.pdf",  tuyChon);
exportFile(sql, "baocao.csv");        // CSV thường, có BOM cho Excel
```

### Tải xuống từ trang web (HQL.WEB)

Người dùng chọn định dạng và màu trên trang, server trả file về luôn.
File `web/XuatServlet.hql` → truy cập `/MyApp/Xuat?fmt=pdf&mau=%237C3AED`:

```java
use hql.web;
use hql.database;
use hql.export;

func doGet() {
    var dinhDang = param("fmt", "xlsx");      // xlsx | docx | pdf | csv
    var mau = param("mau", "#1D4ED8");        // mã màu người dùng chọn, dạng #RRGGBB
    if (len(mau) != 7) { mau = "#1D4ED8"; }   // chặn chuỗi lạ chèn vào tuỳ chọn

    connectEnv();
    var tuyChon = "{ title:'Danh sách sản phẩm', header:{ bg:'" + mau + "', color:'white', bold:true } }";
    webExport("SELECT * FROM SanPham", "sanpham." + dinhDang, tuyChon);
}
```

> Màu hoặc cỡ chữ không hiểu được sẽ bị bỏ qua (dùng giá trị mặc định), không gây lỗi.
> Chuỗi JSON sai cú pháp thì báo lỗi rõ vị trí.

---

## 8. Lưu ý

* **PDF** cần một font TrueType có sẵn trên máy (Windows/macOS/Linux đều có Arial hoặc tương đương).
  Font được nhúng nguyên bản nên tệp PDF khá nặng (~500 KB) — chỉ chứa đúng phông bạn dùng.
  Muốn dùng phông riêng: `fontFile: 'C:/fonts/Roboto-Regular.ttf'`.
* **XLSX** giữ số là **số thật** (sắp xếp, cộng được trong Excel); ngày từ SQL ra dạng chữ `2026-11-22`.
* Bảng lớn: thử 20.000 dòng XLSX ≈ 1 giây (đã dựng sẵn trong bộ nhớ, nên đừng xuất hàng triệu dòng).
* Muốn **CSV thật nhẹ, đọc từng lô** cho bảng khổng lồ: dùng `exportCSV(sql, path)` (ghi trực tiếp, không giữ cả bảng trong RAM).
