package me.hql.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * ==========================================================================
 *  BÁO LỖI CỦA HQL — nói rõ HỎNG Ở ĐÂU, DÒNG NÀO, FILE NÀO, VÌ SAO.
 * ==========================================================================
 *
 *  VÌ SAO PHẢI CÓ FILE NÀY
 *
 *  Trước đây lỗi lúc chạy chỉ hiện ra đúng một câu, kiểu:
 *
 *      500 - Lỗi khi chạy code HQL
 *      Không tìm thấy biến 'soLam'
 *
 *  Nhìn vào đó thì chịu, không biết nó nằm ở file nào, dòng bao nhiêu, hàm nào
 *  gọi tới. Với một dự án như VNTracker (hơn 9.000 dòng, hai chục file .hql)
 *  thì đi dò bằng mắt mất cả buổi.
 *
 *  Từ nay mọi lỗi đều in ra một BÁO CÁO đầy đủ:
 *
 *      1. LOẠI LỖI      — mất mạng / cơ sở dữ liệu / thiếu dữ liệu / lỗi mã nguồn
 *      2. CÂU BÁO LỖI   — nguyên văn thứ động cơ ném ra
 *      3. VỊ TRÍ        — đường dẫn tuyệt đối + số dòng (bấm được trong IDE)
 *      4. TRÍCH ĐOẠN MÃ — vài dòng quanh chỗ hỏng, có mũi tên ► chỉ đúng dòng
 *      5. NGĂN XẾP GỌI  — hàm nào gọi hàm nào dẫn tới đây
 *      6. GỢI Ý         — theo từng loại lỗi, chỉ luôn việc cần làm
 *
 *  Cùng một báo cáo ấy được in ra HAI CHỖ: cửa sổ dòng lệnh (dạng chữ) và
 *  trình duyệt (dạng HTML), nên xem ở đâu cũng thấy như nhau.
 */
public final class BaoLoi {

    private BaoLoi() { }

    // ----------------------------------------------------------------------
    //  MỘT KHUNG TRONG NGĂN XẾP GỌI HÀM
    // ----------------------------------------------------------------------

    /** Một lượt gọi hàm đang dở: tên hàm, và chỗ đã gọi nó. */
    public static class Khung {
        public final String tenHam;
        public final String file;
        public final int dong;

        public Khung(String tenHam, String file, int dong) {
            this.tenHam = tenHam;
            this.file = file;
            this.dong = dong;
        }

        /**
         * ĐỌC CHO ĐÚNG NGHĨA: tenHam là hàm ĐƯỢC GỌI, còn file:dong là CHỖ ĐÃ GỌI
         * nó. Bản đầu in thành "được gọi từ &lt;tenHam&gt; — file:dòng" nên ai đọc cũng
         * hiểu ngược: tưởng tenHam là hàm đi gọi. Câu đúng phải là:
         *
         *      goiApiRiot()  — được gọi tại main.hql:1244
         */
        @Override
        public String toString() {
            if (file == null || file.isEmpty() || dong <= 0) {
                return tenHam + "()  — điểm vào của request";
            }
            return tenHam + "()  — được gọi tại " + duongDanGon(file) + ":" + dong;
        }
    }

    // ----------------------------------------------------------------------
    //  PHÂN LOẠI LỖI
    //
    //  Cùng một câu "Connection refused" mà người đọc hiểu là "mất mạng" thì
    //  sửa đúng chỗ ngay, còn hiểu nhầm thành "code sai" thì đi lục mã nguồn cả
    //  buổi. Nên bước đầu tiên luôn là gọi đúng tên cái hỏng.
    // ----------------------------------------------------------------------

    public static final String LOAI_MANG    = "MẤT KẾT NỐI MẠNG";
    public static final String LOAI_CSDL    = "LỖI CƠ SỞ DỮ LIỆU";
    public static final String LOAI_API     = "MÁY CHỦ DỮ LIỆU TRẢ VỀ LỖI";
    public static final String LOAI_THIEU   = "THIẾU DỮ LIỆU";
    public static final String LOAI_MA_NGUON = "LỖI TRONG MÃ NGUỒN HQL";
    public static final String LOAI_CU_PHAP = "LỖI CÚ PHÁP";
    public static final String LOAI_KHAC    = "LỖI KHÔNG RÕ NGUYÊN NHÂN";

