# 📧 HQL.MAIL — Thư viện gửi email tự động cho ngôn ngữ HQL

Thư viện thứ 21 của HQL, bổ sung tháng 8/2026. Dựa trên `jakarta.mail` (đã có sẵn trong `lib/`).

```java
use hql.mail;
```

---

## 1. Cấu hình hộp thư gửi đi

| Hàm | Công dụng |
|---|---|
| `mailConfig(email, matKhauUngDung)` | Cấu hình Gmail (mặc định `smtp.gmail.com:587`, STARTTLS) |
| `mailConfig(email, matKhau, host, port)` | Dùng máy chủ SMTP khác. Cổng `465` tự bật SSL |
| `mailSenderName("Smart Farm")` | Tên hiển thị của người gửi |
| `mailBrand(ten, khauHieu)` | Tên và dòng mô tả in trên **đầu mỗi lá thư** |
| `mailSupport(email1[, email2])` | Địa chỉ hỗ trợ in ở **cuối mỗi lá thư** |
| `mailIsConfigured()` | Đã cấu hình hộp thư chưa (`true`/`false`) |
| `mailError()` | Nội dung lỗi của lần gửi gần nhất |

> Gmail bắt buộc dùng **Mật khẩu ứng dụng** (App Password) 16 ký tự,
> không dùng được mật khẩu đăng nhập thường.

```java
mailConfig("smartfarmmanage@gmail.com", "grxhduafxftczxjm");
mailSenderName("Smart Farm - Hệ thống quản lý trang trại");
mailBrand("SMART FARM", "Hệ thống quản lý trang trại sầu riêng");
mailSupport("linhhqth08598@gmail.com");
```

---

## 2. Gửi thư

| Hàm | Công dụng |
|---|---|
| `mailSend(toi, tieuDe, htmlBody)` | Gửi thư HTML. Trả `true`/`false` |
| `mailSendText(toi, tieuDe, vanBan)` | Gửi thư văn bản thuần |
| `mailSendAsync(toi, tieuDe, htmlBody)` | Gửi ở luồng riêng — trang web **không bị đứng** chờ Gmail |

```java
var ok = mailSend("abc@gmail.com", "Chào bạn", "<h2>Xin chào</h2><p>Nội dung thư</p>");
if (ok == false) { print("Gửi lỗi: " + mailError()); }
```

---

## 3. Mẫu thư dựng sẵn (đã định dạng đẹp, tiếng Việt)

| Hàm | Dùng khi |
|---|---|
| `mailSendAccount(email, hoTen, tenDangNhap, matKhau)` | Cấp tài khoản mới cho nhân viên |
| `mailSendOtp(email, hoTen, ma, soPhut)` | Gửi mã xác thực quên mật khẩu |
| `mailSendNotify(email, tieuDe, noiDungHtml)` | Thông báo chung của hệ thống |

```java
mailSendAccount("nv@gmail.com", "Trần Thị Mai", "maitt", "Kx7dPq2a");
mailSendOtp("nv@gmail.com", "Trần Thị Mai", mailOtp(6), 10);
```

### Giao diện thư (thiết kế lại tháng 8/2026)

Cả ba mẫu thư dùng chung một khung dựng bằng `<table>` + style nội tuyến —
đây là cách duy nhất hiển thị đúng trên Gmail và Outlook, vì hai nơi này
cắt bỏ thẻ `<style>` và không hiểu flexbox/grid.

Khung thư gồm 4 tầng:

```
┌──────────────────────────────────────────┐
│ 🌱 SMART FARM                            │  ← dải màu xanh lá chuyển sắc
│    Hệ thống quản lý trang trại sầu riêng │     (mailBrand)
│    ┌────────────┐                        │
│    │ TÀI KHOẢN MỚI │  ← huy hiệu nhỏ      │
│    └────────────┘                        │
│    Chào mừng bạn gia nhập Smart Farm!    │
├──────────────────────────────────────────┤
│ Nội dung thư                             │
│   ┌───────────┬────────────────────┐     │
│   │ TÊN ĐĂNG NHẬP │ minhpv          │    │  ← ô "nhãn : giá trị"
│   ├───────────┼────────────────────┤     │
│   │ MẬT KHẨU  │ Tf2PRhfj           │     │
│   └───────────┴────────────────────┘     │
│   ⚠️ Lưu ý bảo mật (khung đỏ nhạt)       │
├──────────────────────────────────────────┤
│ Cần hỗ trợ? → email kỹ thuật (mailSupport)│
├──────────────────────────────────────────┤
│ Thư tự động, vui lòng không phản hồi     │
└──────────────────────────────────────────┘
```

Mỗi mẫu có màu chủ đạo riêng cho dễ phân biệt trong hộp thư:

| Mẫu | Huy hiệu | Màu chủ đạo |
|---|---|---|
| `mailSendAccount` | TÀI KHOẢN MỚI | Xanh lá `#27ae60` |
| `mailSendOtp` | XÁC THỰC | Cam `#e67e22` — mã OTP hiện thành từng ô số rời |
| `mailSendNotify` | THÔNG BÁO | Xanh dương `#2d7ff9` |

Thư cũng có sẵn **preheader ẩn** (dòng xem trước hiện cạnh tiêu đề trong hộp thư)
và co giãn tốt trên điện thoại nhờ `max-width:100%`.

---

## 4. Tiện ích

| Hàm | Công dụng |
|---|---|
| `mailOtp(n)` | Sinh mã OTP ngẫu nhiên `n` chữ số (mặc định 6), dùng `SecureRandom` |
| `mailPassword(n)` | Sinh mật khẩu ngẫu nhiên dễ đọc (luôn có chữ hoa, chữ thường, số) |
| `mailIsValid(email)` | Kiểm tra định dạng địa chỉ email |
| `mailAttach(duongDan)` | Đính kèm tệp cho **lần gửi kế tiếp** |
| `mailClearAttach()` | Xoá danh sách tệp đính kèm đang chờ |

```java
var ma = mailOtp(6);          // "482915"
var mk = mailPassword(10);    // "Kf3aWqz8Rt"
print(mailIsValid("a@b.com"));   // true

mailAttach("D:/bao-cao-thang.pdf");
mailSend("sep@gmail.com", "Báo cáo tháng", "<p>Xem file đính kèm.</p>");
```

---

## 5. Ví dụ đầy đủ trong servlet

```java
use hql.web;
use hql.mail;
use hql.database;

func doPost() {
    var email = param("email");
    var ma = mailOtp(6);

    executeSafe("INSERT INTO MaXacThucMatKhau (id, tai_khoan_id, ma_xac_thuc, thoi_gian_tao, thoi_gian_het_han, da_su_dung) VALUES (?, ?, ?, GETDATE(), DATEADD(minute, 10, GETDATE()), 0)",
                [idMoi, tkId, ma]);

    if (mailSendOtp(email, hoTen, ma, 10)) {
        printWeb("<p>Đã gửi mã xác thực, vui lòng kiểm tra hộp thư.</p>");
    } else {
        printWeb("<p>Không gửi được: " + htmlEscape(mailError()) + "</p>");
    }
}
```

---

## 6. Lỗi thường gặp

| Lỗi | Cách xử lý |
|---|---|
| `Chưa gọi mailConfig(...)` | Phải gọi `mailConfig()` một lần trước khi gửi (nên đặt trong file main) |
| `Username and Password not accepted` | Sai App Password, hoặc tài khoản chưa bật xác thực 2 bước |
| `Couldn't connect to host` | Máy không ra được Internet, hoặc tường lửa chặn cổng 587 |
| Trang web bị treo vài giây khi gửi | Dùng `mailSendAsync()` thay cho `mailSend()` |
| `ClassNotFoundException: jakarta.mail...` | Thiếu `lib/jakarta.mail-2.0.1.jar` và `lib/jakarta.activation-2.0.1.jar` trong classpath |