    public static String loai(Throwable loi) {
        if (loi == null) { return LOAI_KHAC; }

        String ten = loi.getClass().getName();

        /*
         * PHẢI XÉT CẢ CHUỖI getMessage() LẪN TÊN LỚP.
         *
         * Rất nhiều ngoại lệ mạng của Java KHÔNG có mô tả:
         * UnresolvedAddressException ném ra với getMessage() = null. Bản đầu chỉ
         * dò trong getMessage() nên "không phân giải nổi tên miền" — tức là mất
         * mạng rành rành — lại bị xếp thành "lỗi trong mã nguồn HQL", rồi gợi ý
         * bảo người dùng đi đọc mã. Sai địa chỉ hoàn toàn.
         */
        String tin = ((loi.getMessage() == null ? "" : loi.getMessage()) + " " + ten).toLowerCase();

        // --- Mạng ---
        if (ten.contains("UnknownHostException")
         || ten.contains("UnresolvedAddressException")   // DNS không phân giải được
         || ten.contains("ConnectException")
         || ten.contains("HttpConnectTimeoutException")
         || ten.contains("HttpTimeoutException")
         || ten.contains("NoRouteToHostException")
         || ten.contains("PortUnreachableException")
         || ten.contains("SocketTimeoutException")
         || ten.contains("SocketException")
         || ten.contains("ClosedChannelException")
         || ten.contains("SSLException")
         || ten.contains("SSLHandshakeException")
         || tin.contains("connection refused")
         || tin.contains("connection reset")
         || tin.contains("connection timed out")
         || tin.contains("network is unreachable")
         || tin.contains("no such host")
         || tin.contains("không kết nối được ra internet")) {
            return LOAI_MANG;
        }

        // --- Cơ sở dữ liệu ---
        if (ten.contains("SQLException")
         || ten.contains("SQLServerException")
         || tin.contains("chưa kết nối database")
         || tin.contains("không kết nối được cơ sở dữ liệu")
         || tin.contains("invalid object name")
         || tin.contains("invalid column name")
         || tin.contains("login failed")) {
            return LOAI_CSDL;
        }

        // --- Máy chủ dữ liệu bên ngoài ---
        if (tin.contains("khoá api")
         || tin.contains("máy chủ dữ liệu trả về mã")
         || tin.contains("gọi quá nhanh")
         || tin.contains("henrikdev")) {
            return LOAI_API;
        }

        // --- Thiếu dữ liệu ---
        if (tin.contains("chưa được khởi tạo")
         || tin.contains("không tìm thấy biến")
         || tin.contains("nằm ngoài mảng")
         || tin.contains("nằm ngoài phạm vi")
         || ten.contains("NullPointerException")
         || ten.contains("IndexOutOfBoundsException")) {
            return LOAI_THIEU;
        }

        // --- Cú pháp ---
        if (tin.contains("lỗi cú pháp")
         || tin.contains("thiếu dấu")
         || tin.contains("dư toán tử")
         || tin.contains("không hợp lệ")) {
            return LOAI_CU_PHAP;
        }

        return LOAI_MA_NGUON;
    }

    /**
     * Gợi ý việc cần làm, viết theo kiểu chỉ tay chứ không nói chung chung.
     * Người đang bí thì cần biết BẤM VÀO ĐÂU, không cần nghe giảng.
     */
    public static String goiY(Throwable loi) {
        String l = loai(loi);
        String tin = loi == null || loi.getMessage() == null ? "" : loi.getMessage().toLowerCase();

        switch (l) {
            case LOAI_MANG:
                return "Máy không ra được Internet, hoặc máy chủ bên kia đang sập.\n"
                     + "  · Thử mở một trang web bất kỳ xem mạng còn không.\n"
                     + "  · Nếu đang gọi API Riot: máy chủ henrikdev hay sập vài phút rồi tự lên lại.\n"
                     + "  · Phần dữ liệu ĐÃ NẠP trong cơ sở dữ liệu vẫn xem được bình thường,\n"
                     + "    chỉ mỗi phần đồng bộ mới là phải chờ.";

            case LOAI_CSDL:
                if (tin.contains("invalid object name")) {
                    return "Câu truy vấn gọi tới một BẢNG CHƯA TỒN TẠI.\n"
                         + "  · Mở SQL Server Management Studio, chạy các file trong thư mục SQL\\\n"
                         + "    theo đúng thứ tự: VN_Tracker.sql -> VN_Tracker_DuLieuMau.sql -> rồi các file bổ sung.\n"
                         + "  · Tên bảng nằm ngay trong câu báo lỗi ở trên.";
                }
                if (tin.contains("login failed")) {
                    return "Sai tài khoản hoặc mật khẩu SQL Server.\n"
                         + "  · Sửa khối cấu hình DB_USER / DB_PASS ở đầu file main.hql.\n"
                         + "  · Kiểm tra SQL Server có đang bật chế độ đăng nhập hỗn hợp (Mixed Mode) không.";
                }
                return "Không nói chuyện được với SQL Server.\n"
                     + "  · Kiểm tra dịch vụ SQL Server đã chạy chưa (services.msc).\n"
                     + "  · Kiểm tra DB_HOST / DB_PORT / DB_NAME ở đầu file main.hql.\n"
                     + "  · Cổng 1433 có thể đang bị tường lửa chặn.";

            case LOAI_API:
                return "Máy chủ dữ liệu Riot từ chối lời gọi.\n"
                     + "  · Vào trang /DongBo xem khoá API còn đúng không (khoá henrikdev dài đúng 41 ký tự).\n"
                     + "  · Dính mã 429 nghĩa là gọi quá dày — chờ khoảng một phút rồi thử lại.\n"
                     + "  · Mã 500/502/503 là hỏng ở phía họ, khoá của mình vẫn tốt.";

            case LOAI_THIEU:
                return "Mã nguồn đọc một thứ chưa có giá trị.\n"
                     + "  · Biến chưa gán mà đã dùng, hoặc truy vấn không trả về dòng nào\n"
                     + "    mà mã vẫn đọc thẳng r.tenCot.\n"
                     + "  · Cách vá: kiểm tra len(danhSach) > 0 trước khi đọc, hoặc dùng\n"
                     + "    fetchOne() rồi so sánh khác null trước khi lấy cột.";

            case LOAI_CU_PHAP:
                return "Câu lệnh viết sai quy tắc của HQL.\n"
                     + "  · Xem lại dấu ngoặc, dấu chấm phẩy và toán tử ở đúng dòng ghi bên trên.\n"
                     + "  · Nhắc lại một bẫy hay dính: nối mảng phải dùng .push(), dấu + giữa hai\n"
                     + "    mảng là PHÉP CỘNG SỐ nên [] + [x] ra số 0 mà không báo lỗi gì.";

            default:
                return "Xem dòng mã được đánh dấu (>> ở dòng lệnh) ở trên, đó là câu lệnh đang chạy lúc nổ lỗi.\n"
                     + "  · Ngăn xếp gọi hàm cho biết đường đi tới chỗ đó.";
        }
    }

    // ----------------------------------------------------------------------
    //  ĐỌC MÃ NGUỒN QUANH DÒNG LỖI
    // ----------------------------------------------------------------------

    /** Một dòng mã trong đoạn trích: số dòng, nội dung, và có phải dòng lỗi không. */
    public static class DongMa {
        public final int so;
        public final String chu;
        public final boolean laDongLoi;

        DongMa(int so, String chu, boolean laDongLoi) {
            this.so = so;
            this.chu = chu;
            this.laDongLoi = laDongLoi;
        }
    }

    /**
     * Lấy vài dòng quanh dòng lỗi. Trả về danh sách rỗng nếu không đọc được file
     * — báo lỗi thì không bao giờ được phép ném ra lỗi mới.
     */
    public static List<DongMa> trichDoan(String file, int dongLoi, int banKinh) {
        List<DongMa> kq = new ArrayList<>();
        if (file == null || file.isEmpty() || dongLoi <= 0) { return kq; }

        try {
            Path p = Paths.get(file);
            if (!Files.exists(p)) { return kq; }

            List<String> dong = Files.readAllLines(p, StandardCharsets.UTF_8);
            int tu = Math.max(1, dongLoi - banKinh);
            int den = Math.min(dong.size(), dongLoi + banKinh);
            for (int i = tu; i <= den; i++) {
                kq.add(new DongMa(i, dong.get(i - 1), i == dongLoi));
            }
        } catch (IOException | RuntimeException e) {
            // Đọc không được thì thôi, phần còn lại của báo cáo vẫn có ích
        }
        return kq;
    }

    /** Đường dẫn tuyệt đối để bấm được trong IDE; hỏng thì trả nguyên đường dẫn cũ. */
    public static String duongDanDay(String file) {
        if (file == null || file.isEmpty()) { return "(không rõ file)"; }
        try {
            return Paths.get(file).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return file;
        }
    }

    /** Chỉ lấy tên file cho gọn, dùng trong ngăn xếp gọi hàm. */
    public static String duongDanGon(String file) {
        if (file == null || file.isEmpty()) { return "(không rõ file)"; }
        int vt = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return vt >= 0 ? file.substring(vt + 1) : file;
    }

    /** Lột hết lớp bọc để lấy đúng lỗi gốc. */
    public static Throwable goc(Throwable loi) {
        Throwable r = loi;
        while (r != null && r.getCause() != null && r.getCause() != r) { r = r.getCause(); }
        return r == null ? loi : r;
    }

    /**
     * Câu báo lỗi cho người đọc.
     *
     * Nhiều ngoại lệ của Java không kèm mô tả nào, in nguyên tên lớp ra thì
     * người đọc nhận về đúng một chuỗi vô nghĩa kiểu
     * "java.nio.channels.UnresolvedAddressException". Bảng dưới dịch mấy cái
     * hay gặp nhất sang câu tiếng Việt nói đúng chuyện gì đã xảy ra.
     */
    public static String cauBao(Throwable loi) {
        Throwable r = goc(loi);
        if (r == null) { return "(không có mô tả)"; }

        String m = r.getMessage();
        if (m != null && !m.isEmpty()) { return m; }

        String ten = r.getClass().getSimpleName();
        switch (ten) {
            case "UnresolvedAddressException":
                return "Không phân giải được tên miền của máy chủ — máy không ra được Internet, "
                     + "hoặc DNS đang hỏng. (" + ten + ")";
            case "UnknownHostException":
                return "Không tìm thấy máy chủ theo tên miền đã cho. (" + ten + ")";
            case "ConnectException":
                return "Máy chủ từ chối kết nối, hoặc không với tới được. (" + ten + ")";
            case "SocketTimeoutException":
            case "HttpConnectTimeoutException":
            case "HttpTimeoutException":
                return "Chờ quá lâu mà máy chủ không trả lời. (" + ten + ")";
            case "ClosedChannelException":
                return "Kết nối bị đóng giữa chừng. (" + ten + ")";
            case "NullPointerException":
                return "Đọc một giá trị đang rỗng (null). (" + ten + ")";
            default:
                return r.toString();
        }
    }

    // ----------------------------------------------------------------------
    //  BÁO CÁO DẠNG CHỮ — in ra cửa sổ dòng lệnh / log của Tomcat
    // ----------------------------------------------------------------------

    private static final String DO = "\u001B[31m";
    private static final String VANG = "\u001B[33m";
    private static final String XAM = "\u001B[90m";
    private static final String THOI = "\u001B[0m";

    /**
     * ==================================================================
     *  CỬA SỔ DÒNG LỆNH CỦA WINDOWS KHÔNG PHẢI LÚC NÀO CŨNG NHẬN UTF-8
     * ==================================================================
     *
     *  Bản đầu in thẳng chữ có dấu và khung kẻ ╔═╗, ra màn hình thành một mớ
     *  "L?I TRONG M� NGU?N HQL" — báo lỗi mà không đọc nổi thì thà đừng in.
     *
     *  Nguyên nhân: cmd.exe chạy ở code page 437/1258, còn Java lấy bảng mã cho
     *  System.err từ code page ấy chứ không phải từ -Dfile.encoding.
     *
     *  Cách chữa gồm hai lớp:
     *    · chay.bat truyền thêm -Dstdout.encoding / -Dstderr.encoding=UTF-8
     *    · và ngay tại đây: hỏi thẳng luồng err xem nó có encode nổi chữ Việt
     *      không. Không nổi thì tự BỎ DẤU và đổi khung sang ký tự ASCII.
     *      Nhờ vậy chạy ở IDE, ở cmd hay ở nhật ký Tomcat đều đọc được.
     */
    private static boolean consoleDocDuocTiengViet() {
        try {
            java.nio.charset.Charset cs = System.err.charset();
            return cs.newEncoder().canEncode("Lỗi mã nguồn — ►");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Bỏ dấu tiếng Việt: "Lỗi mã nguồn" -> "Loi ma nguon". */
    public static String boDau(String s) {
        if (s == null) { return ""; }
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                       .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return t.replace('đ', 'd').replace('Đ', 'D');
    }

    public static String banChu(Throwable loi, Token viTri, Deque<Khung> nganXep, boolean mauSac) {
        String s = dungBanChu(loi, viTri, nganXep, mauSac);
        return consoleDocDuocTiengViet() ? s : boDau(s);
    }

    private static String dungBanChu(Throwable loi, Token viTri, Deque<Khung> nganXep, boolean mauSac) {
        String do_ = mauSac ? DO : "";
        String vang = mauSac ? VANG : "";
        String xam = mauSac ? XAM : "";
        String thoi = mauSac ? THOI : "";

        // Khung kẻ bằng ASCII: ký tự ╔═╗ cũng vỡ y như chữ có dấu trên cmd.exe
        String ngang = "======================================================================";

        StringBuilder sb = new StringBuilder();
        String luc = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        sb.append(do_).append("\n+").append(ngang).append("\n");
        sb.append("|  ").append(loai(loi)).append("\n");
        sb.append("|  ").append(luc).append("\n");
        sb.append("+").append(ngang).append("\n");
        sb.append("|  ").append(cauBao(loi)).append("\n");
        sb.append("+").append(ngang).append(thoi).append("\n");

        // --- Vị trí ---
        if (viTri != null) {
            sb.append("\n").append(vang).append("VỊ TRÍ:").append(thoi).append("\n");
            sb.append("  ").append(duongDanDay(viTri.file)).append(":").append(viTri.line).append("\n");

            List<DongMa> doan = trichDoan(viTri.file, viTri.line, 3);
            if (!doan.isEmpty()) {
                sb.append("\n").append(vang).append("MÃ NGUỒN:").append(thoi).append("\n");
                for (DongMa d : doan) {
                    if (d.laDongLoi) {
                        sb.append(do_).append(String.format("  >> %4d | %s", d.so, d.chu)).append(thoi).append("\n");
                    } else {
                        sb.append(xam).append(String.format("    %4d | %s", d.so, d.chu)).append(thoi).append("\n");
                    }
                }
            }
        } else {
            sb.append("\n").append(vang).append("VỊ TRÍ:").append(thoi)
              .append(" không xác định được (lỗi nổ ra ngoài phần thân chương trình).\n");
        }

        // --- Ngăn xếp gọi hàm ---
        if (nganXep != null && !nganXep.isEmpty()) {
            sb.append("\n").append(vang).append("ĐƯỜNG ĐI TỚI CHỖ LỖI:").append(thoi).append("\n");
            int i = 0;
            for (Khung k : nganXep) {
                sb.append("  ").append(i == 0 ? "* đang trong  " : "  ^ nằm trong ").append(k).append("\n");
                i++;
            }
        }

        // --- Gợi ý ---
        sb.append("\n").append(vang).append("NÊN LÀM GÌ:").append(thoi).append("\n");
        for (String d : goiY(loi).split("\n")) { sb.append("  ").append(d).append("\n"); }

        // --- Dòng chuẩn cho IDE bấm được ---
        if (viTri != null) {
            sb.append("\n\tat ").append(duongDanDay(viTri.file)).append(":").append(viTri.line).append("\n");
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------------
    //  BÁO CÁO DẠNG HTML — hiện thẳng trên trình duyệt
    // ----------------------------------------------------------------------

    public static String banHtml(Throwable loi, Token viTri, Deque<Khung> nganXep, String duongDanTrang) {
        String luc = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy"));

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
          .append("<title>Lỗi HQL — ").append(esc(loai(loi))).append("</title><style>")
          .append("*{box-sizing:border-box}")
          .append("body{margin:0;padding:32px;background:#0f172a;color:#e2e8f0;")
          .append("font-family:'Segoe UI',system-ui,sans-serif;line-height:1.6}")
          .append(".hop{max-width:1000px;margin:0 auto}")
          .append(".dau{background:#7f1d1d;border:1px solid #b91c1c;border-radius:10px 10px 0 0;padding:18px 22px}")
          .append(".dau h1{margin:0;font-size:1.15rem;letter-spacing:.05em}")
          .append(".dau .luc{color:#fca5a5;font-size:.82rem;margin-top:4px}")
          .append(".tin{background:#1e293b;border:1px solid #334155;border-top:0;")
          .append("border-radius:0 0 10px 10px;padding:18px 22px;font-family:Consolas,monospace;")
          .append("font-size:.95rem;color:#fecaca;white-space:pre-wrap;word-break:break-word}")
          .append("h2{font-size:.78rem;letter-spacing:.12em;text-transform:uppercase;")
          .append("color:#94a3b8;margin:28px 0 10px}")
          .append(".vitri{background:#1e293b;border:1px solid #334155;border-radius:8px;")
          .append("padding:12px 16px;font-family:Consolas,monospace;font-size:.9rem;color:#7dd3fc;")
          .append("word-break:break-all}")
          .append("table.ma{width:100%;border-collapse:collapse;background:#111827;")
          .append("border:1px solid #334155;border-radius:8px;overflow:hidden;font-family:Consolas,monospace;font-size:.88rem}")
          .append("table.ma td{padding:3px 10px;vertical-align:top;white-space:pre}")
          .append("table.ma td.so{color:#64748b;text-align:right;width:60px;user-select:none;border-right:1px solid #334155}")
          .append("table.ma tr.loi{background:#450a0a}")
          .append("table.ma tr.loi td{color:#fecaca}")
          .append("table.ma tr.loi td.so{color:#f87171;font-weight:700}")
          .append(".xep{list-style:none;padding:0;margin:0}")
          .append(".xep li{background:#1e293b;border:1px solid #334155;border-radius:6px;")
          .append("padding:8px 14px;margin-bottom:6px;font-family:Consolas,monospace;font-size:.86rem}")
          .append(".xep li b{color:#fbbf24}")
          .append(".goi{background:#1c2a3a;border-left:4px solid #38bdf8;border-radius:0 8px 8px 0;")
          .append("padding:14px 18px;white-space:pre-wrap}")
          .append(".nut{display:inline-block;margin-top:26px;padding:10px 20px;background:#334155;")
          .append("color:#e2e8f0;text-decoration:none;border-radius:6px}")
          .append(".nut:hover{background:#475569}")
          .append(".chan{margin-top:30px;color:#64748b;font-size:.8rem}")
          .append("</style></head><body><div class=\"hop\">");

        // --- Đầu trang ---
        sb.append("<div class=\"dau\"><h1>⛔ ").append(esc(loai(loi))).append("</h1>")
          .append("<div class=\"luc\">Động cơ HQL · ").append(esc(luc));
        if (duongDanTrang != null && !duongDanTrang.isEmpty()) {
            sb.append(" · trang <code>").append(esc(duongDanTrang)).append("</code>");
        }
        sb.append("</div></div>");

        sb.append("<div class=\"tin\">").append(esc(cauBao(loi))).append("</div>");

        // --- Vị trí + trích đoạn mã ---
        if (viTri != null) {
            sb.append("<h2>Hỏng ở đâu</h2>")
              .append("<div class=\"vitri\">").append(esc(duongDanDay(viTri.file)))
              .append(" <b style=\"color:#f87171\">: dòng ").append(viTri.line).append("</b></div>");

            List<DongMa> doan = trichDoan(viTri.file, viTri.line, 4);
            if (!doan.isEmpty()) {
                sb.append("<h2>Mã nguồn quanh chỗ đó</h2><table class=\"ma\">");
                for (DongMa d : doan) {
                    sb.append("<tr").append(d.laDongLoi ? " class=\"loi\"" : "").append(">")
                      .append("<td class=\"so\">").append(d.laDongLoi ? "►" : "").append(d.so).append("</td>")
                      .append("<td>").append(esc(d.chu)).append("</td></tr>");
                }
                sb.append("</table>");
            }
        }

        // --- Ngăn xếp gọi hàm ---
        if (nganXep != null && !nganXep.isEmpty()) {
            sb.append("<h2>Đường đi tới chỗ lỗi</h2><ul class=\"xep\">");
            int i = 0;
            for (Khung k : nganXep) {
                sb.append("<li>").append(i == 0 ? "└─ đang trong " : "↑ nằm trong ")
                  .append("<b>").append(esc(k.tenHam)).append("()</b>");
                if (k.file == null || k.file.isEmpty() || k.dong <= 0) {
                    sb.append(" <span style=\"color:#64748b\">— điểm vào của request</span>");
                } else {
                    sb.append(" — được gọi tại ")
                      .append(esc(duongDanGon(k.file))).append(":").append(k.dong);
                }
                sb.append("</li>");
                i++;
            }
            sb.append("</ul>");
        }

        // --- Gợi ý ---
        sb.append("<h2>Nên làm gì</h2><div class=\"goi\">").append(esc(goiY(loi))).append("</div>");

        sb.append("<a class=\"nut\" href=\"javascript:location.reload()\">↻ Thử lại</a>");
        sb.append("<div class=\"chan\">Báo cáo này chỉ hiện khi chạy ở máy nhà. "
                + "Đưa trang ra Internet thì nhớ tắt bằng <code>webHienLoiChiTiet(false)</code> "
                + "— không ai cần thấy mã nguồn của bạn.</div>");

        sb.append("</div></body></html>");
        return sb.toString();
    }

    /** Bản rút gọn cho người dùng cuối: không lộ file, không lộ mã nguồn. */
    public static String banHtmlGon(Throwable loi) {
        return "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
             + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
             + "<title>Trang đang gặp sự cố</title></head>"
             + "<body style=\"margin:0;padding:48px;background:#0f172a;color:#e2e8f0;"
             + "font-family:'Segoe UI',system-ui,sans-serif;text-align:center\">"
             + "<h1 style=\"color:#f87171\">Trang đang gặp sự cố</h1>"
             + "<p style=\"color:#94a3b8\">" + esc(nhanHienNhe(loi)) + "</p>"
             + "<p><a href=\"javascript:location.reload()\" style=\"color:#38bdf8\">↻ Thử lại</a></p>"
             + "</body></html>";
    }

    /** Câu ngắn cho người dùng cuối đọc, không kèm chi tiết kỹ thuật. */
    public static String nhanHienNhe(Throwable loi) {
        switch (loai(loi)) {
            case LOAI_MANG: return "Máy chủ đang không ra được Internet. Thử lại sau ít phút.";
            case LOAI_CSDL: return "Không đọc được cơ sở dữ liệu. Người quản trị đã được ghi nhận lỗi này.";
            case LOAI_API:  return "Máy chủ dữ liệu Riot đang bận. Thử lại sau ít phút.";
            default:        return "Đã có lỗi xảy ra. Người quản trị đã được ghi nhận lỗi này.";
        }
    }

    // ----------------------------------------------------------------------
    private static String esc(String s) {
        if (s == null) { return ""; }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&#39;");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
