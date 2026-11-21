package me.hql.script;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.net.*;
import javax.net.ssl.*;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.awt.geom.Path2D;
import java.awt.geom.Ellipse2D;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.Graphics2D;
import java.awt.Image;
import java.io.File;
import java.awt.geom.AffineTransform;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.sound.sampled.*;
import java.awt.event.*;
import javax.sound.sampled.*;
import java.util.regex.Matcher;
import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoUnit;
import java.io.*;
import java.sql.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.swing.*;
import java.awt.*;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.lang.management.ManagementFactory;
import java.io.FileOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.time.Duration;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.util.Map;
import java.util.Iterator;
import java.awt.geom.Area;

/**
 * Trình thông dịch hoàn thiện 100% cho ngôn ngữ HQL (LinhScript).
 */
public class Interpreter {

    // Biến kiểm soát quy tắc cấu trúc: Chỉ cho phép USE/IMPORT ở đầu file
    private boolean isHeaderSection = true;

    // --- BỘ NHỚ HQL.LOGGING ---
    private String loggingLevel = "info"; // Các mức: debug, info, warning, error, off
    private String loggingFilePath = null;

    private static class Scope {


        private final Scope parent;
        /** true = đây là vùng nhớ gốc của một hàm (hoặc của toàn chương trình). */
        private final boolean isFunctionRoot;
        private final java.util.Map<String, Object> variables;

        Scope(Scope parent) { this(parent, false); }
        Scope(Scope parent, boolean isFunctionRoot) {
            this.parent = parent;
            this.isFunctionRoot = isFunctionRoot;
            this.variables = new HashMap<>();   // vùng nhớ cục bộ: chỉ một luồng đụng tới
        }

        /*
         * VÙNG NHỚ GỐC — nơi ở của biến toàn cục, và là thứ DUY NHẤT dùng chung
         * giữa các luồng (luồng chính, luồng web, luồng hẹn giờ). HashMap trần mà
         * hai luồng cùng ghi đúng lúc nó nở bảng là hỏng ngầm, không báo lỗi gì
         * cả — kiểu hỏng khó lần ra nhất. Nên gốc dùng map có khoá.
         */
        Scope() {
            this.parent = null;
            this.isFunctionRoot = true;
            this.variables = java.util.Collections.synchronizedMap(new HashMap<>());
        }

        public Scope getParent() { return parent; }

        public void put(String name, Object value) { variables.put(name, value); }

        /**
         * Khai báo biến bằng từ khoá 'var'.
         * Biến luôn nằm trong vùng nhớ của HÀM hiện tại (giống 'var' của JavaScript)
         * nên KHÔNG BAO GIỜ đè lên biến trùng tên của hàm đã gọi nó.
         */
        public void declare(String name, Object value) {
            Scope s = this;
            while (!s.isFunctionRoot && s.parent != null) s = s.parent;
            s.variables.put(name, value);
        }
        public void assign(String name, Object value) {
            if (variables.containsKey(name) || parent == null) {
                variables.put(name, value);
            } else {
                parent.assign(name, value);
            }
        }

        /**
         * Chụp danh sách tên biến. Vùng nhớ gốc dùng map có khoá, mà duyệt map
         * loại đó vẫn phải tự giữ khoá — không thì luồng khác ghi một cái là
         * ném ConcurrentModificationException giữa chừng.
         */
        public java.util.Set<String> tenBien() {
            synchronized (variables) { return new java.util.HashSet<>(variables.keySet()); }
        }

        public boolean has(String name) {
            if (variables.containsKey(name)) return true;
            return parent != null && parent.has(name);
        }

        public Object get(String name) {
            if (variables.containsKey(name)) return variables.get(name);
            if (parent != null) return parent.get(name);
            return null;
        }
    }

    // --- BỘ NHỚ HQL.CONFIG ---
    private class HqlConfig {
        String id;
        // Dùng LinkedHashMap để giữ nguyên thứ tự dòng khi lưu file
        java.util.LinkedHashMap<String, String> globalKeys = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, String>> sections = new java.util.LinkedHashMap<>();
    }

    // --- BỘ NHỚ HQL.SOCKET ---
    private final HashMap<String, Socket> tcpClients = new HashMap<>();
    private final HashMap<String, ServerSocket> tcpServers = new HashMap<>();
    private final HashMap<String, DatagramSocket> udpSockets = new HashMap<>();
    private final HashMap<String, WebSocket> webSockets = new HashMap<>();
    private final HashMap<String, ArrayList<String>> wsMessages = new HashMap<>(); // Lưu tin nhắn WebSocket tới
    private int socketIdCounter = 0;
    // --- BỘ TRỢ THỦ SOCKET ĐẠT 110% ---
    private final HashMap<String, Cipher> socketEncryptors = new HashMap<>();
    private final HashMap<String, Cipher> socketDecryptors = new HashMap<>();

    private final HashMap<String, HqlConfig> configLibrary = new HashMap<>();
    private int configIdCounter = 0;

    // --- BỘ NHỚ HQL.CHART ---
    private class HqlChart {
        String id;
        String title;
        int width = 800;
        int height = 600;
        Color color = new Color(70, 130, 180); // Mặc định: Xanh SteelBlue (rất hợp phân tích dữ liệu)
        ArrayList<Double> dataX = new ArrayList<>();
        ArrayList<Double> dataY = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        String type; // "bar", "line", "pie", "scatter", "histogram"
        int bins = 10; // Dùng cho histogram
    }
    private final HashMap<String, HqlChart> chartLibrary = new HashMap<>();
    private int chartIdCounter = 0;

    // --- BỘ NHỚ HQL.IMAGE ---
    private final HashMap<String, BufferedImage> imageLibrary = new HashMap<>();
    private int imageIdCounter = 0;

    // --- LỚP HỖ TRỢ NGẮT LUỒNG (BREAK / CONTINUE) ---
    private static class BreakException extends RuntimeException {}
    private static class ContinueException extends RuntimeException {}
    /** Ném ra khi gặp lệnh 'return' để nhảy thẳng ra khỏi hàm (kể cả khi đang nằm sâu trong if/while). */
    private static class ReturnException extends RuntimeException {
        ReturnException() { super(null, null, false, false); } // tắt stack trace cho nhẹ
    }

    /*
     * VÙNG NHỚ: MỖI LUỒNG MỘT CHUỖI RIÊNG, CHUNG ĐÚNG MỘT GỐC TOÀN CỤC.
     *
     * Mã HQL chạy trên nhiều luồng: luồng chính nạp main.hql, luồng web xử lý
     * request, luồng hẹn giờ chạy taskRepeat. Trước đây cả ba dùng chung một
     * biến scope(), mà mỗi lần vào/ra hàm là nó bị trỏ đi chỗ khác — nên
     * luồng này nhảy vào hàm là luồng kia mất sạch biến cục bộ giữa chừng.
     *
     * Nó hỏng đúng vào bộ việc nền của VNChecker: hẹn giờ gọi goiNoiBo(viec),
     * hàm ấy gọi HTTP sang /Nen của chính máy chủ, luồng web chạy việc nặng và
     * đạp lên scope(); hẹn giờ quay lại đọc tham số 'viec' thì không còn:
     *     [ERROR] Task thuThapNen failed: Không tìm thấy biến 'viec'
     * Nghĩa là hai luồng CHẮC CHẮN chồng nhau, không phải xui mới gặp.
     *
     * Không chữa bằng synchronized được: luồng hẹn giờ đang giữ khoá lại đi gọi
     * HTTP vào chính mình, luồng web cần đúng khoá đó — kẹt cứng tới lúc hết giờ.
     * Tách vùng nhớ theo luồng mới là đúng gốc: biến cục bộ ai nấy giữ, còn biến
     * toàn cục vẫn chung một gốc nên mọi luồng vẫn thấy CONG, TEN_PROJECT...
     */
    private final Scope scopeGoc = new Scope();
    private final ThreadLocal<Scope> scopeCuaLuong = ThreadLocal.withInitial(() -> scopeGoc);
    private Scope scope() { return scopeCuaLuong.get(); }
    private void datScope(Scope s) { scopeCuaLuong.set(s); }
    private final HashMap<String, FunctionDef> functionMemory = new HashMap<>();
    private final HashMap<String, HqlClass> hqlClasses = new HashMap<>();
    private final Set<String> usedLibraries = new HashSet<>();
    private final List<String> parsedFiles = new ArrayList<>();
    private final Set<String> executedFiles = new HashSet<>();
    private boolean isImporting = false;
    private Object returnValue = "";
    private boolean previousIfMatched = false;
    private Exception pendingException = null; // Quản lý lỗi cho Try-Catch

    // ------------------------------------------------------------------
    //  NGĂN XẾP GỌI HÀM — để báo lỗi chỉ được ĐƯỜNG ĐI tới chỗ hỏng
    //
    //  vet().cuoi chỉ cho biết câu lệnh CUỐI CÙNG đang chạy. Biết mỗi
    //  chừng đó thì vẫn phải tự dò xem ai gọi tới đó. Ngăn xếp này ghi lại từng
    //  lượt gọi hàm còn dở, nên báo lỗi in ra được đủ:
    //
    //      └─ đang trong  ghiNetVe()   — main.hql:857
    //         ↑ được gọi từ doPost()   — BanDoServlet.hql:141
    //
    //  Đỉnh ngăn xếp là hàm trong cùng. Xoá sạch mỗi khi bắt đầu một request web.
    // ------------------------------------------------------------------
    //
    //  MOI LUONG MOT VET CHAY RIENG.
    //
    //  May chu web, bo hen gio (taskRepeat) va luong chinh cung dung CHUNG mot
    //  Interpreter. De chung mot ngan xep thi bao loi cua luong nay chen khung
    //  cua luong kia: ban dau in ra "coBangThuThap() ... nam trong nenLichDongBo()"
    //  trong khi hai ham ay chang he goi nhau - mot cai chay o luong chinh, mot
    //  cai o luong hen gio. Doc bao loi kieu do la di lac duong ngay.
    //  Tach vet theo luong la het chen nhau.
    // ------------------------------------------------------------------
    private static final class VetChay {
        Token cuoi = null;                                                   // cau lenh cuoi cung da chay
        final java.util.ArrayDeque<BaoLoi.Khung> nganXep = new java.util.ArrayDeque<>();
    }
    private final ThreadLocal<VetChay> vetChay = ThreadLocal.withInitial(VetChay::new);
    private VetChay vet() { return vetChay.get(); }

    /** Có in báo lỗi chi tiết (file, dòng, mã nguồn) ra trình duyệt không. */
    private boolean webHienLoiChiTiet = true;
    // --- BỘ NHỚ GIAO DIỆN HQL.UI ---
    private JFrame hqlWindow = null;
    private JPanel hqlPanel = null;
    // --- BỘ NHỚ DATABASE HQL.DATABASE ---
    /** Bể kết nối (xem HqlDbPool). null = chưa connect(). */
    private volatile HqlDbPool dbPool = null;
    private volatile String lastDbError = "";
    /** Cỡ bể cho lần connect() kế tiếp (dbPoolSize(...) hoặc DB_POOL_MAX / DB_POOL_MIN trong .henv). */
    private int dbPoolMax = -1, dbPoolMin = -1;
    /** Giao dịch đang mở của TỪNG LUỒNG: beginTransaction() giữ 1 kết nối riêng tới khi commit/rollback. */
    private final ThreadLocal<Connection> dbTx = new ThreadLocal<>();
    /** Khoá tự tăng do INSERT gần nhất của luồng này sinh ra (cho identity()). */
    private final ThreadLocal<Double> dbLastIdentity = new ThreadLocal<>();

    private interface SqlWork<T> { T run(Connection con) throws SQLException; }

    private static final class DbTarget { String url, user, pass; boolean sqlite; }

    /** Bắt buộc phải có kết nối trước khi chạy lệnh SQL (nếu .henv có cấu hình DB thì tự kết nối). */
    private void requireDb() {
        if (dbPool == null && !dbTuKetNoiTuHenv()) {
            throw new RuntimeException("Chưa kết nối Database! Hãy gọi connect(...) hoặc connectEnv() trước, "
                    + "hoặc khai báo DB_HOST / DB_NAME / DB_USER / DB_PASSWORD trong tệp .henv.");
        }
    }

    /** Có thể chạy SQL được không (đang trong giao dịch, đã có bể, hoặc tự nối được từ .henv). */
    private boolean dbSanSang() {
        return dbTx.get() != null || dbPool != null || dbTuKetNoiTuHenv();
    }

    /**
     * Chạy một đoạn việc với 1 kết nối: đang trong giao dịch thì dùng đúng kết nối của giao dịch,
     * không thì mượn từ bể rồi TRẢ LẠI ngay khi xong (dù thành công hay lỗi).
     */
    private <T> T dbRun(SqlWork<T> work) throws SQLException {
        Connection tx = dbTx.get();
        if (tx != null) return work.run(tx);
        requireDb();
        HqlDbPool p = dbPool;
        if (p == null) throw new SQLException("Kết nối Database vừa bị đóng.");
        try (Connection con = p.borrow()) {
            return work.run(con);
        }
    }

    private boolean dbCoCauHinhTrongHenv() {
        return HqlEnv.first(null, "DB_URL", "db.url") != null
                || HqlEnv.first(null, "DB_NAME", "db.dbname", "DB_DATABASE") != null;
    }

    /** Tự kết nối theo .henv (chỉ chạy khi script chưa gọi connect). Kiểu "lười": lần dùng đầu tiên mới nối. */
    private synchronized boolean dbTuKetNoiTuHenv() {
        if (dbPool != null) return true;
        if (!dbCoCauHinhTrongHenv()) return false;
        try {
            dbMoBe(dbTargetTuHenv());
            return true;
        } catch (Exception e) {
            lastDbError = e.getMessage() == null ? e.toString() : e.getMessage();
            System.err.println("[HQL.DATABASE] Tự kết nối theo .henv thất bại: " + lastDbError);
            return false;
        }
    }

    /** Đọc DB_URL hoặc (DB_TYPE, DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD); nhận cả khoá kiểu db.hostname. */
    private DbTarget dbTargetTuHenv() throws SQLException {
        String url  = HqlEnv.first(null, "DB_URL", "db.url");
        String user = HqlEnv.first(null, "DB_USER", "DB_USERNAME", "db.username");
        String pass = HqlEnv.first(null, "DB_PASSWORD", "DB_PASS", "db.password");
        if (url != null) {
            DbTarget t = new DbTarget();
            t.url = url; t.user = user; t.pass = pass;
            t.sqlite = url.toLowerCase().startsWith("jdbc:sqlite:");
            return t;
        }
        String name = HqlEnv.first(null, "DB_NAME", "db.dbname", "DB_DATABASE");
        if (name == null) throw new SQLException("Không thấy DB_NAME (hoặc DB_URL) trong tệp .henv.");
        String kind = HqlEnv.first("sqlserver", "DB_TYPE", "db.type").toLowerCase();
        if (kind.equals("sqlite")) {
            DbTarget t = new DbTarget();
            t.url = "jdbc:sqlite:" + name; t.sqlite = true;
            return t;
        }
        List<Object> a = new ArrayList<>();
        a.add(HqlEnv.first("127.0.0.1", "DB_HOST", "db.hostname"));
        String port = HqlEnv.first(null, "DB_PORT", "db.port");
        if (port != null) a.add(port.trim());
        a.add(name);
        a.add(user == null ? "sa" : user);
        a.add(pass == null ? "" : pass);
        return buildJdbcTarget(kind, a);
    }

    /** Mở bể mới rồi đổi sang dùng nó (bể cũ, nếu có, đóng sau). Ném SQLException nếu sai thông tin đăng nhập. */
    private void dbMoBe(DbTarget t) throws SQLException {
        HqlDbPool.Config c = new HqlDbPool.Config();
        c.url = t.url; c.user = t.user; c.pass = t.pass;
        int max = dbPoolMax > 0 ? dbPoolMax : HqlEnv.getInt("DB_POOL_MAX", 10);
        int min = dbPoolMin >= 0 ? dbPoolMin : HqlEnv.getInt("DB_POOL_MIN", 2);
        if (t.sqlite) { max = 1; min = 0; }          // SQLite chỉ cho một người ghi tại một thời điểm
        c.maxSize = max;
        c.minIdle = min;
        c.connectionTimeoutMs = HqlEnv.getInt("DB_TIMEOUT_MS", 15000);
        HqlDbPool moi = new HqlDbPool(c);
        HqlDbPool cu = dbPool;
        dbPool = moi;
        if (cu != null) cu.close();
    }

    /** Đóng bể hiện tại (kết nối đang cho mượn sẽ tự đóng khi được trả). */
    private void dbDongBe() {
        HqlDbPool p = dbPool;
        dbPool = null;
        if (p != null) p.close();
    }

    /** Request/luồng kết thúc mà còn giao dịch bỏ dở → hủy, kẻo luồng web này (dùng lại cho request sau) kẹt trong giao dịch cũ. */
    private void dbHuyGiaoDichBoQuen() {
        Connection tx = dbTx.get();
        if (tx == null) return;
        dbTx.remove();
        try { tx.rollback(); } catch (Exception ignore) { }
        try { tx.close(); } catch (Exception ignore) { }     // bể tự bật lại autoCommit và nhận kết nối về
        System.err.println("[HQL.DATABASE] Cảnh báo: giao dịch chưa commit/rollback khi kết thúc request — đã tự rollback.");
    }

    private void dbGhiNhoKhoaVuaSinh(PreparedStatement ps) {
        try (ResultSet k = ps.getGeneratedKeys()) {
            if (k != null && k.next()) {
                Object v = k.getObject(1);
                if (v instanceof Number) { dbLastIdentity.set(((Number) v).doubleValue()); return; }
            }
        } catch (SQLException ignore) { }
        dbLastIdentity.remove();
    }

    /**
     * Xác định nơi cần nối cho mọi kiểu gọi connect(...) mà sếp hay dùng:
     *   connect("jdbc:sqlserver://...")                          -> URL đầy đủ
     *   connect("localhost", 1433, "DemoDB", "sa", "123")        -> host, port, db, user, pass
     *   connect("localhost", "DemoDB", "sa", "123")              -> mặc định cổng 1433
     *   connect("localhost", "DemoDB", "123")                    -> mặc định user 'sa' (kiểu cũ)
     *   connect("sqlserver"|"mysql"|"postgres", host, [port,] db, user, pass)
     *   connect("sqlite", "duong/dan/file.db")
     */
    private DbTarget resolveDbTarget(List<Object> args) throws SQLException {
        if (args.isEmpty()) throw new SQLException("connect() thiếu tham số.");

        String first = stringify(args.get(0));
        DbTarget t = new DbTarget();

        // 1. Truyền thẳng chuỗi JDBC URL
        if (first.toLowerCase().startsWith("jdbc:")) {
            t.url = first;
            if (args.size() >= 3) { t.user = stringify(args.get(1)); t.pass = stringify(args.get(2)); }
            t.sqlite = first.toLowerCase().startsWith("jdbc:sqlite:");
            return t;
        }

        // 2. Có chỉ định rõ loại CSDL ở tham số đầu
        String kind = first.toLowerCase();
        List<Object> rest = args.subList(1, args.size());
        switch (kind) {
            case "sqlite":
                if (rest.isEmpty()) throw new SQLException("connect(\"sqlite\", duongDanFile) thiếu đường dẫn file.");
                t.url = "jdbc:sqlite:" + stringify(rest.get(0));
                t.sqlite = true;
                return t;
            case "mysql":
            case "mariadb":
            case "postgres":
            case "postgresql":
            case "sqlserver":
            case "mssql":
                return buildJdbcTarget(kind, rest);
            default:
                // 3. Không ghi loại -> mặc định SQL Server (đúng thói quen của dự án)
                return buildJdbcTarget("sqlserver", args);
        }
    }

    /** Ghép URL JDBC từ danh sách (host [, port], database, user, password). */
    private DbTarget buildJdbcTarget(String kind, List<Object> a) throws SQLException {
        if (a.size() < 2) throw new SQLException("connect() thiếu tham số (cần ít nhất host và tên database).");

        String host = stringify(a.get(0));
        int idx = 1;
        int port = -1;

        // Tham số thứ 2 là SỐ -> đó là cổng (port), nếu không thì bỏ qua và dùng cổng mặc định
        Object second = a.get(1);
        if (second instanceof Double || stringify(second).matches("\\d{2,5}")) {
            port = (int) toDouble(second);
            idx = 2;
        }
        if (a.size() <= idx) throw new SQLException("connect() thiếu tên database.");

        String database = stringify(a.get(idx++));
        String user = null, pass = null;
        if (a.size() - idx >= 2) {           // ... db, user, pass
            user = stringify(a.get(idx));
            pass = stringify(a.get(idx + 1));
        } else if (a.size() - idx == 1) {    // ... db, pass  (kiểu cũ: user mặc định là 'sa')
            user = "sa";
            pass = stringify(a.get(idx));
        }

        String url;
        switch (kind) {
            case "mysql":
            case "mariadb":
                url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=UTC",
                        host, port == -1 ? 3306 : port, database);
                break;
            case "postgres":
            case "postgresql":
                url = String.format("jdbc:postgresql://%s:%d/%s", host, port == -1 ? 5432 : port, database);
                break;
            default: // SQL Server
                // host có thể là "MAYTINH\\SQLEXPRESS" (named instance) -> khi đó không gắn cổng
                boolean namedInstance = host.contains("\\");
                url = "jdbc:sqlserver://" + host + (namedInstance || port == -1 ? "" : ":" + port)
                        + ";databaseName=" + database
                        + ";encrypt=" + HqlEnv.getBool("DB_ENCRYPT", true) + ";trustServerCertificate=true";
        }

        DbTarget t = new DbTarget();
        t.url = url; t.user = user; t.pass = pass;
        return t;
    }

    /** Gắn tham số cho câu lệnh có dấu ? — tự đổi số nguyên kiểu Double (21.0) về 21. */
    private void bindSqlParams(PreparedStatement pstmt, Object rawParams) throws SQLException {
        if (!(rawParams instanceof List)) return;
        List<?> params = (List<?>) rawParams;
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v instanceof Double) {
                double d = (Double) v;
                if (d == Math.rint(d) && !Double.isInfinite(d)) pstmt.setLong(i + 1, (long) d);
                else pstmt.setDouble(i + 1, d);
            } else {
                pstmt.setObject(i + 1, v);
            }
        }
    }

    /** Đọc 1 dòng kết quả, giữ nguyên thứ tự cột và đổi kiểu SQL sang kiểu HQL. */
    private LinkedHashMap<String, Object> readSqlRow(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i);
            if (label == null || label.isEmpty()) label = "col" + i;
            row.put(label, sqlToHql(rs.getObject(i)));
        }
        return row;
    }

    /**
     * Chuyển giá trị JDBC (Integer, BigDecimal, Timestamp...) sang kiểu HQL hiểu được.
     * Không có bước này thì mọi phép so sánh kiểu row.Age > 30 đều sai bét.
     */
    private Object sqlToHql(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Boolean) return v;
        if (v instanceof byte[]) return Base64.getEncoder().encodeToString((byte[]) v);
        if (v instanceof java.sql.Timestamp) return ((java.sql.Timestamp) v).toLocalDateTime().toString();
        if (v instanceof java.sql.Date) return ((java.sql.Date) v).toLocalDate().toString();
        if (v instanceof java.sql.Time) return ((java.sql.Time) v).toLocalTime().toString();
        return v.toString();
    }

    /** Bọc 1 ô dữ liệu CSV theo chuẩn RFC 4180. */
    private String csvQuote(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ==========================================================================================
    //   BỘ NHỚ & ĐỘNG CƠ WEB (HQL.WEB) — CHẠY HQLSP + SERVLET, GIỐNG JSP/SERVLET CỦA JAVA
    // ==========================================================================================

    /** Toàn bộ dữ liệu của 1 lượt request/response. Tomcat và server nội bộ đều dùng chung lớp này. */
    public static class WebContext {
        public String method = "GET";
        public String path = "/";
        public String contextPath = "";   // Tên project trên URL, ví dụ "/QuanLyKho"
        public String query = "";
        public String body = "";
        public String ip = "";
        public final LinkedHashMap<String, String> params = new LinkedHashMap<>();
        public final LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        public final LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        public final LinkedHashMap<String, String> resHeaders = new LinkedHashMap<>();
        public final ArrayList<String> resCookies = new ArrayList<>();
        public int status = 200;
        public String contentType = "text/html; charset=UTF-8";
        public String redirect = null;
        public StringBuilder out = new StringBuilder();
        public Map<String, Object> session = null;   // null = chưa đụng tới session
        public String sessionId = "";
        public boolean sessionNew = false;
        public boolean sessionKilled = false;
        public byte[] rawOut = null;                 // Dùng khi trả về file tĩnh (ảnh, css, js...)
    }

    private WebContext webCtx = null;                                  // Request đang xử lý
    private File webRootDir = new File(".").getAbsoluteFile();         // Thư mục gốc của website
    private String webAppContext = "";                                 // Tên project trên URL: /name
    private int webServerPort = 8080;
    private boolean webContainerMode = false;                          // true = đang chạy trong Tomcat
    private String entryScriptPath = null;                             // File .hql được chạy đầu tiên
    private com.sun.net.httpserver.HttpServer webHttpServer = null;    // Server nội bộ (không cần Tomcat)
    private final LinkedHashMap<String, String> webRoutes = new LinkedHashMap<>();       // /duong-dan -> tên hàm HQL
    private final LinkedHashMap<String, String> webServletMap = new LinkedHashMap<>();   // /duong-dan -> file servlet
    private final HashMap<String, HashMap<String, FunctionDef>> servletFuncCache = new HashMap<>();
    private final HashMap<String, HashMap<String, HqlClass>> servletClassCache = new HashMap<>();
    private final Map<String, Map<String, Object>> webSessionStore = new ConcurrentHashMap<>();

    /**
     * Dùng cho Tomcat: trỏ thư mục gốc vào webapp rồi chạy file main của người dùng
     * (WEB-INF/main.hql) để nạp thư viện 'use', khai báo route, cấu hình...
     * Khi chạy trong Tomcat thì webStart()/webWait() tự động vô hiệu vì đã có sẵn máy chủ.
     */
    public void webInitForContainer(String rootPath) {
        webContainerMode = true;
        usedLibraries.add("hql.web");
        usedLibraries.add("hql.io");
        if (rootPath != null && !rootPath.isEmpty()) webRootDir = new File(rootPath).getAbsoluteFile();

        File mainFile = new File(new File(webRootDir, "WEB-INF"), "main.hql");
        if (!mainFile.isFile()) mainFile = new File(webRootDir, "main.hql");
        if (mainFile.isFile()) {
            try {
                runFile(mainFile.getPath());
                System.out.println("[HQL.WEB] Đã nạp file main: " + mainFile.getPath());
            } catch (Exception e) {
                System.err.println("[HQL.WEB] Lỗi khi chạy file main '" + mainFile.getPath() + "': " + e.getMessage());
            }
        } else {
            System.out.println("[HQL.WEB] Không thấy WEB-INF/main.hql — chỉ chạy được các thư viện mặc định.");
        }
    }

    /** Cổng vào duy nhất để xử lý 1 request. Đồng bộ vì trình thông dịch dùng chung 1 bộ nhớ biến. */
    public void webDispatch(WebContext ctx) {
        synchronized (this) {
            WebContext saved = webCtx;
            webCtx = ctx;

            /*
             * Dọn sạch DẤU VẾT CỦA REQUEST TRƯỚC.
             *
             * vet().nganXep có thể còn sót khung nếu lượt trước nổ lỗi (lúc đó khung
             * cố ý không bị pop, để chỗ bắt lỗi in được đường đi).
             *
             * vet().cuoi thì phải xoá vì lý do khác, tinh hơn: khung ngoài
             * cùng của ngăn xếp lấy vị trí từ chính nó. Không xoá thì hàm doGet /
             * doPost của request NÀY nhận vị trí là dòng cuối cùng chạy ở request
             * TRƯỚC — báo lỗi in ra "doPost() được gọi tại main.hql:1250" trong khi
             * doPost nằm ở DongBoServlet.hql. Nhìn là biết sai mà rất khó đoán vì sao.
             */
            vet().nganXep.clear();
            vet().cuoi = null;

            try {
                routeWebRequest(ctx);
            } catch (Throwable e) {
                // ------------------------------------------------------
                //  BÁO LỖI ĐẦY ĐỦ — xem lớp BaoLoi để biết nó in những gì
                // ------------------------------------------------------
                Throwable goc = BaoLoi.goc(e);

                // In ra cửa sổ dòng lệnh trước: dù trình duyệt có đóng ngay thì
                // nhật ký máy chủ vẫn còn nguyên vết.
                System.err.println(BaoLoi.banChu(goc, vet().cuoi, vet().nganXep, true));

                ctx.status = 500;
                ctx.rawOut = null;
                ctx.out.setLength(0);
                ctx.contentType = "text/html; charset=UTF-8";
                ctx.out.append(webHienLoiChiTiet
                        ? BaoLoi.banHtml(goc, vet().cuoi, vet().nganXep, ctx.path)
                        : BaoLoi.banHtmlGon(goc));
            } finally {
                dbHuyGiaoDichBoQuen();      // request xong mà quên commit/rollback -> hủy, đừng để kẹt sang request sau
                webCtx = saved;
                vet().nganXep.clear();
            }
        }
    }

    /** Bảng định tuyến: route tay -> .hqlsp -> index -> <Ten>Servlet.hql -> file tĩnh -> 404. */
    private void routeWebRequest(WebContext ctx) throws Exception {
        String path = ctx.path;
        if (path.isEmpty()) path = "/";

        // 1. Đường dẫn được gán bằng webRoute("/abc", "tenHam")
        if (webRoutes.containsKey(path)) {
            executeFunctionCall(webRoutes.get(path), new ArrayList<>());
            return;
        }
        // 2. Đường dẫn được gán bằng webServlet("/abc", "XyzServlet.hql")
        if (webServletMap.containsKey(path)) {
            File f = resolveWebFile(webServletMap.get(path));
            if (f == null) f = new File(webServletMap.get(path));
            runHqlServletFile(f, ctx);
            return;
        }
        // 3. Trang chủ / thư mục -> tìm file index
        if (path.equals("/") || path.endsWith("/")) {
            for (String idx : new String[]{"index.hqlsp", "index.html", "index.htm"}) {
                File cand = resolveWebFile(path + idx);
                if (cand != null && cand.isFile()) { serveWebFile(cand, ctx); return; }
            }
        }
        // 4. Gọi thẳng file .hqlsp
        if (path.toLowerCase().endsWith(".hqlsp")) {
            File f = resolveWebFile(path);
            if (f != null && f.isFile()) { renderHqlsp(f, ctx); return; }
        }
        // 5. Quy ước Servlet: /BaoCao  ->  BaoCaoServlet.hql
        File servletFile = findServletFile(path);
        if (servletFile != null) { runHqlServletFile(servletFile, ctx); return; }

        // 6. File tĩnh (html, css, js, ảnh...)
        File direct = resolveWebFile(path);
        if (direct != null && direct.isFile()) { serveWebFile(direct, ctx); return; }

        // 7. Gõ /trangchu cũng chạy được trangchu.hqlsp / trangchu.html
        for (String ext : new String[]{".hqlsp", ".html", ".htm"}) {
            File cand = resolveWebFile(path + ext);
            if (cand != null && cand.isFile()) { serveWebFile(cand, ctx); return; }
        }

        ctx.status = 404;
        ctx.out.append(webErrorPage("404 - Không tìm thấy", "Không có trang nào khớp với đường dẫn <code>" + htmlEscape(path) + "</code>"));
    }

    /** Đổi đường dẫn URL sang file thật, chặn tuyệt đối kiểu tấn công ../../ ra ngoài thư mục web. */
    /**
     * Đường dẫn này có phải file xác minh quyền sở hữu tên miền không?
     * Nhận đúng mấy tên chuẩn, còn lại vẫn rơi vào nhánh 404 "Sai tên project"
     * như cũ — mở rộng ra cả gốc thì hoá ra chạy hai website chồng nhau.
     * Tham số phải là đường dẫn đã chuyển hết sang chữ thường.
     */
    private boolean laFileXacMinhGoc(String pathThuong) {
        if (pathThuong.startsWith("/.well-known/")) return true;   // ACME, App Links, Apple
        switch (pathThuong) {
            case "/riot.txt":                       // Riot Games
            case "/robots.txt":
            case "/favicon.ico":
                return true;
            default:
                return false;
        }
    }

    private File resolveWebFile(String urlPath) {
        try {
            String clean = urlPath.replace('\\', '/');
            int q = clean.indexOf('?');
            if (q != -1) clean = clean.substring(0, q);
            while (clean.startsWith("/")) clean = clean.substring(1);
            clean = URLDecoder.decode(clean, StandardCharsets.UTF_8);
            if (clean.isEmpty()) return null;

            File root = webRootDir.getCanonicalFile();
            File target = new File(root, clean).getCanonicalFile();
            if (!target.getPath().startsWith(root.getPath())) return null; // Thoát ra ngoài -> chặn
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    /** Tìm file servlet theo quy ước: /BaoCao -> BaoCaoServlet.hql (tìm ở gốc, /servlets, /WEB-INF/hql). */
    private File findServletFile(String path) {
        String seg = path;
        while (seg.endsWith("/")) seg = seg.substring(0, seg.length() - 1);
        int slash = seg.lastIndexOf('/');
        if (slash != -1) seg = seg.substring(slash + 1);
        if (seg.isEmpty()) return null;
        if (seg.toLowerCase().endsWith(".hql")) seg = seg.substring(0, seg.length() - 4);

        String capitalized = Character.toUpperCase(seg.charAt(0)) + seg.substring(1);
        List<String> names = new ArrayList<>();
        if (seg.endsWith("Servlet")) { names.add(seg + ".hql"); }
        names.add(capitalized + "Servlet.hql");
        names.add(seg + "Servlet.hql");

        String prefix = path.substring(0, path.length() - (path.endsWith("/") ? 1 : 0));
        int lastSlash = prefix.lastIndexOf('/');
        String dir = lastSlash > 0 ? prefix.substring(0, lastSlash + 1) : "/";

        for (String folder : new String[]{dir, "/", "/servlets/", "/WEB-INF/hql/"}) {
            for (String n : names) {
                File f = resolveWebFile(folder + n);
                if (f != null && f.isFile()) return f;
            }
        }

        /*
         * DÒ LẠI, LẦN NÀY KHÔNG PHÂN BIỆT HOA THƯỜNG.
         *
         * Địa chỉ viết thường (/vnchecker/trangchu) sở dĩ ra được TrangChuServlet.hql
         * là nhờ ổ đĩa Windows không phân biệt hoa thường — ăn may chứ không phải
         * do mã xử lý. Bê nguyên trang lên máy chủ Linux là 404 hàng loạt, mà lỗi
         * đó cực khó đoán vì ở máy nhà chạy ngon lành.
         *
         * Quét thẳng thư mục rồi so tên bằng chữ thường thì chạy đúng ở mọi hệ
         * điều hành. Chỉ chạy khi cách nhanh phía trên đã trượt, nên không tốn
         * thêm lần đọc đĩa nào cho đường đi bình thường.
         */
        List<String> canTim = new ArrayList<>();
        canTim.add((seg + "servlet.hql").toLowerCase());
        if (seg.toLowerCase().endsWith("servlet")) { canTim.add((seg + ".hql").toLowerCase()); }

        for (String folder : new String[]{dir, "/", "/servlets/", "/WEB-INF/hql/"}) {
            File thuMuc = resolveWebFile(folder);
            if (thuMuc == null || !thuMuc.isDirectory()) continue;
            File[] ds = thuMuc.listFiles();
            if (ds == null) continue;
            for (File f : ds) {
                if (!f.isFile()) continue;
                String ten = f.getName().toLowerCase();
                for (String c : canTim) { if (ten.equals(c)) return f; }
            }
        }
        return null;
    }

    /** Trả file về trình duyệt: .hqlsp thì chạy, file mã nguồn thì cấm, còn lại đọc nhị phân. */
    private void serveWebFile(File file, WebContext ctx) throws Exception {
        String lower = file.getName().toLowerCase();
        if (lower.endsWith(".hqlsp")) { renderHqlsp(file, ctx); return; }
        if (lower.endsWith(".hql")) {
            // Không bao giờ để lộ mã nguồn HQL ra ngoài trình duyệt
            ctx.status = 403;
            ctx.out.append(webErrorPage("403 - Cấm truy cập", "Không được xem mã nguồn file .hql"));
            return;
        }
        ctx.contentType = webMimeType(file.getName());
        ctx.rawOut = Files.readAllBytes(file.toPath());
    }

    // ------------------------------------------------------------------
    //   HQLSP: file HTML có nhúng code HQL trong cặp <% ... %>
    // ------------------------------------------------------------------

    /** Chạy 1 file .hqlsp và ghi kết quả vào response. */
    private void renderHqlsp(File file, WebContext ctx) throws IOException {
        String source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        String hqlCode = compileHqlsp(source);
        runGeneratedHql(hqlCode, file.getPath());
    }

    /**
     * Biên dịch HQLSP thành mã HQL thuần:
     *   HTML thường     -> printWeb("...")
     *   &lt;% code %&gt;      -> chép nguyên code
     *   &lt;%= biểu thức %&gt; -> printWeb(biểu thức)
     *   &lt;%-- ghi chú --%&gt; -> bỏ qua
     *   &lt;%@ page ... %&gt;  -> chỉ thị (contentType, import)
     */
    private String compileHqlsp(String src) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < src.length()) {
            int open = src.indexOf("<%", i);
            if (open == -1) { emitHtmlLiteral(sb, src.substring(i)); break; }
            emitHtmlLiteral(sb, src.substring(i, open));

            // Ghi chú <%-- ... --%>
            if (src.startsWith("<%--", open)) {
                int endC = src.indexOf("--%>", open);
                if (endC == -1) break;
                appendNewLines(sb, countNewLines(src.substring(open, endC)));
                i = endC + 4;
                continue;
            }

            int close = src.indexOf("%>", open);
            if (close == -1) { emitHtmlLiteral(sb, src.substring(open)); break; }
            String tag = src.substring(open + 2, close);

            if (tag.startsWith("=")) {                 // <%= biểu thức %>
                sb.append("printWeb(").append(tag.substring(1).trim()).append(");\n");
                appendNewLines(sb, countNewLines(tag));
            } else if (tag.startsWith("@")) {          // <%@ chỉ thị %>
                sb.append(compileHqlspDirective(tag.substring(1)));
                appendNewLines(sb, countNewLines(tag));
            } else {                                   // <% code %>
                sb.append(tag).append("\n");
            }
            i = close + 2;
        }
        return sb.toString();
    }

    /** Xử lý <%@ page contentType="..." %> và <%@ include file="..." %>. */
    private String compileHqlspDirective(String directive) {
        String d = directive.trim();
        Matcher m = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"").matcher(d);
        StringBuilder code = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            if (key.equalsIgnoreCase("contentType")) {
                code.append("webType(\"").append(value.replace("\"", "\\\"")).append("\");\n");
            } else if (key.equalsIgnoreCase("file") || key.equalsIgnoreCase("include")) {
                code.append("webInclude(\"").append(value.replace("\"", "\\\"")).append("\");\n");
            }
        }
        return code.toString();
    }

    /** Biến 1 đoạn HTML thành lệnh printWeb("...") an toàn (escape đúng chuẩn Lexer). */
    private void emitHtmlLiteral(StringBuilder sb, String html) {
        if (html.isEmpty()) return;
        int lines = countNewLines(html);
        StringBuilder esc = new StringBuilder(html.length() + 16);
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            switch (c) {
                case '\\': esc.append("\\\\"); break;
                case '"':  esc.append("\\\""); break;
                case '\n': esc.append("\\n"); break;
                case '\t': esc.append("\\t"); break;
                case '\r': break;
                default:   esc.append(c);
            }
        }
        sb.append("printWeb(\"").append(esc).append("\");\n");
        appendNewLines(sb, lines);
    }

    private int countNewLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private void appendNewLines(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append('\n');
    }

    /** Nạp khai báo func/class rồi chạy đoạn mã HQL vừa sinh ra từ HQLSP. */
    private void runGeneratedHql(String code, String virtualFileName) {
        List<Token> tokens = Lexer.tokenize(code, virtualFileName);
        boolean savedHeader = isHeaderSection;
        isHeaderSection = true;
        try {
            for (List<Token> cmd : splitCommands(tokens)) {
                if (cmd.isEmpty()) continue;
                if (isUseCommand(cmd)) executeUse(cmd);
                else if (isFunctionDeclaration(cmd)) declareFunction(cmd);
                else if (isClassDeclaration(cmd)) declareClass(cmd);
            }
        } finally {
            isHeaderSection = savedHeader;
        }
        enterFunctionScope();
        try {
            runAll(tokens);
        } finally {
            exitScope();
            returnValue = "";
        }
    }

    // ------------------------------------------------------------------
    //   SERVLET: file khóa cứng tên <Ten>Servlet.hql, có doGet()/doPost()
    // ------------------------------------------------------------------

    /** Nạp file servlet 1 lần duy nhất, cất riêng hàm/lớp của nó để không đè lên servlet khác. */
    private void loadHqlServlet(File file) throws IOException {
        String key = file.getCanonicalPath();
        if (servletFuncCache.containsKey(key)) return;

        Set<String> beforeFuncs = new HashSet<>(functionMemory.keySet());
        Set<String> beforeClasses = new HashSet<>(hqlClasses.keySet());

        boolean savedHeader = isHeaderSection;
        isHeaderSection = true;                 // Cho phép servlet đặt 'use hql.database;' ở đầu file
        parsedFiles.remove(key);
        parsedFiles.remove(file.getPath());
        try {
            parseFileForDeclarations(file.getPath());
        } finally {
            isHeaderSection = savedHeader;
        }

        HashMap<String, FunctionDef> myFuncs = new HashMap<>();
        for (String k : new ArrayList<>(functionMemory.keySet())) {
            if (!beforeFuncs.contains(k)) myFuncs.put(k, functionMemory.remove(k));
        }
        HashMap<String, HqlClass> myClasses = new HashMap<>();
        for (String k : new ArrayList<>(hqlClasses.keySet())) {
            if (!beforeClasses.contains(k)) myClasses.put(k, hqlClasses.remove(k));
        }
        servletFuncCache.put(key, myFuncs);
        servletClassCache.put(key, myClasses);

        // Chạy phần code ngoài hàm đúng 1 lần (giống init() của Servlet Java)
        withServletScope(key, () -> {
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                runAll(Lexer.tokenize(content, file.getPath()));
                if (findServletFunc(key, "init") != null) executeFunctionCall("init", new ArrayList<>());
            } catch (IOException e) {
                throw new RuntimeException("Không đọc được servlet: " + e.getMessage());
            }
        });
    }

    /** Bật tạm hàm/lớp riêng của servlet lên bộ nhớ chung, chạy xong thì trả lại nguyên trạng. */
    private void withServletScope(String key, Runnable action) {
        HashMap<String, FunctionDef> myFuncs = servletFuncCache.getOrDefault(key, new HashMap<>());
        HashMap<String, HqlClass> myClasses = servletClassCache.getOrDefault(key, new HashMap<>());
        HashMap<String, FunctionDef> shadowedFuncs = new HashMap<>();
        HashMap<String, HqlClass> shadowedClasses = new HashMap<>();

        for (Map.Entry<String, FunctionDef> e : myFuncs.entrySet()) {
            if (functionMemory.containsKey(e.getKey())) shadowedFuncs.put(e.getKey(), functionMemory.get(e.getKey()));
            functionMemory.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, HqlClass> e : myClasses.entrySet()) {
            if (hqlClasses.containsKey(e.getKey())) shadowedClasses.put(e.getKey(), hqlClasses.get(e.getKey()));
            hqlClasses.put(e.getKey(), e.getValue());
        }
        try {
            action.run();
        } finally {
            for (String k : myFuncs.keySet()) functionMemory.remove(k);
            for (String k : myClasses.keySet()) hqlClasses.remove(k);
            functionMemory.putAll(shadowedFuncs);
            hqlClasses.putAll(shadowedClasses);
        }
    }

    private FunctionDef findServletFunc(String key, String funcName) {
        HashMap<String, FunctionDef> map = servletFuncCache.get(key);
        if (map == null) return null;
        if (map.containsKey(funcName)) return map.get(funcName);
        return map.get("@" + funcName);   // Hỗ trợ cả kiểu đặt tên public @doGet
    }

    /** Gọi doGet/doPost/service của file servlet theo đúng method của request. */
    private void runHqlServletFile(File file, WebContext ctx) throws IOException {
        loadHqlServlet(file);
        String key = file.getCanonicalPath();

        String verb = "do" + ctx.method.charAt(0) + ctx.method.substring(1).toLowerCase();  // GET -> doGet
        final String[] candidates = {verb, "service", "doGet", "main"};

        withServletScope(key, () -> {
            for (String c : candidates) {
                FunctionDef def = findServletFunc(key, c);
                if (def != null) {
                    executeFunctionCall(def.name, new ArrayList<>());
                    return;
                }
            }
            ctx.status = 405;
            ctx.out.append(webErrorPage("405 - Sai phương thức",
                    "Servlet <code>" + htmlEscape(file.getName()) + "</code> chưa có hàm <code>" + htmlEscape(verb) + "()</code>"));
        });
    }

    // ------------------------------------------------------------------
    //   MÁY CHỦ NỘI BỘ (không cần cài Tomcat vẫn chạy được)
    // ------------------------------------------------------------------

    // =====================================================================
    //  MÁY CHỦ WEBSOCKET  —  đẩy dữ liệu xuống trang ngay khi có thay đổi
    //
    //  VÌ SAO PHẢI CÓ Ổ CẮM RIÊNG: máy chủ web ở trên dùng
    //  com.sun.net.httpserver.HttpServer, mà lớp ấy KHÔNG nhả socket thô ra —
    //  không có cách nào bắt lấy HTTP Upgrade để đổi sang WebSocket trên cùng
    //  cổng. Nên phần này mở ServerSocket của riêng nó ở một cổng khác.
    //
    //  VÌ SAO KHÔNG ĐỤNG webDispatch(): hàm ấy có synchronized(this) và trình
    //  thông dịch dùng chung một bộ nhớ biến, nên chỉ một request được chạy tại
    //  một thời điểm. Kết nối WebSocket thì SỐNG HÀNG GIỜ — nhét vào đó là giữ
    //  khoá vĩnh viễn và cả máy chủ đứng hình. Vì vậy mọi thứ ở đây chạy trên
    //  luồng riêng và KHÔNG gọi vào mã HQL.
    //
    //  ĐÂY LÀ ĐƯỜNG MỘT CHIỀU: máy chủ đẩy xuống trang. Trang muốn nói gì thì
    //  vẫn gọi HTTP như cũ. Làm hai chiều nghĩa là chạy mã HQL từ luồng khác,
    //  đúng cái bẫy vừa nói ở trên.
    // =====================================================================

    private ServerSocket wsServer = null;
    private Thread wsThread = null;
    private final List<Socket> wsClients =
            java.util.Collections.synchronizedList(new ArrayList<Socket>());

    private void startWsServer(int port) throws IOException {
        stopWsServer();
        wsServer = new ServerSocket(port);

        wsThread = new Thread(() -> {
            while (wsServer != null && !wsServer.isClosed()) {
                try {
                    Socket s = wsServer.accept();
                    Thread t = new Thread(() -> wsPhucVu(s));
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    break;   // đóng cổng thì accept() ném lỗi — đó là lối thoát
                }
            }
        });
        wsThread.setDaemon(true);   // đừng giữ tiến trình sống khi mọi thứ khác đã xong
        wsThread.start();

        System.out.println("[HQL.WS] May chu WebSocket dang nghe o cong " + port);
    }

    private void stopWsServer() {
        synchronized (wsClients) {
            for (Socket s : wsClients) { try { s.close(); } catch (IOException ignored) { } }
            wsClients.clear();
        }
        if (wsServer != null) {
            try { wsServer.close(); } catch (IOException ignored) { }
            wsServer = null;
        }
    }

    /** Bắt tay theo RFC 6455 rồi nằm đó nghe cho tới khi bên kia đóng. */
    private void wsPhucVu(Socket s) {
        try {
            s.setTcpNoDelay(true);   // tin nhắn ngắn, đừng để Nagle gom lại rồi trễ thêm 40ms

            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            // --- Đọc phần đầu HTTP (kết thúc bằng một dòng trống) ---
            StringBuilder dau = new StringBuilder();
            int c, lienTiep = 0;
            while ((c = in.read()) != -1) {
                dau.append((char) c);
                if (c == '\n') { lienTiep++; if (lienTiep == 2) break; }
                else if (c != '\r') { lienTiep = 0; }
                if (dau.length() > 8192) break;   // đầu request dài bất thường: bỏ
            }

            String khoa = null;
            for (String dong : dau.toString().split("\r?\n")) {
                String d = dong.trim();
                if (d.toLowerCase().startsWith("sec-websocket-key:")) {
                    khoa = d.substring(d.indexOf(':') + 1).trim();
                }
            }
            if (khoa == null) { s.close(); return; }

            // --- Sec-WebSocket-Accept = Base64(SHA1(khoa + GUID)) ---
            // Chuỗi GUID này do RFC ấn định, không phải mình bịa ra.
            String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            byte[] bam = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((khoa + GUID).getBytes(StandardCharsets.UTF_8));
            String nhan = Base64.getEncoder().encodeToString(bam);

            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + nhan + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            wsClients.add(s);

            // --- Nghe cho tới khi đóng ---
            // Chỉ cần xử lý đúng hai loại khung: ping (trả pong) và close.
            // Dữ liệu bên kia gửi lên thì bỏ qua — đây là đường một chiều.
            while (!s.isClosed()) {
                int b0 = in.read();
                if (b0 == -1) break;
                int opcode = b0 & 0x0F;

                int b1 = in.read();
                if (b1 == -1) break;
                boolean coMask = (b1 & 0x80) != 0;
                long dai = b1 & 0x7F;

                if (dai == 126) {
                    dai = ((long) in.read() << 8) | in.read();
                } else if (dai == 127) {
                    dai = 0;
                    for (int i = 0; i < 8; i++) { dai = (dai << 8) | in.read(); }
                }

                byte[] mask = new byte[4];
                if (coMask) { in.readNBytes(mask, 0, 4); }

                // Khung quá lớn thì cắt kết nối chứ đừng cấp phát theo lời khai
                // của bên kia — đó là cách dễ nhất để bị làm cạn bộ nhớ.
                if (dai > 1_000_000) break;

                byte[] than = new byte[(int) dai];
                in.readNBytes(than, 0, (int) dai);
                if (coMask) {
                    for (int i = 0; i < than.length; i++) { than[i] = (byte) (than[i] ^ mask[i & 3]); }
                }

                if (opcode == 0x8) { break; }                    // close
                if (opcode == 0x9) { wsGuiKhung(s, 0xA, than); }  // ping -> pong
            }
        } catch (Exception ignored) {
            // Mất mạng, tab đóng đột ngột, bên kia nói sai giao thức — tất cả
            // đều chỉ có nghĩa là một kết nối chết, không phải lỗi máy chủ.
        } finally {
            wsClients.remove(s);
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** Đóng gói một khung theo RFC 6455. Máy chủ gửi thì KHÔNG mask. */
    private void wsGuiKhung(Socket s, int opcode, byte[] than) throws IOException {
        OutputStream out = s.getOutputStream();
        ByteArrayOutputStream khung = new ByteArrayOutputStream();

        khung.write(0x80 | opcode);   // FIN + opcode

        if (than.length < 126) {
            khung.write(than.length);
        } else if (than.length < 65536) {
            khung.write(126);
            khung.write((than.length >> 8) & 0xFF);
            khung.write(than.length & 0xFF);
        } else {
            khung.write(127);
            for (int i = 7; i >= 0; i--) { khung.write((int) ((long) than.length >> (8 * i)) & 0xFF); }
        }
        khung.write(than, 0, than.length);

        // Một socket không cho hai luồng cùng ghi: khung sẽ đan vào nhau và
        // bên kia nhận được rác. Khoá theo từng socket.
        synchronized (s) {
            out.write(khung.toByteArray());
            out.flush();
        }
    }

    /** Đẩy một chuỗi xuống MỌI trang đang mở. Trả về số nơi nhận được. */
    private int wsGuiTatCa(String chu) {
        byte[] than = chu.getBytes(StandardCharsets.UTF_8);
        int dem = 0;

        List<Socket> chet = new ArrayList<>();
        List<Socket> ban;
        synchronized (wsClients) { ban = new ArrayList<>(wsClients); }

        for (Socket s : ban) {
            try {
                wsGuiKhung(s, 0x1, than);   // 0x1 = khung chữ
                dem++;
            } catch (Exception e) {
                chet.add(s);
            }
        }

        // Dọn những kết nối vừa hỏng, không thì lần sau lại thử ghi vào chúng.
        for (Socket s : chet) {
            wsClients.remove(s);
            try { s.close(); } catch (IOException ignored) { }
        }
        return dem;
    }

    private void startWebServer(int port) throws IOException {
        stopWebServer();
        webServerPort = port;
        webHttpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        webHttpServer.createContext("/", this::handleHttpExchange);
        webHttpServer.setExecutor(Executors.newFixedThreadPool(8));
        webHttpServer.start();
        printWebBanner();
    }

    /** In ra cửa sổ Run toàn bộ đường dẫn của website để bấm vào là mở được ngay. */
    private void printWebBanner() {
        String base = "http://localhost:" + webServerPort + webAppContext;
        String lan = getClientIpAddress();

        System.out.println();
        System.out.println("=========================================================");
        System.out.println("   HQL WEB SERVER - " + webAppContext.substring(1));
        System.out.println("=========================================================");
        System.out.println("   Dia chi        : " + base + "/");
        System.out.println("   Trong mang LAN : http://" + lan + ":" + webServerPort + webAppContext + "/");
        System.out.println("   Thu muc goc    : " + webRootDir.getPath());
        System.out.println("---------------------------------------------------------");

        // Gom hết đường dẫn lại: [loại, url, nguồn]
        List<String[]> rows = new ArrayList<>();
        File[] files = webRootDir.listFiles();
        if (files != null) {
            Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File f : files) {
                String n = f.getName();
                String low = n.toLowerCase();
                if (low.endsWith(".hqlsp") || low.endsWith(".html") || low.endsWith(".htm")) {
                    rows.add(new String[]{"[Trang]  ", low.startsWith("index.") ? base + "/" : base + "/" + n, n});
                } else if (low.endsWith("servlet.hql")) {
                    /*
                     * IN ĐƯỜNG DẪN BẰNG CHỮ THƯỜNG.
                     *
                     * Tên file là TrangChuServlet.hql nên bản đầu in ra
                     * /TrangChu — người đọc banner tưởng phải gõ đúng hoa
                     * thường như thế. Địa chỉ web theo thông lệ viết thường
                     * hết, và bộ dò servlet đã nhận cả hai kiểu, nên in kiểu
                     * chuẩn cho khỏi dạy nhau thói quen sai.
                     */
                    String tenServlet = n.substring(0, n.length() - "Servlet.hql".length());
                    rows.add(new String[]{"[Servlet]", base + "/" + tenServlet.toLowerCase(), n});
                }
            }
        }
        for (Map.Entry<String, String> r : webRoutes.entrySet()) {
            rows.add(new String[]{"[Route]  ", base + r.getKey(), r.getValue() + "()"});
        }
        for (Map.Entry<String, String> r : webServletMap.entrySet()) {
            rows.add(new String[]{"[Servlet]", base + r.getKey(), r.getValue()});
        }

        int width = 0;
        for (String[] r : rows) width = Math.max(width, r[1].length());
        for (String[] r : rows) System.out.println("   " + r[0] + " " + padRight(r[1], width + 3) + r[2]);
        if (rows.isEmpty()) System.out.println("   (Thư mục gốc chưa có file .hqlsp / .html / <Ten>Servlet.hql nào)");

        System.out.println("=========================================================");
        System.out.println("   Bam Ctrl+C de dung may chu.");
        System.out.println();
    }

    private String padRight(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /** Lấy địa chỉ IP của máy đang chạy (dùng cho banner và khi client là localhost). */
    private String getClientIpAddress() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return "Lỗi lấy IP";
        }
    }

    private void stopWebServer() {
        if (webHttpServer != null) {
            webHttpServer.stop(0);
            webHttpServer = null;
        }
    }

    private void handleHttpExchange(com.sun.net.httpserver.HttpExchange ex) {
        WebContext ctx = new WebContext();
        try {
            ctx.method = ex.getRequestMethod().toUpperCase();
            ctx.query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            ctx.contextPath = webAppContext;

            String clientIp = ex.getRemoteAddress() == null ? "" : ex.getRemoteAddress().getAddress().getHostAddress();
            // Máy tự truy cập chính nó thì trả về IP thật của máy cho dễ đọc (thay vì 0:0:0:0:0:0:0:1)
            ctx.ip = (clientIp.isEmpty() || clientIp.startsWith("127.") || clientIp.equals("0:0:0:0:0:0:0:1") || clientIp.equals("::1"))
                    ? getClientIpAddress() : clientIp;

            // Bóc tên project khỏi URL: /QuanLyKho/BaoCao  ->  /BaoCao  (giống Tomcat)
            String rawPath = ex.getRequestURI().getPath();
            if (!webAppContext.isEmpty()) {
                /*
                 * SO TÊN PROJECT KHÔNG PHÂN BIỆT HOA THƯỜNG.
                 *
                 * Tên servlet thì đã so kiểu này sẵn (/VNChecker/trangchu vào được),
                 * riêng tên project thì không — nên /vnchecker/trangchu trả 404 trong
                 * khi /VNChecker/trangchu ra trang. Người ta gõ địa chỉ bằng tay hay
                 * gõ thường hết, mà lỗi 404 kiểu này không ai đoán ra vì sao.
                 * Chuẩn URL cũng nói phần tên miền không phân biệt hoa thường, nên
                 * người dùng mặc nhiên tưởng cả địa chỉ đều thế.
                 */
                String rawThuong = rawPath.toLowerCase();
                String ctxThuong = webAppContext.toLowerCase();
                if (rawThuong.equals(ctxThuong) || rawPath.equals("/") || rawPath.isEmpty()) {
                    ex.getResponseHeaders().set("Location", webAppContext + "/");
                    ex.sendResponseHeaders(302, -1);
                    return;
                }
                if (rawThuong.startsWith(ctxThuong + "/")) {
                    rawPath = rawPath.substring(webAppContext.length());
                } else if (laFileXacMinhGoc(rawThuong)) {
                    /*
                     * FILE XÁC MINH QUYỀN SỞ HỮU TÊN MIỀN — PHỤC VỤ Ở GỐC.
                     *
                     * Riot (và Google, Apple, Let's Encrypt...) đều đòi đặt một
                     * file ở ĐÚNG GỐC tên miền để chứng minh mình sở hữu nó:
                     *     https://ten-mien/riot.txt
                     * Website này lại chạy dưới /vnchecker, nên mọi đường dẫn
                     * ngoài đó rơi vào nhánh 404 "Sai tên project" bên dưới —
                     * tức là KHÔNG CÁCH NÀO xác minh được, và trang 404 lại nói
                     * về tên project nên chẳng ai đoán ra vì sao.
                     *
                     * Danh sách đóng, không mở cửa cho mọi file ở gốc: chỉ đúng
                     * mấy tên chuẩn dùng để xác minh.
                     */
                    File fileGoc = resolveWebFile(rawPath);
                    if (fileGoc != null && fileGoc.isFile()) {
                        byte[] du = Files.readAllBytes(fileGoc.toPath());
                        ex.getResponseHeaders().set("Content-Type", webMimeType(fileGoc.getName()));
                        ex.sendResponseHeaders(200, du.length);
                        ex.getResponseBody().write(du);
                        return;
                    }
                    ex.sendResponseHeaders(404, -1);
                    return;
                } else {
                    byte[] msg = webErrorPage("404 - Sai tên project",
                            "Website này chạy tại <code>" + htmlEscape(webAppContext) + "/</code>. "
                                    + "Hãy mở <a style=\"color:#38bdf8\" href=\"" + webAppContext + "/\">http://localhost:" + webServerPort + webAppContext + "/</a>")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    ex.sendResponseHeaders(404, msg.length);
                    ex.getResponseBody().write(msg);
                    return;
                }
            }
            ctx.path = rawPath.isEmpty() ? "/" : rawPath;

            for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                if (!h.getValue().isEmpty()) ctx.headers.put(h.getKey().toLowerCase(), h.getValue().get(0));
            }
            parseCookieHeader(ctx.headers.get("cookie"), ctx.cookies);

            ctx.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            parseQueryString(ctx.query, ctx.params);
            String ctype = ctx.headers.getOrDefault("content-type", "");
            if (ctype.startsWith("application/x-www-form-urlencoded")) parseQueryString(ctx.body, ctx.params);

            // Nhận diện phiên làm việc cũ (nếu trình duyệt gửi kèm cookie)
            String sid = ctx.cookies.get("HQLSESSIONID");
            if (sid != null && webSessionStore.containsKey(sid)) {
                ctx.sessionId = sid;
                ctx.session = webSessionStore.get(sid);
            }

            webDispatch(ctx);

            if (ctx.sessionKilled && !ctx.sessionId.isEmpty()) webSessionStore.remove(ctx.sessionId);

            /*
             * THU TU O DAY QUAN TRONG - HEADER TRUOC, COOKIE SAU.
             *
             * resHeaders dat bang set(), ma set() THAY THE moi gia tri cu cung
             * ten. De vong nay chay SAU thi mot resHeader("Set-Cookie", ...) se
             * xoa sach nhung Set-Cookie da add truoc do - ke ca cookie phien
             * HQLSESSIONID. Hau qua: trang nao tu dat mot cookie bang resHeader
             * la nguoi dung MAT LUON PHIEN dang nhap ngay o phan hoi do, ma
             * chang co loi nao hien ra ca.
             *
             * Dat header truoc roi add cookie sau thi ca hai cung song. Duong
             * servlet (LinhScriptRunner) von da lam dung thu tu nay.
             */
            for (Map.Entry<String, String> h : ctx.resHeaders.entrySet()) ex.getResponseHeaders().set(h.getKey(), h.getValue());

            if (ctx.sessionNew) {
                ex.getResponseHeaders().add("Set-Cookie", "HQLSESSIONID=" + ctx.sessionId + "; Path=/; HttpOnly");
            }
            for (String c : ctx.resCookies) ex.getResponseHeaders().add("Set-Cookie", c);

            if (ctx.redirect != null) {
                ex.getResponseHeaders().set("Location", withContextPath(ctx.redirect, ctx.contextPath));
                ex.sendResponseHeaders(302, -1);
                return;
            }

            byte[] payload = ctx.rawOut != null ? ctx.rawOut : ctx.out.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", ctx.contentType);
            ex.sendResponseHeaders(ctx.status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) ex.getResponseBody().write(payload);
        } catch (Exception e) {
            try {
                byte[] msg = webErrorPage("500 - Lỗi máy chủ", String.valueOf(e.getMessage())).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                ex.sendResponseHeaders(500, msg.length);
                ex.getResponseBody().write(msg);
            } catch (IOException ignore) { }
        } finally {
            ex.close();
        }
    }

    private void parseQueryString(String raw, Map<String, String> into) {
        if (raw == null || raw.isEmpty()) return;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq == -1 ? pair : pair.substring(0, eq);
            String v = eq == -1 ? "" : pair.substring(eq + 1);
            try {
                into.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (Exception e) {
                into.put(k, v);
            }
        }
    }

    private void parseCookieHeader(String header, Map<String, String> into) {
        if (header == null) return;
        for (String c : header.split(";")) {
            int eq = c.indexOf('=');
            if (eq > 0) into.put(c.substring(0, eq).trim(), c.substring(eq + 1).trim());
        }
    }

    /** Tự gắn tên project vào đầu đường dẫn khi chuyển hướng: "/BaoCao" -> "/QuanLyKho/BaoCao". */
    public static String withContextPath(String url, String contextPath) {
        if (url == null || contextPath == null || contextPath.isEmpty()) return url;
        if (!url.startsWith("/") || url.startsWith(contextPath + "/") || url.equals(contextPath)) return url;
        return contextPath + url;
    }

    /** Lấy request hiện tại, báo lỗi rõ ràng nếu gọi hàm web ngoài luồng xử lý request. */
    private WebContext requireWeb(String funcName) {
        if (webCtx == null) {
            throw new RuntimeException("Lỗi HQL.WEB: '" + funcName + "()' chỉ dùng được bên trong Servlet hoặc file .hqlsp.");
        }
        return webCtx;
    }

    /** Tạo session ngay khi script cần dùng (giống request.getSession(true) của Java). */
    private Map<String, Object> ensureWebSession() {
        WebContext ctx = requireWeb("session");
        if (ctx.session == null) {
            if (ctx.sessionId == null || ctx.sessionId.isEmpty()) {
                ctx.sessionId = UUID.randomUUID().toString().replace("-", "");
                ctx.sessionNew = true;
            }
            ctx.session = new LinkedHashMap<>();
            webSessionStore.put(ctx.sessionId, ctx.session);
        }
        return ctx.session;
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String webMimeType(String fileName) {
        String n = fileName.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (n.endsWith(".css")) return "text/css; charset=UTF-8";
        if (n.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (n.endsWith(".json")) return "application/json; charset=UTF-8";
        if (n.endsWith(".xml")) return "application/xml; charset=UTF-8";
        if (n.endsWith(".csv")) return "text/csv; charset=UTF-8";
        if (n.endsWith(".txt")) return "text/plain; charset=UTF-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        // Không có dòng này thì .apk rơi xuống octet-stream: Chrome trên
        // Android vẫn tải về được, nhưng bấm vào file chỉ mở trình quản lý
        // file chứ không mời cài. Đúng kiểu MIME thì nó hỏi cài luôn.
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private String webErrorPage(String title, String detail) {
        return "<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\">"
                + "<title>" + htmlEscape(title) + "</title>"
                + "<body style=\"font-family:Segoe UI,system-ui,sans-serif;background:#0f172a;color:#e2e8f0;padding:48px\">"
                + "<h1 style=\"color:#f87171;margin:0 0 8px\">" + htmlEscape(title) + "</h1>"
                + "<p style=\"color:#94a3b8;margin:0 0 24px\">HQL Web Engine</p>"
                + "<pre style=\"background:#1e293b;padding:16px;border-radius:8px;white-space:pre-wrap\">" + detail + "</pre>"
                + "</body></html>";
    }

    /** Đổi dữ liệu HQL sang chuỗi JSON (dùng cho webJson). */
    private String webToJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Double) {
            double d = (Double) v;
            return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(",");
                sb.append(webJsonString(String.valueOf(e.getKey()))).append(":").append(webToJson(e.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) v;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(webToJson(list.get(i)));
            }
            return sb.append("]").toString();
        }
        if (v instanceof HqlObject) return webToJson(((HqlObject) v).fields);
        return webJsonString(stringify(v));
    }

    private String webJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    /**
     * Đóng gói dự án thành thư mục webapp chạy được trên Tomcat 10.1:
     * sinh WEB-INF/web.xml, chép class đã biên dịch và các thư viện .jar.
     */
    private String exportTomcatWebapp(String targetDir, String mainScript) throws IOException {
        File target = new File(targetDir).getAbsoluteFile();
        File webInf = new File(target, "WEB-INF");
        File classesDir = new File(webInf, "classes");
        File libDir = new File(webInf, "lib");
        for (File d : new File[]{target, webInf, classesDir, libDir}) d.mkdirs();

        String webXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd\"\n"
                + "         version=\"6.0\">\n"
                + "    <display-name>HQL Web Application</display-name>\n"
                + "    <servlet>\n"
                + "        <servlet-name>HqlDispatcher</servlet-name>\n"
                + "        <servlet-class>me.hql.script.LinhScriptRunner$HqlServlet</servlet-class>\n"
                + "        <load-on-startup>1</load-on-startup>\n"
                + "    </servlet>\n"
                + "    <servlet-mapping>\n"
                + "        <servlet-name>HqlDispatcher</servlet-name>\n"
                + "        <url-pattern>/</url-pattern>\n"
                + "    </servlet-mapping>\n"
                + "    <welcome-file-list>\n"
                + "        <welcome-file>index.hqlsp</welcome-file>\n"
                + "        <welcome-file>index.html</welcome-file>\n"
                + "    </welcome-file-list>\n"
                + "</web-app>\n";
        Files.writeString(new File(webInf, "web.xml").toPath(), webXml, StandardCharsets.UTF_8);

        // Chép file main của người dùng vào WEB-INF/main.hql để Tomcat nạp lúc khởi động webapp
        if (mainScript != null && new File(mainScript).isFile()) {
            Files.copy(new File(mainScript).toPath(), new File(webInf, "main.hql").toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[HQL.WEB] File main: " + mainScript + "  ->  WEB-INF/main.hql");
        } else {
            System.out.println("[HQL.WEB] Chưa có file main — thêm bằng webExportTomcat(\"" + target.getName() + "\", \"web.hql\")");
        }

        // Chép class đã biên dịch + thư viện jar đang nằm trên classpath
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            File f = new File(entry);
            if (f.isDirectory() && new File(f, "me/hql/script/Interpreter.class").exists()) {
                copyDirectory(f, classesDir);
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                String n = f.getName().toLowerCase();
                if (n.contains("servlet-api") || n.contains("jsp") || n.contains("idea_rt")) continue; // Tomcat đã có sẵn
                Files.copy(f.toPath(), new File(libDir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Chép nội dung website (bỏ qua mã nguồn Java và thư mục build)
        File[] children = webRootDir.listFiles();
        if (children != null) {
            for (File c : children) {
                String n = c.getName();
                if (n.equals("WEB-INF") || n.equals("src") || n.equals("out") || n.equals(".idea") || n.equals(".git")) continue;
                if (c.getCanonicalPath().equals(target.getCanonicalPath())) continue;
                if (c.isDirectory()) copyDirectory(c, new File(target, n));
                else if (n.matches("(?i).*\\.(hqlsp|hql|html|htm|css|js|png|jpg|jpeg|gif|svg|ico|json|txt|csv|ini)$")) {
                    Files.copy(c.toPath(), new File(target, n).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        System.out.println("[HQL.WEB] Đã đóng gói webapp tại: " + target.getPath());
        System.out.println("[HQL.WEB] Chép thư mục này vào <Tomcat>/webapps/ rồi mở http://localhost:8080/" + target.getName());
        return target.getPath();
    }

    private void copyDirectory(File from, File to) throws IOException {
        if (!from.isDirectory()) return;
        to.mkdirs();
        File[] items = from.listFiles();
        if (items == null) return;
        for (File item : items) {
            File dest = new File(to, item.getName());
            if (item.isDirectory()) copyDirectory(item, dest);
            else Files.copy(item.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- BỘ NHỚ GAME ENGINE (HQL.GAME) ---
    private Clip currentGameClip = null;
    private double cameraX = 0, cameraY = 0;
    private JFrame gameWindow = null;
    private JPanel gameCanvas = null;
    private BufferedImage gameBuffer = null;
    private Graphics2D gameGraphics = null;
    private boolean isGameRunning = false;
    private final Set<String> keysDown = new HashSet<>();
    private final Set<String> keysPressed = new HashSet<>();
    private int mouseX = 0, mouseY = 0;
    private boolean mouseLeftDown = false, mouseRightDown = false;
    private boolean mouseLeftPressed = false, mouseRightPressed = false;
    private final StringBuilder chuDaGo = new StringBuilder();   // ký tự người chơi vừa gõ (ô chat)
    private int frameCount = 0;

    // --- BỘ NHỚ GAME ENGINE V2 (NÂNG CẤP CHO GAME HÀNH ĐỘNG) ---
    // Kho ảnh: nạp 1 lần rồi dùng lại, tránh đọc đĩa 60 lần/giây gây giật lag
    private final HashMap<String, Image> gameImageCache = new HashMap<>();

    // Hạt hiệu ứng (máu bắn, lửa, khói...) do engine tự cập nhật + tự vẽ
    private static class GameParticle {
        double x, y, vx, vy, size, life, maxLife, gravity;
        Color color;
    }
    private final List<GameParticle> gameParticles = new ArrayList<>();

    // Chữ bay (số sát thương, "TRÚNG!", "NÉ!"...) do engine tự cập nhật + tự vẽ
    private static class GameFloatText {
        String text; double x, y, vy, size, life, maxLife; Color color;
    }
    private final List<GameFloatText> gameFloatTexts = new ArrayList<>();

    // Rung màn hình
    private double shakePower = 0;
    private int shakeFrames = 0;
    private double shakeOffX = 0, shakeOffY = 0;

    // Nhịp khung hình
    private int targetFPS = 60;
    private double lastDeltaSec = 1.0 / 60.0;
    private double measuredFPS = 60;

    // Âm thanh nâng cao: nhạc nền riêng 1 kênh, hiệu ứng nhiều kênh có cache
    private Clip gameMusicClip = null;
    private final HashMap<String, Clip> gameSfxCache = new HashMap<>();

    // Phông chữ hiện hành của game
    private String gameFontName = "Arial";
    private boolean gameFontBold = true;

    // ================== BỘ NHỚ BẢN ĐỒ & TẦM NHÌN (HQL.GAME - dùng cho game bắn súng) ==================
    // Bản đồ dạng lưới ký tự. Ký tự nào chặn tầm nhìn / chặn đạn do kịch bản khai báo.
    private char[][] banDoLuoi = null;
    private int banDoOKichThuoc = 32;

    // --- CHẾ ĐỘ 2.5D NHÌN CHÉO (ISOMETRIC) ---
    // Toạ độ thế giới vẫn là mặt phẳng X-Y bình thường; khi vẽ thì chiếu chéo 45 độ:
    //     sx = (tx - ty) * isoRong/2      sy = (tx + ty) * isoCao/2
    // với tx, ty là toạ độ tính theo Ô.
    private double isoRong = 64;     // bề rộng hình thoi của một ô
    private double isoCao = 32;      // chiều cao hình thoi của một ô (tỉ lệ 2:1)
    private String banDoChanNhin = "#BW";   // ký tự chặn tầm nhìn
    private String banDoChanDan = "#B";     // ký tự chặn đạn hoàn toàn (W = gỗ: đạn xuyên được)

    /** Quả khói: chặn tầm nhìn theo hình tròn. */
    private static class QuaKhoi { double x, y, r; }
    private final List<QuaKhoi> danhSachKhoi = new ArrayList<>();

    // ================== BỘ NHỚ MẠNG NHIỀU NGƯỜI CHƠI (HQL.MANG) ==================
    private ServerSocket mangServerSocket = null;
    private final ConcurrentHashMap<String, Socket> mangKhach = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BufferedWriter> mangGhiKhach = new ConcurrentHashMap<>();
    private final java.util.concurrent.BlockingQueue<String[]> mangHopThu =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.BlockingQueue<String> mangHopThuKhach =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private Socket mangSocketToiServer = null;
    private BufferedWriter mangGhiToiServer = null;
    private volatile boolean mangServerDangChay = false;
    private volatile boolean mangDaNoiToiServer = false;
    private int mangDemId = 0;

    // --- BỘ NHỚ AUTO PC (HQL.PC) ---
    private java.awt.Robot pcRobot = null;

    private java.awt.Robot getRobot() throws Exception {
        if (pcRobot == null) pcRobot = new java.awt.Robot();
        return pcRobot;
    }

    // --- BỘ NHỚ HỆ ĐIỀU HÀNH TÁC VỤ (HQL.SCHEDULE) ---
    private static class HqlTask {
        String name;
        String type; // once, repeat, cron, chained
        String funcName;
        boolean isPaused = false;
        int runCount = 0;
        int maxRuns = -1; // -1 = vô hạn
        ScheduledFuture<?> future;
        String cronExpr;
        String nextRunStr = "N/A";
        String lastRunStr = "N/A";
        String stateFile = null; // Dùng cho Persistence
        ArrayList<String> onCompleteChain = new ArrayList<>(); // Task Chaining

        // ---> SẾP THÊM 2 DÒNG NÀY VÀO ĐỂ FIX LỖI NHÉ <---
        String onSuccessFunc = null;
        String onFailureFunc = null;
    }
    // Dùng ConcurrentHashMap để Thread-Safe tuyệt đối khi chạy Parallel
    private final ConcurrentHashMap<String, HqlTask> schedulerMemory = new ConcurrentHashMap<>();
    private ScheduledExecutorService masterScheduler = Executors.newScheduledThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));

    private int getKeyCode(String key) {
        key = key.toUpperCase();
        switch(key) {
            case "CTRL": return KeyEvent.VK_CONTROL;
            case "SHIFT": return KeyEvent.VK_SHIFT;
            case "ALT": return KeyEvent.VK_ALT;
            case "ENTER": return KeyEvent.VK_ENTER;
            case "TAB": return KeyEvent.VK_TAB;
            case "ESC": return KeyEvent.VK_ESCAPE;
            case "SPACE": return KeyEvent.VK_SPACE;
            case "WIN": return KeyEvent.VK_WINDOWS;
            case "UP": return KeyEvent.VK_UP;
            case "DOWN": return KeyEvent.VK_DOWN;
            case "LEFT": return KeyEvent.VK_LEFT;
            case "RIGHT": return KeyEvent.VK_RIGHT;
            default:
                if (key.length() == 1) return KeyEvent.getExtendedKeyCodeForChar(key.charAt(0));
                return 0;
        }
    }

    // Lớp hỗ trợ màu sắc nhanh
    private Color parseColor(String c) {
        // Hỗ trợ rgb(r,g,b) và rgba(r,g,b,a)
        if (c.startsWith("rgb")) {
            String nums = c.replaceAll("[^0-9,\\.]", "");
            String[] parts = nums.split(",");
            int r = Integer.parseInt(parts[0].trim());
            int g = Integer.parseInt(parts[1].trim());
            int b = Integer.parseInt(parts[2].trim());
            if (c.startsWith("rgba") && parts.length >= 4) {
                float a = Float.parseFloat(parts[3].trim());
                return new Color(r, g, b, (int)(a*255));
            }
            return new Color(r, g, b);
        }
        // Hỗ trợ #abc (rút gọn)
        if (c.startsWith("#") && c.length() == 4) {
            int r = Integer.parseInt(c.substring(1,2)+c.substring(1,2), 16);
            int g = Integer.parseInt(c.substring(2,3)+c.substring(2,3), 16);
            int b = Integer.parseInt(c.substring(3,4)+c.substring(3,4), 16);
            return new Color(r, g, b);
        }
        switch(c.toLowerCase()) {
            case "red": return Color.RED; case "green": return Color.GREEN;
            case "blue": return Color.BLUE; case "yellow": return Color.YELLOW;
            case "orange": return Color.ORANGE; case "black": return Color.BLACK;
            case "white": return Color.WHITE; case "skyblue": return new Color(135, 206, 235);
            default: try { return Color.decode(c); } catch(Exception e) { return Color.WHITE; }
        }
    }

    // Quản lý Sprite
    private class Sprite {
        String id; double x, y, w, h, angle = 0; Image img;
        Sprite(String id, double x, double y, double w, double h, Image img) {
            this.id = id; this.x = x; this.y = y; this.w = w; this.h = h; this.img = img;
        }
    }
    private final HashMap<String, Sprite> sprites = new HashMap<>();

    // ================== TIỆN ÍCH NỘI BỘ CHO GAME ENGINE V2 ==================

    /**
     * Chuẩn hoá tên phím để người viết game gõ kiểu nào cũng nhận:
     * "esc" = "ESC" = "ESCAPE", "ctrl" = "CONTROL", "return" = "ENTER"...
     */
    private String chuanHoaTenPhim(String phim) {
        String p = phim.trim().toUpperCase();
        switch (p) {
            case "ESCAPE": return "ESC";
            case "CONTROL": return "CTRL";
            case "RETURN": return "ENTER";
            case "SPACEBAR": case " ": return "SPACE";
            case "ARROWLEFT": return "LEFT";
            case "ARROWRIGHT": return "RIGHT";
            case "ARROWUP": return "UP";
            case "ARROWDOWN": return "DOWN";
            default: return p;
        }
    }

    /** Đang vẽ giao diện (HUD)? Khi bật thì mọi nét vẽ bám theo màn hình, không bị camera kéo đi. */
    private boolean cheDoGiaoDien = false;

    /** Độ lệch X khi vẽ vật thể trong thế giới game (camera + rung màn hình). */
    private int camOffX() { return cheDoGiaoDien ? 0 : (int) Math.round(cameraX + shakeOffX); }
    /** Độ lệch Y khi vẽ vật thể trong thế giới game (camera + rung màn hình). */
    private int camOffY() { return cheDoGiaoDien ? 0 : (int) Math.round(cameraY + shakeOffY); }

    /** Nạp ảnh có cache: cùng 1 đường dẫn chỉ đọc từ ổ cứng đúng 1 lần. */
    private Image loadGameImage(String path) {
        Image img = gameImageCache.get(path);
        if (img == null) {
            img = new ImageIcon(path).getImage();
            gameImageCache.put(path, img);
        }
        return img;
    }

    /** Lấy danh sách số thực từ tham số HQL (mảng) để vẽ đa giác. */
    private int[] toIntArray(Object listObj, int offset) {
        if (!(listObj instanceof List)) throw new RuntimeException("Cần truyền vào một mảng toạ độ.");
        List<?> src = (List<?>) listObj;
        int[] out = new int[src.size()];
        for (int i = 0; i < src.size(); i++) out[i] = (int) Math.round(toDouble(src.get(i))) - offset;
        return out;
    }

    /** Phát 1 nốt nhạc tổng hợp (không cần file .wav) - dùng làm tiếng chém, tiếng trúng đòn. */
    private void playTone(double freq, int millis, double volume) {
        Thread t = new Thread(() -> {
            try {
                float sampleRate = 44100f;
                int soLuongMau = (int) (sampleRate * millis / 1000.0);
                byte[] buf = new byte[soLuongMau * 2];
                for (int i = 0; i < soLuongMau; i++) {
                    double tienDo = (double) i / soLuongMau;
                    // Bao hình: to dần rất nhanh rồi tắt dần -> nghe như tiếng gõ/chém
                    double baoHinh = Math.min(1.0, tienDo * 20.0) * Math.pow(1.0 - tienDo, 2.0);
                    double goc = 2.0 * Math.PI * freq * i / sampleRate;
                    short mau = (short) (Math.sin(goc) * 32767.0 * baoHinh * Math.max(0, Math.min(1, volume)));
                    buf[i * 2] = (byte) (mau & 0xff);
                    buf[i * 2 + 1] = (byte) ((mau >> 8) & 0xff);
                }
                AudioFormat af = new AudioFormat(sampleRate, 16, 1, true, false);
                SourceDataLine line = AudioSystem.getSourceDataLine(af);
                line.open(af, buf.length);
                line.start();
                line.write(buf, 0, buf.length);
                line.drain();
                line.close();
            } catch (Exception ignored) { /* Máy không có loa thì bỏ qua, không làm sập game */ }
        });
        t.setDaemon(true);
        t.start();
    }

    /** Chỉnh âm lượng cho 1 Clip theo thang 0..100. */
    private void setClipVolume(Clip clip, double vol) {
        try {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            if (vol <= 0) { gain.setValue(gain.getMinimum()); return; }
            if (vol > 100) vol = 100;
            gain.setValue((float) (Math.log10(vol / 100.0) * 20.0));
        } catch (Exception ignored) {}
    }

    // ================== TIỆN ÍCH BẢN ĐỒ & TẦM NHÌN ==================

    /** Ký tự của ô tại toạ độ thế giới (x, y). Trả về ' ' nếu ra ngoài bản đồ. */
    private char oTaiDiem(double x, double y) {
        if (banDoLuoi == null) return ' ';
        int c = (int) Math.floor(x / banDoOKichThuoc);
        int h = (int) Math.floor(y / banDoOKichThuoc);
        if (h < 0 || h >= banDoLuoi.length) return '#';
        if (c < 0 || c >= banDoLuoi[h].length) return '#';
        return banDoLuoi[h][c];
    }

    private boolean diemChanNhin(double x, double y) {
        char k = oTaiDiem(x, y);
        if (banDoChanNhin.indexOf(k) >= 0) return true;
        for (int i = 0; i < danhSachKhoi.size(); i++) {
            QuaKhoi q = danhSachKhoi.get(i);
            double dx = x - q.x, dy = y - q.y;
            if (dx * dx + dy * dy <= q.r * q.r) return true;
        }
        return false;
    }

    /**
     * Bắn một tia từ (x1,y1) tới (x2,y2). Trả về khoảng cách tới chỗ bị chặn,
     * hoặc -1 nếu đi thông suốt. Bước nhảy nhỏ nên đủ chính xác cho game 2D.
     */
    private double doTiaNhin(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double dai = Math.hypot(dx, dy);
        if (dai < 0.0001) return -1;
        double buoc = Math.max(3.0, banDoOKichThuoc / 6.0);
        int soBuoc = (int) (dai / buoc);
        for (int i = 1; i <= soBuoc; i++) {
            double t = (i * buoc) / dai;
            if (diemChanNhin(x1 + dx * t, y1 + dy * t)) return i * buoc;
        }
        return -1;
    }

    // ================== TIỆN ÍCH 2.5D NHÌN CHÉO ==================

    /** Đổi toạ độ thế giới (pixel) sang toạ độ MÀN HÌNH kiểu nhìn chéo (chưa trừ camera). */
    private double isoTruc_X(double wx, double wy) {
        double tx = wx / banDoOKichThuoc, ty = wy / banDoOKichThuoc;
        return (tx - ty) * (isoRong / 2.0);
    }
    private double isoTruc_Y(double wx, double wy) {
        double tx = wx / banDoOKichThuoc, ty = wy / banDoOKichThuoc;
        return (tx + ty) * (isoCao / 2.0);
    }
    /** Đã trừ camera - dùng khi vẽ thật. */
    private int isoManHinhX(double wx, double wy) { return (int) Math.round(isoTruc_X(wx, wy) - camOffX()); }
    private int isoManHinhY(double wx, double wy) { return (int) Math.round(isoTruc_Y(wx, wy) - camOffY()); }

    /** Vẽ một khối hộp (ô tường) trong không gian nhìn chéo. */
    private void veKhoiIso(double wx, double wy, double caoKhoi, Color mauTren, Color mauTrai, Color mauPhai) {
        double nuaR = isoRong / 2.0, nuaC = isoCao / 2.0;
        int cx = isoManHinhX(wx, wy);
        int cy = isoManHinhY(wx, wy);
        int h = (int) Math.round(caoKhoi);

        // Mặt trên (hình thoi)
        int[] tx = { cx, (int) (cx + nuaR), cx, (int) (cx - nuaR) };
        int[] ty = { cy - h, (int) (cy - h + nuaC), (int) (cy - h + isoCao), (int) (cy - h + nuaC) };
        // Mặt trái
        int[] lx = { (int) (cx - nuaR), cx, cx, (int) (cx - nuaR) };
        int[] ly = { (int) (cy - h + nuaC), (int) (cy - h + isoCao), (int) (cy + isoCao), (int) (cy + nuaC) };
        // Mặt phải
        int[] rx = { cx, (int) (cx + nuaR), (int) (cx + nuaR), cx };
        int[] ry = { (int) (cy - h + isoCao), (int) (cy - h + nuaC), (int) (cy + nuaC), (int) (cy + isoCao) };

        gameGraphics.setColor(mauTrai);
        gameGraphics.fillPolygon(lx, ly, 4);
        gameGraphics.setColor(mauPhai);
        gameGraphics.fillPolygon(rx, ry, 4);
        gameGraphics.setColor(mauTren);
        gameGraphics.fillPolygon(tx, ty, 4);
    }

    /** Dựng đa giác tầm nhìn quanh (x, y) bằng cách phóng tia ra xung quanh. */
    private Polygon dungDaGiacTamNhin(double x, double y, double banKinh, int soTia) {
        Polygon p = new Polygon();
        double buoc = Math.max(3.0, banDoOKichThuoc / 8.0);
        for (int i = 0; i < soTia; i++) {
            double goc = i * 2 * Math.PI / soTia;
            double cx = Math.cos(goc), cy = Math.sin(goc);
            double t = 0;
            double px = x, py = y;
            while (t < banKinh) {
                t += buoc;
                double nx = x + cx * t, ny = y + cy * t;
                if (diemChanNhin(nx, ny)) { px = nx; py = ny; break; }
                px = nx; py = ny;
            }
            p.addPoint((int) Math.round(px), (int) Math.round(py));
        }
        return p;
    }

    // ================== TIỆN ÍCH MẠNG ==================

    /** Mở luồng đọc cho một kết nối: mỗi dòng nhận được đẩy vào hộp thư. */
    private void moLuongDoc(Socket s, String idKhach, boolean laServer) {
        Thread t = new Thread(() -> {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String dong;
                while ((dong = br.readLine()) != null) {
                    if (laServer) mangHopThu.offer(new String[]{idKhach, dong});
                    else mangHopThuKhach.offer(dong);
                }
            } catch (Exception ignored) {
            } finally {
                if (laServer) {
                    mangKhach.remove(idKhach);
                    mangGhiKhach.remove(idKhach);
                    mangHopThu.offer(new String[]{idKhach, "@ROI"});   // báo cho kịch bản biết khách đã thoát
                } else {
                    mangDaNoiToiServer = false;
                    mangHopThuKhach.offer("@MAT_KET_NOI");
                }
                try { s.close(); } catch (Exception ignored2) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void mangGuiChoKhach(String id, String noiDung) {
        BufferedWriter w = mangGhiKhach.get(id);
        if (w == null) return;
        try {
            synchronized (w) {
                w.write(noiDung);
                w.write("\n");
                w.flush();
            }
        } catch (Exception e) {
            mangKhach.remove(id);
            mangGhiKhach.remove(id);
        }
    }

    /** Dựng cửa sổ game (dùng chung cho gameWindow và gameWindowFull). */
    private void taoCuaSoGame(String tieuDe, int w, int h, boolean toanManHinh) {
        if (gameWindow != null) gameWindow.dispose();

        gameWindow = new JFrame(tieuDe);
        gameWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        gameWindow.setResizable(false);
        if (toanManHinh) {
            gameWindow.setUndecorated(true);          // bỏ viền, phủ kín màn hình
            gameWindow.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        gameWindow.setSize(w, h);
        if (!toanManHinh) gameWindow.setLocationRelativeTo(null);
        else gameWindow.setLocation(0, 0);

        gameBuffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        gameGraphics = gameBuffer.createGraphics();
        gameGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gameGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        gameGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        gameCanvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.drawImage(gameBuffer, 0, 0, null);
            }
        };
        gameCanvas.setPreferredSize(new Dimension(w, h));
        gameCanvas.setFocusable(false);

        gameWindow.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                String key = chuanHoaTenPhim(KeyEvent.getKeyText(e.getKeyCode()));
                if (!keysDown.contains(key)) keysPressed.add(key);
                keysDown.add(key);
            }
            @Override
            public void keyReleased(KeyEvent e) {
                keysDown.remove(chuanHoaTenPhim(KeyEvent.getKeyText(e.getKeyCode())));
            }
            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (c >= 32 && c != 127 && chuDaGo.length() < 400) chuDaGo.append(c);
            }
        });

        gameCanvas.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) { mouseX = e.getX(); mouseY = e.getY(); }
            @Override public void mouseDragged(MouseEvent e) { mouseX = e.getX(); mouseY = e.getY(); }
        });
        gameCanvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) { mouseLeftDown = true; mouseLeftPressed = true; }
                if (SwingUtilities.isRightMouseButton(e)) { mouseRightDown = true; mouseRightPressed = true; }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) mouseLeftDown = false;
                if (SwingUtilities.isRightMouseButton(e)) mouseRightDown = false;
            }
        });

        gameWindow.add(gameCanvas);
        gameWindow.pack();
        if (toanManHinh) gameWindow.setSize(w, h);
        gameWindow.setVisible(true);
        gameWindow.requestFocus();
    }

    /** Engine tự cập nhật + vẽ hạt hiệu ứng và chữ bay sau mỗi khung hình. */
    private void updateAndDrawEffects() {
        if (gameGraphics == null) return;
        cheDoGiaoDien = false; // phòng khi kịch bản quên gọi endUI()
        int dx = camOffX(), dy = camOffY();

        // 1. Hạt hiệu ứng
        Iterator<GameParticle> itp = gameParticles.iterator();
        while (itp.hasNext()) {
            GameParticle p = itp.next();
            p.x += p.vx; p.y += p.vy; p.vy += p.gravity;
            p.life -= 1;
            if (p.life <= 0) { itp.remove(); continue; }
            float alpha = (float) Math.max(0, Math.min(1, p.life / p.maxLife));
            Composite old = gameGraphics.getComposite();
            gameGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            gameGraphics.setColor(p.color);
            double s = p.size * (0.35 + 0.65 * alpha);
            gameGraphics.fillOval((int) (p.x - s - dx), (int) (p.y - s - dy), (int) (s * 2), (int) (s * 2));
            gameGraphics.setComposite(old);
        }

        // 2. Chữ bay
        Iterator<GameFloatText> itt = gameFloatTexts.iterator();
        while (itt.hasNext()) {
            GameFloatText f = itt.next();
            f.y += f.vy;
            f.life -= 1;
            if (f.life <= 0) { itt.remove(); continue; }
            float alpha = (float) Math.max(0, Math.min(1, f.life / f.maxLife));
            Composite old = gameGraphics.getComposite();
            gameGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            gameGraphics.setFont(new Font(gameFontName, gameFontBold ? Font.BOLD : Font.PLAIN, (int) f.size));
            // Viền đen cho chữ nổi bật
            gameGraphics.setColor(Color.BLACK);
            gameGraphics.drawString(f.text, (int) (f.x - dx) + 1, (int) (f.y - dy) + 1);
            gameGraphics.setColor(f.color);
            gameGraphics.drawString(f.text, (int) (f.x - dx), (int) (f.y - dy));
            gameGraphics.setComposite(old);
        }

        // 3. Rung màn hình giảm dần
        if (shakeFrames > 0) {
            shakeFrames--;
            double p = shakePower * (shakeFrames / 10.0 + 0.2);
            shakeOffX = (Math.random() * 2 - 1) * p;
            shakeOffY = (Math.random() * 2 - 1) * p;
            if (shakeFrames == 0) { shakeOffX = 0; shakeOffY = 0; shakePower = 0; }
        }
    }

    private final HashMap<String, JComponent> hqlWidgets = new HashMap<>();
    private final HashMap<String, ButtonGroup> hqlRadioGroups = new HashMap<>();

    public Token getLastExecutedToken() { return vet().cuoi; }

    /** Ngăn xếp gọi hàm lúc này — BaoLoi dùng để in đường đi tới chỗ lỗi. */
    public java.util.Deque<BaoLoi.Khung> getNganXepGoi() { return vet().nganXep; }

    // --- BỘ NHỚ CẤU HÌNH HQL.NET ---
    private HashMap<String, String> netHeaders = new HashMap<>();
    private String netCookie = null;
    private int netTimeoutMs = 10000; // Mặc định chờ 10 giây
    private ProxySelector netProxy = null;
    private boolean netIgnoreSSL = false;

    // --- LƯU TRỮ PHẢN HỒI ---
    private int lastStatusCode = 0;
    private String lastContentType = "";
    private java.net.http.HttpHeaders lastHeaders = null;

    private static final Scanner scanner = new Scanner(System.in);


    public Interpreter() {
        scope().put("PI", Math.PI);
        scope().put("E", Math.E);
    }

    public void runFile(String filePath) throws IOException {
        if (entryScriptPath == null) entryScriptPath = filePath;   // Ghi nhớ file main để đóng gói Tomcat
        executedFiles.add(filePath); // Đánh dấu file chính đã chạy
        // Tự nạp tệp cấu hình .henv (thư mục của file main trước, rồi tới thư mục đang chạy)
        try {
            HqlEnv.autoLoad(new File(filePath).getAbsoluteFile().getParentFile());
            HqlEnv.autoLoad(new File(System.getProperty("user.dir", ".")));
        } catch (Exception ignore) { }
        parseFileForDeclarations(filePath);
        String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
        List<Token> tokens = Lexer.tokenize(content, filePath);
        try {
            runAll(tokens);
        } catch (ReturnException bo) {
            // Cho phép gõ 'return' ở ngoài cùng để dừng sớm cả file, không coi là lỗi
        }
    }

    private void parseFileForDeclarations(String filePath) throws IOException {
        if (parsedFiles.contains(filePath)) return;
        parsedFiles.add(filePath);

        String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
        List<Token> tokens = Lexer.tokenize(content, filePath);
        List<List<Token>> commands = splitCommands(tokens);

        for (List<Token> command : commands) {
            if (command.isEmpty()) continue;
            String cmdName = command.get(0).value;

            // --- BẮT ĐẦU CHECK LOGIC CẤU TRÚC FILE ---
            if (cmdName.equals("use") || cmdName.equals("import")) {
                if (!isHeaderSection) {
                    throw new RuntimeException("Lỗi cấu trúc: Lệnh '" + cmdName + "' BẮT BUỘC phải được đặt ở trên cùng của file, trước tất cả các dòng code logic khác!");
                }
            } else {
                // Nếu gặp bất kỳ lệnh nào không mang tính chất khai báo (như print, var, if...)
                // thì lập tức KHOÁ KHU VỰC HEADER LẠI.
                if (!cmdName.equals("func") && !cmdName.equals("class")) {
                    isHeaderSection = false;
                }
            }
            // --- KẾT THÚC CHECK LOGIC ---

            if (isUseCommand(command)) executeUse(command);
            else if (isImportCommand(command)) {
                if (command.size() >= 2) executeImport(command.get(1).value);
                else throw new RuntimeException("Lỗi: Thiếu tên file sau lệnh import.");
            }
            else if (isFunctionDeclaration(command)) declareFunction(command);
            else if (isClassDeclaration(command)) declareClass(command);
        }
    }

    private boolean isUseCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("use"); }
    private void executeUse(List<Token> t) {
        if (t.size() != 4) throw new RuntimeException("Lỗi: Cú pháp 'use' không hợp lệ.");
        String lib = t.get(1).value + "." + t.get(3).value;
        usedLibraries.add(lib);
        if (lib.equals("hql.math")) {
            scope().put("KM_TO_MILE", 0.621371);
            scope().put("MILE_TO_KM", 1.60934);
            scope().put("INCH_TO_CM", 2.54);
            scope().put("CM_TO_INCH", 0.393701);
            scope().put("KG_TO_POUND", 2.20462);
            scope().put("POUND_TO_KG", 0.453592);
            scope().put("CELSIUS_OFFSET", 32.0);
            scope().put("LITRE_TO_GALLON", 0.264172);
            scope().put("GALLON_TO_LITRE", 3.78541);
            scope().put("METER_TO_FEET", 3.28084);
            scope().put("FEET_TO_METER", 0.3048);
        }
    }

    private boolean isImportCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("import"); }

    // --- THUẬT TOÁN IMPORT MODULE BẢO MẬT (HỖ TRỢ @) ---
    private void executeImport(String fileName) {
        try {
            // 1. CHỤP LẠI TRẠNG THÁI TRƯỚC KHI IMPORT (Làm mốc để lọc Export)
            java.util.Set<String> oldVars = scope().tenBien();
            java.util.Set<String> oldFuncs = new java.util.HashSet<>(functionMemory.keySet());
            java.util.Set<String> oldClasses = new java.util.HashSet<>(hqlClasses.keySet());

            // 2. BƯỚC QUAN TRỌNG: Quét file để nạp các Hàm (func) và Lớp (class)
            parseFileForDeclarations(fileName);

            // 3. Bật cờ isImporting = true để CHẶN các lệnh in rác (print, if, vòng lặp...)
            boolean oldIsImporting = isImporting;
            isImporting = true;

            // 4. Đọc file và chạy để nạp các biến toàn cục (var)
            String code = java.nio.file.Files.readString(java.nio.file.Paths.get(fileName), java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<Token> importedTokens = Lexer.tokenize(code, fileName);
            runAll(importedTokens);

            // 5. Tắt cờ để file chính tiếp tục chạy bình thường
            isImporting = oldIsImporting;

            // 6. BỘ LỌC XUẤT KHẨU BẢO MẬT (Xóa đi các Biến/Hàm/Lớp không có dấu @)
            java.util.List<String> toRemoveVars = new java.util.ArrayList<>();
            for (String key : scope().tenBien()) {
                if (!oldVars.contains(key) && !key.startsWith("@")) toRemoveVars.add(key);
            }
            for (String key : toRemoveVars) scope().variables.remove(key);

            java.util.List<String> toRemoveFuncs = new java.util.ArrayList<>();
            for (String key : functionMemory.keySet()) {
                if (!oldFuncs.contains(key) && !key.startsWith("@")) toRemoveFuncs.add(key);
            }
            for (String key : toRemoveFuncs) functionMemory.remove(key);

            java.util.List<String> toRemoveClasses = new java.util.ArrayList<>();
            for (String key : hqlClasses.keySet()) {
                if (!oldClasses.contains(key) && !key.startsWith("@")) toRemoveClasses.add(key);
            }
            for (String key : toRemoveClasses) hqlClasses.remove(key);

        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi import thư viện '" + fileName + "': " + e.getMessage());
        }
    }

    private boolean isFunctionDeclaration(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("func"); }
    private void declareFunction(List<Token> tokens) {
        String functionName = tokens.get(1).value;
        int bodyBraceIndex = findTokenIndex(tokens, "L_BRACE", 0);
        List<String> params = parseParamNames(trimWrappingParentheses(tokens.subList(2, bodyBraceIndex)));
        int bodyEndIndex = findMatchingBrace(tokens, bodyBraceIndex);
        if (bodyEndIndex == -1) {
            throw new RuntimeException("Lỗi: Thiếu dấu đóng ngoặc '}' cho hàm '" + functionName + "'");
        }
        functionMemory.put(functionName, new FunctionDef(functionName, params, tokens.subList(bodyBraceIndex + 1, bodyEndIndex)));
    }

    private boolean isClassDeclaration(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("class"); }
    private void declareClass(List<Token> tokens) {
        String className = tokens.get(1).value;
        String superclassName = null;
        int bodyBraceIndex = findTokenIndex(tokens, "L_BRACE", 0);
        if (bodyBraceIndex > 3 && tokens.get(2).value.equals("extends")) superclassName = tokens.get(3).value;
        HqlClass newClass = new HqlClass(className, superclassName);
        List<Token> body = tokens.subList(bodyBraceIndex + 1, findMatchingBrace(tokens, bodyBraceIndex));

        List<List<Token>> members = splitCommands(body);
        for (List<Token> memberTokens : members) {
            if (memberTokens.isEmpty()) continue;
            if (isFunctionDeclaration(memberTokens)) {
                String memberName = memberTokens.get(1).value;
                int methodBodyBraceIndex = findTokenIndex(memberTokens, "L_BRACE", 0);
                List<String> params = parseParamNames(trimWrappingParentheses(memberTokens.subList(2, methodBodyBraceIndex)));
                int methodBodyEndIndex = findMatchingBrace(memberTokens, methodBodyBraceIndex);
                newClass.methods.put(memberName, new FunctionDef(memberName, params, memberTokens.subList(methodBodyBraceIndex + 1, methodBodyEndIndex)));
            } else if (memberTokens.get(0).value.equals("var")) {
                newClass.fields.add(memberTokens.get(1).value);
            }
        }
        hqlClasses.put(className, newClass);
    }

    public void runAll(List<Token> tokens) {
        // 1. Cất trạng thái IF của "cha" vào một biến cục bộ
        boolean savedIfState = this.previousIfMatched;

        for (List<Token> command : splitCommands(tokens)) {
            runSingle(command);
        }

        // 2. Trả lại trạng thái cho "cha" sau khi các lệnh con chạy xong
        this.previousIfMatched = savedIfState;
    }

    public void runSingle(List<Token> tokens) {
        if (tokens.isEmpty() || isUseCommand(tokens) || isImportCommand(tokens) || isFunctionDeclaration(tokens) || isClassDeclaration(tokens)) return;

        vet().cuoi = tokens.get(0);

        if (isImporting) {
            // Chỉ cho phép khai báo biến (var) và gán giá trị (=)
            if (!(isAssignment(tokens) || tokens.get(0).value.equals("var"))) {
                return; // Âm thầm vứt bỏ mọi lệnh print, if, while... Không cho phép nổ log!
            }
        }

        if (pendingException != null && !tokens.get(0).value.equals("catch")) {
            Exception ex = pendingException;
            pendingException = null;
            throw new RuntimeException(ex);
        }
        if (isTryCommand(tokens)) { executeTry(tokens); return; }
        if (isCatchCommand(tokens)) { executeCatch(tokens); return; }
        if (isThrowCommand(tokens)) {
            throw new RuntimeException(stringify(resolveExpression(tokens.subList(1, tokens.size()))));
        }

        if (isIfCommand(tokens)) { executeIf(tokens); return; }
        if (isElseCommand(tokens)) { executeElse(tokens); return; }
        if (isWhileCommand(tokens)) { executeWhile(tokens); return; }
        if (isForCommand(tokens)) { executeFor(tokens); return; }

        // Bắt lệnh break, continue và switch
        if (tokens.size() == 1 && tokens.get(0).value.equals("break")) throw new BreakException();
        if (tokens.size() == 1 && tokens.get(0).value.equals("continue")) throw new ContinueException();
        if (isSwitchCommand(tokens)) { executeSwitch(tokens); return; }

        if (isPrintCommand(tokens)) { executePrint(tokens); return; }

        if (tokens.size() == 2 && tokens.get(0).type.equals("IDENTIFIER") && (tokens.get(1).value.equals("++") || tokens.get(1).value.equals("--"))) {
            String varName = tokens.get(0).value;
            double val = toDouble(scope().get(varName));
            scope().assign(varName, tokens.get(1).value.equals("++") ? val + 1 : val - 1);
            return;
        }

        if (isAssignment(tokens)) { executeAssignment(tokens); return; }

        // RETURN: ghi lại giá trị rồi NHẢY THẲNG ra khỏi hàm.
        // (Trước đây return chỉ ghi giá trị nên các lệnh phía sau vẫn chạy tiếp -> sai logic.)
        if (isReturnCommand(tokens)) {
            if (tokens.size() == 1) returnValue = "";
            else returnValue = resolveExpression(tokens.subList(1, tokens.size()));
            throw new ReturnException();
        }

        // --- CHỐT CHẶN CUỐI CÙNG ---
        // Bắt lỗi các biểu thức rác đứng một mình (ví dụ: b-, a + 5).
        // Các lệnh hợp lệ rơi xuống đây chỉ có thể là gọi hàm (luôn kết thúc bằng dấu ')').
        if (!tokens.isEmpty() && !tokens.get(tokens.size() - 1).type.equals("R_PAREN")) {
            throw new RuntimeException("Lỗi Cú Pháp (Interpreter): Câu lệnh không hợp lệ hoặc dư toán tử '" + tokens.get(0).value + "'");
        }

        resolveExpression(tokens);
    }

    private void enterScope() { datScope(new Scope(scope())); }
    /** Mở vùng nhớ riêng của một hàm: biến khai báo bằng 'var' bên trong sẽ không đè ra ngoài. */
    private void enterFunctionScope() { datScope(new Scope(scope(), true)); }
    private void exitScope() { datScope(scope().getParent()); }

    private boolean isPrintCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("print"); }
    private void executePrint(List<Token> tokens) {
        List<Token> printTokens = trimWrappingParentheses(tokens.subList(1, tokens.size()));
        // THÊM DÒNG NÀY: Bỏ qua an toàn nếu print rỗng
        if (printTokens.isEmpty()) { System.out.println(); return; }
        System.out.println(stringify(resolveExpression(printTokens)));
    }

    private boolean isAssignment(List<Token> t) { return findTopLevelOperator(t, "ASSIGN") != -1 || findTopLevelOperator(t, "ASSIGN_OP") != -1; }
    private void executeAssignment(List<Token> tokens) {
        int assignIndex = findTopLevelOperator(tokens, "ASSIGN");
        if (assignIndex == -1) assignIndex = findTopLevelOperator(tokens, "ASSIGN_OP");

        List<Token> lhs = tokens.subList(0, assignIndex);
        boolean laKhaiBao = false;   // Có từ khoá 'var' đứng trước hay không
        if (!lhs.isEmpty() && lhs.get(0).type.equals("KEYWORD") && lhs.get(0).value.equals("var")) {
            lhs = lhs.subList(1, lhs.size());
            laKhaiBao = true;
        }
        String op = tokens.get(assignIndex).value;
        Object rhsValue = resolveExpression(tokens.subList(assignIndex + 1, tokens.size()));

        if (lhs.size() == 1 && lhs.get(0).type.equals("IDENTIFIER")) {
            String varName = lhs.get(0).value;
            if (op.equals("=")) {
                if (laKhaiBao) scope().declare(varName, rhsValue);
                else scope().assign(varName, rhsValue);
            } else {
                Object lhsValue = scope().get(varName);
                if (lhsValue == null) throw new RuntimeException("Lỗi: Biến '" + varName + "' chưa được khởi tạo.");
                double result = switch (op) {
                    case "+=" -> toDouble(lhsValue) + toDouble(rhsValue);
                    case "-=" -> toDouble(lhsValue) - toDouble(rhsValue);
                    case "*=" -> toDouble(lhsValue) * toDouble(rhsValue);
                    case "/=" -> toDouble(lhsValue) / toDouble(rhsValue);
                    default -> throw new RuntimeException("Toán tử gán không hợp lệ: " + op);
                };
                scope().assign(varName, result);
            }
        } else if (!lhs.isEmpty() && lhs.get(lhs.size() - 1).type.equals("R_BRACKET")) {
            // --- GÁN THEO CHỈ SỐ: mang[i] = giaTri  /  bang["khoa"] = giaTri  (kể cả += -= *= /=) ---
            int openIdx = -1, depth = 0;
            for (int i = lhs.size() - 1; i >= 0; i--) {
                if (lhs.get(i).type.equals("R_BRACKET")) depth++;
                else if (lhs.get(i).type.equals("L_BRACKET")) {
                    depth--;
                    if (depth == 0) { openIdx = i; break; }
                }
            }
            if (openIdx <= 0) throw new RuntimeException("Lỗi: Vế trái của phép gán không hợp lệ.");

            Object container = resolveExpression(lhs.subList(0, openIdx));
            Object khoa = resolveExpression(lhs.subList(openIdx + 1, lhs.size() - 1));

            if (container instanceof List) {
                List<Object> ds = (List<Object>) container;
                int idx = (int) toDouble(khoa);
                if (idx < 0 || idx >= ds.size())
                    throw new RuntimeException("Lỗi: Vị trí " + idx + " nằm ngoài mảng (kích thước " + ds.size() + ").");
                Object giaTriMoi = rhsValue;
                if (!op.equals("=")) {
                    double cu = toDouble(ds.get(idx));
                    giaTriMoi = switch (op) {
                        case "+=" -> cu + toDouble(rhsValue);
                        case "-=" -> cu - toDouble(rhsValue);
                        case "*=" -> cu * toDouble(rhsValue);
                        case "/=" -> cu / toDouble(rhsValue);
                        default -> throw new RuntimeException("Toán tử gán không hợp lệ: " + op);
                    };
                }
                ds.set(idx, giaTriMoi);
            } else if (container instanceof Map) {
                Map<Object, Object> bang = (Map<Object, Object>) container;
                Object giaTriMoi = rhsValue;
                if (!op.equals("=")) {
                    double cu = toDouble(bang.get(stringify(khoa)));
                    giaTriMoi = switch (op) {
                        case "+=" -> cu + toDouble(rhsValue);
                        case "-=" -> cu - toDouble(rhsValue);
                        case "*=" -> cu * toDouble(rhsValue);
                        case "/=" -> cu / toDouble(rhsValue);
                        default -> throw new RuntimeException("Toán tử gán không hợp lệ: " + op);
                    };
                }
                bang.put(stringify(khoa), giaTriMoi);
            } else {
                throw new RuntimeException("Lỗi: Chỉ có thể gán bằng [ ] cho mảng hoặc bảng (map).");
            }
        } else if (findTopLevelOperator(lhs, "DOT") != -1) {
            int dotIndex = findTopLevelOperator(lhs, "DOT");
            List<Token> callerTokens = lhs.subList(0, dotIndex);
            Object target = resolveExpression(callerTokens);
            String fieldName = lhs.get(dotIndex + 1).value;

            if (target instanceof HqlObject) {
                // --- KIỂM TRA BẢO MẬT OOP KHI GÁN GIÁ TRỊ ---
                boolean isInternal = (callerTokens.size() == 1 && callerTokens.get(0).value.equals("this"));
                boolean isPublic = fieldName.startsWith("@");

                if (!isPublic && !isInternal) {
                    throw new RuntimeException("Lỗi OOP: Không thể gán! Thuộc tính '" + fieldName + "' là Private.");
                }

                ((HqlObject) target).fields.put(fieldName, rhsValue);
            } else {
                throw new RuntimeException("Lỗi: Không thể gán thuộc tính cho một giá trị không phải đối tượng.");
            }
        } else {
            throw new RuntimeException("Lỗi: Vế trái của phép gán không hợp lệ.");
        }
    }

    // --- KHỐI XỬ LÝ NGOẠI LỆ (TRY...CATCH...THROW) ---

    private boolean isTryCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("try"); }
    private void executeTry(List<Token> tokens) {
        int bodyBraceIndex = findTokenIndex(tokens, "L_BRACE", 0);
        int bodyEndIndex = findMatchingBrace(tokens, bodyBraceIndex);
        List<Token> body = tokens.subList(bodyBraceIndex + 1, bodyEndIndex);

        pendingException = null;
        Scope savedScope = scope(); // LƯU LẠI GỐC

        try {
            datScope(new Scope(savedScope)); // Mở scope an toàn
            runAll(body);
        } catch (ReturnException | BreakException | ContinueException dieuKhien) {
            // return/break/continue KHÔNG phải lỗi -> phải cho nó bay tiếp ra ngoài,
            // nếu nuốt ở đây thì 'return' nằm trong try...catch sẽ mất tác dụng.
            datScope(savedScope);
            throw dieuKhien;
        } catch (Exception ex) {
            pendingException = ex; // Chỉ treo lỗi, không đụng vào scope
        } finally {
            datScope(savedScope); // ÉP BUỘC KHÔI PHỤC GỐC dù có lỗi hay không
        }

        int nextIndex = bodyEndIndex + 1;

        // 1. Chạy Catch
        if (nextIndex < tokens.size() && tokens.get(nextIndex).value.equals("catch")) {
            int catchBraceIndex = findTokenIndex(tokens, "L_BRACE", nextIndex);
            int catchEndIndex = findMatchingBrace(tokens, catchBraceIndex);
            executeCatch(tokens.subList(nextIndex, catchEndIndex + 1));
            nextIndex = catchEndIndex + 1;
        }

        // 2. Chạy Finally
        if (nextIndex < tokens.size() && tokens.get(nextIndex).value.equals("finally")) {
            int finallyBraceIndex = findTokenIndex(tokens, "L_BRACE", nextIndex);
            int finallyEndIndex = findMatchingBrace(tokens, finallyBraceIndex);
            List<Token> finallyBody = tokens.subList(finallyBraceIndex + 1, finallyEndIndex);

            Scope preFinallyScope = scope();
            try {
                datScope(new Scope(preFinallyScope));
                runAll(finallyBody);
            } finally {
                datScope(preFinallyScope);
            }
        }
    }

    private boolean isCatchCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("catch"); }
    private void executeCatch(List<Token> tokens) {
        if (pendingException == null) return; // Nếu try chạy mượt (không có lỗi) thì bỏ qua catch

        String errVarName = "e"; // Biến lỗi mặc định
        int parenIndex = findTokenIndex(tokens, "L_PAREN", 0);
        int braceIndex = findTokenIndex(tokens, "L_BRACE", 0);

        if (parenIndex != -1 && parenIndex < braceIndex) {
            errVarName = tokens.get(parenIndex + 1).value;
        }

        int bodyEndIndex = findMatchingBrace(tokens, braceIndex);
        List<Token> body = tokens.subList(braceIndex + 1, bodyEndIndex);

        // FIX LỖI CRASH CHÍNH LÀ Ở ĐÂY:
        // Lưu thông báo lỗi lại, sau đó XÓA pendingException NGAY LẬP TỨC trước khi chạy code bên trong catch!
        String errorMessage = pendingException.getMessage() != null ? pendingException.getMessage() : pendingException.toString();
        pendingException = null;

        Scope gocCatch = scope();
        try {
            enterScope();
            scope().put(errVarName, errorMessage);
            runAll(body); // Bây giờ chạy code thoải mái không sợ bị sập nữa
        } finally {
            datScope(gocCatch);   // luôn đóng vùng nhớ, kể cả khi bên trong có return/break
        }
    }

    private boolean isThrowCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("throw"); }

    private boolean isIfCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("if"); }
    private void executeIf(List<Token> tokens) {
        int bodyBraceIndex = findTokenIndex(tokens, "L_BRACE", 0);
        List<Token> condition = extractCondition(tokens, 1, bodyBraceIndex);
        int bodyEndIndex = findMatchingBrace(tokens, bodyBraceIndex);

        // Tính toán điều kiện
        boolean isMatched = evaluateBooleanExpression(condition);
        this.previousIfMatched = isMatched; // Lưu lại để cái 'else' kế tiếp check

        if (isMatched) {
            List<Token> body = tokens.subList(bodyBraceIndex + 1, bodyEndIndex);
            Scope gocIf = scope();
            try {
                enterScope();
                runAll(body); // Hàm runAll mới sẽ tự bảo vệ trạng thái này
            } finally {
                datScope(gocIf);  // return/break nhảy ra giữa chừng vẫn phải trả lại vùng nhớ
            }
        }

        // --- SỬA LỖI Ở ĐÂY: Hỗ trợ else viết dính liền " } else { " ---
        if (bodyEndIndex + 1 < tokens.size() && tokens.get(bodyEndIndex + 1).value.equals("else")) {
            executeElse(tokens.subList(bodyEndIndex + 1, tokens.size()));
        }
    }

    private boolean isElseCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("else"); }
    private void executeElse(List<Token> tokens) {
        boolean daCoNhanhKhop = previousIfMatched;

        // --- HỖ TRỢ CHUỖI "else if" ---
        // Trước đây "else if (dk) {A} else {B}" bị hiểu nhầm: nó nhảy tới dấu { đầu tiên
        // (chính là { của if) rồi chạy A mà KHÔNG kiểm tra điều kiện dk, đồng thời nuốt luôn nhánh else cuối.
        if (tokens.size() > 1 && tokens.get(1).value.equals("if")) {
            if (!daCoNhanhKhop) {
                // Chưa nhánh nào khớp -> coi phần còn lại là một lệnh if hoàn chỉnh (nó tự lo else phía sau)
                executeIf(tokens.subList(1, tokens.size()));
            } else {
                // Đã có nhánh khớp rồi -> bỏ qua toàn bộ chuỗi else if / else còn lại
                previousIfMatched = true;
            }
            return;
        }

        if (!daCoNhanhKhop) {
            int bodyBraceIndex = findTokenIndex(tokens, "L_BRACE", 0);
            int bodyEndIndex = findMatchingBrace(tokens, bodyBraceIndex);
            List<Token> body = tokens.subList(bodyBraceIndex + 1, bodyEndIndex);
            Scope gocElse = scope();
            try {
                enterScope();
                runAll(body);
            } finally {
                datScope(gocElse);
            }
        }
        // Set thành true để báo hiệu cụm IF-ELSE này đã xong
        previousIfMatched = true;
    }

    private boolean isWhileCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("while"); }
    private void executeWhile(List<Token> tokens) {
        int bodyBraceIndex = findTokenIndex(tokens, "L_BRACE", 0);
        List<Token> condition = extractCondition(tokens, 1, bodyBraceIndex);
        int bodyEndIndex = findMatchingBrace(tokens, bodyBraceIndex);
        List<Token> body = tokens.subList(bodyBraceIndex + 1, bodyEndIndex);
        Scope gocWhile = scope();
        while (evaluateBooleanExpression(condition)) {
            try {
                enterScope();
                runAll(body);
            } catch (BreakException e) {
                break;                 // Bị ngắt thì thoát luôn vòng lặp
            } catch (ContinueException e) {
                continue;              // Bỏ qua và chạy vòng tiếp theo
            } finally {
                datScope(gocWhile);   // đóng vùng nhớ trong MỌI trường hợp (kể cả return)
            }
        }
    }

    private boolean isForCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("for"); }
    private void executeFor(List<Token> tokens) {
        int bodyBraceIndex = findTokenIndex(tokens, "L_BRACE", 0);

        // BƯỚC 1: Lột sạch dấu ngoặc ( ) nếu người dùng có viết (Hỗ trợ cả chuẩn C/Java và Python)
        List<Token> header = trimWrappingParentheses(tokens.subList(1, bodyBraceIndex));

        int bodyEndIndex = findMatchingBrace(tokens, bodyBraceIndex);
        List<Token> body = tokens.subList(bodyBraceIndex + 1, bodyEndIndex);

        int inPos = -1, toPos = -1;
        for (int i=0; i<header.size(); i++) {
            if (header.get(i).value.equals("in")) inPos = i;
            if (header.get(i).type.equals("RANGE") || header.get(i).value.equals("to")) toPos = i;
        }

        if (inPos != -1) {
            String varName = header.get(0).value;

            // TRƯỜNG HỢP 1: Lặp theo Range (for i in 1 .. 3)
            if (toPos != -1 && inPos < toPos) {
                int start = (int) toDouble(resolveExpression(header.subList(inPos + 1, toPos)));
                int end = (int) toDouble(resolveExpression(header.subList(toPos + 1, header.size())));
                Scope gocFor = scope();
                for (int i = start; i <= end; i++) {
                    try {
                        enterScope();
                        scope().put(varName, (double) i);
                        runAll(body);
                    } catch (BreakException e) { break; }
                    catch (ContinueException e) { continue; }
                    finally { datScope(gocFor); }
                }
            }
            // TRƯỜNG HỢP 2: Lặp Foreach cho Mảng và Chuỗi (for member in team)
            else {
                Object iterable = resolveExpression(header.subList(inPos + 1, header.size()));

                // Nếu duyệt qua danh sách (List)
                if (iterable instanceof ArrayList) {
                    ArrayList<?> list = (ArrayList<?>) iterable;
                    Scope gocDs = scope();
                    for (Object item : list) {
                        try {
                            enterScope();
                            scope().put(varName, item);
                            runAll(body);
                        } catch (BreakException e) { break; }
                        catch (ContinueException e) { continue; }
                        finally { datScope(gocDs); }
                    }
                }
                // Nếu duyệt qua chữ cái trong một Chuỗi (String)
                else if (iterable instanceof String) {
                    String str = (String) iterable;
                    Scope gocChuoi = scope();
                    for (char c : str.toCharArray()) {
                        try {
                            enterScope();
                            scope().put(varName, String.valueOf(c));
                            runAll(body);
                        } catch (BreakException e) { break; }
                        catch (ContinueException e) { continue; }
                        finally { datScope(gocChuoi); }
                    }
                } else {
                    throw new RuntimeException("Lỗi: Không thể dùng 'in' cho kiểu dữ liệu này.");
                }
            }
        }
    }

    // --- THUẬT TOÁN XỬ LÝ SWITCH - CASE ---
    private boolean isSwitchCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("switch"); }

    private void executeSwitch(List<Token> tokens) {
        int bodyBraceIndex = findTokenIndex(tokens, "L_BRACE", 0);
        Object switchValue = resolveExpression(trimWrappingParentheses(tokens.subList(1, bodyBraceIndex)));
        int bodyEndIndex = findMatchingBrace(tokens, bodyBraceIndex);
        List<Token> body = tokens.subList(bodyBraceIndex + 1, bodyEndIndex);

        List<List<Token>> statements = splitCommands(body);
        boolean fallThrough = false; // Cờ hiệu chạy tuột xuống nếu không có break

        Scope gocSwitch = scope();
        enterScope();
        try {
            for (List<Token> stmt : statements) {
                if (stmt.isEmpty()) continue;

                if (stmt.get(0).value.equals("case")) {
                    int colonIdx = findTokenIndex(stmt, "COLON", 0);
                    if (colonIdx == -1) throw new RuntimeException("Lỗi cú pháp: Thiếu ':' sau 'case'.");

                    Object caseVal = resolveExpression(stmt.subList(1, colonIdx));
                    if (isEqual(switchValue, caseVal)) {
                        fallThrough = true; // Khớp giá trị thì mở cờ cho chạy
                    }

                    // Nếu người dùng viết lệnh ngay sau dấu hai chấm (VD: case 1: print("A");)
                    if (fallThrough && stmt.size() > colonIdx + 1) {
                        runSingle(stmt.subList(colonIdx + 1, stmt.size()));
                    }
                } else if (stmt.get(0).value.equals("default")) {
                    int colonIdx = findTokenIndex(stmt, "COLON", 0);
                    if (colonIdx == -1) throw new RuntimeException("Lỗi cú pháp: Thiếu ':' sau 'default'.");

                    fallThrough = true; // Mở cờ chạy cho trường hợp mặc định
                    if (fallThrough && stmt.size() > colonIdx + 1) {
                        runSingle(stmt.subList(colonIdx + 1, stmt.size()));
                    }
                } else {
                    // Nếu cờ đang mở (đã khớp case trên), thực thi các lệnh bên trong case
                    if (fallThrough) runSingle(stmt);
                }
            }
        } catch (BreakException e) {
            // Khi người dùng gọi 'break;', nó sẽ văng ngoại lệ ra tới đây
            // Catch này sẽ cản lại, kết quả là thoát khỏi cấu trúc switch êm ru!
        } finally {
            datScope(gocSwitch);
        }
    }

    private boolean isReturnCommand(List<Token> t) { return !t.isEmpty() && t.get(0).value.equals("return"); }

    /**
     * Cắt bỏ các dấu xuống dòng (END) thừa ở hai đầu biểu thức.
     * Nhờ đó mảng/biểu thức viết trải trên nhiều dòng vẫn chạy được, ví dụ:
     *     var ds = [
     *         "Laptop",
     *         "Phone"
     *     ];
     */
    private List<Token> trimExpressionNoise(List<Token> tokens) {
        int start = 0, end = tokens.size();
        while (start < end && tokens.get(start).type.equals("END")) start++;
        while (end > start && tokens.get(end - 1).type.equals("END")) end--;
        if (start == 0 && end == tokens.size()) return tokens;
        return trimWrappingParentheses(tokens.subList(start, end));
    }

    private Object resolveExpression(List<Token> tokens) {
        tokens = trimExpressionNoise(trimWrappingParentheses(tokens));
        if (tokens.isEmpty()) throw new RuntimeException("Lỗi Cú Pháp: Biểu thức bị khuyết (có thể do dư toán tử).");

        // --- THÊM KHỐI NÀY ĐỂ CỨU TOÁN TỬ MỘT NGÔI (-5, +5) ---
        // Nếu biểu thức bắt đầu bằng dấu + hoặc -, tự động chèn số 0 vào trước (thành 0-5)
        if (tokens.get(0).type.equals("OPERATOR") && (tokens.get(0).value.equals("-") || tokens.get(0).value.equals("+"))) {
            List<Token> fixedTokens = new java.util.ArrayList<>();
            fixedTokens.add(new Token("NUMBER", "0", tokens.get(0).file, tokens.get(0).line));
            fixedTokens.addAll(tokens);
            tokens = fixedTokens;
        }
        // --------------------------------------------------------

        if (tokens.size() == 1) return getObjectValue(tokens.get(0));

        vet().cuoi = tokens.get(0);

        int questionIndex = findTopLevelOperator(tokens, "TERNARY_Q");
        if (questionIndex != -1) {
            int colonIndex = findTopLevelOperator(tokens, "COLON");
            if (colonIndex == -1) throw new RuntimeException("Lỗi: Thiếu dấu ':' trong toán tử ba ngôi.");

            // 1. Giải quyết vế điều kiện (trước dấu ?)
            boolean condition = toBoolean(resolveExpression(tokens.subList(0, questionIndex)));

            // 2. Trả về kết quả tương ứng
            if (condition) {
                return resolveExpression(tokens.subList(questionIndex + 1, colonIndex));
            } else {
                return resolveExpression(tokens.subList(colonIndex + 1, tokens.size()));
            }
        }

        if (tokens.get(0).type.equals("L_BRACKET") && tokens.get(tokens.size() - 1).type.equals("R_BRACKET")) {
            ArrayList<Object> list = new ArrayList<>();
            List<List<Token>> items = splitTopLevel(tokens.subList(1, tokens.size() - 1), "COMMA");
            for(List<Token> item : items) {
                if (!item.isEmpty()) list.add(resolveExpression(item));
            }
            return list;
        }

        if (tokens.get(0).value.equals("new")) return executeNew(tokens.subList(1, tokens.size()));

        int opIndex;
        if ((opIndex = findTopLevelOperator(tokens, "LOGIC")) != -1) return evaluateBooleanExpression(tokens);
        if ((opIndex = findTopLevelOperator(tokens, "COMPARE")) != -1) return evaluateBooleanExpression(tokens);
        if (tokens.get(0).type.equals("NOT") || (tokens.get(0).type.equals("KEYWORD") && tokens.get(0).value.equals("not"))) {
            return evaluateBooleanExpression(tokens);
        }

        if ((opIndex = findBinaryOperator(tokens, Arrays.asList("+", "-"))) != -1) {
            Object left = resolveExpression(tokens.subList(0, opIndex));
            Object right = resolveExpression(tokens.subList(opIndex + 1, tokens.size()));
            if (left instanceof String || right instanceof String) return stringify(left) + stringify(right);
            return tokens.get(opIndex).value.equals("+") ? toDouble(left) + toDouble(right) : toDouble(left) - toDouble(right);
        }
        if ((opIndex = findBinaryOperator(tokens, Arrays.asList("*", "/", "%"))) != -1) {
            Object left = resolveExpression(tokens.subList(0, opIndex));
            Object right = resolveExpression(tokens.subList(opIndex + 1, tokens.size()));
            return switch (tokens.get(opIndex).value) {
                case "*" -> toDouble(left) * toDouble(right);
                case "/" -> toDouble(left) / toDouble(right);
                case "%" -> toDouble(left) % toDouble(right);
                default -> throw new IllegalStateException();
            };
        }

        // --- DÁN THÊM KHỐI XỬ LÝ MŨ (^) VÀO ĐÂY ---
        if ((opIndex = findTopLevelOperator(tokens, "OPERATOR", Arrays.asList("^"))) != -1) {
            Object left = resolveExpression(tokens.subList(0, opIndex));
            Object right = resolveExpression(tokens.subList(opIndex + 1, tokens.size()));
            return Math.pow(toDouble(left), toDouble(right));
        }

        if (tokens.size() > 1) return resolveCallOrAccess(tokens);

        throw new RuntimeException("Lỗi: Không thể giải quyết biểu thức.");
    }

    private Object resolveCallOrAccess(List<Token> tokens) {
        // --- 1. XỬ LÝ TRUY CẬP MẢNG VÀ CHUỖI LIÊN HOÀN (Ví dụ: arr[0][1] hoặc txt.split()[0]) ---
        if (tokens.get(tokens.size() - 1).type.equals("R_BRACKET")) {
            int openBracketIndex = -1;
            int depth = 0;
            for (int i = tokens.size() - 1; i >= 0; i--) {
                if (tokens.get(i).type.equals("R_BRACKET")) depth++;
                else if (tokens.get(i).type.equals("L_BRACKET")) {
                    depth--;
                    if (depth == 0) { openBracketIndex = i; break; }
                }
            }
            if (openBracketIndex > 0) {
                // Tính toán đối tượng đằng trước dấu ngoặc vuông (Hỗ trợ truy cập đệ quy)
                Object arrayObj = resolveExpression(tokens.subList(0, openBracketIndex));
                // Tính toán biểu thức bên trong ngoặc vuông
                Object indexObj = resolveExpression(tokens.subList(openBracketIndex + 1, tokens.size() - 1));
                int index = (int) toDouble(indexObj);

                if (arrayObj instanceof ArrayList) {
                    ArrayList<?> list = (ArrayList<?>) arrayObj;
                    if (index < 0 || index >= list.size()) throw new RuntimeException("Lỗi mảng: Chỉ số " + index + " nằm ngoài phạm vi (0-" + (list.size() - 1) + ")");
                    return list.get(index);
                }
                if (arrayObj instanceof String) {
                    String str = (String) arrayObj;
                    if (index < 0 || index >= str.length()) throw new RuntimeException("Lỗi chuỗi: Vị trí " + index + " không hợp lệ (Độ dài: " + str.length() + ")");
                    return String.valueOf(str.charAt(index));
                }
                throw new RuntimeException("Lỗi: Không thể dùng cú pháp [ ] cho kiểu dữ liệu này.");
            }
        }

        // --- 2. XỬ LÝ GỌI HÀM VÀ PHƯƠNG THỨC LIÊN HOÀN (Ví dụ: getArray().size() hoặc obj.method()) ---
        if (tokens.get(tokens.size() - 1).type.equals("R_PAREN")) {
            int openParenIndex = -1;
            int depth = 0;
            for (int i = tokens.size() - 1; i >= 0; i--) {
                if (tokens.get(i).type.equals("R_PAREN")) depth++;
                else if (tokens.get(i).type.equals("L_PAREN")) {
                    depth--;
                    if (depth == 0) { openParenIndex = i; break; }
                }
            }
            if (openParenIndex > 0) {
                List<Token> callerTokens = tokens.subList(0, openParenIndex);
                List<Token> argTokens = tokens.subList(openParenIndex + 1, tokens.size() - 1);
                List<List<Token>> argGroups = splitTopLevel(argTokens, "COMMA");
                if (argGroups.size() == 1 && argGroups.get(0).isEmpty()) argGroups.clear();

                // Kiểm tra xem là object.method() hay là function() gọi độc lập
                int dotIndex = findTopLevelOperator(callerTokens, "DOT");
                if (dotIndex != -1) {
                    List<Token> objTokens = callerTokens.subList(0, dotIndex);
                    Object target = resolveExpression(objTokens);
                    String methodName = callerTokens.get(dotIndex + 1).value;

                    // --- KIỂM TRA BẢO MẬT OOP CHO HÀM ---
                    if (target instanceof HqlObject) {
                        // Chỉ cho phép gọi hàm Private nếu gọi bằng từ khóa 'this'
                        boolean isInternal = (objTokens.size() == 1 && objTokens.get(0).value.equals("this"));
                        boolean isPublic = methodName.startsWith("@");

                        if (!isPublic && !isInternal) {
                            throw new RuntimeException("Lỗi OOP: Phương thức '" + methodName + "()' là Private!");
                        }
                    }

                    return executeMethodCall(target, methodName, argGroups);
                }
                // !!! ĐÃ FIX TẠI ĐÂY: Cho phép gọi hàm nếu nó là IDENTIFIER hoặc KEYWORD (như num, str, len...) !!!
                else if (callerTokens.size() == 1 && (callerTokens.get(0).type.equals("IDENTIFIER") || callerTokens.get(0).type.equals("KEYWORD"))) {
                    String functionName = callerTokens.get(0).value;
                    return executeFunctionCall(functionName, argGroups);
                }
                // Sửa tại file Interpreter.java, bên trong hàm resolveCallOrAccess
                else if (callerTokens.size() >= 2 && callerTokens.get(callerTokens.size() - 2).type.equals("DOT")) {
                    int lastDot = callerTokens.size() - 2;
                    List<Token> objTokens = callerTokens.subList(0, lastDot);
                    Object target = resolveExpression(objTokens);
                    String methodName = callerTokens.get(callerTokens.size() - 1).value;

                    // Kiểm tra bảo mật cho HqlObject
                    if (target instanceof HqlObject) {
                        boolean isInternal = (objTokens.size() == 1 && objTokens.get(0).value.equals("this"));
                        boolean isPublic = methodName.startsWith("@");
                        if (!isPublic && !isInternal) {
                            throw new RuntimeException("Lỗi OOP: Phương thức '" + methodName + "()' là Private!");
                        }
                    }

                    // Đảm bảo LUÔN LUÔN trả về kết quả ở đây, chặn đứng việc rơi xuống Exception bên dưới
                    return executeMethodCall(target, methodName, argGroups);
                }
            }
        }

        // --- 3. XỬ LÝ TRUY CẬP THUỘC TÍNH BẰNG DẤU CHẤM (object.field) ---
        int dotIndex = findTopLevelOperator(tokens, "DOT");
        if (dotIndex != -1) {
            List<Token> callerTokens = tokens.subList(0, dotIndex);
            Object object = resolveExpression(callerTokens);
            String memberName = tokens.get(dotIndex + 1).value;

            if (object instanceof HqlObject) {
                // ... (bỏ qua đoạn OOP check)
                return ((HqlObject) object).fields.get(memberName);
            }

            // ---> [PHẦN ĐƯỢC THÊM/SỬA Ở ĐÂY] <---
            // Hỗ trợ truy cập thuộc tính của HashMap (vd: iface.name)
            if (object instanceof java.util.HashMap) {
                java.util.HashMap<?, ?> map = (java.util.HashMap<?, ?>) object;
                if (map.containsKey(memberName)) {
                    return map.get(memberName);
                }
                return ""; // Trả về chuỗi rỗng nếu không tìm thấy key (tránh lỗi crash sảng)
            }

            // FIX: Nếu không phải object hoặc map, văng lỗi tường minh hơn để dễ trace
            throw new RuntimeException("Lỗi: Không thể truy cập thuộc tính '" + memberName + "' của " + detectType(object));
        }

        // FIX: Nếu không rơi vào bất kỳ trường hợp nào, in hẳn token ra để sếp xem token nào bị kẹt
        throw new RuntimeException("Lỗi cú pháp lời gọi hàm hoặc truy cập thuộc tính. Chuỗi Tokens bị lỗi: " + tokens.toString());
    }

    private Object executeNew(List<Token> tokens) {
        String className = tokens.get(0).value;
        if (!hqlClasses.containsKey(className)) {
            throw new RuntimeException("Lỗi: Không tìm thấy lớp '" + className + "'. (Gợi ý: Nếu là Class từ file Import, hãy chắc chắn sếp đã thêm cờ '@' ở đầu tên Class nhé!)");
        }
        HqlClass hqlClass = hqlClasses.get(className);
        HqlObject newObject = new HqlObject(hqlClass);

        // Nâng cấp: Tự động nhận diện cả 2 kiểu viết constructor (có @ và không có @)
        String initFuncName = null;
        if (hqlClass.methods.containsKey("init")) initFuncName = "init";
        else if (hqlClass.methods.containsKey("@init")) initFuncName = "@init";

        if (initFuncName != null) {
            List<List<Token>> argGroups = splitTopLevel(trimWrappingParentheses(tokens.subList(1, tokens.size())), "COMMA");
            if (argGroups.size() == 1 && argGroups.get(0).isEmpty()) argGroups.clear();
            executeMethodCall(newObject, initFuncName, argGroups);
        }
        return newObject;
    }

    public Object executeFunctionCall(String functionName, List<List<Token>> argGroups) {
        if (isBuiltin(functionName)) return executeBuiltin(functionName, argGroups);
        if (functionMemory.containsKey(functionName)) {
            FunctionDef def = functionMemory.get(functionName);
            if (argGroups.size() != def.params.size())
                throw new RuntimeException("Lỗi: Sai số lượng tham số khi gọi hàm '" + functionName
                        + "' (cần " + def.params.size() + ", nhận " + argGroups.size() + ").");

            // Tính giá trị tham số TRƯỚC (ở vùng nhớ của nơi gọi), rồi mới mở vùng nhớ riêng của hàm
            List<Object> giaTriThamSo = new ArrayList<>();
            for (int i = 0; i < def.params.size(); i++) giaTriThamSo.add(resolveExpression(argGroups.get(i)));

            Scope gocHam = scope();

            // Ghi lại CHỖ GỌI trước khi nhảy vào hàm. Vào rồi thì vet().cuoi
            // đổi sang thân hàm, lúc đó không lần ngược ra được nữa.
            Token choGoi = vet().cuoi;
            vet().nganXep.push(new BaoLoi.Khung(functionName,
                    choGoi == null ? "" : choGoi.file,
                    choGoi == null ? 0 : choGoi.line));

            boolean xongEm = false;
            try {
                enterFunctionScope();
                for (int i = 0; i < def.params.size(); i++) {
                    scope().put(def.params.get(i), giaTriThamSo.get(i));
                }
                runAll(def.body);
                xongEm = true;
            } catch (ReturnException bo) {
                // Gặp 'return' -> thoát hàm ngay, giá trị đã nằm trong returnValue
                xongEm = true;
            } finally {
                datScope(gocHam);   // trả lại đúng vùng nhớ của nơi gọi

                // Chỉ dọn khung khi hàm chạy XONG ÊM. Đang có lỗi bay ra thì GIỮ
                // NGUYÊN, vì chỗ bắt lỗi trên cùng cần cả ngăn xếp mới in được
                // đường đi. webDispatch / runFile xoá sạch ở đầu mỗi lượt chạy.
                if (xongEm && !vet().nganXep.isEmpty()) { vet().nganXep.pop(); }
            }
            Object result = returnValue;
            returnValue = "";
            return result;
        }
        throw new RuntimeException("Lỗi: Không tìm thấy hàm '" + functionName + "'.");
    }

    @SuppressWarnings("unchecked")
    private Object executeMethodCall(Object target, String methodName, List<List<Token>> argGroups) {
        if (target instanceof String) {
            String str = (String) target;
            switch(methodName) {
                case "split":
                    String delimiter = argGroups.isEmpty() || argGroups.get(0).isEmpty() ? "\\s+" : stringify(resolveExpression(argGroups.get(0)));
                    return new ArrayList<>(Arrays.asList(str.split(Pattern.quote(delimiter))));
                case "trim": return str.trim();
                case "strip": return str.strip();
                case "lstrip": return str.replaceAll("^\\s+", "");
                case "rstrip": return str.replaceAll("\\s+$", "");
                case "substring":
                    int start = (int) toDouble(resolveExpression(argGroups.get(0)));
                    if (argGroups.size() > 1) {
                        int end = (int) toDouble(resolveExpression(argGroups.get(1)));
                        // Bọc try-catch đề phòng người dùng truyền end dài hơn cả chuỗi
                        try { return str.substring(start, end); } catch (Exception e) { return str; }
                    }
                    try {
                        return str.substring(start);
                    } catch (Exception e) {
                        return str;
                    }
                case "toLowerCase": return str.toLowerCase();
                case "toUpperCase": return str.toUpperCase();
                case "indexOf": return (double) str.indexOf(stringify(resolveExpression(argGroups.get(0))));
                case "lastIndexOf": return (double) str.lastIndexOf(stringify(resolveExpression(argGroups.get(0))));
                case "startsWith": return str.startsWith(stringify(resolveExpression(argGroups.get(0))));
                case "endsWith": return str.endsWith(stringify(resolveExpression(argGroups.get(0))));
                case "replace": return str.replace(stringify(resolveExpression(argGroups.get(0))), stringify(resolveExpression(argGroups.get(1))));

                case "contains": return str.contains(stringify(resolveExpression(argGroups.get(0))));
                case "containsRegex": return java.util.regex.Pattern.compile(stringify(resolveExpression(argGroups.get(0)))).matcher(str).find();
                case "matches": return str.matches(stringify(resolveExpression(argGroups.get(0))));

                case "insert":
                    int insIdx = (int) toDouble(resolveExpression(argGroups.get(0)));
                    String insStr = stringify(resolveExpression(argGroups.get(1)));
                    if (insIdx < 0) insIdx = 0;
                    if (insIdx > str.length()) insIdx = str.length();
                    return str.substring(0, insIdx) + insStr + str.substring(insIdx);
                case "removeAt":
                    int rmIdx = (int) toDouble(resolveExpression(argGroups.get(0)));
                    if (rmIdx < 0 || rmIdx >= str.length())
                        throw new RuntimeException("removeAt index out of bounds: " + rmIdx);
                    return str.substring(0, rmIdx) + str.substring(rmIdx + 1);
            }
        }
        if (target instanceof ArrayList) {
            ArrayList<Object> list = (ArrayList<Object>) target;
            switch(methodName) {
                case "push":
                case "add":
                    list.add(resolveExpression(argGroups.get(0)));
                    return (double) list.size();
                case "pop":
                    if (list.isEmpty()) throw new RuntimeException("Lỗi: Mảng rỗng.");
                    return list.remove(list.size() - 1);
                case "set": {
                    // mang.set(viTri, giaTri) - đổi giá trị 1 phần tử tại chỗ
                    int viTri = (int) toDouble(resolveExpression(argGroups.get(0)));
                    if (viTri < 0 || viTri >= list.size())
                        throw new RuntimeException("Lỗi: Vị trí " + viTri + " nằm ngoài mảng (kích thước " + list.size() + ").");
                    return list.set(viTri, resolveExpression(argGroups.get(1)));
                }
                case "clear":
                    list.clear();
                    return null;
                case "insert":
                    list.add((int) toDouble(resolveExpression(argGroups.get(0))), resolveExpression(argGroups.get(1)));
                    return null;
                case "removeAt":
                    return list.remove((int) toDouble(resolveExpression(argGroups.get(0))));
                case "indexOf":
                    return (double) list.indexOf(resolveExpression(argGroups.get(0)));
                case "join":
                    String sep = argGroups.isEmpty() ? "" : stringify(resolveExpression(argGroups.get(0)));
                    return list.stream().map(this::stringify).collect(Collectors.joining(sep));
                case "map": {
                    String mapFunc = stringify(resolveExpression(argGroups.get(0)));
                    FunctionDef def = functionMemory.get(mapFunc);
                    if (def == null) throw new RuntimeException("Lỗi: Không tìm thấy hàm callback '" + mapFunc + "'");

                    ArrayList<Object> mapResult = new ArrayList<>();
                    Scope gocMap = scope();
                    for (Object item : list) {
                        try {
                            enterFunctionScope();
                            if (!def.params.isEmpty()) scope().put(def.params.get(0), item);
                            runAll(def.body);
                        } catch (ReturnException bo) {
                        } finally { datScope(gocMap); }
                        mapResult.add(returnValue);
                        returnValue = "";
                    }
                    return mapResult;
                }
                case "filter": {
                    String filterFunc = stringify(resolveExpression(argGroups.get(0)));
                    FunctionDef def = functionMemory.get(filterFunc);
                    if (def == null) throw new RuntimeException("Lỗi: Không tìm thấy hàm callback '" + filterFunc + "'");

                    ArrayList<Object> filterResult = new ArrayList<>();
                    Scope gocLoc = scope();
                    for (Object item : list) {
                        try {
                            enterFunctionScope();
                            if (!def.params.isEmpty()) scope().put(def.params.get(0), item);
                            runAll(def.body);
                        } catch (ReturnException bo) {
                        } finally { datScope(gocLoc); }
                        if (toBoolean(returnValue)) filterResult.add(item);
                        returnValue = "";
                    }
                    return filterResult;
                }
                case "reduce": {
                    String reduceFunc = stringify(resolveExpression(argGroups.get(0)));
                    FunctionDef def = functionMemory.get(reduceFunc);
                    if (def == null || def.params.size() < 2)
                        throw new RuntimeException("Lỗi: Hàm callback reduce phải có ít nhất 2 tham số (accumulator, currentValue)");

                    Object accumulator = argGroups.size() > 1 ? resolveExpression(argGroups.get(1)) : (list.isEmpty() ? null : list.get(0));
                    int startIndex = argGroups.size() > 1 ? 0 : 1;

                    Scope gocGop = scope();
                    for (int i = startIndex; i < list.size(); i++) {
                        try {
                            enterFunctionScope();
                            scope().put(def.params.get(0), accumulator);
                            scope().put(def.params.get(1), list.get(i));
                            runAll(def.body);
                        } catch (ReturnException bo) {
                        } finally { datScope(gocGop); }
                        accumulator = returnValue;
                        returnValue = "";
                    }
                    return accumulator;
                }
            }
        }
        if (target instanceof HqlObject) {
            HqlObject hqlObj = (HqlObject) target;
            FunctionDef methodDef = findHqlMethod(hqlObj.hqlClass, methodName);
            if (methodDef != null) {
                List<Object> giaTriThamSo = new ArrayList<>();
                for (int i = 0; i < methodDef.params.size(); i++) giaTriThamSo.add(resolveExpression(argGroups.get(i)));
                Scope gocPT = scope();
                try {
                    enterFunctionScope();
                    scope().put("this", hqlObj);
                    for (int i = 0; i < methodDef.params.size(); i++) {
                        scope().put(methodDef.params.get(i), giaTriThamSo.get(i));
                    }
                    runAll(methodDef.body);
                } catch (ReturnException bo) {
                } finally {
                    datScope(gocPT);
                }
                Object result = returnValue;
                returnValue = "";
                return result;
            }
            throw new RuntimeException("Lỗi: Không tìm thấy phương thức.");
        }
        throw new RuntimeException("Lỗi: Kiểu đối tượng không hỗ trợ phương thức.");
    }

    private FunctionDef findHqlMethod(HqlClass clazz, String methodName) {
        if (clazz.methods.containsKey(methodName)) return clazz.methods.get(methodName);
        if (clazz.superclassName != null && hqlClasses.containsKey(clazz.superclassName)) {
            return findHqlMethod(hqlClasses.get(clazz.superclassName), methodName);
        }
        return null;
    }

    private boolean isBuiltin(String name) {
        return Arrays.asList ("print", "sqrt", "pow", "round", "ceil", "floor", "abs", "max", "min", "sin", "cos", "tan", "random",
                "cbrt", "trunc", "toRadians", "hypot", "log", "log10", "log2", "factorial", "gcd", "lcm", "comb", "clamp",
                "isqrt", "fsum", "isclose", "isnan", "isinf", "perm", "degrees", "exp",
                "read", "write", "readA", "writeA", "input", "create", "exists", "delete", "copy", "move",
                "mean", "median", "variance", "stddev", "correlation", "linearRegression",
                "matrixMultiply", "matrixTranspose", "matrixDeterminant", "matrixInverse","PI", "E", "TAU", "INF", "NAN",
                "unitConvert", "listUnits", "celsiusToFahrenheit", "fahrenheitToCelsius", "unitCompare",
                "undefined", "finally", "map", "filter", "reduce",
                // --- THÊM LOẠT HÀM HỆ THỐNG MỚI VÀO ĐÂY ---
                "fileSize", "getName", "extension", "isDir", "lastModified", "list", "listFull", "find",
                "freeSpace", "totalSpace", "homeDir", "workingDir", "zip", "unzip",
                // Thêm đoạn này vào bên trong Arrays.asList(...) của isBuiltin
                "rotateSprite", "scaleSprite", "stopSound", "setVolume", "setCamera", "getTextWidth",
                "fetchOne", "createServer", "table", "setInterval", "progressBar",
                // --- THÊM LOẠT HÀM MẠNG VÀO ĐÂY ---
                // --- WEBSOCKET (máy chủ đẩy dữ liệu xuống trang) ---
                "wsStart", "wsGuiTatCa", "wsSoKetNoi", "wsStop",
                "httpGet", "httpPost", "httpPostFile", "download", "isOnline", "addHeader", "setCookie",
                "setTimeout", "setProxy", "ignoreSSL", "getStatusCode", "getContentType",
                "getResponseHeader", "getIP", "getHost", "ping", "checkPort",
                // --- BỔ SUNG HÀM HQL.SYS VÀO ĐÂY ---
                "cpuModel", "cpuCores", "cpuThreads", "cpuSpeed", "cpuVendor", "machineModel",
                "gpuModel", "gpuMemory", "gpuDriver", "resolution",
                "totalRam", "freeRam", "usedRam", "ramSpeed",
                "mainboard", "biosVersion", "serialNumber",
                "osName", "osArch", "userName", "uptime",
                "exec", "execRead", "exit", "env", "gc",
                "diskName", "diskStatus", "diskTemp", "diskWear",
                "sort", "reverse", "shuffle", "unique", "count",
                "md5", "sha256", "base64Encode", "base64Decode", "uuid",
                "toJson", "fromJson", "jsonEscape", "sleep", "range",
                "type", "str", "num", "bool", "call", "len", "size",
                // --- BỔ SUNG HÀM HQL.UI VÀO ĐÂY ---
                "alert", "confirm", "prompt", "chooseFile", "chooseColor",
                "window", "setLayout", "show", "hide", "close",
                "button", "label", "textField", "textArea", "image", "setStyle", "getText",
                "checkbox", "radioButton", "comboBox", "onHover", "onKeyPress", "trayInit",
                "encrypt", "decrypt", "generateKeys", "sign", "verify", "secureRandom", "pbkdf2",
                // --- BỔ SUNG HÀM HQL.DATABASE VÀO ĐÂY ---
                "connect", "closeDB", "isOpen", "query", "querySafe",
                "execute", "executeSafe", "identity", "procedure", "beginTransaction",
                "commit", "rollback", "tables", "columns", "exportCSV",
                "dbError", "queryValue",
                // --- BỔ SUNG HÀM HQL.WEB (HQLSP + SERVLET) VÀO ĐÂY ---
                "printWeb", "webStart", "webStop", "webWait", "webRoot", "webRoute", "webServlet", "webExportTomcat", "webContext",
                "param", "params", "webMethod", "webPath", "webQuery", "webBody", "webIP", "reqHeader",
                "webStatus", "webType", "resHeader", "webRedirect", "webInclude", "webRender", "webJson",
                "webHienLoiChiTiet", "webBaoLoi",
                "webJsonText", "webAnh", "webImage", "webNhiPhan", "webBinary",
                "sessionSet", "sessionGet", "sessionDel", "sessionId", "sessionKill", "cookieSet", "cookieGet",
                "htmlEscape", "urlEncode", "urlDecode", "webMime",
                "now", "today", "currentTime", "format", "parse","addMinutes", "addDays", "addMonths",
                "addYears", "daysBetween", "getYear", "getMonth", "getDay", "getWeekday",
                "age", "isWeekend", "isPast", "countdown",
                // --- BỔ SUNG HÀM HQL.REGEX VÀO ĐÂY ---
                "matches", "containsRegex", "findRegex", "findAll", "replaceRegex",
                "replaceFirst", "splitRegex", "group", "groups", "compile", "quote",
                // --- BỔ SUNG HÀM HQL.GAME VÀO ĐÂY ---
                "gameWindow", "setTitle", "closeGame",
                "clear", "drawRect", "fillRect", "drawCircle", "fillCircle", "drawLine", "drawText", "drawImage",
                "sprite", "moveTo", "moveBy", "collide", "removeSprite", "spritePos",
                "isKeyDown", "isKeyPressed", "pressKey", "releaseKey", "mouseX", "mouseY", "isMouseDown",
                "gameLoop", "playSound", "randomInt", "distance", "frameCount",
                // --- GAME ENGINE V2: VẼ NÂNG CAO ---
                "drawImageEx", "imageReady", "gameWindowFull", "screenW", "screenH", "isMousePressed",
                // --- HQL.MANG (mạng nhiều người chơi) ---
                "mangMoServer", "mangNhan", "mangGui", "mangPhat", "mangDanhSachKhach", "mangSoKhach",
                "mangDuoiKhach", "mangDongServer", "mangKetNoi", "mangGuiServer", "mangNhanServer",
                "mangDaNoi", "mangNgatKetNoi", "mangIpMay",
                "mapNap", "mapO", "mapOTai", "mapSoCot", "mapSoHang", "mapKichThuocO", "mapDatO",
                "mapThayDuoc", "banTia", "themKhoi", "xoaKhoiHet", "trongKhoi", "veSuongMu", "veVungNhin",
                "veBanDo", "veBanDoNho",
                // --- 2.5D nhin cheo + chuot/ban phim nang cao ---
                "isoCaiDat", "isoX", "isoY", "isoManHinhX", "isoManHinhY", "isoNguocVeTheGioi",
                "isoVeBanDo", "isoVeAnh", "isoVeAnhNen", "isoVeElip", "isoVeVienElip", "isoVeDuong", "isoVeSuongMu",
                "anConTro", "layChuVuaGo", "xoaChuVuaGo",
                "fillOval", "drawOval", "fillRoundRect", "drawRoundRect", "fillArc", "drawArc",
                "fillTriangle", "fillPolygon", "drawPolygon", "gradientRect",
                "drawTextCenter", "textWidth", "setFont", "setAlpha", "setStroke",
                // --- GAME ENGINE V2: NHỊP KHUNG HÌNH, HIỆU ỨNG, ÂM THANH, TOÁN ---
                "setFPS", "getFPS", "deltaTime", "camX", "camY", "beginUI", "endUI",
                "screenShake", "spawnParticle", "burst", "floatText", "clearEffects", "saveScreenshot",
                "tone", "playMusic", "stopMusic", "setMusicVolume", "playSfx",
                "randomFloat", "lerp", "angleTo",
                // --- BỔ SUNG HÀM HQL.PC VÀO ĐÂY ---
                "pos", "scroll", "press", "release", "combo", "findPixel", "getClip", "setClip",
                // Bổ sung vào bên trong Arrays.asList(...)
                "logInfo", "logWarning", "logError", "logDebug", "setLogLevel", "setLogFile", "logReset",
                // --- BỔ SUNG HÀM HQL.IMAGE VÀO ĐÂY ---
                "loadImage", "saveImage", "resizeImage", "cropImage", "rotateImage", "flipImage",
                "grayscaleImage", "blurImage", "invertImage", "getPixelColor", "setPixelColor",
                "imageWidth", "imageHeight", "imageToSvg", "svgToImage",
                // Nhóm ẢNH <-> CHUỖI BASE64 (cất ảnh thẳng vào cơ sở dữ liệu)
                "anhTuFile", "imageFileToBase64", "anhRaFile", "imageBase64ToFile",
                "anhLamSach", "imageBase64Clean", "anhKieu", "imageBase64Mime",
                "anhDataUri", "imageBase64Uri", "anhDungLuong", "imageBase64Size",
                "anhHopLe", "imageBase64Valid", "anhRong", "imageBase64Width",
                "anhCao", "imageBase64Height", "anhThongTin", "imageBase64Info",
                "anhThuNho", "imageBase64Resize", "anhCatVuong", "imageBase64Square",
                // --- BỔ SUNG HÀM HQL.CHART VÀO ĐÂY ---
                "drawBarChart", "drawLineChart", "drawPieChart", "drawScatterChart",
                "drawHistogram", "setChartSize", "setChartColor", "saveChart", "chartTitle",
                // --- BỔ SUNG HÀM HQL.CONFIG VÀO ĐÂY ---
                "loadConfig", "getConfig", "setConfig", "saveConfig", "listConfigKeys",
                "hasConfigKey", "removeConfigKey", "getSectionConfig", "setSectionConfig",
                "listSections", "listSectionKeys", "removeSection",
                // --- BỔ SUNG HÀM HQL.CSV VÀO ĐÂY ---
                "readCsvFile", "writeCsvFile", "readCsvRecords", "writeCsvRecords",
                "readDelimitedFile", "writeDelimitedFile", "csvRowCount", "csvColumnCount",
                "getCsvCell", "setCsvCell", "getCsvField", "setCsvField", "addCsvRow",
                "addCsvRecord", "removeCsvRow", "removeCsvRecord", "sortCsvByColumn",
                "sortCsvRecordsByField", "filterCsvByColumn", "filterCsvRecordsByField",
                "csvRecordsToJson", "jsonToCsvRecords",
                // --- THƯ VIỆN XML ---
                "xmlReadFile", "xmlWriteFile", "xmlParseString", "xmlToString", "xmlCreateRoot",
                "xmlXpathQuery", "xmlXpathAttr", "xmlFindNodes", "xmlGetNode", "xmlGetAttr",
                "xmlToJson", "jsonToXml", "xmlToCsv", "csvToXml", "xmlSetNode", "xmlSetAttr",
                "xmlAddChild", "xmlRemoveNode", "xmlRemoveAttr", "xmlChildrenCount", "xmlHasNode",
                // --- THƯ VIỆN BINARY ---
                "binaryRead", "binaryWrite", "binaryReadByte", "binaryWriteByte", "binaryFileSize",
                "binaryPack", "binaryUnpack", "binaryPackToFile", "binaryUnpackFromFile",
                "binaryHexDump", "binaryHexToBytes", "binaryBytesToHex", "binaryXor",
                "binaryCRC32", "binaryMD5", "binarySHA256",
                "binaryReadBytes", "binaryWriteBytes", "binaryCalcSize", "binarySlice",
                "binaryConcat", "binaryRepeat", "binaryToBase64", "binaryFromBase64",
                "binaryReverse", "binaryCompare",
                // --- THƯ VIỆN COLOR ---
                "colorRgbToHex", "colorHexToRgb", "colorRgbToHsv", "colorHsvToRgb", "colorRgbToHsl", "colorHslToRgb", "colorRgbToCmyk", "colorCmykToRgb",
                "colorBlend", "colorMultiBlend", "colorGradient", "colorMultiGradient", "colorRandomGradient",
                "colorLighten", "colorDarken", "colorSaturate", "colorDesaturate", "colorAdjustHue",
                "colorLuminance", "colorContrastRatio", "colorIsLight", "colorIsDark", "colorDistance", "colorSimilarity", "colorToGreyscale",
                "colorRandomHex", "colorRandomRgb", "colorRandomPalette", "colorNameToRgb", "colorRgbToName", "colorWithOpacity", "colorExtractPalette",
                // --- BỔ SUNG HÀM HQL.SOCKET VÀO ĐÂY ---
                "socketTcpConnect", "socketTcpSend", "socketTcpReceive", "socketTcpClose", "socketTcpStatus",
                "socketTcpListen", "socketTcpAccept", "socketTcpSendTo", "socketTcpBroadcast", "socketTcpDisconnectClient",
                "socketUdpBind", "socketUdpSend", "socketUdpReceive", "socketUdpClose", "socketUdpBroadcast",
                "socketUdpMulticastJoin", "socketUdpMulticastLeave", "socketSslTcpConnect", "socketSslTcpListen",
                "socketSslTcpAccept", "socketSslTcpSend", "socketSetTimeout", "socketSetTcpNoDelay", "socketSetKeepAlive",
                "socketResolveHost", "socketGetLocalIp", "socketGetRemoteIp", "socketGetRemotePort", "socketListInterfaces",
                "socketGetHostName", "socketRawPing", "socketRawSend", "socketWebSocketConnect", "socketWebSocketSend", "socketWebSocketReceive",
                "socketSendFile", "socketReceiveFile", "socketEnableEncryption", "socketDisableEncryption",
                "socketSetNonBlocking", "socketWaitData", "socketSetOption", // Phẩy nối tiếp danh sách socket
                // --- BỔ SUNG HÀM HQL.SCHEDULE VÀO ĐÂY ---
                "taskRunOnce", "taskRunAt", "taskRepeat", "taskRepeatFixedDelay", "taskCancel", "taskCancelAll", "taskExists",
                "taskCron", "taskCronAt", "taskCronUntil", "cronValidate", "cronNextRun",
                "taskPause", "taskResume", "taskFireNow", "taskReschedule", "taskSetMaxRuns",
                "taskChain", "taskDepend", "taskOnSuccess", "taskOnFailure",
                "taskWeekly", "taskMonthly", "taskLastDayOfMonth", "taskHoliday",
                "taskSaveState", "taskLoadState", "taskEnablePersistence", "taskGetRuns", "taskResetStats",
                "taskRunParallel", "taskAcquireLock", "taskReleaseLock", "taskWaitFor", "taskBroadcastEvent",
                "taskList", "taskDetails", "taskCount", "taskActiveCount", "taskPauseAll", "taskResumeAll", "taskShutdown",
                // --- BỔ SUNG HÀM HQL.MAIL (GỬI EMAIL TỰ ĐỘNG) VÀO ĐÂY ---
                "mailConfig", "mailSenderName", "mailIsConfigured", "mailError",
                "mailAttach", "mailClearAttach", "mailOtp", "mailPassword", "mailIsValid",
                "mailSend", "mailSendText", "mailSendAsync", "mailSendAsyncText", "mailConfigEnv", "mailWarmUp",
                // --- BỔ SUNG HQL.ENV (TỆP CẤU HÌNH .henv) VÀ BỂ KẾT NỐI DATABASE ---
                "envLoad", "envGet", "envHas", "envSet", "envInt", "envBool", "envAll", "envFiles", "envRequire",
                "connectEnv", "dbPoolSize", "dbPoolStats", "executeBatch",
                // --- THU VIEN hql.app: DUNG APP MAY TINH TU MOT TRANG WEB HQL ---
                //  DANH SACH NAY LA CUA KHAU: viet xong than ham trong
                //  thuVienApp() ma quen khai ten o day thi HQL bao "Khong tim
                //  thay ham 'appCuaSo'" — nghe y nhu chua viet gi, mat cong di
                //  tim trong khi ham nam san do. Da dinh dung mot lan.
                "appCuaSo", "appDangMo", "appCho", "appDong", "appCoTrinhDuyet",
                "tienTrinhChay", "tienTrinhSong", "tienTrinhTat",
                "choCong", "congDangMo", "sysProp", "appThuMucGoc", "appKhay").contains(name);
    }


    /** Cờ báo "thư viện này không xử lý hàm đó" (dùng khi tách bớt executeBuiltin cho nhẹ). */
    private static final Object CHUA_XU_LY = new Object();

    // ======================================================================
    //  THƯ VIỆN hql.app — DỰNG APP MÁY TÍNH TỪ MỘT TRANG WEB HQL
    //
    //  VÌ SAO CÓ THƯ VIỆN NÀY
    //  ----------------------
    //  Một dự án HQL đã có sẵn máy chủ web (hql.web) thì gần như đã có sẵn cả
    //  giao diện app: chỉ thiếu một cái cửa sổ để bày trang ấy ra. Thư viện
    //  này đúng là cái cửa sổ đó, cộng mấy việc lặt vặt quanh nó — chạy tiến
    //  trình con, chờ cổng, tắt tiến trình.
    //
    //  Nhờ vậy app viết TRỌN BẰNG HQL, và quan trọng hơn: giao diện app CHÍNH
    //  LÀ trang web, nên sửa giao diện web là app đổi theo ngay, không phải
    //  dựng lại app.
    //
    //  VÌ SAO CỬA SỔ LÀ MỘT TRÌNH DUYỆT CHỨ KHÔNG PHẢI SWING
    //  ------------------------------------------------------
    //  Swing không dựng được HTML/CSS hiện đại — JEditorPane dừng ở HTML 3.2,
    //  không biết flexbox, grid, biến CSS. Dựng lại giao diện bằng button và
    //  label thì app hiện ra KHÁC web, tức là mất đúng cái lợi lớn nhất.
    //  Nên cửa sổ ở đây là khung Chromium sẵn có trên máy (Edge/Chrome) chạy
    //  chế độ --app: không thanh địa chỉ, không tab, có mục riêng trên thanh
    //  tác vụ. Cùng cách Discord, Spotify, VS Code đang làm.
    //
    //  CÁC HÀM
    //  -------
    //    appCuaSo(url, tieuDe, rong, cao)  mở cửa sổ app, trả về MÃ CỬA SỔ
    //    appDangMo(ma)                     cửa sổ còn mở không
    //    appCho(ma)                        chặn tới khi người dùng đóng cửa sổ
    //    appDong(ma)                       đóng cửa sổ
    //    appCoTrinhDuyet()                 máy có Edge/Chrome không
    //
    //    tienTrinhChay(mang, thuMuc, fileLog)  chạy chương trình ngoài
    //    tienTrinhSong(ma) / tienTrinhTat(ma)  hỏi / tắt tiến trình đó
    //    choCong(cong, giay)                   chờ một cổng TCP mở
    //    congDangMo(cong)                      cổng có ai giữ không
    //
    //  VÌ SAO KHÔNG DÙNG exec() CÓ SẴN: exec() cắt chuỗi lệnh theo DẤU CÁCH,
    //  nên đường dẫn kiểu "C:\Program Files (x86)\...\msedge.exe" bị xé làm ba
    //  mảnh và chạy hỏng. Đường dẫn trên Windows đầy dấu cách, nên ở đây nhận
    //  MỘT MẢNG tham số, không đụng gì tới việc cắt chuỗi.
    // ======================================================================

    /** Cửa sổ app và tiến trình con đang mở, đánh số từ 1. */
    private final java.util.Map<Integer, Process> tienTrinhApp = new java.util.HashMap<>();
    private int maTienTrinhKe = 0;

    private Object thuVienApp(String name, List<Object> args) {
        try {
            switch (name) {

                // ---------- CỬA SỔ APP ----------
                case "appCoTrinhDuyet":
                    return timTrinhDuyetApp() != null;

                case "appCuaSo": {
                    String url = stringify(args.get(0));
                    String tieuDe = args.size() > 1 ? stringify(args.get(1)) : "App";
                    int rong = args.size() > 2 ? (int) toDouble(args.get(2)) : 1400;
                    int cao  = args.size() > 3 ? (int) toDouble(args.get(3)) : 900;
                    // Tham số thứ 5: đường dẫn ảnh icon cửa sổ. Bỏ trống thì
                    // cửa sổ đeo icon mặc định của CEF — CON CHIM CÁNH CỤT,
                    // chẳng liên quan gì tới app của mình.
                    String anh = args.size() > 4 ? stringify(args.get(4)) : "";

                    maTienTrinhKe++;
                    int ma = maTienTrinhKe;
                    cuaSoApp.put(ma, moCuaSoChromium(url, tieuDe, rong, cao, anh));
                    return (double) ma;
                }

                case "appDangMo": {
                    CuaSoApp cs = cuaSoApp.get((int) toDouble(args.get(0)));
                    if (cs == null) { return false; }
                    if (cs.tienTrinhLui != null) { return cs.tienTrinhLui.isAlive(); }
                    return cs.daDong.getCount() > 0;
                }

                case "appCho": {
                    CuaSoApp cs = cuaSoApp.get((int) toDouble(args.get(0)));
                    if (cs == null) { return 0.0; }
                    if (cs.tienTrinhLui != null) { return (double) cs.tienTrinhLui.waitFor(); }
                    cs.daDong.await();
                    return 0.0;
                }

                case "appDong": {
                    CuaSoApp cs = cuaSoApp.get((int) toDouble(args.get(0)));
                    if (cs == null) { return false; }
                    if (cs.tienTrinhLui != null) {
                        if (!cs.tienTrinhLui.isAlive()) { return false; }
                        cs.tienTrinhLui.destroy();
                        return true;
                    }
                    if (cs.khung != null) {
                        javax.swing.SwingUtilities.invokeLater(() -> cs.khung.dispose());
                    }
                    cs.daDong.countDown();
                    return true;
                }

                case "tienTrinhSong": {
                    Process tt = tienTrinhApp.get((int) toDouble(args.get(0)));
                    return tt != null && tt.isAlive();
                }

                case "tienTrinhTat": {
                    Process tt = tienTrinhApp.get((int) toDouble(args.get(0)));
                    if (tt == null || !tt.isAlive()) { return false; }
                    tt.destroy();
                    // Xin tử tế trước, không chết thì mới ép. Ép ngay thì tiến
                    // trình không kịp dọn — máy chủ web không kịp nhả cổng,
                    // đúng cái đã đẻ ra lỗi "Address already in use" ở bản
                    // chạy khay (mục 33 work.txt).
                    if (!tt.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { tt.destroyForcibly(); }
                    return true;
                }

                // ---------- TIẾN TRÌNH CON ----------
                case "tienTrinhChay": {
                    List<String> lenh = new ArrayList<>();
                    Object dau = args.get(0);
                    if (dau instanceof List) {
                        for (Object o : (List<?>) dau) { lenh.add(stringify(o)); }
                    } else {
                        // Nhận cả chuỗi cho tiện, nhưng CHỈ nên dùng khi đường
                        // dẫn không có dấu cách.
                        lenh.add(stringify(dau));
                    }
                    if (lenh.isEmpty()) { throw new RuntimeException("tienTrinhChay: danh sach lenh rong."); }

                    ProcessBuilder pb = new ProcessBuilder(lenh);
                    if (args.size() > 1 && !stringify(args.get(1)).isEmpty()) {
                        pb.directory(new java.io.File(stringify(args.get(1))));
                    }
                    pb.redirectErrorStream(true);
                    if (args.size() > 2 && !stringify(args.get(2)).isEmpty()) {
                        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                                new java.io.File(stringify(args.get(2)))));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    }

                    Process tt = pb.start();
                    maTienTrinhKe++;
                    tienTrinhApp.put(maTienTrinhKe, tt);
                    return (double) maTienTrinhKe;
                }

                // ---------- HỎI MÔI TRƯỜNG ----------
                //  Cần cho việc tìm bộ Java đang chạy: bản đóng gói mang theo
                //  Java riêng trong app, java.home trỏ đúng vào đó nên app
                //  không phụ thuộc Java cài sẵn trên máy người dùng.
                //  Trả về CHUỖI RỖNG khi không có, không trả null — để bên HQL
                //  so sánh với "" cho gọn, khỏi phải phân biệt null với rỗng.
                case "sysProp": {
                    String v = System.getProperty(stringify(args.get(0)));
                    return v == null ? "" : v;
                }


                // ======================================================================
                //  KHAY HỆ THỐNG CHO CỬA SỔ APP
                //
                //  appKhay(ma, anh, nhanMo, nhanThoat)
                //
                //  Gọi hàm này rồi thì BẤM DẤU X KHÔNG CÒN LÀ THOÁT — cửa sổ chỉ ẩn đi,
                //  app nằm lại ở khay. Muốn mở lại thì bấm đúp icon khay hoặc chọn mục
                //  "Mở ứng dụng"; muốn thoát hẳn thì chọn "Thoát".
                //
                //  VÌ SAO KHÔNG DÙNG trayInit() CÓ SẴN: hàm đó dựng sẵn ba mục Bật/Tắt/
                //  Thoát cho việc bật tắt máy chủ, và nó gọi ngược lại hàm HQL theo tên.
                //  Ở đây cần đúng hai mục, và việc phải làm là ẩn/hiện một JFrame ngay
                //  trong Java — vòng qua HQL rồi vòng lại chỉ thêm chỗ hỏng.
                //
                //  KHÔNG PHẢI MÁY NÀO CŨNG CÓ KHAY: SystemTray.isSupported() sai thì
                //  KHÔNG được im lặng bỏ qua. Im lặng thì dấu X vẫn ẩn cửa sổ như thường,
                //  mà không có icon khay nào để mở lại — app biến mất khỏi màn hình trong
                //  khi tiến trình vẫn chạy, người dùng không có đường nào chạm tới nó.
                //  Nên máy không có khay thì GIỮ NGUYÊN nết cũ: đóng là thoát.
                // ======================================================================
                case "appKhay": {
                    CuaSoApp cs = cuaSoApp.get((int) toDouble(args.get(0)));
                    if (cs == null || cs.khung == null) { return false; }

                    String anh       = args.size() > 1 ? stringify(args.get(1)) : "";
                    String nhanMo    = args.size() > 2 ? stringify(args.get(2)) : "Mo ung dung";
                    String nhanThoat = args.size() > 3 ? stringify(args.get(3)) : "Thoat";

                    if (!java.awt.SystemTray.isSupported()) {
                        System.out.println("[hql.app] May khong ho tro khay he thong - giu nguyen: dong cua so la thoat.");
                        return false;
                    }

                    java.awt.Image bt = null;
                    if (!anh.isEmpty() && new java.io.File(anh).isFile()) {
                        try { bt = javax.imageio.ImageIO.read(new java.io.File(anh)); } catch (java.io.IOException ignored) { }
                    }
                    if (bt == null) {
                        // TrayIcon KHÔNG nhận null: ném NullPointerException ngay. Thà vẽ
                        // một chấm vuông còn hơn làm chết app vì thiếu cái icon.
                        bt = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        java.awt.Graphics g = bt.getGraphics();
                        g.setColor(new java.awt.Color(255, 70, 85));
                        g.fillRect(0, 0, 16, 16);
                        g.dispose();
                    }

                    final java.awt.Image icon = bt;
                    final javax.swing.JFrame khung = cs.khung;
                    final CuaSoApp csf = cs;

                    java.awt.PopupMenu menu = new java.awt.PopupMenu();
                    java.awt.MenuItem mMo = new java.awt.MenuItem(nhanMo);
                    java.awt.MenuItem mThoat = new java.awt.MenuItem(nhanThoat);
                    menu.add(mMo);
                    menu.addSeparator();
                    menu.add(mThoat);

                    java.awt.TrayIcon ti = new java.awt.TrayIcon(icon, khung.getTitle(), menu);
                    ti.setImageAutoSize(true);

                    java.awt.event.ActionListener mo = (ev) -> javax.swing.SwingUtilities.invokeLater(() -> {
                        khung.setVisible(true);
                        // Bị thu nhỏ trước đó thì phải bỏ cờ ICONIFIED, không thì
                        // setVisible(true) chỉ trả nó về thanh tác vụ chứ không mở ra.
                        khung.setState(javax.swing.JFrame.NORMAL);
                        khung.toFront();
                        khung.requestFocus();
                    });
                    mMo.addActionListener(mo);
                    ti.addActionListener(mo);   // bấm đúp icon khay

                    mThoat.addActionListener((ev) -> {
                        java.awt.SystemTray.getSystemTray().remove(ti);
                        javax.swing.SwingUtilities.invokeLater(khung::dispose);
                        csf.daDong.countDown();   // appCho() nhả ra, app.hql chạy tiếp phần dọn dẹp
                    });

                    try {
                        java.awt.SystemTray.getSystemTray().add(ti);
                    } catch (java.awt.AWTException ex) {
                        System.out.println("[hql.app] Khong them duoc icon khay: " + ex.getMessage());
                        return false;
                    }

                    // Dấu X giờ chỉ ẩn cửa sổ. Gỡ hết listener cũ trước, không thì cái
                    // listener dựng lúc mở cửa sổ vẫn countDown và app vẫn thoát như cũ.
                    for (java.awt.event.WindowListener wl : khung.getWindowListeners()) {
                        khung.removeWindowListener(wl);
                    }
                    khung.addWindowListener(new java.awt.event.WindowAdapter() {
                        @Override
                        public void windowClosing(java.awt.event.WindowEvent e) {
                            khung.setVisible(false);
                        }
                    });
                    csf.oKhay = true;
                    return true;
                }

                // ---------- THƯ MỤC GỐC CỦA APP ----------
                //
                //  Chạy từ mã nguồn thì thư mục hiện hành đã đúng. Chạy từ bản
                //  đóng gói (jpackage) thì KHÔNG: thư mục hiện hành là chỗ
                //  người dùng bấm chuột, chẳng liên quan gì tới chỗ đặt app.
                //  Nên dò ngược lên từ chính vị trí file .exe cho tới khi thấy
                //  cái mốc mình đưa vào (ví dụ "VNChecker/main.hql").
                //
                //  Trả về chuỗi rỗng nếu không tìm ra, để bên HQL tự quyết
                //  định báo lỗi thế nào.
                case "appThuMucGoc": {
                    String moc = stringify(args.get(0));
                    List<java.io.File> ung = new ArrayList<>();

                    String duAn = System.getProperty("jpackage.app-path");
                    if (duAn != null) {
                        java.io.File f = new java.io.File(duAn).getAbsoluteFile().getParentFile();
                        while (f != null && ung.size() < 12) { ung.add(f); f = f.getParentFile(); }
                    }
                    java.io.File f2 = new java.io.File("").getAbsoluteFile();
                    while (f2 != null && ung.size() < 24) { ung.add(f2); f2 = f2.getParentFile(); }

                    for (java.io.File g : ung) {
                        if (new java.io.File(g, moc).exists()) { return g.getAbsolutePath(); }
                    }
                    return "";
                }

                // ---------- CỔNG MẠNG ----------
                case "congDangMo": {
                    return congCoAiGiu((int) toDouble(args.get(0)));
                }

                case "choCong": {
                    int cong = (int) toDouble(args.get(0));
                    double giay = args.size() > 1 ? toDouble(args.get(1)) : 30;
                    long het = System.currentTimeMillis() + (long) (giay * 1000);
                    while (System.currentTimeMillis() < het) {
                        if (congCoAiGiu(cong)) { return true; }
                        Thread.sleep(200);
                    }
                    return false;
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Loi Thu vien (hql.app): " + e.getMessage(), e);
        }
        return CHUA_XU_LY;
    }


    // ======================================================================
    //  CỬA SỔ CHROMIUM NHÚNG (JCEF) — RUỘT CỦA appCuaSo()
    //
    //  VÌ SAO NHÚNG CHỨ KHÔNG MƯỢN EDGE
    //  ---------------------------------
    //  Bản đầu của thư viện này gọi msedge.exe với cờ --app. Chạy đúng, giao
    //  diện khớp web, nhưng app KHÔNG tự đứng được: Task Manager hiện một
    //  dòng msedge, và app phụ thuộc vào việc máy người dùng có Edge.
    //
    //  Bản này gói hẳn Chromium vào trong (JCEF). Một tiến trình duy nhất
    //  mang tên app, không dính gì tới trình duyệt của người dùng. Cùng cách
    //  Discord, Spotify, VS Code, Figma làm.
    //
    //  CHROMIUM TẢI VỀ MỘT LẦN, RỒI NẰM LẠI TRONG THƯ MỤC APP
    //  -------------------------------------------------------
    //  setInstallDir trỏ vào thư mục của app chứ không để jcefmaven tự chọn
    //  chỗ trong hồ sơ người dùng. Hai lý do: bản đóng gói mang Chromium theo
    //  mình nên chạy được ngay không cần mạng; và gỡ app là xoá một thư mục,
    //  không để lại rác vài trăm MB.
    //
    //  KHÔNG DÙNG ĐƯỢC THÌ LÙI VỀ EDGE
    //  --------------------------------
    //  Lần chạy đầu tiên phải tải ~150 MB. Máy không có mạng, hoặc bản JCEF
    //  không khớp hệ điều hành, thì thà mở bằng Edge còn hơn để người dùng
    //  ngồi nhìn một app không mở nổi. Có lùi thì in ra một dòng nói rõ, đừng
    //  âm thầm — không thì lúc thấy msedge trong Task Manager lại tưởng app
    //  vẫn đang mượn trình duyệt.
    // ======================================================================

    /** Một cửa sổ app đang mở: khung cửa sổ + chốt chờ lúc đóng. */
    private static class CuaSoApp {
        javax.swing.JFrame khung;
        java.util.concurrent.CountDownLatch daDong = new java.util.concurrent.CountDownLatch(1);
        Process tienTrinhLui;   // khác null nghĩa là đang lùi về Edge
        boolean oKhay;          // đã thu về khay: bấm X chỉ ẩn, không thoát
    }

    private final java.util.Map<Integer, CuaSoApp> cuaSoApp = new java.util.HashMap<>();

    /** CefApp chỉ được dựng MỘT LẦN cho cả tiến trình — dựng lần hai là nó ném lỗi. */
    private static Object cefAppChung = null;

    private CuaSoApp moCuaSoChromium(String url, String tieuDe, int rong, int cao, String anh) throws Exception {
        CuaSoApp cs = new CuaSoApp();
        try {
            java.io.File nha = new java.io.File("hql_app_cef");
            nha.mkdirs();
            java.io.File phien = new java.io.File("hql_app_cef/phien_"
                    + tieuDe.replaceAll("[^a-zA-Z0-9]", "_"));
            phien.mkdirs();

            if (cefAppChung == null) {
                me.friwi.jcefmaven.CefAppBuilder xay = new me.friwi.jcefmaven.CefAppBuilder();
                xay.setInstallDir(nha);
                xay.getCefSettings().windowless_rendering_enabled = false;
                // Hồ sơ riêng: cookie đăng nhập của app KHÔNG trộn với phiên
                // duyệt web cá nhân, và ngược lại.
                xay.getCefSettings().cache_path = phien.getAbsolutePath();
                cefAppChung = xay.build();
            }
            org.cef.CefApp cefApp = (org.cef.CefApp) cefAppChung;
            org.cef.CefClient client = cefApp.createClient();
            org.cef.browser.CefBrowser trinhDuyet = client.createBrowser(url, false, false);

            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.JFrame khung = new javax.swing.JFrame(tieuDe);
                khung.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
                khung.getContentPane().add(trinhDuyet.getUIComponent(), java.awt.BorderLayout.CENTER);
                khung.setSize(new java.awt.Dimension(rong, cao));
                khung.setMinimumSize(new java.awt.Dimension(900, 600));
                khung.setLocationRelativeTo(null);

                // ICON CỬA SỔ. Không đặt thì Windows bày icon mặc định của
                // CEF (con chim cánh cụt) trên thanh tác vụ và góc cửa sổ —
                // trông không ra app của mình chút nào.
                // PHẢI LÀ PNG/GIF/JPG: Toolkit của Java KHÔNG đọc được .ico
                // hay .svg, mà nó cũng không báo lỗi — chỉ lặng lẽ trả về một
                // ảnh rỗng và cửa sổ vẫn đeo icon mặc định.
                //
                //  DÙNG ImageIO CHỨ KHÔNG DÙNG Toolkit.getImage(): getImage()
                //  nạp ảnh BẤT ĐỒNG BỘ và trả về ngay một ảnh còn rỗng, nên
                //  setIconImage() rất hay nhận phải ảnh chưa có gì — icon lúc
                //  hiện lúc không, tuỳ máy nhanh chậm. ImageIO.read() nạp
                //  xong mới trả về, và ném lỗi nếu file hỏng.
                if (!anh.isEmpty()) {
                    java.io.File fAnh = new java.io.File(anh);
                    if (fAnh.isFile()) {
                        try {
                            java.awt.image.BufferedImage bt = javax.imageio.ImageIO.read(fAnh);
                            if (bt != null) { khung.setIconImage(bt); }
                            else { System.out.println("[hql.app] Khong doc duoc anh icon: " + anh
                                    + " (ImageIO khong hieu dinh dang - phai la PNG/GIF/JPG)."); }
                        } catch (java.io.IOException ex) {
                            System.out.println("[hql.app] Loi doc anh icon " + anh + ": " + ex.getMessage());
                        }
                    } else {
                        System.out.println("[hql.app] Khong thay anh icon: " + anh);
                    }
                }

                khung.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        khung.dispose();
                        cs.daDong.countDown();
                    }
                });
                khung.setVisible(true);
                cs.khung = khung;
            });
            return cs;

        } catch (Throwable e) {
            System.out.println("[hql.app] Khong dung duoc Chromium nhung (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ").");
            System.out.println("[hql.app] Lui ve khung Edge/Chrome co san tren may.");
            cs.tienTrinhLui = moCuaSoMuonTrinhDuyet(url, tieuDe, rong, cao);
            return cs;
        }
    }

    /** Đường lùi: mượn Edge/Chrome của máy làm khung (chế độ --app). */
    private Process moCuaSoMuonTrinhDuyet(String url, String tieuDe, int rong, int cao) throws Exception {
        String trinhDuyet = timTrinhDuyetApp();
        if (trinhDuyet == null) {
            throw new RuntimeException("Khong dung duoc Chromium nhung, ma may cung khong co "
                    + "Microsoft Edge hay Google Chrome de lam khung cua so.");
        }

        // Thu muc ho so RIENG. Thieu no thi Edge nhap cua so nay vao ho so Edge
        // nguoi dung dang mo: tien trinh vua sinh ra da thoat ngay (vi ban giao
        // cho Edge cu) nen appCho() tra ve tuc thi va app tat lien.
        java.io.File hoSo = new java.io.File("hql_app_cuaso/"
                + tieuDe.replaceAll("[^a-zA-Z0-9]", "_"));
        hoSo.mkdirs();

        List<String> lenh = new ArrayList<>();
        lenh.add(trinhDuyet);
        lenh.add("--app=" + url);
        lenh.add("--user-data-dir=" + hoSo.getAbsolutePath());
        lenh.add("--window-size=" + rong + "," + cao);
        lenh.add("--no-first-run");
        lenh.add("--no-default-browser-check");
        lenh.add("--disable-features=Translate,AutofillServerCommunication");

        ProcessBuilder pb = new ProcessBuilder(lenh);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    /** Có ai đang nghe ở cổng này trên máy mình không. */
    private boolean congCoAiGiu(int cong) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", cong), 400);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Tìm Edge hoặc Chrome để làm khung cửa sổ. Ưu tiên Edge vì Windows 10/11
     * nào cũng có sẵn, không phải cài thêm gì.
     */
    private String timTrinhDuyetApp() {
        String[] ung = {
                System.getenv("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
                System.getenv("ProgramFiles")      + "\\Microsoft\\Edge\\Application\\msedge.exe",
                System.getenv("ProgramFiles")      + "\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("LOCALAPPDATA")      + "\\Google\\Chrome\\Application\\chrome.exe",
        };
        for (String d : ung) {
            if (d == null || d.startsWith("null")) { continue; }
            if (new java.io.File(d).isFile()) { return d; }
        }
        return null;
    }

    /** Thư viện hql.game (tách riêng khỏi executeBuiltin vì quá dài). */
    private Object thuVienGame(String name, List<Object> args) {
        try {
            switch (name) {
                // --- NHÓM 1: CỬA SỔ GAME ---
                case "gameWindow": {
                    taoCuaSoGame(stringify(args.get(0)), (int) toDouble(args.get(1)), (int) toDouble(args.get(2)), false);
                    return null;
                }
                case "gameWindowFull": {
                    // Cửa sổ chiếm trọn màn hình, không viền (như bấm F11)
                    Dimension mh = Toolkit.getDefaultToolkit().getScreenSize();
                    taoCuaSoGame(stringify(args.get(0)), mh.width, mh.height, true);
                    return null;
                }
                case "screenW": return (double) Toolkit.getDefaultToolkit().getScreenSize().width;
                case "screenH": return (double) Toolkit.getDefaultToolkit().getScreenSize().height;

                // ---- NHÓM BẢN ĐỒ LƯỚI & TẦM NHÌN (dùng cho game bắn súng góc nhìn từ trên xuống) ----
                case "mapNap": {
                    // mapNap(danhSachHang, kichThuocO, kyTuChanNhin, kyTuChanDan)
                    List<?> hang = (List<?>) args.get(0);
                    banDoLuoi = new char[hang.size()][];
                    for (int i = 0; i < hang.size(); i++) banDoLuoi[i] = stringify(hang.get(i)).toCharArray();
                    banDoOKichThuoc = args.size() > 1 ? (int) toDouble(args.get(1)) : 32;
                    if (args.size() > 2) banDoChanNhin = stringify(args.get(2));
                    if (args.size() > 3) banDoChanDan = stringify(args.get(3));
                    danhSachKhoi.clear();
                    return (double) banDoLuoi.length;
                }
                case "mapO": {
                    // mapO(cot, hang) -> ký tự
                    int c = (int) toDouble(args.get(0)), h = (int) toDouble(args.get(1));
                    if (banDoLuoi == null || h < 0 || h >= banDoLuoi.length || c < 0 || c >= banDoLuoi[h].length) return "#";
                    return String.valueOf(banDoLuoi[h][c]);
                }
                case "mapOTai": return String.valueOf(oTaiDiem(toDouble(args.get(0)), toDouble(args.get(1))));
                case "mapSoCot": return banDoLuoi == null ? 0.0 : (double) banDoLuoi[0].length;
                case "mapSoHang": return banDoLuoi == null ? 0.0 : (double) banDoLuoi.length;
                case "mapKichThuocO": return (double) banDoOKichThuoc;
                case "mapDatO": {
                    // mapDatO(cot, hang, kyTu) - dùng để mở/đóng cửa, phá tường...
                    int c = (int) toDouble(args.get(0)), h = (int) toDouble(args.get(1));
                    String k = stringify(args.get(2));
                    if (banDoLuoi != null && h >= 0 && h < banDoLuoi.length && c >= 0 && c < banDoLuoi[h].length && !k.isEmpty()) {
                        banDoLuoi[h][c] = k.charAt(0);
                    }
                    return null;
                }
                case "mapThayDuoc": {
                    // mapThayDuoc(x1,y1,x2,y2) -> hai điểm có nhìn thấy nhau không
                    return doTiaNhin(toDouble(args.get(0)), toDouble(args.get(1)),
                            toDouble(args.get(2)), toDouble(args.get(3))) < 0;
                }
                case "banTia": {
                    // banTia(x, y, gocDo, tamXa) -> [xTrung, yTrung, kyTuTuong, khoangCach]
                    // Dùng cho đường đạn: trả về điểm chạm tường CHẶN ĐẠN đầu tiên.
                    double bx = toDouble(args.get(0)), by = toDouble(args.get(1));
                    double goc = Math.toRadians(toDouble(args.get(2)));
                    double tam = toDouble(args.get(3));
                    double cx = Math.cos(goc), cy = Math.sin(goc);
                    double buoc = Math.max(2.0, banDoOKichThuoc / 10.0);
                    double t = 0;
                    while (t < tam) {
                        t += buoc;
                        double px = bx + cx * t, py = by + cy * t;
                        char k = oTaiDiem(px, py);
                        if (banDoChanDan.indexOf(k) >= 0) {
                            return new ArrayList<>(Arrays.asList(px, py, String.valueOf(k), t));
                        }
                    }
                    return new ArrayList<>(Arrays.asList(bx + cx * tam, by + cy * tam, " ", tam));
                }
                case "veBanDo": {
                    // veBanDo(anhSan, anhTuong, anhGo, anhThung, anhSite)
                    // Vẽ toàn bộ ô đang lọt vào khung nhìn - chạy trong Java nên nhanh gấp trăm lần
                    // so với việc lặp từng ô bằng kịch bản HQL.
                    if (gameGraphics == null || banDoLuoi == null) return null;
                    Image aSan = loadGameImage(stringify(args.get(0)));
                    Image aTuong = loadGameImage(stringify(args.get(1)));
                    Image aGo = args.size() > 2 ? loadGameImage(stringify(args.get(2))) : aTuong;
                    Image aThung = args.size() > 3 ? loadGameImage(stringify(args.get(3))) : aTuong;
                    Image aSite = args.size() > 4 ? loadGameImage(stringify(args.get(4))) : null;

                    int dx = camOffX(), dy = camOffY();
                    int cotDau = Math.max(0, dx / banDoOKichThuoc - 1);
                    int hangDau = Math.max(0, dy / banDoOKichThuoc - 1);
                    int cotCuoi = (dx + gameBuffer.getWidth()) / banDoOKichThuoc + 1;
                    int hangCuoi = (dy + gameBuffer.getHeight()) / banDoOKichThuoc + 1;

                    for (int h = hangDau; h <= hangCuoi && h < banDoLuoi.length; h++) {
                        for (int c = cotDau; c <= cotCuoi && c < banDoLuoi[h].length; c++) {
                            char k = banDoLuoi[h][c];
                            int px = c * banDoOKichThuoc - dx;
                            int py = h * banDoOKichThuoc - dy;
                            Image anh = aSan;
                            if (k == '#') anh = aTuong;
                            else if (k == 'W') anh = aGo;
                            else if (k == 'B') anh = aThung;
                            gameGraphics.drawImage(anh, px, py, banDoOKichThuoc, banDoOKichThuoc, null);
                            if (aSite != null && (k == 'A' || k == 'b' || k == 'C')) {
                                gameGraphics.drawImage(aSite, px, py, banDoOKichThuoc, banDoOKichThuoc, null);
                            }
                        }
                    }
                    return null;
                }
                // ================= NHÓM 2.5D NHÌN CHÉO (ISOMETRIC) =================
                case "isoCaiDat": {
                    // isoCaiDat(rongO, caoO) - mặc định 64 x 32 (góc chéo 45 độ, tỉ lệ 2:1)
                    isoRong = toDouble(args.get(0));
                    isoCao = toDouble(args.get(1));
                    return null;
                }
                case "isoX": return isoTruc_X(toDouble(args.get(0)), toDouble(args.get(1)));
                case "isoY": return isoTruc_Y(toDouble(args.get(0)), toDouble(args.get(1)));
                case "isoManHinhX": return (double) isoManHinhX(toDouble(args.get(0)), toDouble(args.get(1)));
                case "isoManHinhY": return (double) isoManHinhY(toDouble(args.get(0)), toDouble(args.get(1)));
                case "isoNguocVeTheGioi": {
                    // isoNguocVeTheGioi(sxManHinh, syManHinh) -> [wx, wy]
                    // Dùng để biết con trỏ chuột đang trỏ vào điểm nào trên mặt đất.
                    double sx = toDouble(args.get(0)) + camOffX();
                    double sy = toDouble(args.get(1)) + camOffY();
                    double a = sx / (isoRong / 2.0);
                    double b = sy / (isoCao / 2.0);
                    double tx = (a + b) / 2.0;
                    double ty = (b - a) / 2.0;
                    return new ArrayList<>(Arrays.asList(tx * banDoOKichThuoc, ty * banDoOKichThuoc));
                }
                case "isoVeBanDo": {
                    // isoVeBanDo(caoTuong, mauSan, mauSanToi, mauSite,
                    //            mauTuongTren, mauTuongTrai, mauTuongPhai,
                    //            mauGoTren, mauGoTrai, mauGoPhai,
                    //            mauThungTren, mauThungTrai, mauThungPhai)
                    if (gameGraphics == null || banDoLuoi == null) return null;
                    double caoTuong = toDouble(args.get(0));
                    Color mSan = parseColor(stringify(args.get(1)));
                    Color mSanToi = parseColor(stringify(args.get(2)));
                    Color mSite = parseColor(stringify(args.get(3)));
                    Color tT = parseColor(stringify(args.get(4)));
                    Color tL = parseColor(stringify(args.get(5)));
                    Color tR = parseColor(stringify(args.get(6)));
                    Color gT = parseColor(stringify(args.get(7)));
                    Color gL = parseColor(stringify(args.get(8)));
                    Color gR = parseColor(stringify(args.get(9)));
                    Color thT = parseColor(stringify(args.get(10)));
                    Color thL = parseColor(stringify(args.get(11)));
                    Color thR = parseColor(stringify(args.get(12)));

                    double nuaR = isoRong / 2.0, nuaC = isoCao / 2.0;
                    int W = gameBuffer.getWidth(), H = gameBuffer.getHeight();

                    // Vẽ từ xa tới gần (theo tổng hàng+cột) để khối che nhau đúng thứ tự
                    int tongMax = banDoLuoi.length + banDoLuoi[0].length;
                    for (int tong = 0; tong < tongMax; tong++) {
                        for (int h = 0; h <= tong && h < banDoLuoi.length; h++) {
                            int c = tong - h;
                            if (c < 0 || c >= banDoLuoi[h].length) continue;
                            double wx = c * banDoOKichThuoc, wy = h * banDoOKichThuoc;
                            int sx = isoManHinhX(wx, wy), sy = isoManHinhY(wx, wy);
                            if (sx < -isoRong * 2 || sx > W + isoRong * 2) continue;
                            if (sy < -isoCao * 6 || sy > H + isoCao * 6) continue;

                            char k = banDoLuoi[h][c];
                            if (k == '#') { veKhoiIso(wx, wy, caoTuong, tT, tL, tR); }
                            else if (k == 'W') { veKhoiIso(wx, wy, caoTuong * 0.75, gT, gL, gR); }
                            else if (k == 'B') { veKhoiIso(wx, wy, caoTuong * 0.5, thT, thL, thR); }
                            else {
                                Color m = (((c + h) % 2) == 0) ? mSan : mSanToi;
                                if (k == 'A' || k == 'b' || k == 'C') m = mSite;
                                int[] px = { sx, (int) (sx + nuaR), sx, (int) (sx - nuaR) };
                                int[] py = { sy, (int) (sy + nuaC), (int) (sy + isoCao), (int) (sy + nuaC) };
                                gameGraphics.setColor(m);
                                gameGraphics.fillPolygon(px, py, 4);
                            }
                        }
                    }
                    return null;
                }
                case "isoVeAnhNen": {
                    // isoVeAnhNen(duongDanAnhBanDo)
                    // Vẽ NGUYÊN tấm ảnh bản đồ thật, chiếu chéo 45 độ cho khớp lưới va chạm.
                    // Ảnh được coi là phủ đúng toàn bộ lưới đã nạp bằng mapNap().
                    if (gameGraphics == null || banDoLuoi == null) return null;
                    Image anh = loadGameImage(stringify(args.get(0)));
                    int rongAnh = anh.getWidth(null);
                    if (rongAnh <= 0) return null;
                    double theGioiRong = banDoLuoi[0].length * (double) banDoOKichThuoc;
                    double s = theGioiRong / rongAnh;            // 1 điểm ảnh = bao nhiêu đơn vị thế giới
                    double a = s * isoRong / (2.0 * banDoOKichThuoc);
                    double b = s * isoCao / (2.0 * banDoOKichThuoc);
                    AffineTransform tx = new AffineTransform(a, b, -a, b, -camOffX(), -camOffY());
                    Object cuNoiSuy = gameGraphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                    gameGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    gameGraphics.drawImage(anh, tx, null);
                    if (cuNoiSuy != null) gameGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, cuNoiSuy);
                    return null;
                }
                case "isoVeAnh": {
                    // isoVeAnh(duongDan, wx, wy, rong, cao, caoNhacLen)
                    // Ảnh được neo ở CHÂN nhân vật đứng tại ô (wx, wy).
                    Image img = loadGameImage(stringify(args.get(0)));
                    double wx = toDouble(args.get(1)), wy = toDouble(args.get(2));
                    double rw = toDouble(args.get(3)), rh = toDouble(args.get(4));
                    double nhac = args.size() > 5 ? toDouble(args.get(5)) : 0;
                    int sx = isoManHinhX(wx, wy);
                    int sy = isoManHinhY(wx, wy);
                    gameGraphics.drawImage(img, (int) (sx - rw / 2), (int) (sy + isoCao / 2 - rh - nhac),
                            (int) rw, (int) rh, null);
                    return null;
                }
                case "isoVeElip": {
                    // isoVeElip(wx, wy, banKinh, mau) - vòng tròn dưới chân bị bẹt theo phối cảnh
                    double wx = toDouble(args.get(0)), wy = toDouble(args.get(1));
                    double bk = toDouble(args.get(2));
                    gameGraphics.setColor(parseColor(stringify(args.get(3))));
                    double rx = bk * (isoRong / banDoOKichThuoc);
                    double ry = bk * (isoCao / banDoOKichThuoc);
                    int sx = isoManHinhX(wx, wy), sy = isoManHinhY(wx, wy);
                    gameGraphics.fillOval((int) (sx - rx), (int) (sy + isoCao / 2 - ry), (int) (rx * 2), (int) (ry * 2));
                    return null;
                }
                case "isoVeVienElip": {
                    double wx = toDouble(args.get(0)), wy = toDouble(args.get(1));
                    double bk = toDouble(args.get(2));
                    gameGraphics.setColor(parseColor(stringify(args.get(3))));
                    double rx = bk * (isoRong / banDoOKichThuoc);
                    double ry = bk * (isoCao / banDoOKichThuoc);
                    int sx = isoManHinhX(wx, wy), sy = isoManHinhY(wx, wy);
                    gameGraphics.drawOval((int) (sx - rx), (int) (sy + isoCao / 2 - ry), (int) (rx * 2), (int) (ry * 2));
                    return null;
                }
                case "isoVeDuong": {
                    // isoVeDuong(wx1, wy1, wx2, wy2, mau, nhacLen)
                    double nhac = args.size() > 5 ? toDouble(args.get(5)) : 0;
                    gameGraphics.setColor(parseColor(stringify(args.get(4))));
                    gameGraphics.drawLine(isoManHinhX(toDouble(args.get(0)), toDouble(args.get(1))),
                            (int) (isoManHinhY(toDouble(args.get(0)), toDouble(args.get(1))) + isoCao / 2 - nhac),
                            isoManHinhX(toDouble(args.get(2)), toDouble(args.get(3))),
                            (int) (isoManHinhY(toDouble(args.get(2)), toDouble(args.get(3))) + isoCao / 2 - nhac));
                    return null;
                }
                case "isoVeSuongMu": {
                    // isoVeSuongMu(x, y, banKinh, soTia, mauToi, doToi)
                    if (gameGraphics == null || gameBuffer == null) return null;
                    double x = toDouble(args.get(0)), y = toDouble(args.get(1));
                    double bk = toDouble(args.get(2));
                    int soTia = args.size() > 3 ? (int) toDouble(args.get(3)) : 160;
                    Color mauToi = args.size() > 4 ? parseColor(stringify(args.get(4))) : Color.BLACK;
                    float doToi = args.size() > 5 ? (float) toDouble(args.get(5)) : 0.8f;

                    Polygon theGioi = dungDaGiacTamNhin(x, y, bk, soTia);
                    Polygon manHinh = new Polygon();
                    for (int i = 0; i < theGioi.npoints; i++) {
                        manHinh.addPoint(isoManHinhX(theGioi.xpoints[i], theGioi.ypoints[i]),
                                (int) (isoManHinhY(theGioi.xpoints[i], theGioi.ypoints[i]) + isoCao / 2));
                    }
                    Area toi = new Area(new Rectangle(0, 0, gameBuffer.getWidth(), gameBuffer.getHeight()));
                    toi.subtract(new Area(manHinh));
                    Composite cu = gameGraphics.getComposite();
                    gameGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                            Math.max(0f, Math.min(1f, doToi))));
                    gameGraphics.setColor(mauToi);
                    gameGraphics.fill(toi);
                    gameGraphics.setComposite(cu);
                    return null;
                }

                // ================= CHUỘT & BÀN PHÍM NÂNG CAO =================
                case "anConTro": {
                    // anConTro(true) - ẩn con trỏ chuột để dùng tâm bắn tự vẽ
                    if (gameCanvas != null && gameWindow != null) {
                        boolean an = args.isEmpty() || toBoolean(args.get(0));
                        if (an) {
                            Cursor troTrong = Toolkit.getDefaultToolkit().createCustomCursor(
                                    new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB),
                                    new Point(0, 0), "khongCoTro");
                            gameCanvas.setCursor(troTrong);
                            gameWindow.setCursor(troTrong);
                        } else {
                            gameCanvas.setCursor(Cursor.getDefaultCursor());
                            gameWindow.setCursor(Cursor.getDefaultCursor());
                        }
                    }
                    return null;
                }
                case "layChuVuaGo": {
                    // Trả về các ký tự người chơi vừa gõ (dùng cho ô chat), rồi xoá đi.
                    String s = chuDaGo.toString();
                    chuDaGo.setLength(0);
                    return s;
                }
                case "xoaChuVuaGo": chuDaGo.setLength(0); return null;

                case "veBanDoNho": {
                    // veBanDoNho(x, y, tyLe, mauSan, mauTuong, mauSite) - vẽ minimap
                    if (gameGraphics == null || banDoLuoi == null) return null;
                    double mx = toDouble(args.get(0)), my = toDouble(args.get(1));
                    double ty = toDouble(args.get(2));
                    Color mSan = parseColor(stringify(args.get(3)));
                    Color mTuong = parseColor(stringify(args.get(4)));
                    Color mSite = args.size() > 5 ? parseColor(stringify(args.get(5))) : Color.YELLOW;
                    int o = Math.max(1, (int) Math.round(banDoOKichThuoc * ty));
                    for (int h = 0; h < banDoLuoi.length; h++) {
                        for (int c = 0; c < banDoLuoi[h].length; c++) {
                            char k = banDoLuoi[h][c];
                            Color m = mSan;
                            if (k == '#' || k == 'B' || k == 'W') m = mTuong;
                            else if (k == 'A' || k == 'b' || k == 'C') m = mSite;
                            gameGraphics.setColor(m);
                            gameGraphics.fillRect((int) (mx + c * banDoOKichThuoc * ty),
                                    (int) (my + h * banDoOKichThuoc * ty), o, o);
                        }
                    }
                    return null;
                }
                case "themKhoi": {
                    QuaKhoi q = new QuaKhoi();
                    q.x = toDouble(args.get(0)); q.y = toDouble(args.get(1)); q.r = toDouble(args.get(2));
                    danhSachKhoi.add(q);
                    return null;
                }
                case "xoaKhoiHet": danhSachKhoi.clear(); return null;
                case "trongKhoi": {
                    double px = toDouble(args.get(0)), py = toDouble(args.get(1));
                    for (QuaKhoi q : danhSachKhoi) {
                        if (Math.hypot(px - q.x, py - q.y) <= q.r) return true;
                    }
                    return false;
                }
                case "veSuongMu": {
                    // veSuongMu(x, y, banKinh, soTia, mauToi, doToi)
                    // Phóng tia quanh (x,y), phần KHÔNG nhìn thấy bị phủ tối.
                    if (gameGraphics == null || gameBuffer == null) return null;
                    double x = toDouble(args.get(0)), y = toDouble(args.get(1));
                    double bk = toDouble(args.get(2));
                    int soTia = args.size() > 3 ? (int) toDouble(args.get(3)) : 180;
                    Color mauToi = args.size() > 4 ? parseColor(stringify(args.get(4))) : Color.BLACK;
                    float doToi = args.size() > 5 ? (float) toDouble(args.get(5)) : 0.88f;

                    Polygon nhinThay = dungDaGiacTamNhin(x, y, bk, soTia);
                    // Đổi sang toạ độ màn hình
                    AffineTransform dich = AffineTransform.getTranslateInstance(-camOffX(), -camOffY());
                    Shape vungSang = dich.createTransformedShape(nhinThay);

                    Area toi = new Area(new Rectangle(0, 0, gameBuffer.getWidth(), gameBuffer.getHeight()));
                    toi.subtract(new Area(vungSang));

                    Composite cu = gameGraphics.getComposite();
                    gameGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                            Math.max(0f, Math.min(1f, doToi))));
                    gameGraphics.setColor(mauToi);
                    gameGraphics.fill(toi);
                    gameGraphics.setComposite(cu);
                    return null;
                }
                case "veVungNhin": {
                    // veVungNhin(x, y, banKinh, soTia, mau, doMo) - tô sáng vùng nhìn thấy
                    if (gameGraphics == null) return null;
                    Polygon p = dungDaGiacTamNhin(toDouble(args.get(0)), toDouble(args.get(1)),
                            toDouble(args.get(2)), args.size() > 3 ? (int) toDouble(args.get(3)) : 120);
                    AffineTransform dich = AffineTransform.getTranslateInstance(-camOffX(), -camOffY());
                    Shape hinh = dich.createTransformedShape(p);
                    Composite cu = gameGraphics.getComposite();
                    gameGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                            args.size() > 5 ? (float) toDouble(args.get(5)) : 0.15f));
                    gameGraphics.setColor(parseColor(args.size() > 4 ? stringify(args.get(4)) : "white"));
                    gameGraphics.fill(hinh);
                    gameGraphics.setComposite(cu);
                    return null;
                }

                case "setTitle": if (gameWindow != null) gameWindow.setTitle(stringify(args.get(0))); return null;
                case "closeGame": isGameRunning = false; if (gameWindow != null) gameWindow.dispose(); return null;

                // --- NHÓM 2: VẼ HÌNH ---
                case "clear": {
                    String colorName = args.isEmpty() ? "black" : stringify(args.get(0));
                    // Reset trạng thái vẽ mỗi khung hình để alpha/độ dày nét của khung trước không dính sang
                    gameGraphics.setComposite(AlphaComposite.SrcOver);
                    gameGraphics.setStroke(new BasicStroke(1f));
                    gameGraphics.setColor(parseColor(colorName));
                    gameGraphics.fillRect(0, 0, gameBuffer.getWidth(), gameBuffer.getHeight());
                    return null;
                }
                // --- CẬP NHẬT TRONG case "drawRect" ---
                case "drawRect": {
                    gameGraphics.setColor(parseColor(stringify(args.get(4))));
                    gameGraphics.drawRect(
                            (int)toDouble(args.get(0)) - camOffX(), // Trừ Camera X (+ rung màn hình)
                            (int)toDouble(args.get(1)) - camOffY(), // Trừ Camera Y
                            (int)toDouble(args.get(2)),
                            (int)toDouble(args.get(3))
                    );
                    return null;
                }

                // --- CẬP NHẬT TRONG case "fillRect" ---
                case "fillRect": {
                    gameGraphics.setColor(parseColor(stringify(args.get(4))));
                    gameGraphics.fillRect(
                            (int)toDouble(args.get(0)) - camOffX(),
                            (int)toDouble(args.get(1)) - camOffY(),
                            (int)toDouble(args.get(2)),
                            (int)toDouble(args.get(3))
                    );
                    return null;
                }
                case "drawCircle": {
                    gameGraphics.setColor(parseColor(stringify(args.get(3))));
                    int r = (int)toDouble(args.get(2));
                    gameGraphics.drawOval((int)toDouble(args.get(0)) - r - camOffX(), (int)toDouble(args.get(1)) - r - camOffY(), r * 2, r * 2);
                    return null;
                }
                case "fillCircle": {
                    gameGraphics.setColor(parseColor(stringify(args.get(3))));
                    int r = (int)toDouble(args.get(2));
                    gameGraphics.fillOval((int)toDouble(args.get(0)) - r - camOffX(), (int)toDouble(args.get(1)) - r - camOffY(), r * 2, r * 2);
                    return null;
                }
                case "drawLine": {
                    gameGraphics.setColor(parseColor(stringify(args.get(4))));
                    gameGraphics.drawLine(
                            (int)toDouble(args.get(0)) - camOffX(), (int)toDouble(args.get(1)) - camOffY(),
                            (int)toDouble(args.get(2)) - camOffX(), (int)toDouble(args.get(3)) - camOffY());
                    return null;
                }
                case "drawText": {
                    // Chữ luôn nằm ở toạ độ màn hình (không bị camera kéo) -> tiện làm HUD
                    gameGraphics.setColor(parseColor(stringify(args.get(4))));
                    gameGraphics.setFont(new Font(gameFontName, gameFontBold ? Font.BOLD : Font.PLAIN, (int)toDouble(args.get(3))));
                    gameGraphics.drawString(stringify(args.get(0)), (int)toDouble(args.get(1)), (int)toDouble(args.get(2)));
                    return null;
                }
                case "drawImage": {
                    Image img = loadGameImage(stringify(args.get(0)));
                    gameGraphics.drawImage(img, (int)toDouble(args.get(1)) - camOffX(), (int)toDouble(args.get(2)) - camOffY(),
                            (int)toDouble(args.get(3)), (int)toDouble(args.get(4)), null);
                    return null;
                }

                case "drawImageEx": {
                    // drawImageEx(duongDan, x, y, rong, cao, latNgang, gocXoay)
                    // x,y là GÓC TRÊN-TRÁI; ảnh xoay quanh tâm của chính nó.
                    Image img = loadGameImage(stringify(args.get(0)));
                    double ix = toDouble(args.get(1)) - camOffX();
                    double iy = toDouble(args.get(2)) - camOffY();
                    double iw = toDouble(args.get(3));
                    double ih = toDouble(args.get(4));
                    boolean lat = args.size() > 5 && toBoolean(args.get(5));
                    double goc = args.size() > 6 ? toDouble(args.get(6)) : 0;

                    AffineTransform oldTx = gameGraphics.getTransform();
                    gameGraphics.translate(ix + iw / 2.0, iy + ih / 2.0);
                    if (goc != 0) gameGraphics.rotate(Math.toRadians(goc));
                    if (lat) gameGraphics.scale(-1, 1);
                    gameGraphics.drawImage(img, (int) (-iw / 2), (int) (-ih / 2), (int) iw, (int) ih, null);
                    gameGraphics.setTransform(oldTx);
                    return null;
                }
                case "imageReady": {
                    // Kiểm tra ảnh có nạp được không (tránh vẽ ra ô trống mà không biết vì sao)
                    Image img = loadGameImage(stringify(args.get(0)));
                    return img != null && img.getWidth(null) > 0;
                }

                // --- NHÓM 2B: BỘ VẼ NÂNG CAO (GAME ENGINE V2) ---
                case "fillOval": {
                    gameGraphics.setColor(parseColor(stringify(args.get(4))));
                    gameGraphics.fillOval((int)toDouble(args.get(0)) - camOffX(), (int)toDouble(args.get(1)) - camOffY(),
                            (int)toDouble(args.get(2)), (int)toDouble(args.get(3)));
                    return null;
                }
                case "drawOval": {
                    gameGraphics.setColor(parseColor(stringify(args.get(4))));
                    gameGraphics.drawOval((int)toDouble(args.get(0)) - camOffX(), (int)toDouble(args.get(1)) - camOffY(),
                            (int)toDouble(args.get(2)), (int)toDouble(args.get(3)));
                    return null;
                }
                case "fillRoundRect": {
                    gameGraphics.setColor(parseColor(stringify(args.get(5))));
                    int bo = (int)toDouble(args.get(4));
                    gameGraphics.fillRoundRect((int)toDouble(args.get(0)) - camOffX(), (int)toDouble(args.get(1)) - camOffY(),
                            (int)toDouble(args.get(2)), (int)toDouble(args.get(3)), bo, bo);
                    return null;
                }
                case "drawRoundRect": {
                    gameGraphics.setColor(parseColor(stringify(args.get(5))));
                    int bo = (int)toDouble(args.get(4));
                    gameGraphics.drawRoundRect((int)toDouble(args.get(0)) - camOffX(), (int)toDouble(args.get(1)) - camOffY(),
                            (int)toDouble(args.get(2)), (int)toDouble(args.get(3)), bo, bo);
                    return null;
                }
                case "fillArc": {
                    gameGraphics.setColor(parseColor(stringify(args.get(6))));
                    gameGraphics.fillArc((int)toDouble(args.get(0)) - camOffX(), (int)toDouble(args.get(1)) - camOffY(),
                            (int)toDouble(args.get(2)), (int)toDouble(args.get(3)),
                            (int)toDouble(args.get(4)), (int)toDouble(args.get(5)));
                    return null;
                }
                case "drawArc": {
                    gameGraphics.setColor(parseColor(stringify(args.get(6))));
                    gameGraphics.drawArc((int)toDouble(args.get(0)) - camOffX(), (int)toDouble(args.get(1)) - camOffY(),
                            (int)toDouble(args.get(2)), (int)toDouble(args.get(3)),
                            (int)toDouble(args.get(4)), (int)toDouble(args.get(5)));
                    return null;
                }
                case "fillTriangle": {
                    gameGraphics.setColor(parseColor(stringify(args.get(6))));
                    int[] tx = { (int)toDouble(args.get(0)) - camOffX(), (int)toDouble(args.get(2)) - camOffX(), (int)toDouble(args.get(4)) - camOffX() };
                    int[] ty = { (int)toDouble(args.get(1)) - camOffY(), (int)toDouble(args.get(3)) - camOffY(), (int)toDouble(args.get(5)) - camOffY() };
                    gameGraphics.fillPolygon(tx, ty, 3);
                    return null;
                }
                case "fillPolygon": {
                    int[] px = toIntArray(args.get(0), camOffX());
                    int[] py = toIntArray(args.get(1), camOffY());
                    gameGraphics.setColor(parseColor(stringify(args.get(2))));
                    gameGraphics.fillPolygon(px, py, Math.min(px.length, py.length));
                    return null;
                }
                case "drawPolygon": {
                    int[] px = toIntArray(args.get(0), camOffX());
                    int[] py = toIntArray(args.get(1), camOffY());
                    gameGraphics.setColor(parseColor(stringify(args.get(2))));
                    gameGraphics.drawPolygon(px, py, Math.min(px.length, py.length));
                    return null;
                }
                case "gradientRect": {
                    // gradientRect(x, y, w, h, mauTren, mauDuoi)
                    int gx = (int)toDouble(args.get(0)) - camOffX();
                    int gy = (int)toDouble(args.get(1)) - camOffY();
                    int gw = (int)toDouble(args.get(2));
                    int gh = (int)toDouble(args.get(3));
                    Paint oldPaint = gameGraphics.getPaint();
                    gameGraphics.setPaint(new GradientPaint(gx, gy, parseColor(stringify(args.get(4))),
                            gx, gy + gh, parseColor(stringify(args.get(5)))));
                    gameGraphics.fillRect(gx, gy, gw, gh);
                    gameGraphics.setPaint(oldPaint);
                    return null;
                }
                case "drawTextCenter": {
                    // drawTextCenter(chu, tamX, y, coChu, mau)
                    gameGraphics.setColor(parseColor(stringify(args.get(4))));
                    gameGraphics.setFont(new Font(gameFontName, gameFontBold ? Font.BOLD : Font.PLAIN, (int)toDouble(args.get(3))));
                    String chu = stringify(args.get(0));
                    int rong = gameGraphics.getFontMetrics().stringWidth(chu);
                    gameGraphics.drawString(chu, (int)toDouble(args.get(1)) - rong / 2, (int)toDouble(args.get(2)));
                    return null;
                }
                case "textWidth": {
                    // textWidth(chu, coChu) -> bề rộng pixel, dùng để canh giữa/căn phải
                    Font f = new Font(gameFontName, gameFontBold ? Font.BOLD : Font.PLAIN, (int)toDouble(args.get(1)));
                    return (double) gameGraphics.getFontMetrics(f).stringWidth(stringify(args.get(0)));
                }
                case "setFont": {
                    // setFont(tenPhong, inDam)
                    gameFontName = stringify(args.get(0));
                    if (args.size() > 1) gameFontBold = toBoolean(args.get(1));
                    return null;
                }
                case "setAlpha": {
                    // setAlpha(0..1): độ trong suốt cho MỌI nét vẽ phía sau
                    float a = (float) Math.max(0, Math.min(1, toDouble(args.get(0))));
                    gameGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                    return null;
                }
                case "setStroke": {
                    gameGraphics.setStroke(new BasicStroke((float) toDouble(args.get(0)),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    return null;
                }

                // --- NHÓM 3: ĐỐI TƯỢNG GAME (SPRITE) ---
                case "sprite": {
                    String id = stringify(args.get(0));
                    Image img = new ImageIcon(stringify(args.get(5))).getImage();
                    sprites.put(id, new Sprite(id, toDouble(args.get(1)), toDouble(args.get(2)), toDouble(args.get(3)), toDouble(args.get(4)), img));
                    return id;
                }
                case "moveTo": { Sprite s = sprites.get(stringify(args.get(0))); if (s != null) { s.x = toDouble(args.get(1)); s.y = toDouble(args.get(2)); } return null; }
                case "moveBy": { Sprite s = sprites.get(stringify(args.get(0))); if (s != null) { s.x += toDouble(args.get(1)); s.y += toDouble(args.get(2)); } return null; }
                case "collide": {
                    Sprite s1 = sprites.get(stringify(args.get(0))); Sprite s2 = sprites.get(stringify(args.get(1)));
                    if (s1 == null || s2 == null) return false;
                    return new Rectangle((int)s1.x, (int)s1.y, (int)s1.w, (int)s1.h).intersects(new Rectangle((int)s2.x, (int)s2.y, (int)s2.w, (int)s2.h));
                }
                case "removeSprite": sprites.remove(stringify(args.get(0))); return null;
                case "spritePos": {
                    Sprite s = sprites.get(stringify(args.get(0)));
                    return s != null ? new ArrayList<>(Arrays.asList(s.x, s.y)) : new ArrayList<>();
                }

                // --- NHÓM 4: ĐIỀU KHIỂN ---
                case "isKeyDown": return keysDown.contains(chuanHoaTenPhim(stringify(args.get(0))));
                case "isKeyPressed": return keysPressed.contains(chuanHoaTenPhim(stringify(args.get(0))));
                // Giả lập bấm/nhả phím bằng mã lệnh: tiện làm màn hình demo tự chạy hoặc kiểm thử game
                case "pressKey": {
                    String p = chuanHoaTenPhim(stringify(args.get(0)));
                    if (!keysDown.contains(p)) keysPressed.add(p);
                    keysDown.add(p);
                    return null;
                }
                case "releaseKey": {
                    keysDown.remove(chuanHoaTenPhim(stringify(args.get(0))));
                    return null;
                }
                case "mouseX": return (double) mouseX;
                case "mouseY": return (double) mouseY;
                case "isMousePressed": {
                    // Chỉ đúng ở khung hình vừa bấm xuống (dùng cho bắn phát một, bấm nút...)
                    String btn2 = stringify(args.get(0)).toUpperCase();
                    return btn2.equals("LEFT") ? mouseLeftPressed : (btn2.equals("RIGHT") ? mouseRightPressed : false);
                }
                case "isMouseDown": {
                    String btn = stringify(args.get(0)).toUpperCase();
                    return btn.equals("LEFT") ? mouseLeftDown : (btn.equals("RIGHT") ? mouseRightDown : false);
                }

                // --- NHÓM 5: VÒNG LẶP & ÂM THANH ---
                case "gameLoop": {
                    String updateFunc = stringify(args.get(0));
                    String drawFunc = args.size() > 1 ? stringify(args.get(1)) : null;

                    isGameRunning = true;
                    long lastTimeNano = System.nanoTime();
                    long fpsMocThoiGian = System.nanoTime();
                    int fpsDem = 0;
                    double delta = 0;

                    while (isGameRunning) {
                        double nsPerFrame = 1000000000.0 / Math.max(1, targetFPS);
                        long now = System.nanoTime();
                        delta += (now - lastTimeNano) / nsPerFrame;
                        lastTimeNano = now;

                        // Máy yếu / cửa sổ bị kéo đi: chặn delta dồn vô hạn gây "tua nhanh"
                        if (delta > 5) delta = 5;

                        if (delta >= 1) {
                            lastDeltaSec = 1.0 / Math.max(1, targetFPS);

                            // 1. Cập nhật Logic
                            executeFunctionCall(updateFunc, new ArrayList<>());

                            // 2. Cập nhật Đồ họa
                            if (drawFunc != null) {
                                executeFunctionCall(drawFunc, new ArrayList<>());
                            }

                            // 3. Vẽ Sprite tự động hỗ trợ Camera và Xoay
                            AffineTransform oldTx = gameGraphics.getTransform();
                            for (Sprite s : sprites.values()) {
                                gameGraphics.translate(s.x - camOffX() + s.w/2, s.y - camOffY() + s.h/2);
                                gameGraphics.rotate(Math.toRadians(s.angle));
                                gameGraphics.drawImage(s.img, (int)(-s.w/2), (int)(-s.h/2), (int)s.w, (int)s.h, null);
                                gameGraphics.setTransform(oldTx);
                            }

                            // 4. Engine tự lo hạt hiệu ứng, chữ bay và rung màn hình
                            updateAndDrawEffects();

                            // 5. Xoá trạng thái "vừa bấm" SAU khi cả update lẫn draw đã chạy xong
                            keysPressed.clear();
                            mouseLeftPressed = false;
                            mouseRightPressed = false;

                            if (gameCanvas != null) gameCanvas.repaint();
                            frameCount++;
                            fpsDem++;
                            delta--;

                            // Đo FPS thực tế mỗi giây
                            if (now - fpsMocThoiGian >= 1000000000L) {
                                measuredFPS = fpsDem;
                                fpsDem = 0;
                                fpsMocThoiGian = now;
                            }
                        }
                        Thread.sleep(1); // Giảm tải CPU
                    }
                    return null;
                }
                case "setFPS": { targetFPS = (int) Math.max(1, toDouble(args.get(0))); return null; }
                case "getFPS": return measuredFPS;
                case "deltaTime": return lastDeltaSec;
                case "playSound": {
                    try {
                        File f = new File(stringify(args.get(0)));
                        AudioInputStream ais = AudioSystem.getAudioInputStream(f);
                        Clip clip = AudioSystem.getClip();
                        clip.open(ais);
                        if (args.size() > 1 && toBoolean(args.get(1))) clip.loop(Clip.LOOP_CONTINUOUSLY);
                        else clip.start();

                        // Gán vào biến toàn cục để stopSound và setVolume có thể điều khiển được
                        currentGameClip = clip;
                    } catch (Exception ex) { System.out.println("Lỗi phát âm thanh: " + ex.getMessage()); }
                    return null;
                }

                case "stopSound": {
                    if (currentGameClip != null && currentGameClip.isRunning()) {
                        currentGameClip.stop();
                        currentGameClip.close();
                    }
                    return null;
                }

                case "setVolume": {
                    if (currentGameClip != null && currentGameClip.isOpen()) {
                        try {
                            // Lấy bộ điều khiển âm lượng của Java Sound
                            FloatControl gainControl = (FloatControl) currentGameClip.getControl(FloatControl.Type.MASTER_GAIN);
                            double vol = toDouble(args.get(0));

                            if (vol <= 0) {
                                gainControl.setValue(gainControl.getMinimum()); // Tắt hẳn tiếng (Mute)
                            } else {
                                if (vol > 100) vol = 100;
                                // Thuật toán chuẩn: Chuyển đổi % (0-100) sang Decibel (dB)
                                float dB = (float) (Math.log10(vol / 100.0) * 20.0);
                                gainControl.setValue(dB);
                            }
                        } catch (Exception ex) {
                            System.out.println("Lỗi chỉnh âm lượng: " + ex.getMessage());
                        }
                    }
                    return null;
                }

                // --- NHÓM 6: TIỆN ÍCH GAME ---
                case "randomInt": {
                    int min = (int)toDouble(args.get(0));
                    int max = (int)toDouble(args.get(1));
                    return (double) (min + (int)(Math.random() * ((max - min) + 1)));
                }
                case "distance": return Math.hypot(toDouble(args.get(0)) - toDouble(args.get(2)), toDouble(args.get(1)) - toDouble(args.get(3)));
                case "frameCount": return (double) frameCount;
                case "setCamera": {
                    cameraX = toDouble(args.get(0));
                    cameraY = toDouble(args.get(1));
                    return null;
                }
                case "getTextWidth": {
                    return (double) gameGraphics.getFontMetrics().stringWidth(stringify(args.get(0)));
                }
                case "rotateSprite": {
                    Sprite s = sprites.get(stringify(args.get(0)));
                    if (s != null) s.angle = toDouble(args.get(1));
                    return null;
                }
                case "scaleSprite": {
                    Sprite s = sprites.get(stringify(args.get(0)));
                    if (s != null) { s.w = toDouble(args.get(1)); s.h = toDouble(args.get(2)); }
                    return null;
                }
                case "camX": return cameraX;
                case "camY": return cameraY;
                // Bật/tắt chế độ vẽ giao diện: giữa beginUI() và endUI() thì camera bị bỏ qua
                case "beginUI": cheDoGiaoDien = true; return null;
                case "endUI": cheDoGiaoDien = false; return null;
                case "saveScreenshot": {
                    // Chụp đúng khung hình đang hiện ra file PNG (tiện làm ảnh bìa / kiểm thử)
                    try {
                        if (gameBuffer != null) {
                            File f = new File(stringify(args.get(0)));
                            if (f.getParentFile() != null) f.getParentFile().mkdirs();
                            ImageIO.write(gameBuffer, "png", f);
                        }
                    } catch (Exception ex) { System.out.println("Lỗi chụp màn hình: " + ex.getMessage()); }
                    return null;
                }

                // --- NHÓM 7: HIỆU ỨNG ĐIỆN ẢNH (ENGINE TỰ CẬP NHẬT + TỰ VẼ) ---
                case "screenShake": {
                    // screenShake(doManh, soKhungHinh)
                    shakePower = toDouble(args.get(0));
                    shakeFrames = (int) toDouble(args.get(1));
                    return null;
                }
                case "spawnParticle": {
                    // spawnParticle(x, y, vx, vy, kichThuoc, tuoiTho, mau, trongLuc)
                    GameParticle p = new GameParticle();
                    p.x = toDouble(args.get(0)); p.y = toDouble(args.get(1));
                    p.vx = toDouble(args.get(2)); p.vy = toDouble(args.get(3));
                    p.size = toDouble(args.get(4));
                    p.life = toDouble(args.get(5)); p.maxLife = p.life;
                    p.color = parseColor(stringify(args.get(6)));
                    p.gravity = args.size() > 7 ? toDouble(args.get(7)) : 0;
                    if (gameParticles.size() < 1200) gameParticles.add(p);
                    return null;
                }
                case "burst": {
                    // burst(x, y, soHat, mau, tocDo, kichThuoc, tuoiTho, trongLuc)
                    double bx = toDouble(args.get(0)), by = toDouble(args.get(1));
                    int soHat = (int) toDouble(args.get(2));
                    Color mau = parseColor(stringify(args.get(3)));
                    double tocDo = args.size() > 4 ? toDouble(args.get(4)) : 4;
                    double kt = args.size() > 5 ? toDouble(args.get(5)) : 4;
                    double tho = args.size() > 6 ? toDouble(args.get(6)) : 25;
                    double tl = args.size() > 7 ? toDouble(args.get(7)) : 0.12;
                    for (int i = 0; i < soHat; i++) {
                        if (gameParticles.size() >= 1200) break;
                        GameParticle p = new GameParticle();
                        double goc = Math.random() * Math.PI * 2;
                        double v = tocDo * (0.35 + Math.random() * 0.85);
                        p.x = bx; p.y = by;
                        p.vx = Math.cos(goc) * v; p.vy = Math.sin(goc) * v;
                        p.size = kt * (0.5 + Math.random());
                        p.life = tho * (0.6 + Math.random() * 0.7); p.maxLife = p.life;
                        p.color = mau; p.gravity = tl;
                        gameParticles.add(p);
                    }
                    return null;
                }
                case "floatText": {
                    // floatText(chu, x, y, coChu, mau, tuoiTho)
                    GameFloatText f = new GameFloatText();
                    f.text = stringify(args.get(0));
                    f.x = toDouble(args.get(1)); f.y = toDouble(args.get(2));
                    f.size = toDouble(args.get(3));
                    f.color = parseColor(stringify(args.get(4)));
                    f.life = args.size() > 5 ? toDouble(args.get(5)) : 45; f.maxLife = f.life;
                    f.vy = args.size() > 6 ? toDouble(args.get(6)) : -1.1;
                    if (gameFloatTexts.size() < 200) gameFloatTexts.add(f);
                    return null;
                }
                case "clearEffects": {
                    gameParticles.clear();
                    gameFloatTexts.clear();
                    shakeFrames = 0; shakeOffX = 0; shakeOffY = 0; shakePower = 0;
                    return null;
                }

                // --- NHÓM 8: ÂM THANH V2 (NHẠC NỀN + HIỆU ỨNG + TIẾNG TỔNG HỢP) ---
                case "tone": {
                    // tone(tanSo, thoiGianMs, amLuong 0..1) - không cần file âm thanh nào cả
                    playTone(toDouble(args.get(0)), (int) toDouble(args.get(1)),
                            args.size() > 2 ? toDouble(args.get(2)) : 0.5);
                    return null;
                }
                case "playMusic": {
                    try {
                        if (gameMusicClip != null) { gameMusicClip.stop(); gameMusicClip.close(); }
                        AudioInputStream ais = AudioSystem.getAudioInputStream(new File(stringify(args.get(0))));
                        gameMusicClip = AudioSystem.getClip();
                        gameMusicClip.open(ais);
                        if (args.size() > 1 && toBoolean(args.get(1))) gameMusicClip.loop(Clip.LOOP_CONTINUOUSLY);
                        else gameMusicClip.start();
                        if (args.size() > 2) setClipVolume(gameMusicClip, toDouble(args.get(2)));
                    } catch (Exception ex) { System.out.println("Lỗi nhạc nền: " + ex.getMessage()); }
                    return null;
                }
                case "stopMusic": {
                    if (gameMusicClip != null) { gameMusicClip.stop(); gameMusicClip.close(); gameMusicClip = null; }
                    return null;
                }
                case "setMusicVolume": {
                    if (gameMusicClip != null) setClipVolume(gameMusicClip, toDouble(args.get(0)));
                    return null;
                }
                case "playSfx": {
                    // playSfx(duongDan, amLuong): có cache nên bắn liên tục không giật
                    try {
                        String duongDan = stringify(args.get(0));
                        Clip c = gameSfxCache.get(duongDan);
                        if (c == null) {
                            AudioInputStream ais = AudioSystem.getAudioInputStream(new File(duongDan));
                            c = AudioSystem.getClip();
                            c.open(ais);
                            gameSfxCache.put(duongDan, c);
                        }
                        c.stop();
                        c.setFramePosition(0);
                        if (args.size() > 1) setClipVolume(c, toDouble(args.get(1)));
                        c.start();
                    } catch (Exception ex) { System.out.println("Lỗi hiệu ứng âm thanh: " + ex.getMessage()); }
                    return null;
                }

                // --- NHÓM 9: TOÁN HỌC TIỆN DỤNG CHO GAME ---
                case "randomFloat": {
                    double lo = toDouble(args.get(0)), hi = toDouble(args.get(1));
                    return lo + Math.random() * (hi - lo);
                }
                case "lerp": {
                    double a = toDouble(args.get(0)), b = toDouble(args.get(1)), t = toDouble(args.get(2));
                    return a + (b - a) * t;
                }
                case "angleTo": {
                    // angleTo(x1,y1,x2,y2) -> góc bằng ĐỘ từ điểm 1 tới điểm 2
                    return Math.toDegrees(Math.atan2(toDouble(args.get(3)) - toDouble(args.get(1)),
                            toDouble(args.get(2)) - toDouble(args.get(0))));
                }
                // Chỉnh sửa phần "Vẽ Sprite tự động" trong gameLoop để hỗ trợ Camera và Xoay:
                    /*
                    AffineTransform oldTx = gameGraphics.getTransform();
                    for (Sprite s : sprites.values()) {
                        gameGraphics.translate(s.x - cameraX + s.w/2, s.y - cameraY + s.h/2);
                        gameGraphics.rotate(Math.toRadians(s.angle));
                        gameGraphics.drawImage(s.img, (int)(-s.w/2), (int)(-s.h/2), (int)s.w, (int)s.h, null);
                        gameGraphics.setTransform(oldTx);
                    }
                    */
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi HQL.Game: " + e.getMessage());
        }

        return CHUA_XU_LY;
    }

    /** Thư viện hql.mang - mạng cho game nhiều người chơi (tách riêng cho nhẹ executeBuiltin). */
    private Object thuVienMang(String name, List<Object> args) {
        try {
            switch (name) {
                // ---------------- PHÍA MÁY CHỦ ----------------
                case "mangMoServer": {
                    int cong = (int) toDouble(args.get(0));
                    if (mangServerSocket != null) { try { mangServerSocket.close(); } catch (Exception ignored) {} }
                    mangServerSocket = new ServerSocket(cong);
                    mangServerDangChay = true;
                    Thread t = new Thread(() -> {
                        while (mangServerDangChay) {
                            try {
                                Socket kh = mangServerSocket.accept();
                                kh.setTcpNoDelay(true);
                                String id = "K" + (++mangDemId);
                                mangKhach.put(id, kh);
                                mangGhiKhach.put(id, new BufferedWriter(
                                        new OutputStreamWriter(kh.getOutputStream(), StandardCharsets.UTF_8)));
                                mangHopThu.offer(new String[]{id, "@VAO"});
                                moLuongDoc(kh, id, true);
                            } catch (Exception e) {
                                if (!mangServerDangChay) break;
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                    return true;
                }
                case "mangNhan": {
                    // Trả về [idKhach, noiDung] hoặc mảng rỗng nếu chưa có tin nào.
                    String[] tin = mangHopThu.poll();
                    if (tin == null) return new ArrayList<>();
                    return new ArrayList<>(Arrays.asList(tin[0], tin[1]));
                }
                case "mangGui": {
                    mangGuiChoKhach(stringify(args.get(0)), stringify(args.get(1)));
                    return null;
                }
                case "mangPhat": {
                    String noiDung = stringify(args.get(0));
                    for (String id : mangGhiKhach.keySet()) mangGuiChoKhach(id, noiDung);
                    return null;
                }
                case "mangDanhSachKhach": {
                    ArrayList<Object> ds = new ArrayList<>(mangKhach.keySet());
                    return ds;
                }
                case "mangSoKhach": return (double) mangKhach.size();
                case "mangDuoiKhach": {
                    String id = stringify(args.get(0));
                    Socket s2 = mangKhach.remove(id);
                    mangGhiKhach.remove(id);
                    if (s2 != null) try { s2.close(); } catch (Exception ignored) {}
                    return null;
                }
                case "mangDongServer": {
                    mangServerDangChay = false;
                    for (Socket s2 : mangKhach.values()) try { s2.close(); } catch (Exception ignored) {}
                    mangKhach.clear();
                    mangGhiKhach.clear();
                    if (mangServerSocket != null) try { mangServerSocket.close(); } catch (Exception ignored) {}
                    mangServerSocket = null;
                    return null;
                }

                // ---------------- PHÍA NGƯỜI CHƠI ----------------
                case "mangKetNoi": {
                    String host = stringify(args.get(0));
                    int cong = (int) toDouble(args.get(1));
                    int cho = args.size() > 2 ? (int) toDouble(args.get(2)) : 4000;
                    try {
                        Socket s2 = new Socket();
                        s2.connect(new InetSocketAddress(host, cong), cho);
                        s2.setTcpNoDelay(true);
                        mangSocketToiServer = s2;
                        mangGhiToiServer = new BufferedWriter(
                                new OutputStreamWriter(s2.getOutputStream(), StandardCharsets.UTF_8));
                        mangDaNoiToiServer = true;
                        mangHopThuKhach.clear();
                        moLuongDoc(s2, "SERVER", false);
                        return true;
                    } catch (Exception e) {
                        mangDaNoiToiServer = false;
                        return false;
                    }
                }
                case "mangGuiServer": {
                    if (mangGhiToiServer == null) return false;
                    try {
                        synchronized (mangGhiToiServer) {
                            mangGhiToiServer.write(stringify(args.get(0)));
                            mangGhiToiServer.write("\n");
                            mangGhiToiServer.flush();
                        }
                        return true;
                    } catch (Exception e) {
                        mangDaNoiToiServer = false;
                        return false;
                    }
                }
                case "mangNhanServer": {
                    String tin = mangHopThuKhach.poll();
                    return tin == null ? "" : tin;
                }
                case "mangDaNoi": return mangDaNoiToiServer;
                case "mangNgatKetNoi": {
                    mangDaNoiToiServer = false;
                    if (mangSocketToiServer != null) try { mangSocketToiServer.close(); } catch (Exception ignored) {}
                    mangSocketToiServer = null;
                    mangGhiToiServer = null;
                    return null;
                }
                case "mangIpMay": {
                    try { return InetAddress.getLocalHost().getHostAddress(); }
                    catch (Exception e) { return "127.0.0.1"; }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi HQL.Mang: " + e.getMessage());
        }

        return CHUA_XU_LY;
    }

    private Object executeBuiltin(String name, List<List<Token>> argGroups) {
        List<Object> args = argGroups.stream()
                .filter(group -> !group.isEmpty())
                .map(this::resolveExpression)
                .collect(Collectors.toList());

        switch (name) {
            case "type": return detectType(args.isEmpty() ? null : args.get(0));
            case "str": return stringify(args.get(0));
            case "num": return toDouble(args.get(0));
            case "bool": return toBoolean(args.get(0));
            case "len":
            case "size":
                if (args.isEmpty()) return 0.0;
                if (args.get(0) instanceof ArrayList) return (double) ((ArrayList<?>)args.get(0)).size();
                if (args.get(0) instanceof String) return (double) ((String)args.get(0)).length();
                return 0.0;
            case "call":
                if (args.isEmpty() || !(args.get(0) instanceof String)) throw new RuntimeException("Lỗi: call() cần tên hàm.");
                String funcToCall = (String) args.get(0);
                List<List<Token>> newArgGroups = new ArrayList<>(argGroups.subList(1, argGroups.size()));
                return executeFunctionCall(funcToCall, newArgGroups);
            case "input":
                if (!args.isEmpty()) System.out.print(stringify(args.get(0)));
                String rawInput = scanner.nextLine();

                // 1. Kiểm tra xem có phải Boolean hoặc Null không
                if (rawInput.equalsIgnoreCase("true")) return true;
                if (rawInput.equalsIgnoreCase("false")) return false;
                if (rawInput.equalsIgnoreCase("null")) return null;

                // 2. Thử ép sang Số (Number - Double)
                try {
                    return Double.parseDouble(rawInput);
                } catch (NumberFormatException e) {
                    // 3. Nếu không phải số, không phải bool thì chắc chắn là Chuỗi (String)
                    return rawInput;
                }
        }

        if (usedLibraries.contains("hql.math")) {
            switch (name) {
                case "print":
                    if (!args.isEmpty()) System.out.println(stringify(args.get(0)));
                    return null;
                case "sqrt": return Math.sqrt(toDouble(args.get(0)));
                case "pow": return Math.pow(toDouble(args.get(0)), toDouble(args.get(1)));
                case "round": return (double) Math.round(toDouble(args.get(0)));
                case "ceil": return Math.ceil(toDouble(args.get(0)));
                case "floor": return Math.floor(toDouble(args.get(0)));
                case "abs": return Math.abs(toDouble(args.get(0)));
                case "max": return Math.max(toDouble(args.get(0)), toDouble(args.get(1)));
                case "min": return Math.min(toDouble(args.get(0)), toDouble(args.get(1)));
                case "sin": return Math.sin(toDouble(args.get(0)));
                case "cos": return Math.cos(toDouble(args.get(0)));
                case "tan": return Math.tan(toDouble(args.get(0)));
                case "random":
                    double minR = toDouble(args.get(0));
                    double maxR = toDouble(args.get(1));
                    return Math.floor(Math.random() * (maxR - minR + 1)) + minR;

                // --- THƯ VIỆN MATH MỚI (JAVA + PYTHON) ---
                case "cbrt": return Math.cbrt(toDouble(args.get(0)));
                case "trunc":
                    double t = toDouble(args.get(0));
                    return t < 0 ? Math.ceil(t) : Math.floor(t);
                case "toRadians": return Math.toRadians(toDouble(args.get(0)));
                case "hypot": return Math.hypot(toDouble(args.get(0)), toDouble(args.get(1)));
                case "log": return Math.log(toDouble(args.get(0)));
                case "log10": return Math.log10(toDouble(args.get(0)));
                case "log2": return Math.log(toDouble(args.get(0))) / Math.log(2.0);
                case "factorial": return (double) factorial((long) toDouble(args.get(0)));
                case "gcd": return (double) gcd((long) toDouble(args.get(0)), (long) toDouble(args.get(1)));
                case "lcm": return (double) lcm((long) toDouble(args.get(0)), (long) toDouble(args.get(1)));
                case "comb": return (double) comb((long) toDouble(args.get(0)), (long) toDouble(args.get(1)));
                case "clamp":
                    double val = toDouble(args.get(0));
                    double cMin = toDouble(args.get(1));
                    double cMax = toDouble(args.get(2));
                    return Math.max(cMin, Math.min(cMax, val));
                // --- THÊM CÁC CASE NÀY VÀO TRONG SWITCH(NAME) CỦA HQL.MATH ---
                case "isqrt": return (double) (long) Math.floor(Math.sqrt(toDouble(args.get(0))));
                case "fsum":
                    if (args.isEmpty() || !(args.get(0) instanceof ArrayList)) throw new RuntimeException("Lỗi: fsum cần truyền vào một mảng.");
                    return fsumHelper((ArrayList<?>) args.get(0));
                case "isclose": return isCloseHelper(toDouble(args.get(0)), toDouble(args.get(1)));
                case "isnan": return Double.isNaN(toDouble(args.get(0)));
                case "isinf": return Double.isInfinite(toDouble(args.get(0)));
                case "perm": return (double) permHelper((long) toDouble(args.get(0)), (long) toDouble(args.get(1)));
                case "degrees": return Math.toDegrees(toDouble(args.get(0)));
                case "exp": return Math.exp(toDouble(args.get(0)));
                // --- THỐNG KÊ ---
                case "mean": {
                    ArrayList<?> list = (ArrayList<?>) args.get(0);
                    double sum = 0;
                    for (Object o : list) sum += toDouble(o);
                    return sum / list.size();
                }
                case "median": {
                    ArrayList<Double> sorted = new ArrayList<>();
                    for (Object o : (ArrayList<?>) args.get(0)) sorted.add(toDouble(o));
                    Collections.sort(sorted);
                    int n = sorted.size();
                    if (n % 2 == 0) return (sorted.get(n/2 - 1) + sorted.get(n/2)) / 2.0;
                    else return sorted.get(n/2);
                }
                case "variance": {
                    ArrayList<?> list = (ArrayList<?>) args.get(0);
                    double avg = meanHelper(list);
                    double var = 0;
                    for (Object o : list) var += Math.pow(toDouble(o) - avg, 2);
                    return var / list.size(); // population variance
                }
                case "stddev": {
                    ArrayList<?> list = (ArrayList<?>) args.get(0);
                    double avg = meanHelper(list);
                    double var = 0;
                    for (Object o : list) var += Math.pow(toDouble(o) - avg, 2);
                    return Math.sqrt(var / list.size());
                }
                case "correlation": {
                    ArrayList<?> xList = (ArrayList<?>) args.get(0);
                    ArrayList<?> yList = (ArrayList<?>) args.get(1);
                    if (xList.size() != yList.size()) throw new RuntimeException("Hai mang phai cung do dai.");
                    double mx = meanHelper(xList), my = meanHelper(yList);
                    double sxy = 0, sx2 = 0, sy2 = 0;
                    for (int i = 0; i < xList.size(); i++) {
                        double dx = toDouble(xList.get(i)) - mx;
                        double dy = toDouble(yList.get(i)) - my;
                        sxy += dx * dy;
                        sx2 += dx * dx;
                        sy2 += dy * dy;
                    }
                    return sxy / Math.sqrt(sx2 * sy2);
                }
                case "linearRegression": {
                    ArrayList<?> xList = (ArrayList<?>) args.get(0);
                    ArrayList<?> yList = (ArrayList<?>) args.get(1);
                    if (xList.size() != yList.size()) throw new RuntimeException("Hai mang phai cung do dai.");
                    double mx = meanHelper(xList), my = meanHelper(yList);
                    double num = 0, den = 0;
                    for (int i = 0; i < xList.size(); i++) {
                        double dx = toDouble(xList.get(i)) - mx;
                        double dy = toDouble(yList.get(i)) - my;
                        num += dx * dy;
                        den += dx * dx;
                    }
                    double slope = num / den;
                    double intercept = my - slope * mx;
                    ArrayList<Double> result = new ArrayList<>();
                    result.add(slope); result.add(intercept);
                    return result;
                }

                // --- MA TRẬN (đầu vào là List<List<Double>>) ---
                case "matrixMultiply": {
                    ArrayList<ArrayList<Double>> A = convertToMatrix(args.get(0));
                    ArrayList<ArrayList<Double>> B = convertToMatrix(args.get(1));
                    if (A.get(0).size() != B.size()) throw new RuntimeException("Kich thuoc ma tran khong hop le de nhan.");
                    ArrayList<ArrayList<Double>> result = new ArrayList<>();
                    for (int i = 0; i < A.size(); i++) {
                        ArrayList<Double> row = new ArrayList<>();
                        for (int j = 0; j < B.get(0).size(); j++) {
                            double sum = 0;
                            for (int k = 0; k < B.size(); k++) sum += A.get(i).get(k) * B.get(k).get(j);
                            row.add(sum);
                        }
                        result.add(row);
                    }
                    return result;
                }
                case "matrixTranspose": {
                    ArrayList<ArrayList<Double>> M = convertToMatrix(args.get(0));
                    ArrayList<ArrayList<Double>> T = new ArrayList<>();
                    for (int j = 0; j < M.get(0).size(); j++) {
                        ArrayList<Double> col = new ArrayList<>();
                        for (int i = 0; i < M.size(); i++) col.add(M.get(i).get(j));
                        T.add(col);
                    }
                    return T;
                }
                case "matrixDeterminant": {
                    ArrayList<ArrayList<Double>> M = convertToMatrix(args.get(0));
                    if (M.size() != M.get(0).size()) throw new RuntimeException("Ma tran phai vuong.");
                    return determinantHelper(M);
                }
                case "matrixInverse": {
                    ArrayList<ArrayList<Double>> M = convertToMatrix(args.get(0));
                    int n = M.size();
                    if (n != M.get(0).size()) throw new RuntimeException("Ma tran phai vuong.");
                    // Tạo ma trận đơn vị
                    ArrayList<ArrayList<Double>> I = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        ArrayList<Double> row = new ArrayList<>();
                        for (int j = 0; j < n; j++) row.add(i == j ? 1.0 : 0.0);
                        I.add(row);
                    }
                    // Khử Gauss-Jordan
                    for (int p = 0; p < n; p++) {
                        // Tìm dòng có pivot lớn nhất
                        int maxRow = p;
                        for (int i = p + 1; i < n; i++) if (Math.abs(M.get(i).get(p)) > Math.abs(M.get(maxRow).get(p))) maxRow = i;
                        // Hoán đổi
                        ArrayList<Double> temp = M.get(p); M.set(p, M.get(maxRow)); M.set(maxRow, temp);
                        temp = I.get(p); I.set(p, I.get(maxRow)); I.set(maxRow, temp);
                        double pivot = M.get(p).get(p);
                        if (Math.abs(pivot) < 1e-12) throw new RuntimeException("Khong kha nghich.");
                        for (int j = 0; j < n; j++) {
                            M.get(p).set(j, M.get(p).get(j) / pivot);
                            I.get(p).set(j, I.get(p).get(j) / pivot);
                        }
                        for (int i = 0; i < n; i++) {
                            if (i != p) {
                                double factor = M.get(i).get(p);
                                for (int j = 0; j < n; j++) {
                                    M.get(i).set(j, M.get(i).get(j) - factor * M.get(p).get(j));
                                    I.get(i).set(j, I.get(i).get(j) - factor * I.get(p).get(j));
                                }
                            }
                        }
                    }
                    return I;
                }
                // --- CHUYỂN ĐỔI ĐƠN VỊ ---
                case "unitConvert": { // Thêm ngoặc nhọn để tách biệt biến
                    double uVal = toDouble(args.get(0));
                    String from = stringify(args.get(1)).toLowerCase();
                    String to = stringify(args.get(2)).toLowerCase();
                    return uVal * (getUnitFactor(from) / getUnitFactor(to));
                }
                case "listUnits":
                    return new ArrayList<>(Arrays.asList("m", "km", "cm", "mm", "inch", "ft", "yard", "mile", "kg", "g", "lb", "oz", "l", "ml", "gal"));
                case "celsiusToFahrenheit":
                    return (toDouble(args.get(0)) * 9.0/5.0) + 32.0;
                case "fahrenheitToCelsius":
                    return (toDouble(args.get(0)) - 32.0) * 5.0/9.0;
                case "unitCompare": { // Thêm ngoặc nhọn để tách biệt biến
                    double v1 = toDouble(args.get(0));
                    String u1 = stringify(args.get(1)).toLowerCase();
                    double v2 = toDouble(args.get(2));
                    String u2 = stringify(args.get(3)).toLowerCase();
                    double val1Base = v1 * getUnitFactor(u1);
                    double val2Base = v2 * getUnitFactor(u2);
                    if (isCloseHelper(val1Base, val2Base)) return 0.0;
                    return val1Base > val2Base ? 1.0 : -1.0;
                }
            }
        }
        if (usedLibraries.contains("hql.io")) {
            try {
                switch (name) {
                    case "write":
                        boolean append = args.size() >= 3 ? toBoolean(args.get(2)) : true;
                        Files.writeString(Paths.get(stringify(args.get(0))), stringify(args.get(1)), StandardCharsets.UTF_8, StandardOpenOption.CREATE, append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                        return null;
                    case "read":
                        return Files.readString(Paths.get(stringify(args.get(0))), StandardCharsets.UTF_8);
                    case "writeA":
                        boolean appendA = args.size() >= 3 ? toBoolean(args.get(2)) : true;
                        Files.write(Paths.get(stringify(args.get(0))), ((ArrayList<?>)args.get(1)).stream().map(this::stringify).collect(Collectors.toList()), StandardCharsets.UTF_8, StandardOpenOption.CREATE, appendA ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                        return null;
                    case "readA":
                        return new ArrayList<>(Files.readAllLines(Paths.get(stringify(args.get(0))), StandardCharsets.UTF_8));

                    // --- BỔ SUNG 5 HÀM QUẢN LÝ FILE MỚI ---

                    case "exists":
                        return Files.exists(Paths.get(stringify(args.get(0))));

                    case "create":
                        String createPath = stringify(args.get(0));
                        if (!Files.exists(Paths.get(createPath))) {
                            if (createPath.contains(".") && !createPath.endsWith("/") && !createPath.endsWith("\\")) {
                                // Nếu có dấu chấm (.) và không kết thúc bằng / -> Coi như là tạo file
                                Path p = Paths.get(createPath);
                                if (p.getParent() != null) Files.createDirectories(p.getParent()); // Tạo thư mục cha nếu chưa có
                                Files.createFile(p);
                            } else {
                                // Nếu không -> Tạo thư mục (Directories)
                                Files.createDirectories(Paths.get(createPath));
                            }
                        }
                        return null;

                    case "delete":
                        Path pathToDelete = Paths.get(stringify(args.get(0)));
                        if (!Files.exists(pathToDelete)) return false;

                        if (Files.isDirectory(pathToDelete)) {
                            // Nếu là thư mục: Duyệt và xóa mọi thứ bên trong trước (đệ quy)
                            try (Stream<Path> walk = Files.walk(pathToDelete)) {
                                walk.sorted(java.util.Comparator.reverseOrder()) // Xóa con trước, cha sau
                                        .forEach(p -> {
                                            try {
                                                Files.delete(p);
                                            } catch (IOException e) {
                                                throw new RuntimeException("Không thể xóa: " + p);
                                            }
                                        });
                            }
                            return true;
                        } else {
                            // Nếu là file: Xóa bình thường
                            return Files.deleteIfExists(pathToDelete);
                        }

                    case "copy":
                        if (args.size() < 2) throw new RuntimeException("Lỗi: copy() cần đường dẫn nguồn và đích.");
                        Files.copy(Paths.get(stringify(args.get(0))), Paths.get(stringify(args.get(1))), StandardCopyOption.REPLACE_EXISTING);
                        return null;

                    case "move":
                        if (args.size() < 2) throw new RuntimeException("Lỗi: move() cần đường dẫn nguồn và đích.");
                        Files.move(Paths.get(stringify(args.get(0))), Paths.get(stringify(args.get(1))), StandardCopyOption.REPLACE_EXISTING);
                        return null;
                    // --- NHÓM THÔNG TIN FILE (METADATA) ---
                    case "fileSize": return (double) Files.size(Paths.get(stringify(args.get(0))));
                    case "getName":
                        Path pName = Paths.get(stringify(args.get(0)));
                        return pName.getFileName() != null ? pName.getFileName().toString() : "";
                    case "extension":
                        String extName = Paths.get(stringify(args.get(0))).getFileName().toString();
                        int dotIdx = extName.lastIndexOf('.');
                        return (dotIdx > 0) ? extName.substring(dotIdx + 1) : "";
                    case "isDir": return Files.isDirectory(Paths.get(stringify(args.get(0))));
                    case "lastModified": return (double) Files.getLastModifiedTime(Paths.get(stringify(args.get(0)))).toMillis();

                    // --- NHÓM DUYỆT THƯ MỤC (WALKING) ---
                    case "list":
                        try (Stream<Path> stream = Files.list(Paths.get(stringify(args.get(0))))) {
                            return new ArrayList<>(stream.map(p -> p.getFileName().toString()).collect(Collectors.toList()));
                        }
                    case "listFull":
                        try (Stream<Path> stream = Files.list(Paths.get(stringify(args.get(0))))) {
                            return new ArrayList<>(stream.map(Path::toString).collect(Collectors.toList()));
                        }
                    case "find":
                        // Mặc định tìm ở thư mục hiện tại ("."), có thể truyền thêm tham số thứ 2 làm thư mục đích
                        String pattern = stringify(args.get(0)).replace("*", "");
                        String searchDir = args.size() > 1 ? stringify(args.get(1)) : ".";
                        try (Stream<Path> walk = Files.walk(Paths.get(searchDir))) {
                            return new ArrayList<>(walk.filter(Files::isRegularFile)
                                    .map(Path::toString)
                                    .filter(s -> s.endsWith(pattern))
                                    .collect(Collectors.toList()));
                        }

                        // --- NHÓM TIỆN ÍCH HỆ THỐNG ---
                    case "freeSpace": return (double) new File(".").getFreeSpace() / (1024 * 1024 * 1024); // GB
                    case "totalSpace": return (double) new File(".").getTotalSpace() / (1024 * 1024 * 1024); // GB
                    case "homeDir": return System.getProperty("user.home");
                    case "workingDir": return System.getProperty("user.dir");

                    // --- NHÓM NÉN VÀ GIẢI NÉN (ARCHIVES - ZIP) ---
                    case "zip":
                        zipFolderHelper(Paths.get(stringify(args.get(0))), Paths.get(stringify(args.get(1))));
                        return null;
                    case "unzip":
                        unzipFolderHelper(Paths.get(stringify(args.get(0))), Paths.get(stringify(args.get(1))));
                        return null;
                }
            } catch (IOException e) {
                throw new RuntimeException("Lỗi I/O: " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.net")) {
            try {
                switch (name) {
                    // GIAI ĐOẠN 2: CẤU HÌNH
                    case "addHeader": netHeaders.put(stringify(args.get(0)), stringify(args.get(1))); return null;
                    case "setCookie": netCookie = stringify(args.get(0)); return null;
                    case "setTimeout": netTimeoutMs = (int) toDouble(args.get(0)); return null;
                    case "setProxy": netProxy = ProxySelector.of(new InetSocketAddress(stringify(args.get(0)), (int) toDouble(args.get(1)))); return null;
                    case "ignoreSSL": netIgnoreSSL = true; return null;

                    // GIAI ĐOẠN 3: ĐỌC DỮ LIỆU TRẢ VỀ
                    case "getStatusCode": return (double) lastStatusCode;
                    case "getContentType": return lastContentType != null ? lastContentType : "";
                    case "getResponseHeader": return lastHeaders != null ? lastHeaders.firstValue(stringify(args.get(0))).orElse("") : "";

                    /*
                     * httpPostFile(url, phanJson, duongDanFile, tenGui, kieuMime)
                     *
                     * GỬI MỘT FILE KÈM MỘT KHỐI JSON — dạng multipart/form-data.
                     *
                     * Vì sao phải có: webhook của Discord KHÔNG nhận sticker_ids
                     * (đã thử thật: trả 200 nhưng bỏ sạch trường ấy), mà nhét ảnh
                     * vào embed thì Discord vẽ thêm một cái khung viền quanh nó.
                     * Chỉ khi ảnh đi dưới dạng TỆP ĐÍNH KÈM nó mới hiện trần
                     * không khung — và ảnh APNG thì chạy động y như nhãn dán
                     * thật, trong khi tin vẫn mang đúng tên người gửi.
                     *
                     * httpPost() không làm được việc này: thân multipart có phần
                     * NHỊ PHÂN, đi qua String là hỏng file.
                     *
                     * phanJson vào trường "payload_json", file vào "files[0]" —
                     * đúng tên mà Discord đợi.
                     */
                    case "httpPostFile": {
                        String urlF   = stringify(args.get(0));
                        String phanJs = args.size() > 1 ? stringify(args.get(1)) : "{}";
                        Path tepF     = Paths.get(stringify(args.get(2)));
                        String tenGui = args.size() > 3 && !stringify(args.get(3)).isEmpty()
                                ? stringify(args.get(3)) : tepF.getFileName().toString();
                        String mime   = args.size() > 4 && !stringify(args.get(4)).isEmpty()
                                ? stringify(args.get(4)) : "application/octet-stream";

                        if (!Files.exists(tepF)) throw new RuntimeException("Không thấy tệp: " + tepF);

                        String ranh = "----hql" + System.currentTimeMillis();
                        String xd = "\r\n";
                        byte[] duLieu = Files.readAllBytes(tepF);

                        ByteArrayOutputStream than = new ByteArrayOutputStream();
                        than.write(("--" + ranh + xd
                                + "Content-Disposition: form-data; name=\"payload_json\"" + xd
                                + "Content-Type: application/json" + xd + xd
                                + phanJs + xd).getBytes(StandardCharsets.UTF_8));
                        than.write(("--" + ranh + xd
                                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\""
                                + tenGui.replace("\"", "") + "\"" + xd
                                + "Content-Type: " + mime + xd + xd).getBytes(StandardCharsets.UTF_8));
                        than.write(duLieu);
                        than.write((xd + "--" + ranh + "--" + xd).getBytes(StandardCharsets.UTF_8));

                        HttpRequest.Builder bF = HttpRequest.newBuilder().uri(URI.create(urlF));
                        for (Map.Entry<String, String> e : netHeaders.entrySet()) {
                            // Content-Type phải là của multipart, không lấy cái đang cài sẵn.
                            if (!e.getKey().equalsIgnoreCase("Content-Type")) bF.header(e.getKey(), e.getValue());
                        }
                        if (netCookie != null) bF.header("Cookie", netCookie);
                        bF.header("Content-Type", "multipart/form-data; boundary=" + ranh);
                        bF.POST(HttpRequest.BodyPublishers.ofByteArray(than.toByteArray()));

                        HttpResponse<String> traF = buildHttpClient().send(bF.build(),
                                HttpResponse.BodyHandlers.ofString());
                        updateNetState(traF);
                        return traF.body();
                    }
                    // GIAI ĐOẠN 1: KẾT NỐI CHÍNH
                    case "httpGet":
                    case "httpPost":
                    case "download":
                        String url = stringify(args.get(0));
                        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(url));
                        for (Map.Entry<String, String> entry : netHeaders.entrySet()) {
                            reqBuilder.header(entry.getKey(), entry.getValue());
                        }
                        if (netCookie != null) reqBuilder.header("Cookie", netCookie);

                        if (name.equals("httpPost")) {
                            reqBuilder.POST(HttpRequest.BodyPublishers.ofString(stringify(args.get(1))));
                        } else {
                            reqBuilder.GET();
                        }

                        HttpClient client = buildHttpClient();
                        HttpRequest request = reqBuilder.build();

                        if (name.equals("download")) {
                            Path targetPath = Paths.get(stringify(args.get(1)));
                            if (targetPath.getParent() != null) Files.createDirectories(targetPath.getParent());
                            HttpResponse<Path> fileRes = client.send(request, HttpResponse.BodyHandlers.ofFile(targetPath));
                            updateNetState(fileRes);
                            return Files.exists(targetPath);
                        } else {
                            HttpResponse<String> textRes = client.send(request, HttpResponse.BodyHandlers.ofString());
                            updateNetState(textRes);
                            return textRes.body();
                        }

                        // GIAI ĐOẠN 4: TIỆN ÍCH MẠNG
                    case "isOnline":
                        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("8.8.8.8", 53), 3000); return true; } catch (Exception e) { return false; }
                    case "getIP": return InetAddress.getLocalHost().getHostAddress();
                    case "getHost": return InetAddress.getLocalHost().getHostName();
                    case "checkPort":
                        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(stringify(args.get(0)), (int) toDouble(args.get(1))), netTimeoutMs); return true; } catch (Exception e) { return false; }
                    case "ping":
                        long start = System.currentTimeMillis();
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(stringify(args.get(0)), 80), netTimeoutMs);
                            return (double) (System.currentTimeMillis() - start);

                        } catch (Exception e) { return -1.0; } // Trả về -1 nếu Ping xịt
                    case "createServer": {
                        try {
                            int port = (int) toDouble(args.get(0));
                            String handlerFunc = stringify(args.get(1));

                            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
                            server.createContext("/", exchange -> {
                                try {
                                    // Gọi hàm xử lý HQL của người dùng
                                    Object response = executeFunctionCall(handlerFunc, new ArrayList<>());
                                    String resStr = stringify(response);
                                    exchange.sendResponseHeaders(200, resStr.getBytes(StandardCharsets.UTF_8).length);
                                    OutputStream os = exchange.getResponseBody();
                                    os.write(resStr.getBytes(StandardCharsets.UTF_8));
                                    os.close();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            });
                            server.setExecutor(null);
                            server.start();
                            System.out.println("HQL Web Server started on port " + port);
                            return true;
                        } catch (Exception e) {
                            throw new RuntimeException("Lỗi tạo Server: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Mạng (hql.net): " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.sys")) {
            try {
                switch (name) {
                    // 1. NHÓM CPU
                    case "cpuModel": return getSysInfo("(Get-CimInstance Win32_Processor).Name");
                    case "cpuCores": return toDouble(getSysInfo("(Get-CimInstance Win32_Processor).NumberOfCores").split(" ")[0]);
                    case "cpuThreads": return toDouble(getSysInfo("(Get-CimInstance Win32_Processor).NumberOfLogicalProcessors").split(" ")[0]);
                    case "cpuSpeed": return toDouble(getSysInfo("(Get-CimInstance Win32_Processor).MaxClockSpeed").split(" ")[0]) / 1000.0; // MHz sang GHz
                    case "cpuVendor": return getSysInfo("(Get-CimInstance Win32_Processor).Manufacturer");

                    // 2. NHÓM GPU & MÀN HÌNH (Dùng [0] để lấy Card rời hoặc Card chính)
                    case "uptime":
                        return toDouble(getSysInfo("[math]::Round((New-TimeSpan -Start (Get-CimInstance Win32_OperatingSystem).LastBootUpTime).TotalMinutes)"));

                    // --- SỬA GPU: Lọc bỏ Card ảo (như DeskIn), chỉ lấy Card thật có VRAM ---
                    // --- GPU: Tự động ghép nối nếu máy có nhiều Card (Intel + RTX) ---
                    case "gpuModel":
                        return getSysInfo("(Get-CimInstance Win32_VideoController).Name -join ' & '");
                    case "gpuMemory":
                        // Lấy VRAM của tất cả các card, đổi sang GB và ghép lại
                        return getSysInfo("((Get-CimInstance Win32_VideoController).AdapterRAM | ForEach-Object { [math]::Round($_ / 1GB, 1) }) -join ' GB & '") + " GB";
                    case "gpuDriver":
                        return getSysInfo("(Get-CimInstance Win32_VideoController).DriverVersion -join ' | '");
                    case "machineModel":
                        return getSysInfo("(Get-CimInstance Win32_ComputerSystemProduct).Version");

                    // --- RESOLUTION: Lấy độ phân giải của card đang thực sự xuất hình ---
                    // --- RESOLUTION: Fix lỗi ParserError do xung đột dấu nháy ---
                    // --- RESOLUTION: Lấy Độ phân giải, Tần số quét (Hz) và tự nhận diện Chuẩn (K) ---
                    case "resolution":
                        String rawRes = getSysInfo("(Get-CimInstance Win32_VideoController | Where-Object {$_.CurrentHorizontalResolution -ne $null} | ForEach-Object { '{0}x{1}@{2}' -f $_.CurrentHorizontalResolution, $_.CurrentVerticalResolution, $_.CurrentRefreshRate }) -join ' | '");

                        if (rawRes.equals("N/A") || rawRes.startsWith("Lỗi")) return rawRes;

                        String[] displays = rawRes.split(" \\| ");
                        StringBuilder finalRes = new StringBuilder();

                        for (int i = 0; i < displays.length; i++) {
                            String[] parts = displays[i].split("@");
                            if (parts.length == 2) {
                                String wXh = parts[0];
                                String hz = parts[1];
                                String type = "Custom";

                                // Thuật toán tự động nhận diện "mấy K"
                                if (wXh.equals("1920x1080")) type = "Full HD";
                                else if (wXh.equals("2560x1440")) type = "2K (QHD)";
                                else if (wXh.equals("3840x2160")) type = "4K (UHD)";
                                else if (wXh.equals("1280x720") || wXh.equals("1366x768")) type = "HD";
                                else if (wXh.equals("2560x1080") || wXh.equals("3440x1440")) type = "Ultrawide";

                                finalRes.append(wXh).append(" (").append(hz).append("Hz - ").append(type).append(")");
                            } else {
                                finalRes.append(displays[i]);
                            }
                            // Nếu có nhiều màn hình, nối bằng dấu &
                            if (i < displays.length - 1) finalRes.append(" & ");
                        }
                        return finalRes.toString();

                    // 3. NHÓM RAM
                    case "totalRam": return toDouble(getSysInfo("(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory").split(" ")[0]) / (1024*1024*1024);
                    case "freeRam": return toDouble(getSysInfo("(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory").split(" ")[0]) / (1024*1024);
                    case "usedRam":
                        double total = toDouble(getSysInfo("(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory").split(" ")[0]) / (1024*1024*1024);
                        double free = toDouble(getSysInfo("(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory").split(" ")[0]) / (1024*1024);
                        return total - free;
                    case "ramSpeed": return toDouble(getSysInfo("(Get-CimInstance Win32_PhysicalMemory)[0].Speed").split(" ")[0]);

                    // 4. NHÓM MAINBOARD & BIOS
                    case "mainboard": return getSysInfo("(Get-CimInstance Win32_BaseBoard).Manufacturer + ' ' + (Get-CimInstance Win32_BaseBoard).Product");
                    case "biosVersion": return getSysInfo("(Get-CimInstance Win32_BIOS).SMBIOSBIOSVersion");
                    case "serialNumber": return getSysInfo("(Get-CimInstance Win32_BIOS).SerialNumber");
                    // 5. NHÓM OS
                    case "osName": return System.getProperty("os.name") + " " + System.getProperty("os.version");
                    case "osArch": return System.getProperty("os.arch");
                    case "userName": return System.getProperty("user.name");

                    // 6. NHÓM THỰC THI CONTROL
                    case "exit": System.exit((int) toDouble(args.get(0))); return null;
                    case "env": return HqlEnv.get(stringify(args.get(0)), args.size() > 1 ? stringify(args.get(1)) : null);
                    case "gc": System.gc(); return null;
                    case "exec":
                        Runtime.getRuntime().exec(stringify(args.get(0)).split(" "));
                        return null;
                    case "execRead":
                        Process process = Runtime.getRuntime().exec(stringify(args.get(0)).split(" "));
                        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                        StringBuilder output = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) output.append(line).append("\n");
                        return output.toString().trim();
                    // --- STORAGE: Kiểm tra "sức khỏe" SSD/HDD ---
                    case "diskName":
                        return getSysInfo("(Get-PhysicalDisk)[0].FriendlyName");
                    case "diskStatus":
                        return getSysInfo("(Get-PhysicalDisk)[0].HealthStatus");
                    case "diskTemp":
                        String tempRaw = getSysInfo("(Get-StorageReliabilityCounter -PhysicalDisk (Get-PhysicalDisk)[0]).Temperature");
                        // Bắt lỗi nếu Windows chặn quyền truy cập
                        if (tempRaw.contains("Access to a CIM resource") || tempRaw.startsWith("Lỗi")) {
                            return "N/A (Yêu cầu chạy bằng quyền Administrator)";
                        }
                        return tempRaw + "°C";

                    case "diskWear":
                        String wearRaw = getSysInfo("(Get-StorageReliabilityCounter -PhysicalDisk (Get-PhysicalDisk)[0]).Wear");
                        if (wearRaw.contains("Access to a CIM resource") || wearRaw.startsWith("Lỗi")) {
                            return "N/A (Yêu cầu chạy bằng quyền Administrator)";
                        }
                        String wearVal = wearRaw.split(" ")[0];
                        if (wearVal.equals("N/A") || wearVal.isEmpty()) return "N/A";

                        double health = 100 - toDouble(wearVal);
                        return health + "%";
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Hệ thống (hql.sys): " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.util")) {
            try {
                switch (name) {
                    // --- NHÓM 1: THUẬT TOÁN MẢNG ---
                    case "sort":
                        ArrayList<Object> listSort = (ArrayList<Object>) args.get(0);
                        // Sắp xếp thông minh: Ép về String để so sánh tránh lỗi khác kiểu dữ liệu
                        listSort.sort((a, b) -> stringify(a).compareTo(stringify(b)));
                        return listSort;
                    case "reverse":
                        ArrayList<Object> listRev = (ArrayList<Object>) args.get(0);
                        Collections.reverse(listRev);
                        return listRev;
                    case "shuffle":
                        ArrayList<Object> listShuf = (ArrayList<Object>) args.get(0);
                        Collections.shuffle(listShuf);
                        return listShuf;
                    case "unique":
                        return new ArrayList<>(new LinkedHashSet<>((ArrayList<?>) args.get(0)));
                    case "count":
                        return (double) Collections.frequency((ArrayList<?>) args.get(0), args.get(1));

                    // --- NHÓM 2: MÃ HÓA & BẢO MẬT ---
                    case "md5": return hashHelper(stringify(args.get(0)), "MD5");
                    case "sha256": return hashHelper(stringify(args.get(0)), "SHA-256");
                    case "base64Encode": return Base64.getEncoder().encodeToString(stringify(args.get(0)).getBytes(StandardCharsets.UTF_8));
                    case "base64Decode": return new String(Base64.getDecoder().decode(stringify(args.get(0))), StandardCharsets.UTF_8);
                    case "uuid": return UUID.randomUUID().toString();

                    // --- NHÓM 3: THỜI GIAN & TIỆN ÍCH ---
                    case "sleep":
                        Thread.sleep((long) toDouble(args.get(0)));
                        return null;
                    case "range":
                        double start = toDouble(args.get(0));
                        double end = toDouble(args.get(1));
                        double step = args.size() > 2 ? toDouble(args.get(2)) : 1.0;
                        ArrayList<Object> rangeList = new ArrayList<>();
                        if (step > 0) {
                            for (double i = start; i < end; i += step) rangeList.add(i);
                        } else if (step < 0) {
                            for (double i = start; i > end; i += step) rangeList.add(i);
                        }
                        return rangeList;

                    // --- NHÓM 4: XỬ LÝ JSON ---
                    // jsonEscape("chuỗi "có" dấu nháy") -> chuỗi an toàn để nhét vào JSON
                    case "jsonEscape": {
                        String s = stringify(args.get(0));
                        StringBuilder sb = new StringBuilder(s.length() + 16);
                        for (int i = 0; i < s.length(); i++) {
                            char c = s.charAt(i);
                            switch (c) {
                                case '"':  sb.append("\\\""); break;
                                case '\\': sb.append("\\\\"); break;
                                case '\n': sb.append("\\n"); break;
                                case '\r': sb.append("\\r"); break;
                                case '\t': sb.append("\\t"); break;
                                case '\b': sb.append("\\b"); break;
                                case '\f': sb.append("\\f"); break;
                                case '/':  sb.append("\\/"); break;
                                default:
                                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                                    else sb.append(c);
                            }
                        }
                        return sb.toString();
                    }
                    case "toJson": return toJsonHelper(args.get(0));
                    case "fromJson":
                        // Thuật toán parse JSON mảng cơ bản (Cắt bỏ ngoặc vuông, chia theo dấu phẩy)
                        String json = stringify(args.get(0)).trim();
                        if (json.startsWith("[") && json.endsWith("]")) {
                            String inner = json.substring(1, json.length() - 1).trim();
                            if (inner.isEmpty()) return new ArrayList<>();
                            // Lưu ý: Thuật toán đơn giản này sẽ chia theo dấu phẩy,
                            // nếu bên trong có chứa chuỗi chứa dấu phẩy sẽ cần nâng cấp sau.
                            return new ArrayList<>(Arrays.asList(inner.split("\\s*,\\s*")));
                        }
                        return json;
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Thư viện (hql.util): " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.ui")) {
            try {
                switch (name) {
                    // --- NHÓM 1: QUICK DIALOGS ---
                    case "alert": {
                        String aTitle = args.size() > 1 ? stringify(args.get(0)) : "Thông báo";
                        String aMsg = args.size() > 1 ? stringify(args.get(1)) : stringify(args.get(0));
                        JOptionPane.showMessageDialog(null, aMsg, aTitle, JOptionPane.INFORMATION_MESSAGE);
                        return null;
                    }
                    case "confirm": {
                        String cTitle = args.size() > 1 ? stringify(args.get(0)) : "Xác nhận";
                        String cMsg = args.size() > 1 ? stringify(args.get(1)) : stringify(args.get(0));
                        return JOptionPane.showConfirmDialog(null, cMsg, cTitle, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
                    }
                    case "prompt": {
                        String pTitle = args.size() > 1 ? stringify(args.get(0)) : "Nhập liệu";
                        String pMsg = args.size() > 1 ? stringify(args.get(1)) : stringify(args.get(0));
                        String result = JOptionPane.showInputDialog(null, pMsg, pTitle, JOptionPane.QUESTION_MESSAGE);
                        return result != null ? result : "";
                    }
                    case "chooseFile": {
                        JFileChooser fileChooser = new JFileChooser();
                        fileChooser.setDialogTitle("Chọn tệp tin");
                        return fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? fileChooser.getSelectedFile().getAbsolutePath() : "";
                    }
                    case "chooseColor": {
                        Color color = JColorChooser.showDialog(null, "Chọn màu sắc", Color.WHITE);
                        return color != null ? String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()) : "";
                    }

                    // --- NHÓM 2: WINDOW & LAYOUT ---
                    case "window": {
                        if (hqlWindow != null) hqlWindow.dispose();
                        hqlWindow = new JFrame(stringify(args.get(0)));
                        hqlWindow.setSize((int)toDouble(args.get(1)), (int)toDouble(args.get(2)));
                        hqlWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                        hqlWindow.setLocationRelativeTo(null);
                        hqlPanel = new JPanel();
                        hqlWindow.add(hqlPanel);
                        return null;
                    }
                    case "setLayout": {
                        if (hqlPanel == null) return null;
                        String layoutType = stringify(args.get(0)).toLowerCase();
                        if (layoutType.equals("grid")) hqlPanel.setLayout(new GridLayout(0, 1, 5, 5));
                        else hqlPanel.setLayout(new FlowLayout());
                        return null;
                    }
                    case "show": { if (hqlWindow != null) hqlWindow.setVisible(true); return null; }
                    case "hide": { if (hqlWindow != null) hqlWindow.setVisible(false); return null; }
                    case "close": { if (hqlWindow != null) hqlWindow.dispose(); return null; }

                    // --- NHÓM 3: WIDGETS & EVENTS ---
                    case "label": {
                        if (hqlPanel != null) {
                            String id = args.size() > 1 ? stringify(args.get(0)) : stringify(args.get(0));
                            String text = args.size() > 1 ? stringify(args.get(1)) : stringify(args.get(0));
                            JLabel lbl = new JLabel(text);
                            hqlWidgets.put(id, lbl);
                            hqlPanel.add(lbl);
                            return id; // Trả về ID để dùng cho các hàm lồng nhau
                        }
                        return null;
                    }
                    case "button": {
                        if (hqlPanel != null) {
                            String id = args.size() > 2 ? stringify(args.get(0)) : stringify(args.get(0));
                            String text = args.size() > 2 ? stringify(args.get(1)) : stringify(args.get(0));
                            String actionFuncName = args.size() > 2 ? stringify(args.get(2)) : (args.size() > 1 ? stringify(args.get(1)) : "");

                            JButton btn = new JButton(text);
                            hqlWidgets.put(id, btn);
                            if (!actionFuncName.isEmpty()) {
                                btn.addActionListener(e -> {
                                    try { executeFunctionCall(actionFuncName, new ArrayList<>()); }
                                    catch (Exception ex) { JOptionPane.showMessageDialog(hqlWindow, "Lỗi HQL Action: " + ex.getMessage()); }
                                });
                            }
                            hqlPanel.add(btn);
                            return id;
                        }
                        return null;
                    }
                    case "textField": {
                        if (hqlPanel != null) {
                            String id = stringify(args.get(0));
                            JTextField txt = new JTextField(20);
                            hqlWidgets.put(id, txt);
                            hqlPanel.add(txt);
                            return id;
                        }
                        return null;
                    }
                    case "textArea": {
                        if (hqlPanel != null) {
                            String id = stringify(args.get(0));
                            JTextArea txtArea = new JTextArea(5, 20);
                            txtArea.setLineWrap(true);
                            txtArea.setWrapStyleWord(true);
                            hqlWidgets.put(id, txtArea);
                            hqlPanel.add(new JScrollPane(txtArea));
                            return id;
                        }
                        return null;
                    }
                    case "image": {
                        if (hqlPanel != null) {
                            try {
                                String id = args.size() > 1 ? stringify(args.get(0)) : stringify(args.get(0));
                                String path = args.size() > 1 ? stringify(args.get(1)) : stringify(args.get(0));
                                JLabel imgLabel = new JLabel(new ImageIcon(path));
                                hqlWidgets.put(id, imgLabel);
                                hqlPanel.add(imgLabel);
                                return id;
                            } catch (Exception ex) { System.err.println("Lỗi tải ảnh"); }
                        }
                        return null;
                    }
                    case "checkbox": {
                        if (hqlPanel != null) {
                            String id = stringify(args.get(0));
                            String text = args.size() > 1 ? stringify(args.get(1)) : stringify(args.get(0));
                            JCheckBox chk = new JCheckBox(text);
                            hqlWidgets.put(id, chk);
                            hqlPanel.add(chk);
                            return id;
                        }
                        return null;
                    }
                    case "radioButton": {
                        if (hqlPanel != null) {
                            String id = stringify(args.get(0));
                            String group = args.size() > 1 ? stringify(args.get(1)) : "default";
                            String text = args.size() > 2 ? stringify(args.get(2)) : stringify(args.get(0));
                            JRadioButton rb = new JRadioButton(text);
                            hqlRadioGroups.computeIfAbsent(group, k -> new ButtonGroup()).add(rb);
                            hqlWidgets.put(id, rb);
                            hqlPanel.add(rb);
                            return id;
                        }
                        return null;
                    }
                    case "comboBox": {
                        if (hqlPanel != null) {
                            String id = args.size() > 1 ? stringify(args.get(0)) : "cb_" + System.currentTimeMillis();
                            ArrayList<?> list = (ArrayList<?>) (args.size() > 1 ? args.get(1) : args.get(0));
                            String[] items = list.stream().map(this::stringify).toArray(String[]::new);
                            JComboBox<String> cb = new JComboBox<>(items);
                            hqlWidgets.put(id, cb);
                            hqlPanel.add(cb);
                            return id;
                        }
                        return null;
                    }
                    case "onHover": {
                        JComponent hoverComp = hqlWidgets.get(stringify(args.get(0)));
                        String hoverFunc = stringify(args.get(1));
                        if (hoverComp != null) {
                            hoverComp.addMouseListener(new java.awt.event.MouseAdapter() {
                                @Override
                                public void mouseEntered(java.awt.event.MouseEvent e) {
                                    try { executeFunctionCall(hoverFunc, new ArrayList<>()); } catch (Exception ex) {}
                                }
                            });
                        }
                        return null;
                    }
                    case "onKeyPress": {
                        JComponent keyComp = hqlWidgets.get(stringify(args.get(0)));
                        String keyFunc = stringify(args.get(1));
                        if (keyComp != null) {
                            keyComp.addKeyListener(new java.awt.event.KeyAdapter() {
                                @Override
                                public void keyPressed(java.awt.event.KeyEvent e) {
                                    try { executeFunctionCall(keyFunc, new ArrayList<>()); } catch (Exception ex) {}
                                }
                            });
                        }
                        return null;
                    }
                    case "trayInit": {
                        return TrayManager.init(stringify(args.get(0)), stringify(args.get(1)), stringify(args.get(2)), stringify(args.get(3)), this);
                    }

                    // --- NHÓM 4: STYLE & DATA RETRIEVAL ---
                    case "getText": {
                        JComponent getComp = hqlWidgets.get(stringify(args.get(0)));
                        if (getComp instanceof JTextField) return ((JTextField)getComp).getText();
                        if (getComp instanceof JTextArea) return ((JTextArea)getComp).getText();
                        if (getComp instanceof JCheckBox) return ((JCheckBox)getComp).isSelected() ? "true" : "false";
                        if (getComp instanceof JRadioButton) return ((JRadioButton)getComp).isSelected() ? "true" : "false";
                        if (getComp instanceof JComboBox) {
                            Object sel = ((JComboBox<?>)getComp).getSelectedItem();
                            return sel != null ? sel.toString() : "";
                        }
                        return "";
                    }
                    case "setStyle": {
                        JComponent comp = hqlWidgets.get(stringify(args.get(0)));
                        String prop = "";
                        String val = "";

                        // Thuật toán bóc tách thông minh: Hỗ trợ cả 2 cách viết "color:red" hoặc ("color", "red")
                        if (args.size() == 2) {
                            String[] parts = stringify(args.get(1)).split(":", 2);
                            prop = parts[0].trim().toLowerCase();
                            if (parts.length > 1) val = parts[1].trim();
                        } else if (args.size() >= 3) {
                            prop = stringify(args.get(1)).toLowerCase();
                            val = stringify(args.get(2));
                        }

                        if (comp != null) {
                            try {
                                switch (prop) {
                                    case "color": comp.setForeground(Color.decode(val)); break;
                                    case "bg": comp.setBackground(Color.decode(val)); break;
                                    case "font_size": comp.setFont(new Font("SansSerif", Font.PLAIN, (int)toDouble(val))); break;
                                    case "text":
                                        if (comp instanceof JTextField) ((JTextField)comp).setText(val);
                                        else if (comp instanceof JTextArea) ((JTextArea)comp).setText(val);
                                        else if (comp instanceof JLabel) ((JLabel)comp).setText(val);
                                        else if (comp instanceof JButton) ((JButton)comp).setText(val);
                                        break;
                                    case "font_weight":
                                        if (val.equals("bold")) comp.setFont(comp.getFont().deriveFont(Font.BOLD));
                                        else if (val.equals("plain")) comp.setFont(comp.getFont().deriveFont(Font.PLAIN));
                                        break;
                                    case "padding":
                                        int p = (int)toDouble(val);
                                        comp.setBorder(BorderFactory.createEmptyBorder(p, p, p, p));
                                        break;
                                    case "border_radius":
                                        comp.setBorder(new javax.swing.border.LineBorder(Color.GRAY, 1, true));
                                        break;
                                }
                            } catch (Exception ignored) {}
                        }
                        return null;
                    }
                    case "table": {
                        if (hqlPanel != null) {
                            // Tự động cấp ID nếu sếp truyền thiếu
                            String id = args.size() > 2 ? stringify(args.get(0)) : "tbl_" + System.currentTimeMillis();
                            ArrayList<?> colData = (ArrayList<?>) (args.size() > 2 ? args.get(1) : args.get(0));
                            ArrayList<?> rowData = (ArrayList<?>) (args.size() > 2 ? args.get(2) : args.get(1));

                            String[] cols = colData.stream().map(this::stringify).toArray(String[]::new);
                            String[][] rows = new String[rowData.size()][cols.length];

                            for (int i = 0; i < rowData.size(); i++) {
                                ArrayList<?> r = (ArrayList<?>) rowData.get(i);
                                for (int j = 0; j < r.size() && j < cols.length; j++) {
                                    rows[i][j] = stringify(r.get(j));
                                }
                            }

                            JTable table = new JTable(rows, cols);
                            JScrollPane scrollPane = new JScrollPane(table);
                            scrollPane.setPreferredSize(new Dimension(400, 200));
                            hqlWidgets.put(id, table);
                            hqlPanel.add(scrollPane);
                            return id;
                        }
                        return null;
                    }
                    case "progressBar": {
                        if (hqlPanel != null) {
                            // Tự động cấp ID nếu sếp truyền thiếu
                            String id = args.size() > 1 ? stringify(args.get(0)) : "pb_" + System.currentTimeMillis();
                            int val = (int) toDouble(args.size() > 1 ? args.get(1) : args.get(0));

                            JProgressBar pb = new JProgressBar(0, 100);
                            pb.setValue(val);
                            pb.setStringPainted(true);
                            hqlWidgets.put(id, pb);
                            hqlPanel.add(pb);
                            return id;
                        }
                        return null;
                    }
                    case "setInterval": {
                        String funcName = stringify(args.get(0));
                        int delay = (int) toDouble(args.get(1));
                        javax.swing.Timer timer = new javax.swing.Timer(delay, e -> {
                            try { executeFunctionCall(funcName, new ArrayList<>()); }
                            catch (Exception ex) { System.err.println("Interval error: " + ex.getMessage()); }
                        });
                        timer.start();
                        return null; // Có thể mở rộng trả về timer ID để làm clearInterval sau
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Giao diện (hql.ui): " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.crypto")) {
            try {
                switch (name) {
                    // --- 1. NHÓM MÃ HÓA ĐỐI XỨNG (AES) ---
                    case "encrypt": {
                        String data = stringify(args.get(0));
                        String key = stringify(args.get(1));
                        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                        cipher.init(Cipher.ENCRYPT_MODE, getAESKey(key));
                        return Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
                    }
                    case "decrypt": {
                        // FIX: Dọn sạch mọi khoảng trắng, dấu xuống dòng dính từ File
                        String cipherText = stringify(args.get(0)).replaceAll("\\s+", "");
                        String key = stringify(args.get(1));
                        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                        cipher.init(Cipher.DECRYPT_MODE, getAESKey(key));
                        return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
                    }

                    // --- 2. NHÓM MÃ HÓA BẤT ĐỐI XỨNG (RSA 2048-bit) ---
                    case "generateKeys": {
                        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
                        keyGen.initialize(2048);
                        KeyPair pair = keyGen.generateKeyPair();
                        String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
                        String priv = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
                        // Trả về một mảng chứa [PublicKey, PrivateKey]
                        return new ArrayList<>(Arrays.asList(pub, priv));
                    }
                    case "sign": {
                        String data = stringify(args.get(0));
                        byte[] keyBytes = Base64.getDecoder().decode(stringify(args.get(1)));
                        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
                        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);

                        Signature sig = Signature.getInstance("SHA256withRSA");
                        sig.initSign(privateKey);
                        sig.update(data.getBytes(StandardCharsets.UTF_8));
                        return Base64.getEncoder().encodeToString(sig.sign());
                    }
                    case "verify": {
                        String data = stringify(args.get(0));
                        // FIX: Dọn sạch khoảng trắng cho Chữ ký và Public Key
                        byte[] sigBytes = Base64.getDecoder().decode(stringify(args.get(1)).replaceAll("\\s+", ""));
                        byte[] keyBytes = Base64.getDecoder().decode(stringify(args.get(2)).replaceAll("\\s+", ""));
                        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
                        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(spec);

                        Signature sig = Signature.getInstance("SHA256withRSA");
                        sig.initVerify(publicKey);
                        sig.update(data.getBytes(StandardCharsets.UTF_8));
                        return sig.verify(sigBytes);
                    }

                    // --- 3. NHÓM TIỆN ÍCH BẢO MẬT ---
                    case "secureRandom": {
                        int length = (int) toDouble(args.get(0));
                        SecureRandom random = new SecureRandom();
                        byte[] bytes = new byte[length];
                        random.nextBytes(bytes);

                        // Chuyển sang dạng mã Hex để dùng làm mã OTP, Token,...
                        StringBuilder sb = new StringBuilder();
                        for (byte b : bytes) sb.append(String.format("%02x", b));
                        return sb.toString();
                    }
                    case "pbkdf2": {
                        String password = stringify(args.get(0));
                        String salt = stringify(args.get(1));
                        // Hàm băm mật khẩu chuyên dụng với 65536 vòng lặp chống Brute-force
                        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), 65536, 256);
                        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                        byte[] hash = f.generateSecret(spec).getEncoded();
                        return Base64.getEncoder().encodeToString(hash);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Bảo mật (hql.crypto): " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.database")) {
            try {
                switch (name) {
                    // --- 1. QUẢN LÝ KẾT NỐI (bể kết nối, an toàn khi nhiều luồng) ---
                    case "connect": {
                        dbDongBe();                      // đóng bể cũ trước khi mở bể mới (tránh rò rỉ kết nối)
                        lastDbError = "";
                        try {
                            dbMoBe(resolveDbTarget(args));
                            return true;
                        } catch (Exception e) {
                            lastDbError = e.getMessage() == null ? e.toString() : e.getMessage();
                            // Không văng lỗi: trả về false để script tự xử lý bằng if/else,
                            // muốn xem chi tiết lỗi thì gọi dbError().
                            System.err.println("[HQL.DATABASE] Kết nối thất bại: " + lastDbError);
                            return false;
                        }
                    }
                    case "connectEnv": {
                        // Nối theo tệp .henv: DB_URL, hoặc DB_TYPE / DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD
                        dbDongBe();
                        lastDbError = "";
                        try {
                            dbMoBe(dbTargetTuHenv());
                            return true;
                        } catch (Exception e) {
                            lastDbError = e.getMessage() == null ? e.toString() : e.getMessage();
                            System.err.println("[HQL.DATABASE] Kết nối theo .henv thất bại: " + lastDbError);
                            return false;
                        }
                    }
                    case "dbPoolSize": {
                        // dbPoolSize(toiDa [, giuSan]) — gọi TRƯỚC connect(); mặc định 10 / 2
                        dbPoolMax = (int) toDouble(args.get(0));
                        if (args.size() > 1) dbPoolMin = (int) toDouble(args.get(1));
                        return true;
                    }
                    case "dbPoolStats": {
                        HqlDbPool p = dbPool;
                        return p == null ? "Chưa kết nối" : p.stats();
                    }
                    case "dbError": return lastDbError;
                    case "closeDB": { dbHuyGiaoDichBoQuen(); dbDongBe(); return null; }
                    case "isOpen": { HqlDbPool p = dbPool; return p != null && !p.isClosed(); }

                    // --- 2. TRUY VẤN DỮ LIỆU ---
                    case "query":
                    case "querySafe": {
                        final String dbSql = stringify(args.get(0));
                        final Object dbPrm = args.size() > 1 ? args.get(1) : null;
                        return this.<Object>dbRun(dbCon -> {
                            try (PreparedStatement pstmt = dbCon.prepareStatement(dbSql)) {
                                bindSqlParams(pstmt, dbPrm);
                                try (ResultSet rs = pstmt.executeQuery()) {
                                    ArrayList<Object> rows = new ArrayList<>();
                                    while (rs.next()) rows.add(readSqlRow(rs));
                                    return rows;
                                }
                            }
                        });
                    }
                    case "queryValue": {
                        // Lấy nhanh 1 ô dữ liệu: queryValue("SELECT COUNT(*) FROM Customers")
                        final String dbSql = stringify(args.get(0));
                        final Object dbPrm = args.size() > 1 ? args.get(1) : null;
                        return this.<Object>dbRun(dbCon -> {
                            try (PreparedStatement pstmt = dbCon.prepareStatement(dbSql)) {
                                bindSqlParams(pstmt, dbPrm);
                                try (ResultSet rs = pstmt.executeQuery()) {
                                    return rs.next() ? sqlToHql(rs.getObject(1)) : null;
                                }
                            }
                        });
                    }
                    case "fetchOne": {
                        final String dbSql = stringify(args.get(0));
                        final Object dbPrm = args.size() > 1 ? args.get(1) : null;
                        return this.<Object>dbRun(dbCon -> {
                            try (PreparedStatement pstmt = dbCon.prepareStatement(dbSql)) {
                                bindSqlParams(pstmt, dbPrm);
                                try (ResultSet rs = pstmt.executeQuery()) {
                                    return rs.next() ? readSqlRow(rs) : new LinkedHashMap<String, Object>();
                                }
                            }
                        });
                    }

                    // --- 3. THAO TÁC DỮ LIỆU ---
                    case "execute":
                    case "executeSafe": {
                        final String dbSql = stringify(args.get(0));
                        final Object dbPrm = args.size() > 1 ? args.get(1) : null;
                        final boolean laInsert = dbSql.stripLeading().regionMatches(true, 0, "insert", 0, 6);
                        return this.<Object>dbRun(dbCon -> {
                            // INSERT: xin luôn khoá tự tăng để identity() đúng dù mỗi lệnh chạy trên một kết nối khác nhau của bể
                            try (PreparedStatement pstmt = laInsert
                                    ? dbCon.prepareStatement(dbSql, Statement.RETURN_GENERATED_KEYS)
                                    : dbCon.prepareStatement(dbSql)) {
                                bindSqlParams(pstmt, dbPrm);
                                int soDong = pstmt.executeUpdate();
                                if (laInsert) dbGhiNhoKhoaVuaSinh(pstmt);
                                return (double) soDong;
                            }
                        });
                    }
                    case "executeBatch": {
                        // executeBatch("INSERT ... VALUES (?, ?)", [[1,"a"],[2,"b"],[3,"c"]]) — cả lô trong 1 giao dịch, nhanh gấp nhiều lần
                        final String dbSql = stringify(args.get(0));
                        if (args.size() < 2 || !(args.get(1) instanceof List)) {
                            throw new SQLException("executeBatch(sql, [[tham so dong 1], [tham so dong 2], ...]) cần danh sách các dòng tham số.");
                        }
                        final List<?> dbRows = (List<?>) args.get(1);
                        return this.<Object>dbRun(dbCon -> {
                            boolean tuQuanLy = dbCon.getAutoCommit();      // đang trong beginTransaction thì để nguyên
                            if (tuQuanLy) dbCon.setAutoCommit(false);
                            try (PreparedStatement pstmt = dbCon.prepareStatement(dbSql)) {
                                for (Object dong : dbRows) { bindSqlParams(pstmt, dong); pstmt.addBatch(); }
                                int[] kq = pstmt.executeBatch();
                                if (tuQuanLy) dbCon.commit();
                                double tong = 0;
                                for (int x : kq) if (x > 0) tong += x;     // SUCCESS_NO_INFO (-2) không đếm được
                                return tong;
                            } catch (SQLException ex) {
                                if (tuQuanLy) { try { dbCon.rollback(); } catch (SQLException ignore) { } }
                                throw ex;
                            }
                        });
                    }
                    case "identity": {
                        Double ghiNho = dbLastIdentity.get();
                        if (ghiNho != null) return ghiNho;
                        if (!dbSanSang()) return 0.0;
                        return this.<Object>dbRun(dbCon -> {
                            // Dự phòng khi đang trong giao dịch mà lệnh INSERT không trả khoá (SCOPE_IDENTITY chuẩn hơn @@IDENTITY)
                            for (String sqlId : new String[]{"SELECT SCOPE_IDENTITY()", "SELECT @@IDENTITY", "SELECT LAST_INSERT_ID()"}) {
                                try (Statement st = dbCon.createStatement(); ResultSet rs = st.executeQuery(sqlId)) {
                                    if (rs.next()) {
                                        Object v = rs.getObject(1);
                                        if (v != null) return toDouble(sqlToHql(v));
                                    }
                                } catch (SQLException ignore) { /* Thử tiếp cú pháp của hệ CSDL khác */ }
                            }
                            return 0.0;
                        });
                    }

                    // --- 4. THỦ TỤC (STORED PROCEDURE) ---
                    case "procedure": {
                        final String procName = stringify(args.get(0));
                        final List<?> params = (args.size() > 1 && args.get(1) instanceof List) ? (List<?>) args.get(1) : new ArrayList<>();
                        final StringBuilder sqlProc = new StringBuilder("{call " + procName + "(");
                        for (int i = 0; i < params.size(); i++) sqlProc.append(i == 0 ? "?" : ",?");
                        sqlProc.append(")}");
                        return this.<Object>dbRun(dbCon -> {
                            try (CallableStatement cstmt = dbCon.prepareCall(sqlProc.toString())) {
                                bindSqlParams(cstmt, params);
                                if (!cstmt.execute()) return true;
                                // Thủ tục trả về bảng thì trả luôn danh sách dòng cho tiện dùng
                                try (ResultSet rs = cstmt.getResultSet()) {
                                    ArrayList<Object> rows = new ArrayList<>();
                                    while (rs.next()) rows.add(readSqlRow(rs));
                                    return rows;
                                }
                            }
                        });
                    }

                    // --- 5. GIAO DỊCH (mỗi luồng một giao dịch riêng, giữ 1 kết nối riêng) ---
                    case "beginTransaction": {
                        if (dbTx.get() != null) return null;          // đã trong giao dịch rồi
                        requireDb();
                        HqlDbPool p = dbPool;
                        if (p == null) throw new SQLException("Kết nối Database vừa bị đóng.");
                        Connection txCon = p.borrow();
                        try {
                            txCon.setAutoCommit(false);
                        } catch (SQLException ex) {
                            try { txCon.close(); } catch (Exception ignore) { }
                            throw ex;
                        }
                        dbTx.set(txCon);
                        return null;
                    }
                    case "commit": {
                        Connection txCon = dbTx.get();
                        if (txCon == null) return null;
                        dbTx.remove();
                        try { txCon.commit(); txCon.setAutoCommit(true); } finally { txCon.close(); }    // close = trả kết nối về bể
                        return null;
                    }
                    case "rollback": {
                        Connection txCon = dbTx.get();
                        if (txCon == null) return null;
                        dbTx.remove();
                        try { txCon.rollback(); txCon.setAutoCommit(true); } finally { txCon.close(); }
                        return null;
                    }

                    // --- 6. KHÁM PHÁ HỆ THỐNG & XUẤT CSV ---
                    case "tables": {
                        if (!dbSanSang()) return new ArrayList<>();
                        return this.<Object>dbRun(dbCon -> {
                            ArrayList<String> tableList = new ArrayList<>();
                            // Chỉ lấy bảng của ĐÚNG database đang kết nối, bỏ qua bảng hệ thống
                            try (ResultSet rs = dbCon.getMetaData().getTables(dbCon.getCatalog(), null, "%", new String[]{"TABLE"})) {
                                while (rs.next()) {
                                    String schema = rs.getString("TABLE_SCHEM");
                                    if (schema != null && (schema.equalsIgnoreCase("sys") || schema.equalsIgnoreCase("INFORMATION_SCHEMA"))) continue;
                                    tableList.add(rs.getString("TABLE_NAME"));
                                }
                            }
                            return tableList;
                        });
                    }
                    case "columns": {
                        if (!dbSanSang()) return new ArrayList<>();
                        final String tableName = stringify(args.get(0));
                        return this.<Object>dbRun(dbCon -> {
                            DatabaseMetaData md = dbCon.getMetaData();
                            // Thử lần lượt: đúng chữ, viết HOA, viết thường (mỗi hệ CSDL lưu tên bảng một kiểu)
                            for (String probe : new String[]{tableName, tableName.toUpperCase(), tableName.toLowerCase()}) {
                                ArrayList<String> colList = new ArrayList<>();
                                try (ResultSet rs = md.getColumns(dbCon.getCatalog(), null, probe, null)) {
                                    while (rs.next()) {
                                        colList.add(rs.getString("COLUMN_NAME") + " (" + rs.getString("TYPE_NAME") + ")");
                                    }
                                }
                                if (!colList.isEmpty()) return colList;
                            }
                            return new ArrayList<String>();
                        });
                    }
                    case "exportCSV": {
                        final String sql = stringify(args.get(0));
                        final String path = stringify(args.get(1));
                        return this.<Object>dbRun(dbCon -> {
                            try (Statement st = dbCon.createStatement()) {
                                st.setFetchSize(1000);                      // đọc từng lô, bảng lớn không phình bộ nhớ
                                try (ResultSet rs = st.executeQuery(sql);
                                     PrintWriter writer = new PrintWriter(new BufferedWriter(
                                             new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8), 65536))) {
                                    writer.write('\ufeff'); // BOM cho Excel
                                    ResultSetMetaData meta = rs.getMetaData();
                                    int colCount = meta.getColumnCount();
                                    for (int i = 1; i <= colCount; i++) {
                                        writer.print(csvQuote(meta.getColumnLabel(i)) + (i < colCount ? "," : ""));
                                    }
                                    writer.print("\r\n");
                                    while (rs.next()) {
                                        for (int i = 1; i <= colCount; i++) {
                                            Object val = rs.getObject(i);
                                            // Bọc nháy đúng chuẩn RFC 4180 thay vì xóa dấu phẩy làm hỏng dữ liệu
                                            writer.print(csvQuote(val == null ? "" : stringify(sqlToHql(val))) + (i < colCount ? "," : ""));
                                        }
                                        writer.print("\r\n");
                                    }
                                    return true;
                                }
                            } catch (IOException ioe) {
                                throw new SQLException("Không ghi được file CSV: " + ioe.getMessage(), ioe);
                            }
                        });
                    }
                }
            } catch (SQLException e) {
                lastDbError = e.getMessage() == null ? e.toString() : e.getMessage();
                throw new RuntimeException("Lỗi Database: " + lastDbError);
            }
        }
        if (usedLibraries.contains("hql.time")) {
            try {
                switch (name) {
                    case "now": return LocalDateTime.now().toString();
                    case "today": return LocalDate.now().toString();
                    case "currentTime": return LocalTime.now().toString();

                    case "format": {
                        LocalDateTime dt = parseHqlDate(args.get(0));
                        String pattern = stringify(args.get(1));
                        return dt.format(DateTimeFormatter.ofPattern(pattern));
                    }
                    case "parse": {
                        String dateStr = stringify(args.get(0));
                        String pattern = stringify(args.get(1));
                        try {
                            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
                            try {
                                // Thử parse đầy đủ Ngày + Giờ
                                return LocalDateTime.parse(dateStr, fmt).toString();
                            } catch (Exception ex) {
                                // Nếu chuỗi bị thiếu Giờ, tự động đắp thêm 00:00:00 vào cuối
                                return LocalDate.parse(dateStr, fmt).atStartOfDay().toString();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Lỗi định dạng parse: " + e.getMessage());
                        }
                    }
                    case "addMinutes": {
                        // Lấy thời gian từ tham số 0, cộng thêm số phút từ tham số 1
                        LocalDateTime dt = parseHqlDate(args.get(0));
                        double minutes = toDouble(args.get(1));
                        return dt.plusMinutes((long) minutes).toString();
                    }
                    case "addDays": {
                        LocalDateTime dt = parseHqlDate(args.get(0));
                        return dt.plusDays((long)toDouble(args.get(1))).toString();
                    }
                    case "addMonths": {
                        LocalDateTime dt = parseHqlDate(args.get(0));
                        return dt.plusMonths((long)toDouble(args.get(1))).toString();
                    }
                    case "addYears": {
                        LocalDateTime dt = parseHqlDate(args.get(0));
                        return dt.plusYears((long)toDouble(args.get(1))).toString();
                    }
                    case "daysBetween": {
                        LocalDate start = parseHqlDate(args.get(0)).toLocalDate();
                        LocalDate end = parseHqlDate(args.get(1)).toLocalDate();
                        return (double) ChronoUnit.DAYS.between(start, end);
                    }
                    case "getYear": return (double) parseHqlDate(args.get(0)).getYear();
                    case "getMonth": return (double) parseHqlDate(args.get(0)).getMonthValue();
                    case "getDay": return (double) parseHqlDate(args.get(0)).getDayOfMonth();
                    case "getWeekday": {
                        // Trả về 1 (Thứ 2) -> 7 (Chủ Nhật) theo chuẩn ISO
                        return (double) parseHqlDate(args.get(0)).getDayOfWeek().getValue();
                    }
                    case "age": {
                        LocalDate birth = parseHqlDate(args.get(0)).toLocalDate();
                        return (double) Period.between(birth, LocalDate.now()).getYears();
                    }
                    case "isWeekend": {
                        DayOfWeek dw = parseHqlDate(args.get(0)).getDayOfWeek();
                        return dw == DayOfWeek.SATURDAY || dw == DayOfWeek.SUNDAY;
                    }
                    case "isPast": return parseHqlDate(args.get(0)).isBefore(LocalDateTime.now());

                    case "countdown": {
                        // Lấy thời gian đích
                        LocalDateTime target = parseHqlDate(args.get(0));

                        while (LocalDateTime.now().isBefore(target)) {
                            Duration d = Duration.between(LocalDateTime.now(), target);

                            // \r là lệnh đưa con trỏ về đầu dòng để ghi đè số mới lên số cũ
                            System.out.printf("\r%02d:%02d:%02d ",
                                    d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());

                            // Ép Java phải in ra ngay lập tức, không được đợi
                            System.out.flush();

                            try {
                                Thread.sleep(1000); // Nghỉ 1 giây để tạo hiệu ứng tích tắc
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        return "Success"; // Trả về chuỗi để sếp in ra không bị null
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Thời gian (hql.time): " + e.getMessage());
            }
        }
        if (usedLibraries.contains("hql.regex")) {
            try {
                switch (name) {
                    // --- 2 HÀM MỚI TỐI ƯU ---
                    case "compile": return Pattern.compile(stringify(args.get(0)));
                    case "quote": return Pattern.quote(stringify(args.get(0)));

                    case "matches": {
                        // Kỹ thuật siêu tốc: Kiểm tra nếu đã là Pattern object thì dùng luôn
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        return p.matcher(stringify(args.get(0))).matches();
                    }
                    case "containsRegex": {
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        return p.matcher(stringify(args.get(0))).find();
                    }
                    // --- HÀM MỚI: Trả về vị trí tìm thấy ---
                    case "findRegex": {
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        Matcher m = p.matcher(stringify(args.get(0)));
                        return m.find() ? (double) m.start() : -1.0;
                    }
                    case "findAll": {
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        Matcher m = p.matcher(stringify(args.get(0)));
                        ArrayList<String> res = new ArrayList<>();
                        while (m.find()) res.add(m.group());
                        return res;
                    }
                    case "replaceRegex": {
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        return p.matcher(stringify(args.get(0))).replaceAll(stringify(args.get(2)));
                    }
                    case "replaceFirst": {
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        return p.matcher(stringify(args.get(0))).replaceFirst(stringify(args.get(2)));
                    }
                    case "splitRegex": {
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        return new ArrayList<>(Arrays.asList(p.split(stringify(args.get(0)))));
                    }
                    // --- 2 HÀM MỚI: Trích xuất Group ---
                    case "group": {
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        Matcher m = p.matcher(stringify(args.get(0)));
                        int idx = (int) toDouble(args.get(2));
                        if (m.find() && idx <= m.groupCount()) return m.group(idx);
                        return "";
                    }
                    case "groups": {
                        Pattern p = args.get(1) instanceof Pattern ? (Pattern) args.get(1) : Pattern.compile(stringify(args.get(1)));
                        Matcher m = p.matcher(stringify(args.get(0)));
                        ArrayList<String> res = new ArrayList<>();
                        if (m.find()) {
                            for (int i = 1; i <= m.groupCount(); i++) res.add(m.group(i));
                        }
                        return res;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Thư viện (hql.regex): " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.game")) {
            Object ketQuaGame = thuVienGame(name, args);
            if (ketQuaGame != CHUA_XU_LY) return ketQuaGame;
        }
        if (usedLibraries.contains("hql.app")) {
            Object ketQuaApp = thuVienApp(name, args);
            if (ketQuaApp != CHUA_XU_LY) return ketQuaApp;
        }
        if (usedLibraries.contains("hql.pc")) {
            try {
                java.awt.Robot bot = getRobot();
                switch (name) {
                    case "pos": {
                        Point p = MouseInfo.getPointerInfo().getLocation();
                        return new ArrayList<>(Arrays.asList((double)p.x, (double)p.y));
                    }
                    case "scroll": {
                        bot.mouseWheel((int)toDouble(args.get(0)));
                        return null;
                    }
                    case "press": {
                        bot.keyPress(getKeyCode(stringify(args.get(0))));
                        return null;
                    }
                    case "release": {
                        bot.keyRelease(getKeyCode(stringify(args.get(0))));
                        return null;
                    }
                    case "combo": {
                        int k1 = getKeyCode(stringify(args.get(0)));
                        int k2 = getKeyCode(stringify(args.get(1)));
                        bot.keyPress(k1); bot.keyPress(k2);
                        bot.keyRelease(k2); bot.keyRelease(k1);
                        return null;
                    }
                    case "getClip": {
                        // Lưu lại kênh báo lỗi gốc của hệ thống
                        java.io.PrintStream originalErr = System.err;
                        try {
                            // Tạm thời "bịt miệng" luồng in lỗi (giấu các dòng đỏ của Java AWT/IntelliJ)
                            System.setErr(new java.io.PrintStream(new java.io.OutputStream() {
                                public void write(int b) {} // Không làm gì cả -> Nuốt trọn cảnh báo
                            }));

                            // Lấy dữ liệu từ Clipboard như bình thường
                            java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                            Object data = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);

                            // Lấy xong rồi thì "mở miệng" trả lại kênh báo lỗi như cũ
                            System.setErr(originalErr);
                            return data;
                        } catch (Exception e) {
                            // Kể cả có lỗi thực sự thì cũng phải nhớ mở lại System.err
                            System.setErr(originalErr);
                            return null;
                        }
                    }
                    case "setClip": {
                        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(stringify(args.get(0)));
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                        return null;
                    }
                    case "findPixel": { // ĐÃ ĐỔI TÊN Ở ĐÂY
                        Color target = parseColor(stringify(args.get(0)));
                        int startX = (int)toDouble(args.get(1));
                        int startY = (int)toDouble(args.get(2));
                        int w = (int)toDouble(args.get(3));
                        int h = (int)toDouble(args.get(4));

                        BufferedImage screen = bot.createScreenCapture(new Rectangle(startX, startY, w, h));
                        int targetRGB = target.getRGB();

                        for (int x = 0; x < w; x++) {
                            for (int y = 0; y < h; y++) {
                                if (screen.getRGB(x, y) == targetRGB) {
                                    return new ArrayList<>(Arrays.asList((double)(startX + x), (double)(startY + y)));
                                }
                            }
                        }
                        return new ArrayList<>(Arrays.asList(-1.0, -1.0));
                    }
                    default: break; // Chặn mọi trường hợp tên lạ rơi vào đây
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Tự động hóa (hql.pc): " + e.getMessage(), e);
            }
            // Sau khi chạy xong switch-case của PC, chúng ta nên return hoặc ném ra ngoài để không lọt xuống thư viện khác
        }
        if (usedLibraries.contains("hql.logging")) {
            switch (name) {
                case "logInfo": printLog("info", stringify(args.get(0))); return null;
                case "logWarning": printLog("warning", stringify(args.get(0))); return null;
                case "logError": printLog("error", stringify(args.get(0))); return null;
                case "logDebug": printLog("debug", stringify(args.get(0))); return null;
                case "setLogLevel": loggingLevel = stringify(args.get(0)).toLowerCase(); return null;
                case "setLogFile": loggingFilePath = stringify(args.get(0)); return null;
                case "logReset":
                    loggingLevel = "info";
                    loggingFilePath = null;
                    return null;
            }
        }
        if (usedLibraries.contains("hql.image")) {
            try {
                switch (name) {
                    case "loadImage": {
                        String path = stringify(args.get(0));
                        BufferedImage img = ImageIO.read(new File(path));
                        if (img == null) throw new RuntimeException("Không thể đọc ảnh: " + path);
                        String id = "@img" + (++imageIdCounter);
                        imageLibrary.put(id, img);
                        return id;
                    }
                    case "saveImage": {
                        String id = stringify(args.get(0));
                        String format = stringify(args.get(1));
                        String path = stringify(args.get(2));
                        BufferedImage img = imageLibrary.get(id);
                        if (img == null) throw new RuntimeException("Không tìm thấy ảnh với id: " + id);
                        ImageIO.write(img, format, new File(path));
                        return null;
                    }
                    case "resizeImage": {
                        String id = stringify(args.get(0));
                        int w = (int) toDouble(args.get(1));
                        int h = (int) toDouble(args.get(2));
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        Image scaled = src.getScaledInstance(w, h, Image.SCALE_SMOOTH);
                        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g2d = resized.createGraphics();
                        g2d.drawImage(scaled, 0, 0, null);
                        g2d.dispose();
                        imageLibrary.put(id, resized);
                        return null;
                    }
                    case "cropImage": {
                        String id = stringify(args.get(0));
                        int x = (int) toDouble(args.get(1));
                        int y = (int) toDouble(args.get(2));
                        int w = (int) toDouble(args.get(3));
                        int h = (int) toDouble(args.get(4));
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        BufferedImage cropped = src.getSubimage(x, y, w, h);
                        imageLibrary.put(id, cropped); // Cập nhật thẳng bằng vùng cắt
                        return null;
                    }
                    case "rotateImage": {
                        String id = stringify(args.get(0));
                        double angle = toDouble(args.get(1));
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        double rad = Math.toRadians(angle);
                        int w = src.getWidth();
                        int h = src.getHeight();
                        int newW = (int) Math.abs(w * Math.cos(rad)) + (int) Math.abs(h * Math.sin(rad));
                        int newH = (int) Math.abs(h * Math.cos(rad)) + (int) Math.abs(w * Math.sin(rad));
                        BufferedImage rotated = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g2d = rotated.createGraphics();
                        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2d.translate(newW / 2, newH / 2);
                        g2d.rotate(rad);
                        g2d.drawImage(src, -w / 2, -h / 2, null);
                        g2d.dispose();
                        imageLibrary.put(id, rotated);
                        return null;
                    }
                    case "flipImage": {
                        String id = stringify(args.get(0));
                        String dir = stringify(args.get(1)).toLowerCase();
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        AffineTransform tx = new AffineTransform();
                        if (dir.equals("h")) {
                            tx.scale(-1, 1);
                            tx.translate(-src.getWidth(), 0);
                        } else if (dir.equals("v")) {
                            tx.scale(1, -1);
                            tx.translate(0, -src.getHeight());
                        } else throw new RuntimeException("Hướng lật không hợp lệ (hỗ trợ 'h' hoặc 'v'): " + dir);
                        BufferedImage flipped = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = flipped.createGraphics();
                        g.drawImage(src, tx, null);
                        g.dispose();
                        imageLibrary.put(id, flipped);
                        return null;
                    }
                    case "grayscaleImage": {
                        String id = stringify(args.get(0));
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        BufferedImage gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                        Graphics2D g = gray.createGraphics();
                        g.drawImage(src, 0, 0, null);
                        g.dispose();
                        imageLibrary.put(id, gray);
                        return null;
                    }
                    case "blurImage": {
                        String id = stringify(args.get(0));
                        int radius = (int) toDouble(args.get(1));
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        float[] kernel = new float[radius * radius];
                        java.util.Arrays.fill(kernel, 1.0f / (radius * radius));
                        BufferedImageOp op = new ConvolveOp(new Kernel(radius, radius, kernel), ConvolveOp.EDGE_NO_OP, null);
                        BufferedImage blurred = op.filter(src, null);
                        imageLibrary.put(id, blurred);
                        return null;
                    }
                    case "invertImage": {
                        String id = stringify(args.get(0));
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        for (int y = 0; y < src.getHeight(); y++) {
                            for (int x = 0; x < src.getWidth(); x++) {
                                int rgb = src.getRGB(x, y);
                                int a = (rgb >> 24) & 0xff;
                                int r = 255 - ((rgb >> 16) & 0xff);
                                int g = 255 - ((rgb >> 8) & 0xff);
                                int b = 255 - (rgb & 0xff);
                                src.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                            }
                        }
                        return null;
                    }
                    case "getPixelColor": {
                        String id = stringify(args.get(0));
                        int x = (int) toDouble(args.get(1));
                        int y = (int) toDouble(args.get(2));
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        int rgb = src.getRGB(x, y);
                        return String.format("#%02x%02x%02x", (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
                    }
                    case "setPixelColor": {
                        String id = stringify(args.get(0));
                        int x = (int) toDouble(args.get(1));
                        int y = (int) toDouble(args.get(2));
                        String hex = stringify(args.get(3));
                        BufferedImage src = imageLibrary.get(id);
                        if (src == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);
                        java.awt.Color c = parseColor(hex);
                        src.setRGB(x, y, c.getRGB());
                        return null;
                    }
                    case "imageWidth": {
                        String id = stringify(args.get(0));
                        BufferedImage img = imageLibrary.get(id);
                        return img != null ? (double) img.getWidth() : 0.0;
                    }
                    case "imageHeight": {
                        String id = stringify(args.get(0));
                        BufferedImage img = imageLibrary.get(id);
                        return img != null ? (double) img.getHeight() : 0.0;
                    }
                    case "imageToSvg": {
                        // Tính năng 1: Raster -> SVG
                        String id = stringify(args.get(0));
                        BufferedImage img = imageLibrary.get(id);
                        if (img == null) throw new RuntimeException("Không tìm thấy ảnh: " + id);

                        StringBuilder svg = new StringBuilder();
                        svg.append(String.format("<svg width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" xmlns=\"http://www.w3.org/2000/svg\">\n",
                                img.getWidth(), img.getHeight(), img.getWidth(), img.getHeight()));

                        for (int y = 0; y < img.getHeight(); y++) {
                            int startX = -1;
                            int currentColor = 0;
                            for (int x = 0; x < img.getWidth(); x++) {
                                int argb = img.getRGB(x, y);
                                int a = (argb >> 24) & 0xff;
                                if (a == 0) { // Nếu trong suốt
                                    if (startX != -1) {
                                        appendSvgRect(svg, startX, y, x - startX, 1, currentColor);
                                        startX = -1;
                                    }
                                } else {
                                    if (startX == -1) {
                                        startX = x;
                                        currentColor = argb;
                                    } else if (argb != currentColor) {
                                        appendSvgRect(svg, startX, y, x - startX, 1, currentColor);
                                        startX = x;
                                        currentColor = argb;
                                    }
                                }
                            }
                            if (startX != -1) appendSvgRect(svg, startX, y, img.getWidth() - startX, 1, currentColor);
                        }
                        svg.append("</svg>");

                        String outputPath = args.size() > 1 ? stringify(args.get(1)) : "output.svg";
                        Files.writeString(Paths.get(outputPath), svg.toString(), StandardCharsets.UTF_8);
                        return outputPath;
                    }

                    case "svgToImage": {
                        // Tính năng 2: SVG -> Raster (Mini Engine)
                        String svgContent = "";
                        String input = stringify(args.get(0));
                        // Kiểm tra xem truyền vào là nội dung SVG hay đường dẫn file
                        if (input.trim().startsWith("<svg")) svgContent = input;
                        else svgContent = Files.readString(Paths.get(input), StandardCharsets.UTF_8);

                        // Dùng DocumentBuilder để đọc XML
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        DocumentBuilder builder = factory.newDocumentBuilder();
                        Document doc = builder.parse(new ByteArrayInputStream(svgContent.getBytes(StandardCharsets.UTF_8)));
                        doc.getDocumentElement().normalize();

                        Element svgRoot = doc.getDocumentElement();
                        // Lấy kích thước ảnh (Mặc định 512 nếu không có)
                        int w = svgRoot.hasAttribute("width") ? (int)Double.parseDouble(svgRoot.getAttribute("width").replaceAll("[^0-9.]", "")) : 512;
                        int h = svgRoot.hasAttribute("height") ? (int)Double.parseDouble(svgRoot.getAttribute("height").replaceAll("[^0-9.]", "")) : 512;

                        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g2d = img.createGraphics();
                        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                        // Parse ViewBox để Scale cho đúng tỷ lệ
                        if (svgRoot.hasAttribute("viewBox")) {
                            String[] vb = svgRoot.getAttribute("viewBox").split("[,\\s]+");
                            if (vb.length == 4) {
                                double vbx = Double.parseDouble(vb[0]); // <-- Thêm
                                double vby = Double.parseDouble(vb[1]); // <-- Thêm
                                double vw = Double.parseDouble(vb[2]);
                                double vh = Double.parseDouble(vb[3]);
                                g2d.translate(-vbx, -vby);              // <-- Giữ nguyên, giờ đã có biến
                                g2d.scale(w / vw, h / vh);
                            }
                        }

                        // Parse các thẻ <path>
                        NodeList paths = doc.getElementsByTagName("path");
                        for (int i = 0; i < paths.getLength(); i++) {
                            Element pathEl = (Element) paths.item(i);
                            String d = pathEl.getAttribute("d");
                            if (!d.isEmpty()) {
                                Path2D p2d = parseSvgPath(d); // Gọi hàm regex ở dưới
                                applySvgStyle(g2d, pathEl);
                                g2d.fill(p2d);
                                if (pathEl.hasAttribute("stroke")) g2d.draw(p2d);
                            }
                        }

                        // Parse các thẻ <ellipse> (Vì logo sếp có dùng thẻ này làm mắt)
                        NodeList ellipses = doc.getElementsByTagName("ellipse");
                        for (int i = 0; i < ellipses.getLength(); i++) {
                            Element el = (Element) ellipses.item(i);
                            double cx = Double.parseDouble(el.getAttribute("cx"));
                            double cy = Double.parseDouble(el.getAttribute("cy"));
                            double rx = Double.parseDouble(el.getAttribute("rx"));
                            double ry = Double.parseDouble(el.getAttribute("ry"));
                            applySvgStyle(g2d, el);
                            Ellipse2D e2d = new Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2);
                            g2d.fill(e2d);
                        }

                        // --- DÁN THÊM VÀO ĐÂY: Parse thẻ <circle> ---
                        NodeList circles = doc.getElementsByTagName("circle");
                        for (int i = 0; i < circles.getLength(); i++) {
                            Element el = (Element) circles.item(i);
                            double cx = Double.parseDouble(el.getAttribute("cx"));
                            double cy = Double.parseDouble(el.getAttribute("cy"));
                            double r = Double.parseDouble(el.getAttribute("r"));
                            applySvgStyle(g2d, el);
                            Ellipse2D c2d = new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
                            g2d.fill(c2d);
                        }

                        // --- DÁN THÊM VÀO ĐÂY: Parse thẻ <rect> ---
                        NodeList rects = doc.getElementsByTagName("rect");
                        for (int i = 0; i < rects.getLength(); i++) {
                            Element el = (Element) rects.item(i);
                            double x = el.hasAttribute("x") ? Double.parseDouble(el.getAttribute("x")) : 0;
                            double y = el.hasAttribute("y") ? Double.parseDouble(el.getAttribute("y")) : 0;
                            double rw = Double.parseDouble(el.getAttribute("width"));
                            double rh = Double.parseDouble(el.getAttribute("height"));

                            // Đọc độ bo góc của hình chữ nhật (nếu có)
                            double arc = 0;
                            if (el.hasAttribute("rx")) arc = Double.parseDouble(el.getAttribute("rx")) * 2;

                            // Tự xử lý màu sắc tinh tế cho Fill và Stroke (Vì logo sếp vẽ cái miệng dùng nét đứt Stroke)
                            if (el.hasAttribute("fill") && !el.getAttribute("fill").equals("none")) {
                                g2d.setColor(parseColor(el.getAttribute("fill")));
                                if (arc > 0) g2d.fillRoundRect((int)x, (int)y, (int)rw, (int)rh, (int)arc, (int)arc);
                                else g2d.fillRect((int)x, (int)y, (int)rw, (int)rh);
                            }

                            if (el.hasAttribute("stroke") && !el.getAttribute("stroke").equals("none")) {
                                g2d.setColor(parseColor(el.getAttribute("stroke")));
                                float sw = el.hasAttribute("stroke-width") ? Float.parseFloat(el.getAttribute("stroke-width")) : 1.0f;
                                g2d.setStroke(new java.awt.BasicStroke(sw));
                                if (arc > 0) g2d.drawRoundRect((int)x, (int)y, (int)rw, (int)rh, (int)arc, (int)arc);
                                else g2d.drawRect((int)x, (int)y, (int)rw, (int)rh);
                            }
                        }
                        // Parse <line>
                        NodeList lines = doc.getElementsByTagName("line");
                        for (int i = 0; i < lines.getLength(); i++) {
                            Element el = (Element) lines.item(i);
                            double x1 = Double.parseDouble(el.getAttribute("x1"));
                            double y1 = Double.parseDouble(el.getAttribute("y1"));
                            double x2 = Double.parseDouble(el.getAttribute("x2"));
                            double y2 = Double.parseDouble(el.getAttribute("y2"));
                            applySvgStyle(g2d, el);
                            g2d.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
                        }
                        // Parse <polygon> và <polyline>
                        String[] polyTags = {"polygon", "polyline"};
                        for (String tag : polyTags) {
                            NodeList polys = doc.getElementsByTagName(tag);
                            for (int i = 0; i < polys.getLength(); i++) {
                                Element el = (Element) polys.item(i);
                                String pts = el.getAttribute("points").trim();
                                if (pts.isEmpty()) continue;
                                String[] coords = pts.split("[\\s,]+");
                                Path2D poly = new Path2D.Double();
                                for (int j=0; j<coords.length; j+=2) {
                                    double x = Double.parseDouble(coords[j]);
                                    double y = Double.parseDouble(coords[j+1]);
                                    if (j==0) poly.moveTo(x,y); else poly.lineTo(x,y);
                                }
                                if (tag.equals("polygon")) poly.closePath();
                                applySvgStyle(g2d, el);
                                g2d.fill(poly);
                                if (el.hasAttribute("stroke")) g2d.draw(poly);
                            }
                        }
                        g2d.dispose();
                        String id = "@img" + (++imageIdCounter);
                        imageLibrary.put(id, img);
                        return id;
                    }

                    // ==========================================================================
                    //   ẢNH  <->  CHUỖI BASE64
                    //   Dùng để CẤT ẢNH THẲNG VÀO CƠ SỞ DỮ LIỆU dưới dạng một loạt ký tự,
                    //   lúc cần hiển thị thì giải mã ngược lại thành ảnh thật.
                    // ==========================================================================

                    // anhTuFile("logo.png") -> "iVBORw0KGgo..."  (đọc file ảnh thành chuỗi)
                    case "anhTuFile":
                    case "imageFileToBase64": {
                        byte[] raw = Files.readAllBytes(Paths.get(stringify(args.get(0))));
                        return Base64.getEncoder().encodeToString(raw);
                    }

                    // anhRaFile(chuoi, "avatar.png") -> ghi chuỗi base64 trở lại thành file ảnh
                    case "anhRaFile":
                    case "imageBase64ToFile": {
                        byte[] raw = anhGiaiMa(stringify(args.get(0)));
                        Files.write(Paths.get(stringify(args.get(1))), raw);
                        return (double) raw.length;
                    }

                    // anhLamSach("data:image/png;base64,AAA") -> "AAA"  (bỏ phần đầu data URI)
                    case "anhLamSach":
                    case "imageBase64Clean": return anhBoTienTo(stringify(args.get(0)));

                    // anhKieu(chuoi) -> "image/png" | "image/jpeg" | "image/gif" | "image/webp"
                    case "anhKieu":
                    case "imageBase64Mime": return anhDoanMime(anhBoTienTo(stringify(args.get(0))));

                    // anhDataUri(chuoi[, kieu]) -> "data:image/png;base64,AAA"  (dán thẳng vào <img src>)
                    case "anhDataUri":
                    case "imageBase64Uri": {
                        String b64 = anhBoTienTo(stringify(args.get(0)));
                        if (b64.isEmpty()) return "";
                        String mime = args.size() > 1 ? stringify(args.get(1)) : anhDoanMime(b64);
                        return "data:" + mime + ";base64," + b64;
                    }

                    // anhDungLuong(chuoi) -> số byte thật của ảnh
                    case "anhDungLuong":
                    case "imageBase64Size": {
                        String b64 = anhBoTienTo(stringify(args.get(0)));
                        if (b64.isEmpty()) return 0.0;
                        return (double) ((b64.length() * 3L) / 4L
                                - (b64.endsWith("==") ? 2 : (b64.endsWith("=") ? 1 : 0)));
                    }

                    // anhHopLe(chuoi) -> true nếu giải mã ra được ảnh thật
                    case "anhHopLe":
                    case "imageBase64Valid": {
                        try {
                            byte[] raw = anhGiaiMa(stringify(args.get(0)));
                            if (raw.length == 0) return false;
                            return ImageIO.read(new ByteArrayInputStream(raw)) != null;
                        } catch (Exception e) { return false; }
                    }

                    // anhRong(chuoi) / anhCao(chuoi) -> kích thước điểm ảnh
                    case "anhRong":
                    case "imageBase64Width": {
                        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(anhGiaiMa(stringify(args.get(0)))));
                        return bi == null ? 0.0 : (double) bi.getWidth();
                    }
                    case "anhCao":
                    case "imageBase64Height": {
                        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(anhGiaiMa(stringify(args.get(0)))));
                        return bi == null ? 0.0 : (double) bi.getHeight();
                    }

                    // anhThongTin(chuoi) -> bảng { rong, cao, kieu, dung_luong }
                    case "anhThongTin":
                    case "imageBase64Info": {
                        String b64 = anhBoTienTo(stringify(args.get(0)));
                        byte[] raw = anhGiaiMa(b64);
                        BufferedImage bi = raw.length == 0 ? null : ImageIO.read(new ByteArrayInputStream(raw));
                        LinkedHashMap<String, Object> info = new LinkedHashMap<>();
                        info.put("rong", bi == null ? 0.0 : (double) bi.getWidth());
                        info.put("cao", bi == null ? 0.0 : (double) bi.getHeight());
                        info.put("kieu", anhDoanMime(b64));
                        info.put("dung_luong", (double) raw.length);
                        info.put("hop_le", bi != null);
                        return info;
                    }

                    // anhThuNho(chuoi, rongToiDa, caoToiDa) -> chuỗi base64 ảnh đã thu nhỏ
                    // Giữ đúng tỷ lệ, ảnh nhỏ hơn mức yêu cầu thì giữ nguyên.
                    case "anhThuNho":
                    case "imageBase64Resize": {
                        String b64 = anhBoTienTo(stringify(args.get(0)));
                        int maxW = args.size() > 1 ? (int) toDouble(args.get(1)) : 512;
                        int maxH = args.size() > 2 ? (int) toDouble(args.get(2)) : maxW;
                        return anhThuNhoBase64(b64, maxW, maxH);
                    }

                    // anhCatVuong(chuoi, canh) -> cắt giữa thành ảnh vuông (dùng cho avatar)
                    case "anhCatVuong":
                    case "imageBase64Square": {
                        byte[] raw = anhGiaiMa(stringify(args.get(0)));
                        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
                        if (src == null) return "";
                        int canh = args.size() > 1 ? (int) toDouble(args.get(1)) : 256;
                        int nho = Math.min(src.getWidth(), src.getHeight());
                        BufferedImage vuong = src.getSubimage((src.getWidth() - nho) / 2, (src.getHeight() - nho) / 2, nho, nho);
                        BufferedImage ra = new BufferedImage(canh, canh, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = ra.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        g.setColor(Color.WHITE);
                        g.fillRect(0, 0, canh, canh);
                        g.drawImage(vuong, 0, 0, canh, canh, null);
                        g.dispose();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ImageIO.write(ra, "jpg", bos);
                        return Base64.getEncoder().encodeToString(bos.toByteArray());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi xử lý ảnh (hql.image): " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.chart")) {
            try {
                switch (name) {
                    case "drawBarChart":
                    case "drawLineChart":
                    case "drawPieChart": {
                        HqlChart chart = new HqlChart();
                        chart.id = "@chart" + (++chartIdCounter);
                        chart.type = name.replace("draw", "").replace("Chart", "").toLowerCase();
                        ArrayList<?> rawData = (ArrayList<?>) args.get(0);
                        for (Object o : rawData) chart.dataY.add(toDouble(o));
                        ArrayList<?> rawLabels = (ArrayList<?>) args.get(1);
                        for (Object o : rawLabels) chart.labels.add(stringify(o));
                        chart.title = stringify(args.get(2));
                        chartLibrary.put(chart.id, chart);
                        return chart.id;
                    }
                    case "drawScatterChart": {
                        HqlChart chart = new HqlChart();
                        chart.id = "@chart" + (++chartIdCounter);
                        chart.type = "scatter";
                        ArrayList<?> rawX = (ArrayList<?>) args.get(0);
                        for (Object o : rawX) chart.dataX.add(toDouble(o));
                        ArrayList<?> rawY = (ArrayList<?>) args.get(1);
                        for (Object o : rawY) chart.dataY.add(toDouble(o));
                        chart.title = stringify(args.get(2));
                        chartLibrary.put(chart.id, chart);
                        return chart.id;
                    }
                    case "drawHistogram": {
                        HqlChart chart = new HqlChart();
                        chart.id = "@chart" + (++chartIdCounter);
                        chart.type = "histogram";
                        ArrayList<?> rawData = (ArrayList<?>) args.get(0);
                        for (Object o : rawData) chart.dataY.add(toDouble(o));
                        chart.bins = (int) toDouble(args.get(1));
                        chart.title = stringify(args.get(2));
                        chartLibrary.put(chart.id, chart);
                        return chart.id;
                    }
                    case "setChartSize": {
                        HqlChart chart = chartLibrary.get(stringify(args.get(0)));
                        if (chart != null) {
                            chart.width = (int) toDouble(args.get(1));
                            chart.height = (int) toDouble(args.get(2));
                        }
                        return null;
                    }
                    case "setChartColor": {
                        HqlChart chart = chartLibrary.get(stringify(args.get(0)));
                        if (chart != null) chart.color = parseColor(stringify(args.get(1)));
                        return null;
                    }
                    case "chartTitle": {
                        HqlChart chart = chartLibrary.get(stringify(args.get(0)));
                        return chart != null ? chart.title : "";
                    }
                    case "saveChart": {
                        String id = stringify(args.get(0));
                        String path = stringify(args.get(1));
                        HqlChart chart = chartLibrary.get(id);
                        if (chart == null) throw new RuntimeException("Lỗi: Không tìm thấy biểu đồ '" + id + "'");
                        renderAndSaveChart(chart, path);
                        return null;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Thư viện (hql.chart): " + e.getMessage(), e);
            }
        }
        // ==========================================================================================
        //   THƯ VIỆN HQL.ENV — ĐỌC TỆP CẤU HÌNH MÔI TRƯỜNG .henv (giống .env của Node.js / .properties của Java)
        //   Tệp .henv tự được nạp khi chạy script (tìm ở thư mục của file main rồi ngược lên tối đa 6 cấp).
        // ==========================================================================================
        if (usedLibraries.contains("hql.env")) {
            try {
                switch (name) {
                    case "envLoad": {
                        // envLoad("duong/dan/.henv" [, ghiDe])  hoặc  envLoad("thu/muc/du/an")
                        File f = new File(stringify(args.get(0)));
                        if (f.isDirectory()) return HqlEnv.autoLoad(f);
                        return HqlEnv.loadFile(f, args.size() > 1 && Boolean.TRUE.equals(args.get(1)));
                    }
                    case "envGet": {
                        // envGet("DB_HOST") hoặc envGet("DB_HOST", "127.0.0.1")  (biến của hệ điều hành thắng .henv)
                        return HqlEnv.get(stringify(args.get(0)), args.size() > 1 ? stringify(args.get(1)) : null);
                    }
                    case "envHas": return HqlEnv.has(stringify(args.get(0)));
                    case "envSet": { HqlEnv.set(stringify(args.get(0)), stringify(args.get(1))); return null; }
                    case "envInt": return (double) HqlEnv.getInt(stringify(args.get(0)), args.size() > 1 ? (int) toDouble(args.get(1)) : 0);
                    case "envBool": return HqlEnv.getBool(stringify(args.get(0)), args.size() > 1 && Boolean.TRUE.equals(args.get(1)));
                    case "envAll": return new LinkedHashMap<String, Object>(HqlEnv.all());
                    case "envFiles": return new ArrayList<Object>(HqlEnv.loadedFiles());
                    case "envRequire": {
                        // envRequire("DB_HOST", "DB_PASSWORD") — thiếu khoá nào thì dừng chương trình và nói rõ thiếu khoá nào
                        ArrayList<String> thieu = new ArrayList<>();
                        for (Object k : args) {
                            String key = stringify(k);
                            String v = HqlEnv.get(key, null);
                            if (v == null || v.isEmpty()) thieu.add(key);
                        }
                        if (!thieu.isEmpty()) {
                            throw new RuntimeException("Thiếu cấu hình bắt buộc trong tệp .henv: " + String.join(", ", thieu));
                        }
                        return true;
                    }
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Lỗi HQL.ENV: " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.config")) {
            try {
                switch (name) {
                    case "loadConfig": {
                        String path = stringify(args.get(0));
                        HqlConfig conf = new HqlConfig();
                        conf.id = "@config" + (++configIdCounter);

                        // Nếu file tồn tại thì tiến hành đọc và parse
                        if (Files.exists(Paths.get(path))) {
                            List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
                            String currentSection = null;
                            for (String line : lines) {
                                line = line.trim();
                                // Bỏ qua dòng trống hoặc dòng comment (#, ;)
                                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

                                // Nếu là bắt đầu Section (VD: [Database])
                                if (line.startsWith("[") && line.endsWith("]")) {
                                    currentSection = line.substring(1, line.length() - 1).trim();
                                    conf.sections.putIfAbsent(currentSection, new java.util.LinkedHashMap<>());
                                } else {
                                    // Bắt cặp Key = Value (hoặc Key : Value)
                                    int sepIndex = line.indexOf('=');
                                    if (sepIndex == -1) sepIndex = line.indexOf(':');

                                    if (sepIndex != -1) {
                                        String k = line.substring(0, sepIndex).trim();
                                        String v = line.substring(sepIndex + 1).trim();
                                        if (currentSection == null) {
                                            conf.globalKeys.put(k, v);
                                        } else {
                                            conf.sections.get(currentSection).put(k, v);
                                        }
                                    }
                                }
                            }
                        }
                        configLibrary.put(conf.id, conf);
                        return conf.id;
                    }
                    case "getConfig": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        if (conf == null) return "";
                        return conf.globalKeys.getOrDefault(stringify(args.get(1)), "");
                    }
                    case "setConfig": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        if (conf != null) conf.globalKeys.put(stringify(args.get(1)), stringify(args.get(2)));
                        return null;
                    }
                    case "saveConfig": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        if (conf == null) throw new RuntimeException("Lỗi: Config ID không tồn tại.");

                        StringBuilder sb = new StringBuilder();
                        // Ghi global keys trước
                        for (Map.Entry<String, String> entry : conf.globalKeys.entrySet()) {
                            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                        }
                        // Ghi từng section
                        for (Map.Entry<String, java.util.LinkedHashMap<String, String>> sec : conf.sections.entrySet()) {
                            sb.append("\n[").append(sec.getKey()).append("]\n");
                            for (Map.Entry<String, String> entry : sec.getValue().entrySet()) {
                                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                            }
                        }
                        // Ghi đè file
                        Files.writeString(Paths.get(stringify(args.get(1))), sb.toString().trim(), StandardCharsets.UTF_8);
                        return null;
                    }
                    case "listConfigKeys": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        return conf != null ? new ArrayList<>(conf.globalKeys.keySet()) : new ArrayList<>();
                    }
                    case "hasConfigKey": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        return conf != null && conf.globalKeys.containsKey(stringify(args.get(1)));
                    }
                    case "removeConfigKey": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        if (conf != null) conf.globalKeys.remove(stringify(args.get(1)));
                        return null;
                    }
                    case "getSectionConfig": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        if (conf == null) return "";
                        java.util.LinkedHashMap<String, String> sec = conf.sections.get(stringify(args.get(1)));
                        return sec != null ? sec.getOrDefault(stringify(args.get(2)), "") : "";
                    }
                    case "setSectionConfig": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        if (conf != null) {
                            String section = stringify(args.get(1));
                            conf.sections.putIfAbsent(section, new java.util.LinkedHashMap<>());
                            conf.sections.get(section).put(stringify(args.get(2)), stringify(args.get(3)));
                        }
                        return null;
                    }
                    case "listSections": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        return conf != null ? new ArrayList<>(conf.sections.keySet()) : new ArrayList<>();
                    }
                    case "listSectionKeys": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        if (conf == null) return new ArrayList<>();
                        java.util.LinkedHashMap<String, String> sec = conf.sections.get(stringify(args.get(1)));
                        return sec != null ? new ArrayList<>(sec.keySet()) : new ArrayList<>();
                    }
                    case "removeSection": {
                        HqlConfig conf = configLibrary.get(stringify(args.get(0)));
                        if (conf != null) conf.sections.remove(stringify(args.get(1)));
                        return null;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi HQL.Config: " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.csv")) {
            try {
                switch (name) {
                    // --- 1. NHÓM ĐỌC / GHI ---
                    case "readDelimitedFile":
                    case "readCsvFile": {
                        String path = stringify(args.get(0));
                        String delim = name.equals("readCsvFile") ? "," : stringify(args.get(1));
                        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
                        ArrayList<ArrayList<String>> result = new ArrayList<>();
                        for (String line : lines) result.add(parseCsvLineHelper(line, delim));
                        return result;
                    }
                    case "readCsvRecords": {
                        String path = stringify(args.get(0));
                        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
                        if (lines.isEmpty()) return new ArrayList<>();
                        ArrayList<String> headers = parseCsvLineHelper(lines.get(0), ",");
                        ArrayList<HashMap<String, Object>> result = new ArrayList<>();
                        for (int i = 1; i < lines.size(); i++) {
                            ArrayList<String> row = parseCsvLineHelper(lines.get(i), ",");
                            HashMap<String, Object> map = new HashMap<>();
                            for (int j = 0; j < headers.size() && j < row.size(); j++) {
                                map.put(headers.get(j), row.get(j));
                            }
                            result.add(map);
                        }
                        return result;
                    }
                    case "writeDelimitedFile":
                    case "writeCsvFile": {
                        String path = stringify(args.get(0));
                        ArrayList<ArrayList<?>> data = (ArrayList<ArrayList<?>>) args.get(1);
                        String delim = name.equals("writeCsvFile") ? "," : stringify(args.get(2));
                        StringBuilder sb = new StringBuilder();
                        for (ArrayList<?> row : data) {
                            for (int i = 0; i < row.size(); i++) {
                                sb.append(toCsvFieldHelper(stringify(row.get(i)), delim));
                                if (i < row.size() - 1) sb.append(delim);
                            }
                            sb.append("\n");
                        }
                        Files.writeString(Paths.get(path), sb.toString().trim(), StandardCharsets.UTF_8);
                        return null;
                    }
                    case "writeCsvRecords": {
                        String path = stringify(args.get(0));
                        ArrayList<?> headers = (ArrayList<?>) args.get(1);
                        ArrayList<HashMap<String, Object>> data = (ArrayList<HashMap<String, Object>>) args.get(2);
                        StringBuilder sb = new StringBuilder();
                        // Ghi Header
                        for (int i = 0; i < headers.size(); i++) {
                            sb.append(toCsvFieldHelper(stringify(headers.get(i)), ","));
                            if (i < headers.size() - 1) sb.append(",");
                        }
                        sb.append("\n");
                        // Ghi Data
                        for (HashMap<String, Object> row : data) {
                            for (int i = 0; i < headers.size(); i++) {
                                sb.append(toCsvFieldHelper(stringify(row.get(stringify(headers.get(i)))), ","));
                                if (i < headers.size() - 1) sb.append(",");
                            }
                            sb.append("\n");
                        }
                        Files.writeString(Paths.get(path), sb.toString().trim(), StandardCharsets.UTF_8);
                        return null;
                    }

                    // --- 2. NHÓM TRUY XUẤT CƠ BẢN ---
                    case "csvRowCount": return (double) ((ArrayList<?>) args.get(0)).size();
                    case "csvColumnCount": {
                        ArrayList<?> list = (ArrayList<?>) args.get(0);
                        if (list.isEmpty()) return 0.0;
                        Object first = list.get(0);
                        if (first instanceof ArrayList) return (double) ((ArrayList<?>) first).size();
                        if (first instanceof HashMap) return (double) ((HashMap<?, ?>) first).size();
                        return 0.0;
                    }
                    case "getCsvCell": return ((ArrayList<ArrayList<Object>>) args.get(0)).get((int)toDouble(args.get(1))).get((int)toDouble(args.get(2)));
                    case "setCsvCell": ((ArrayList<ArrayList<Object>>) args.get(0)).get((int)toDouble(args.get(1))).set((int)toDouble(args.get(2)), args.get(3)); return null;
                    case "getCsvField": return ((HashMap<String, Object>) args.get(0)).get(stringify(args.get(1)));
                    case "setCsvField": ((HashMap<String, Object>) args.get(0)).put(stringify(args.get(1)), args.get(2)); return null;
                    case "addCsvRow": ((ArrayList<Object>) args.get(0)).add(args.get(1)); return null;
                    case "addCsvRecord": {
                        // Tính năng đặc biệt: Nếu truyền Map trống từ Java, nó sẽ xử lý mượt mà
                        HashMap<String, Object> newRec = args.size() > 1 && args.get(1) instanceof HashMap ? (HashMap<String, Object>) args.get(1) : new HashMap<>();
                        ((ArrayList<Object>) args.get(0)).add(newRec);
                        return newRec; // Trả về tham chiếu để người dùng set data
                    }
                    case "removeCsvRow":
                    case "removeCsvRecord": ((ArrayList<?>) args.get(0)).remove((int)toDouble(args.get(1))); return null;

                    // --- 3. NHÓM SẮP XẾP & LỌC CHUYÊN SÂU ---
                    case "sortCsvByColumn": {
                        ArrayList<ArrayList<Object>> list = (ArrayList<ArrayList<Object>>) args.get(0);
                        int col = (int) toDouble(args.get(1));
                        boolean asc = toBoolean(args.get(2));
                        list.sort((a, b) -> {
                            String valA = stringify(a.size() > col ? a.get(col) : "");
                            String valB = stringify(b.size() > col ? b.get(col) : "");
                            try { return asc ? Double.compare(Double.parseDouble(valA), Double.parseDouble(valB)) : Double.compare(Double.parseDouble(valB), Double.parseDouble(valA)); }
                            catch (Exception e) { return asc ? valA.compareTo(valB) : valB.compareTo(valA); }
                        });
                        return list;
                    }
                    case "sortCsvRecordsByField": {
                        ArrayList<HashMap<String, Object>> list = (ArrayList<HashMap<String, Object>>) args.get(0);
                        String field = stringify(args.get(1));
                        boolean asc = toBoolean(args.get(2));
                        list.sort((a, b) -> {
                            String valA = stringify(a.get(field));
                            String valB = stringify(b.get(field));
                            try { return asc ? Double.compare(Double.parseDouble(valA), Double.parseDouble(valB)) : Double.compare(Double.parseDouble(valB), Double.parseDouble(valA)); }
                            catch (Exception e) { return asc ? valA.compareTo(valB) : valB.compareTo(valA); }
                        });
                        return list;
                    }
                    case "filterCsvByColumn": {
                        ArrayList<ArrayList<Object>> list = (ArrayList<ArrayList<Object>>) args.get(0);
                        int col = (int) toDouble(args.get(1));
                        String target = stringify(args.get(2));
                        return new ArrayList<>(list.stream().filter(row -> row.size() > col && stringify(row.get(col)).equals(target)).collect(Collectors.toList()));
                    }
                    case "filterCsvRecordsByField": {
                        ArrayList<HashMap<String, Object>> list = (ArrayList<HashMap<String, Object>>) args.get(0);
                        String field = stringify(args.get(1));
                        String target = stringify(args.get(2));
                        return new ArrayList<>(list.stream().filter(rec -> stringify(rec.get(field)).equals(target)).collect(Collectors.toList()));
                    }

                    // --- 4. NHÓM CHUYỂN ĐỔI JSON ---
                    case "csvRecordsToJson": return toJsonHelper(args.get(0));
                    case "jsonToCsvRecords": {
                        String json = stringify(args.get(0)).trim();
                        ArrayList<HashMap<String, Object>> records = new ArrayList<>();
                        // Parser Regex nhẹ cho Array of Objects [{"k":"v"}, {"k2":"v2"}]
                        Matcher m = Pattern.compile("\\{(.*?)\\}").matcher(json);
                        while (m.find()) {
                            String objStr = m.group(1);
                            HashMap<String, Object> map = new HashMap<>();
                            Matcher pairM = Pattern.compile("\"(.*?)\"\\s*:\\s*(\".*?\"|[0-9.]+|true|false|null)").matcher(objStr);
                            while (pairM.find()) {
                                String k = pairM.group(1);
                                String v = pairM.group(2);
                                if (v.startsWith("\"")) v = v.substring(1, v.length() - 1); // Xóa nháy kép ở value
                                map.put(k, v);
                            }
                            records.add(map);
                        }
                        return records;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi Thư viện HQL.CSV: " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.xml")) {
            try {
                switch (name) {
                    // --- NHÓM 1: ĐỌC / GHI ---
                    case "xmlReadFile": return xmlParseHelper(Files.readString(Paths.get(stringify(args.get(0))), StandardCharsets.UTF_8));
                    case "xmlParseString": return xmlParseHelper(stringify(args.get(0)));
                    case "xmlWriteFile": {
                        @SuppressWarnings("unchecked")
                        HashMap<String, Object> xmlData = (HashMap<String, Object>) args.get(0);
                        String rootName = stringify(args.get(1));
                        String path = stringify(args.get(2));
                        Files.writeString(Paths.get(path), xmlToStringHelper(xmlData, rootName, 0), StandardCharsets.UTF_8);
                        return null;
                    }
                    case "xmlToString": return xmlToStringHelper((HashMap<String, Object>) args.get(0), "root", 0);
                    case "xmlCreateRoot": {
                        HashMap<String, Object> root = new HashMap<>();
                        root.put("@name", stringify(args.get(0)));
                        root.put("@attrs", new HashMap<String, String>());
                        root.put("@text", "");
                        root.put("@children", new ArrayList<HashMap<String, Object>>());
                        return root;
                    }

                    // --- NHÓM 2: TRUY VẤN ---
                    case "xmlXpathQuery": return xmlXpathHelper(stringify(args.get(0)), stringify(args.get(1)), false, "");
                    case "xmlXpathAttr": return xmlXpathHelper(stringify(args.get(0)), stringify(args.get(1)), true, stringify(args.get(2)));
                    case "xmlFindNodes": {
                        ArrayList<HashMap<String, Object>> found = new ArrayList<>();
                        xmlFindNodesHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)), found);
                        return found;
                    }
                    case "xmlGetNode": return xmlGetNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)));
                    case "xmlGetAttr": {
                        HashMap<String, Object> node = xmlGetNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)));
                        if (node != null && node.containsKey("@attrs")) {
                            return ((HashMap<String, String>) node.get("@attrs")).getOrDefault(stringify(args.get(2)), "");
                        }
                        return "";
                    }

                    // --- NHÓM 3: CHUYỂN ĐỔI ---
                    case "xmlToJson": return toJsonHelper(args.get(0));
                    case "jsonToXml": return jsonToXmlHelper(stringify(args.get(0)), stringify(args.get(1)));
                    case "xmlToCsv": {
                        HashMap<String, Object> xmlObj = (HashMap<String, Object>) args.get(0);
                        String path = stringify(args.get(1));
                        StringBuilder csv = new StringBuilder();
                        ArrayList<HashMap<String, Object>> children = (ArrayList<HashMap<String, Object>>) xmlObj.get("@children");
                        if (children != null && !children.isEmpty()) {
                            // Header
                            ArrayList<HashMap<String, Object>> firstChildProps = (ArrayList<HashMap<String, Object>>) children.get(0).get("@children");
                            for (int i = 0; i < firstChildProps.size(); i++) {
                                csv.append(firstChildProps.get(i).get("@name")).append(i < firstChildProps.size() - 1 ? "," : "");
                            }
                            csv.append("\n");
                            // Data
                            for (HashMap<String, Object> child : children) {
                                ArrayList<HashMap<String, Object>> props = (ArrayList<HashMap<String, Object>>) child.get("@children");
                                for (int i = 0; i < props.size(); i++) {
                                    csv.append("\"").append(stringify(props.get(i).get("@text")).replace("\"", "\"\"")).append("\"")
                                            .append(i < props.size() - 1 ? "," : "");
                                }
                                csv.append("\n");
                            }
                        }
                        Files.writeString(Paths.get(path), csv.toString().trim(), StandardCharsets.UTF_8);
                        return null;
                    }
                    case "csvToXml": {
                        ArrayList<HashMap<String, Object>> csvData = (ArrayList<HashMap<String, Object>>) args.get(0);
                        String rootName = stringify(args.get(1));
                        String rowName = stringify(args.get(2));
                        HashMap<String, Object> root = new HashMap<>();
                        root.put("@name", rootName);
                        root.put("@attrs", new HashMap<String, String>());
                        root.put("@text", "");
                        ArrayList<HashMap<String, Object>> children = new ArrayList<>();
                        for (HashMap<String, Object> row : csvData) {
                            HashMap<String, Object> rowNode = new HashMap<>();
                            rowNode.put("@name", rowName);
                            rowNode.put("@attrs", new HashMap<String, String>());
                            rowNode.put("@text", "");
                            ArrayList<HashMap<String, Object>> rowProps = new ArrayList<>();
                            for (Map.Entry<String, Object> entry : row.entrySet()) {
                                HashMap<String, Object> propNode = new HashMap<>();
                                propNode.put("@name", entry.getKey());
                                propNode.put("@attrs", new HashMap<String, String>());
                                propNode.put("@text", stringify(entry.getValue()));
                                propNode.put("@children", new ArrayList<>());
                                rowProps.add(propNode);
                            }
                            rowNode.put("@children", rowProps);
                            children.add(rowNode);
                        }
                        root.put("@children", children);
                        return root;
                    }

                    // --- NHÓM 4: CHỈNH SỬA & DUYỆT ---
                    case "xmlSetNode": {
                        HashMap<String, Object> node = xmlGetNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)));
                        if (node != null) node.put("@text", stringify(args.get(2)));
                        return null;
                    }
                    case "xmlSetAttr": {
                        HashMap<String, Object> node = xmlGetNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)));
                        if (node != null) {
                            HashMap<String, String> attrs = (HashMap<String, String>) node.get("@attrs");
                            if (attrs == null) { attrs = new HashMap<>(); node.put("@attrs", attrs); }
                            attrs.put(stringify(args.get(2)), stringify(args.get(3)));
                        }
                        return null;
                    }
                    case "xmlAddChild": {
                        HashMap<String, Object> parent = xmlGetNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)));
                        if (parent != null) {
                            ArrayList<HashMap<String, Object>> children = (ArrayList<HashMap<String, Object>>) parent.get("@children");
                            if (children == null) { children = new ArrayList<>(); parent.put("@children", children); }
                            children.add((HashMap<String, Object>) args.get(2));
                        }
                        return null;
                    }
                    case "xmlRemoveNode": {
                        xmlRemoveNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)));
                        return null;
                    }
                    case "xmlRemoveAttr": {
                        HashMap<String, Object> node = xmlGetNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)));
                        if (node != null && node.containsKey("@attrs")) ((HashMap<String, String>) node.get("@attrs")).remove(stringify(args.get(2)));
                        return null;
                    }
                    case "xmlChildrenCount": {
                        HashMap<String, Object> node = xmlGetNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1)));
                        return node != null && node.containsKey("@children") ? (double) ((ArrayList<?>) node.get("@children")).size() : 0.0;
                    }
                    case "xmlHasNode": return xmlGetNodeHelper((HashMap<String, Object>) args.get(0), stringify(args.get(1))) != null;
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi HQL.XML: " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.binary")) {
            try {
                switch (name) {
                    // --- NHÓM 1: ĐỌC / GHI ---
                    case "binaryRead": return bytesToHexHelper(Files.readAllBytes(Paths.get(stringify(args.get(0)))));
                    case "binaryWrite": {
                        Files.write(Paths.get(stringify(args.get(0))), hexToBytesHelper(stringify(args.get(1))));
                        return null;
                    }
                    case "binaryReadByte": {
                        try (RandomAccessFile raf = new RandomAccessFile(stringify(args.get(0)), "r")) {
                            raf.seek((long) toDouble(args.get(1)));
                            return (double) raf.readUnsignedByte();
                        }
                    }
                    case "binaryWriteByte": {
                        try (RandomAccessFile raf = new RandomAccessFile(stringify(args.get(0)), "rw")) {
                            raf.seek((long) toDouble(args.get(1)));
                            raf.write((int) toDouble(args.get(2)));
                        }
                        return null;
                    }
                    case "binaryFileSize": return (double) Files.size(Paths.get(stringify(args.get(0))));

                    // --- NHÓM 2: PACK / UNPACK ---
                    case "binaryPack": return bytesToHexHelper(packHelper(stringify(args.get(0)), (ArrayList<?>) args.get(1)));
                    case "binaryUnpack": return unpackHelper(stringify(args.get(0)), hexToBytesHelper(stringify(args.get(1))));
                    case "binaryPackToFile": {
                        byte[] data = packHelper(stringify(args.get(0)), (ArrayList<?>) args.get(1));
                        Files.write(Paths.get(stringify(args.get(2))), data);
                        return null;
                    }
                    case "binaryUnpackFromFile": {
                        byte[] data = Files.readAllBytes(Paths.get(stringify(args.get(1))));
                        return unpackHelper(stringify(args.get(0)), data);
                    }

                    // --- NHÓM 3: TIỆN ÍCH HEX & DUMP ---
                    case "binaryHexDump": {
                        byte[] data = hexToBytesHelper(stringify(args.get(0)));
                        int width = args.size() > 1 ? (int) toDouble(args.get(1)) : 16;
                        return hexDumpHelper(data, width);
                    }
                    case "binaryHexToBytes": {
                        byte[] data = hexToBytesHelper(stringify(args.get(0)));
                        ArrayList<Double> list = new ArrayList<>();
                        for (byte b : data) list.add((double) (b & 0xFF));
                        return list;
                    }
                    case "binaryBytesToHex": {
                        ArrayList<?> list = (ArrayList<?>) args.get(0);
                        byte[] data = new byte[list.size()];
                        for (int i = 0; i < list.size(); i++) data[i] = (byte) toDouble(list.get(i));
                        return bytesToHexHelper(data);
                    }

                    // --- NHÓM 4: MÃ HÓA & CHECKSUM ---
                    case "binaryXor": {
                        byte[] data = hexToBytesHelper(stringify(args.get(0)));
                        byte[] key = hexToBytesHelper(stringify(args.get(1)));
                        if (key.length == 0) return stringify(args.get(0));
                        for (int i = 0; i < data.length; i++) data[i] ^= key[i % key.length];
                        return bytesToHexHelper(data);
                    }
                    case "binaryCRC32": {
                        byte[] data = hexToBytesHelper(stringify(args.get(0)));
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        crc.update(data);
                        return (double) crc.getValue();
                    }
                    case "binaryMD5": return hashBytesHelper(hexToBytesHelper(stringify(args.get(0))), "MD5");
                    case "binarySHA256": return hashBytesHelper(hexToBytesHelper(stringify(args.get(0))), "SHA-256");
                    // --- 10 HÀM BỔ SUNG ---
                    case "binaryReadBytes": {
                        try (RandomAccessFile raf = new RandomAccessFile(stringify(args.get(0)), "r")) {
                            raf.seek((long) toDouble(args.get(1)));
                            int len = (int) toDouble(args.get(2));
                            byte[] buf = new byte[len];
                            int read = raf.read(buf);
                            if (read < len) {
                                byte[] actualBuf = new byte[Math.max(0, read)];
                                if (read > 0) System.arraycopy(buf, 0, actualBuf, 0, read);
                                return bytesToHexHelper(actualBuf);
                            }
                            return bytesToHexHelper(buf);
                        }
                    }
                    case "binaryWriteBytes": {
                        try (RandomAccessFile raf = new RandomAccessFile(stringify(args.get(0)), "rw")) {
                            raf.seek((long) toDouble(args.get(1)));
                            raf.write(hexToBytesHelper(stringify(args.get(2))));
                        }
                        return null;
                    }
                    case "binaryCalcSize": return (double) calcSizeHelper(stringify(args.get(0)));
                    case "binarySlice": {
                        String hex = stringify(args.get(0));
                        int start = (int) toDouble(args.get(1)) * 2; // Nhân 2 vì 1 byte = 2 ký tự hex
                        int len = (int) toDouble(args.get(2)) * 2;
                        if (start < 0) start = 0;
                        if (start > hex.length()) start = hex.length();
                        if (start + len > hex.length()) len = hex.length() - start;
                        return hex.substring(start, start + len);
                    }
                    case "binaryConcat": return stringify(args.get(0)) + stringify(args.get(1));
                    case "binaryRepeat": return stringify(args.get(0)).repeat(Math.max(0, (int) toDouble(args.get(1))));
                    case "binaryToBase64": return java.util.Base64.getEncoder().encodeToString(hexToBytesHelper(stringify(args.get(0))));
                    case "binaryFromBase64": return bytesToHexHelper(java.util.Base64.getDecoder().decode(stringify(args.get(0))));
                    case "binaryReverse": {
                        String hex = stringify(args.get(0));
                        if (hex.length() % 2 != 0) hex = "0" + hex;
                        StringBuilder sb = new StringBuilder();
                        for (int i = hex.length() - 2; i >= 0; i -= 2) {
                            sb.append(hex, i, i + 2);
                        }
                        return sb.toString();
                    }
                    case "binaryCompare": {
                        byte[] b1 = hexToBytesHelper(stringify(args.get(0)));
                        byte[] b2 = hexToBytesHelper(stringify(args.get(1)));
                        int minLen = Math.min(b1.length, b2.length);
                        for (int i = 0; i < minLen; i++) {
                            if (b1[i] != b2[i]) return (double) i;
                        }
                        if (b1.length != b2.length) return (double) minLen;
                        return -1.0;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi HQL.BINARY: " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.color")) {
            switch(name) {
                // --- 1. Chuyển đổi ---
                case "colorRgbToHex":
                    return colorRgbToHexHelper((int)toDouble(args.get(0)), (int)toDouble(args.get(1)), (int)toDouble(args.get(2)));
                case "colorHexToRgb":
                    int[] rgb = colorHexToRgbHelper(stringify(args.get(0)));
                    return new ArrayList<>(java.util.Arrays.asList((double)rgb[0], (double)rgb[1], (double)rgb[2]));
                case "colorRgbToHsv":
                    return colorRgbToHsvHelper((int)toDouble(args.get(0)), (int)toDouble(args.get(1)), (int)toDouble(args.get(2)));
                case "colorHsvToRgb":
                    return colorHsvToRgbHelper(toDouble(args.get(0)), toDouble(args.get(1)), toDouble(args.get(2)));
                case "colorRgbToHsl":
                    return colorRgbToHslHelper((int)toDouble(args.get(0)), (int)toDouble(args.get(1)), (int)toDouble(args.get(2)));
                case "colorHslToRgb":
                    return colorHslToRgbHelper(toDouble(args.get(0)), toDouble(args.get(1)), toDouble(args.get(2)));
                case "colorRgbToCmyk":
                    return colorRgbToCmykHelper((int)toDouble(args.get(0)), (int)toDouble(args.get(1)), (int)toDouble(args.get(2)));
                case "colorCmykToRgb":
                    return colorCmykToRgbHelper(toDouble(args.get(0)), toDouble(args.get(1)), toDouble(args.get(2)), toDouble(args.get(3)));

                // --- 2. Phối trộn ---
                case "colorBlend":
                    return colorBlendHelper(stringify(args.get(0)), stringify(args.get(1)), toDouble(args.get(2)));
                case "colorMultiBlend":
                    return colorMultiBlendHelper((ArrayList<Object>)args.get(0), (ArrayList<Object>)args.get(1));
                case "colorGradient":
                    return colorGradientHelper(stringify(args.get(0)), stringify(args.get(1)), (int)toDouble(args.get(2)));
                case "colorMultiGradient":
                    return colorMultiGradientHelper((ArrayList<Object>)args.get(0), (int)toDouble(args.get(1)));
                case "colorRandomGradient":
                    return colorRandomGradientHelper((int)toDouble(args.get(0)), (args.size() > 1 ? (long)toDouble(args.get(1)) : System.currentTimeMillis()));

                // --- 3. Điều chỉnh ---
                case "colorLighten":
                    return colorAdjustHslHelper(stringify(args.get(0)), 2, toDouble(args.get(1)));
                case "colorDarken":
                    return colorAdjustHslHelper(stringify(args.get(0)), 2, -toDouble(args.get(1)));
                case "colorSaturate":
                    return colorAdjustHslHelper(stringify(args.get(0)), 1, toDouble(args.get(1)));
                case "colorDesaturate":
                    return colorAdjustHslHelper(stringify(args.get(0)), 1, -toDouble(args.get(1)));
                case "colorAdjustHue":
                    return colorAdjustHslHelper(stringify(args.get(0)), 0, toDouble(args.get(1)));

                // --- 4. Phân tích ---
                case "colorLuminance":
                    return colorLuminanceHelper(stringify(args.get(0)));
                case "colorContrastRatio":
                    return colorContrastHelper(stringify(args.get(0)), stringify(args.get(1)));
                case "colorIsLight":
                    return colorLuminanceHelper(stringify(args.get(0))) > 0.179; // Chuẩn WCAG
                case "colorIsDark":
                    return colorLuminanceHelper(stringify(args.get(0))) <= 0.179;
                case "colorDistance":
                    return colorDistanceHelper(stringify(args.get(0)), stringify(args.get(1)));
                case "colorSimilarity":
                    return 1.0 - (colorDistanceHelper(stringify(args.get(0)), stringify(args.get(1))) / 441.67);
                case "colorToGreyscale":
                    int[] gRgb = colorHexToRgbHelper(stringify(args.get(0)));
                    int gray = (int)(gRgb[0]*0.299 + gRgb[1]*0.587 + gRgb[2]*0.114);
                    return colorRgbToHexHelper(gray, gray, gray);

                // --- 5. Sinh màu ---
                case "colorRandomHex":
                    return colorRgbToHexHelper((int)(Math.random()*256), (int)(Math.random()*256), (int)(Math.random()*256));
                case "colorRandomRgb":
                    return new ArrayList<>(java.util.Arrays.asList((double)(int)(Math.random()*256), (double)(int)(Math.random()*256), (double)(int)(Math.random()*256)));
                case "colorRandomPalette":
                    return colorRandomPaletteHelper((int)toDouble(args.get(0)), stringify(args.get(1)));

                // --- 6. Nâng cao ---
                case "colorNameToRgb":
                    return colorNameToRgbHelper(stringify(args.get(0)));
                case "colorRgbToName":
                    return colorRgbToNameHelper((int)toDouble(args.get(0)), (int)toDouble(args.get(1)), (int)toDouble(args.get(2)));
                case "colorWithOpacity":
                    String hexO = stringify(args.get(0));
                    if(hexO.startsWith("#")) hexO = hexO.substring(1);
                    if(hexO.length() > 6) hexO = hexO.substring(0, 6);
                    int alpha = Math.min(255, Math.max(0, (int)(toDouble(args.get(1)) * 255)));
                    return "#" + hexO + String.format("%02X", alpha);
                case "colorExtractPalette":
                    return colorExtractPaletteHelper(stringify(args.get(0)), (int)toDouble(args.get(1)));
            }
        }
        // ==========================================================================
        //  HQL.MANG - THƯ VIỆN MẠNG CHO GAME NHIỀU NGƯỜI CHƠI
        //  Khác hql.socket ở chỗ: KHÔNG CHẶN luồng, gửi/nhận theo TIN NHẮN (mỗi tin 1 dòng),
        //  nên nhét thẳng vào vòng lặp game 60 khung hình/giây được.
        // ==========================================================================
        if (usedLibraries.contains("hql.mang")) {
            Object ketQuaMang = thuVienMang(name, args);
            if (ketQuaMang != CHUA_XU_LY) return ketQuaMang;
        }

        if (usedLibraries.contains("hql.socket")) {
            try {
                switch (name) {
                    // ==========================================
                    // NHÓM 1 & 3: TCP & SSL CLIENT/SERVER
                    // ==========================================
                    case "socketTcpConnect":
                    case "socketSslTcpConnect": {
                        String host = stringify(args.get(0));
                        int port = (int) toDouble(args.get(1));
                        Socket s;
                        if (name.equals("socketSslTcpConnect")) {
                            s = SSLSocketFactory.getDefault().createSocket(host, port);
                        } else {
                            s = new Socket(host, port);
                        }
                        String id = "@tcp" + (++socketIdCounter);
                        tcpClients.put(id, s);
                        return id;
                    }
                    case "socketTcpSend":
                    case "socketSslTcpSend":
                    case "socketTcpSendTo": {
                        String id = stringify(args.get(0));
                        String data = stringify(args.get(1));
                        Socket s = tcpClients.get(id);
                        if (s == null || s.isClosed()) throw new RuntimeException("Socket không tồn tại hoặc đã đóng.");

                        byte[] bytes = data.matches("^[0-9A-Fa-f]+$") && data.length() % 2 == 0
                                ? hexToBytesHelper(data) : data.getBytes(StandardCharsets.UTF_8);

                        // ---> CHỖ ĐỘ CHẾ 110%: Nếu có Key mã hóa thì băm nó ra! <---
                        if (socketEncryptors.containsKey(id)) {
                            bytes = socketEncryptors.get(id).doFinal(bytes);
                        }

                        s.getOutputStream().write(bytes);
                        s.getOutputStream().flush();
                        return null;
                    }
                    case "socketTcpReceive": {
                        String id = stringify(args.get(0));
                        int len = (int) toDouble(args.get(1));
                        Socket s = tcpClients.get(id);
                        if (s == null || s.isClosed()) throw new RuntimeException("Socket không tồn tại hoặc đã đóng.");

                        // Nâng cấp: Non-blocking fake (nếu có dùng socketSetNonBlocking)
                        try {
                            byte[] buf = new byte[len];
                            int read = s.getInputStream().read(buf);
                            if (read == -1) return ""; // EOF

                            byte[] actual = new byte[read];
                            System.arraycopy(buf, 0, actual, 0, read);

                            // ---> CHỖ ĐỘ CHẾ 110%: Giải mã luồng Hex nếu bị khóa <---
                            if (socketDecryptors.containsKey(id)) {
                                actual = socketDecryptors.get(id).doFinal(actual);
                            }

                            return bytesToHexHelper(actual);
                        } catch (java.net.SocketTimeoutException timeoutEx) {
                            // Đây là tính năng Non-Blocking: Nếu timeout thì trả về chuỗi rỗng thay vì crash sập game
                            return "";
                        }
                    }
                    case "socketTcpClose": {
                        String id = stringify(args.get(0));
                        if (tcpClients.containsKey(id)) { tcpClients.get(id).close(); tcpClients.remove(id); }
                        if (tcpServers.containsKey(id)) { tcpServers.get(id).close(); tcpServers.remove(id); }
                        return null;
                    }
                    case "socketTcpStatus": {
                        Socket s = tcpClients.get(stringify(args.get(0)));
                        return s != null && s.isConnected() && !s.isClosed();
                    }
                    case "socketTcpListen":
                    case "socketSslTcpListen": {
                        int port = (int) toDouble(args.get(0));
                        ServerSocket ss;
                        if (name.equals("socketSslTcpListen")) {
                            // Hỗ trợ SSL tự động lấy keystore từ thuộc tính hệ thống
                            System.setProperty("javax.net.ssl.keyStore", stringify(args.get(1)));
                            System.setProperty("javax.net.ssl.keyStorePassword", stringify(args.get(2)));
                            ss = SSLServerSocketFactory.getDefault().createServerSocket(port);
                        } else {
                            ss = new ServerSocket(port);
                        }
                        String id = "@server" + (++socketIdCounter);
                        tcpServers.put(id, ss);
                        return id;
                    }
                    case "socketTcpAccept":
                    case "socketSslTcpAccept": {
                        ServerSocket ss = tcpServers.get(stringify(args.get(0)));
                        if (ss == null) throw new RuntimeException("Server Socket không tồn tại.");
                        Socket client = ss.accept();
                        String clientId = "@client" + (++socketIdCounter);
                        tcpClients.put(clientId, client);
                        return clientId;
                    }
                    case "socketTcpBroadcast": {
                        String data = stringify(args.get(1));
                        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                        for (Socket client : tcpClients.values()) {
                            if (!client.isClosed()) {
                                try { client.getOutputStream().write(bytes); client.getOutputStream().flush(); } catch (Exception ignored) {}
                            }
                        }
                        return null;
                    }
                    case "socketTcpDisconnectClient": {
                        Socket client = tcpClients.remove(stringify(args.get(1)));
                        if (client != null && !client.isClosed()) client.close();
                        return null;
                    }

                    // ==========================================
                    // NHÓM 2: UDP & MULTICAST
                    // ==========================================
                    case "socketUdpBind": {
                        int port = (int) toDouble(args.get(0));
                        DatagramSocket ds = new DatagramSocket(port);
                        String id = "@udp" + (++socketIdCounter);
                        udpSockets.put(id, ds);
                        return id;
                    }
                    case "socketUdpSend": {
                        DatagramSocket ds = udpSockets.get(stringify(args.get(0)));
                        InetAddress ip = InetAddress.getByName(stringify(args.get(1)));
                        int port = (int) toDouble(args.get(2));
                        byte[] data = stringify(args.get(3)).getBytes(StandardCharsets.UTF_8);
                        ds.send(new DatagramPacket(data, data.length, ip, port));
                        return null;
                    }
                    case "socketUdpReceive": {
                        DatagramSocket ds = udpSockets.get(stringify(args.get(0)));
                        int len = (int) toDouble(args.get(1));
                        byte[] buf = new byte[len];
                        DatagramPacket dp = new DatagramPacket(buf, len);
                        ds.receive(dp);
                        ArrayList<Object> result = new ArrayList<>();
                        result.add(new String(dp.getData(), 0, dp.getLength(), StandardCharsets.UTF_8));
                        result.add(dp.getAddress().getHostAddress());
                        result.add((double) dp.getPort());
                        return result; // Trả về [data, senderHost, senderPort]
                    }
                    case "socketUdpClose": {
                        DatagramSocket ds = udpSockets.remove(stringify(args.get(0)));
                        if (ds != null && !ds.isClosed()) ds.close();
                        return null;
                    }
                    case "socketUdpBroadcast": {
                        int port = (int) toDouble(args.get(0));
                        byte[] data = stringify(args.get(1)).getBytes(StandardCharsets.UTF_8);
                        try (DatagramSocket ds = new DatagramSocket()) {
                            ds.setBroadcast(true);
                            ds.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), port));
                        }
                        return null;
                    }
                    case "socketUdpMulticastJoin": {
                        String groupIp = stringify(args.get(0));
                        int port = (int) toDouble(args.get(1));
                        MulticastSocket ms = new MulticastSocket(port);
                        ms.joinGroup(InetAddress.getByName(groupIp));
                        String id = "@multicast" + (++socketIdCounter);
                        udpSockets.put(id, ms);
                        return id;
                    }
                    case "socketUdpMulticastLeave": {
                        MulticastSocket ms = (MulticastSocket) udpSockets.get(stringify(args.get(0)));
                        if (ms != null) ms.leaveGroup(InetAddress.getByName(stringify(args.get(1))));
                        return null;
                    }

                    // ==========================================
                    // NHÓM 4: NON-BLOCKING & TIMEOUT
                    // ==========================================
                    case "socketSetTimeout": {
                        String id = stringify(args.get(0));
                        int timeout = (int) toDouble(args.get(1));
                        if (tcpClients.containsKey(id)) tcpClients.get(id).setSoTimeout(timeout);
                        else if (tcpServers.containsKey(id)) tcpServers.get(id).setSoTimeout(timeout);
                        else if (udpSockets.containsKey(id)) udpSockets.get(id).setSoTimeout(timeout);
                        return null;
                    }
                    case "socketSetTcpNoDelay": {
                        Socket s = tcpClients.get(stringify(args.get(0)));
                        if (s != null) s.setTcpNoDelay(toBoolean(args.get(1)));
                        return null;
                    }
                    case "socketSetKeepAlive": {
                        Socket s = tcpClients.get(stringify(args.get(0)));
                        if (s != null) s.setKeepAlive(toBoolean(args.get(1)));
                        return null;
                    }

                    // ==========================================
                    // NHÓM 5: TIỆN ÍCH MẠNG NÂNG CAO
                    // ==========================================
                    case "socketResolveHost": return InetAddress.getByName(stringify(args.get(0))).getHostAddress();
                    case "socketGetLocalIp": return InetAddress.getLocalHost().getHostAddress();
                    case "socketGetRemoteIp": {
                        Socket s = tcpClients.get(stringify(args.get(0)));
                        return s != null ? s.getInetAddress().getHostAddress() : "";
                    }
                    case "socketGetRemotePort": {
                        Socket s = tcpClients.get(stringify(args.get(0)));
                        return s != null ? (double) s.getPort() : -1.0;
                    }
                    case "socketGetHostName": return InetAddress.getLocalHost().getHostName();
                    case "socketListInterfaces": {
                        ArrayList<HashMap<String, String>> interfaces = new ArrayList<>();
                        java.util.Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
                        for (NetworkInterface netint : java.util.Collections.list(nets)) {
                            java.util.Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
                            for (InetAddress inetAddress : java.util.Collections.list(inetAddresses)) {
                                HashMap<String, String> info = new HashMap<>();
                                info.put("name", netint.getDisplayName());
                                info.put("ip", inetAddress.getHostAddress());
                                interfaces.add(info);
                            }
                        }
                        return interfaces;
                    }

                    // ==========================================
                    // NHÓM 6: RAW SOCKET & PING
                    // ==========================================
                    case "socketRawPing": {
                        String host = stringify(args.get(0));
                        int timeout = (int) toDouble(args.get(1));
                        long start = System.currentTimeMillis();
                        boolean reachable = InetAddress.getByName(host).isReachable(timeout);
                        return reachable ? (double) (System.currentTimeMillis() - start) : -1.0;
                    }
                    case "socketRawSend": {
                        // Ghi chú: Gửi RAW IP Packet thực sự (IPPROTO_ICMP, IPPROTO_RAW) đòi hỏi JNI/JNA trong Java.
                        // Hàm này giả lập ném ra ngoại lệ để cảnh báo người dùng.
                        throw new RuntimeException("Lỗi: Java không hỗ trợ Raw Socket nguyên thủy (C/C++) mà không qua JNI. Hãy dùng socketRawPing hoặc socketUdpSend.");
                    }

                    // ==========================================
                    // NHÓM 7: WEBSOCKET
                    // ==========================================
                    case "socketWebSocketConnect": {
                        String url = stringify(args.get(0));
                        String id = "@ws" + (++socketIdCounter);
                        wsMessages.put(id, new ArrayList<>());

                        WebSocket.Listener listener = new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                wsMessages.get(id).add(data.toString());
                                return WebSocket.Listener.super.onText(webSocket, data, last);
                            }
                        };

                        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                                .buildAsync(URI.create(url), listener).join();
                        webSockets.put(id, ws);
                        return id;
                    }
                    case "socketWebSocketSend": {
                        WebSocket ws = webSockets.get(stringify(args.get(0)));
                        if (ws != null) ws.sendText(stringify(args.get(1)), true);
                        return null;
                    }
                    case "socketWebSocketReceive": {
                        String id = stringify(args.get(0));
                        ArrayList<String> messages = wsMessages.get(id);
                        if (messages != null && !messages.isEmpty()) {
                            return messages.remove(0); // Lấy và xóa tin nhắn cũ nhất (Queue)
                        }
                        return "";
                    }
                    // ==========================================
                    // NHÓM 110%: GỬI/NHẬN FILE TỰ ĐỘNG (CHUNKED)
                    // ==========================================
                    case "socketSendFile": {
                        String id = stringify(args.get(0));
                        String filePath = stringify(args.get(1));
                        Socket s = tcpClients.get(id);
                        if (s == null || s.isClosed()) throw new RuntimeException("Socket không tồn tại.");

                        File file = new File(filePath);
                        if (!file.exists()) throw new RuntimeException("Lỗi: Không tìm thấy file " + filePath);

                        // Mã hóa End-to-End nếu đang bật
                        Cipher encryptor = socketEncryptors.get(id);

                        try (FileInputStream fis = new FileInputStream(file);
                             OutputStream os = s.getOutputStream()) {

                            byte[] buffer = new byte[8192]; // Chia khối 8KB để gửi mượt không nghẽn RAM
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                byte[] toSend = new byte[bytesRead];
                                System.arraycopy(buffer, 0, toSend, 0, bytesRead);

                                // Nếu bật mã hóa, mã hóa từng khối trước khi gửi
                                if (encryptor != null) toSend = encryptor.update(toSend);

                                if (toSend != null && toSend.length > 0) os.write(toSend);
                            }
                            // Chốt mã hóa khối cuối
                            if (encryptor != null) {
                                byte[] finalBlock = encryptor.doFinal();
                                if (finalBlock != null && finalBlock.length > 0) os.write(finalBlock);
                            }
                            os.flush();
                        }
                        return true;
                    }
                    case "socketReceiveFile": {
                        String id = stringify(args.get(0));
                        String savePath = stringify(args.get(1));
                        Socket s = tcpClients.get(id);
                        if (s == null || s.isClosed()) throw new RuntimeException("Socket không tồn tại.");

                        Cipher decryptor = socketDecryptors.get(id);

                        // Lưu ý: Hàm này sẽ block luồng cho đến khi file tải xong hoặc server sập
                        try (InputStream is = s.getInputStream();
                             FileOutputStream fos = new FileOutputStream(savePath)) {

                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                byte[] toWrite = new byte[bytesRead];
                                System.arraycopy(buffer, 0, toWrite, 0, bytesRead);

                                // Giải mã nếu đang bật
                                if (decryptor != null) toWrite = decryptor.update(toWrite);

                                if (toWrite != null && toWrite.length > 0) fos.write(toWrite);

                                // Nếu dùng Non-blocking, phải check thêm data
                                if (s.getInputStream().available() == 0) break;
                            }
                            if (decryptor != null) {
                                byte[] finalBlock = decryptor.doFinal();
                                if (finalBlock != null && finalBlock.length > 0) fos.write(finalBlock);
                            }
                        }
                        return true;
                    }

                    // ==========================================
                    // NHÓM 110%: MÃ HÓA END-TO-END TÍCH HỢP
                    // ==========================================
                    case "socketEnableEncryption": {
                        String id = stringify(args.get(0));
                        String algo = stringify(args.get(1)).toUpperCase(); // Thường là "AES"
                        String keyHex = stringify(args.get(2)); // Cần 16/24/32 bytes Hex

                        byte[] keyBytes = hexToBytesHelper(keyHex);
                        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, algo);

                        Cipher enc = Cipher.getInstance(algo + "/ECB/PKCS5Padding");
                        enc.init(Cipher.ENCRYPT_MODE, secretKey);
                        socketEncryptors.put(id, enc);

                        Cipher dec = Cipher.getInstance(algo + "/ECB/PKCS5Padding");
                        dec.init(Cipher.DECRYPT_MODE, secretKey);
                        socketDecryptors.put(id, dec);

                        return true;
                    }
                    case "socketDisableEncryption": {
                        String id = stringify(args.get(0));
                        socketEncryptors.remove(id);
                        socketDecryptors.remove(id);
                        return true;
                    }

                    // ==========================================
                    // NHÓM 110%: NON-BLOCKING & ASYNC
                    // ==========================================
                    case "socketSetNonBlocking": {
                        // Java cũ không hỗ trợ non-blocking Socket io chuẩn, ta dùng mẹo timeout = 1ms
                        // để hàm read() văng lỗi nhanh nếu không có data, tạo cảm giác non-blocking.
                        String id = stringify(args.get(0));
                        boolean isNonBlock = toBoolean(args.get(1));
                        if (tcpClients.containsKey(id)) tcpClients.get(id).setSoTimeout(isNonBlock ? 1 : 0);
                        return true;
                    }
                    case "socketWaitData": {
                        String id = stringify(args.get(0));
                        int timeout = (int) toDouble(args.get(1));
                        Socket s = tcpClients.get(id);
                        if (s == null || s.isClosed()) return false;

                        long start = System.currentTimeMillis();
                        while (System.currentTimeMillis() - start < timeout) {
                            if (s.getInputStream().available() > 0) return true;
                            Thread.sleep(10); // Ngủ đông tí cho đỡ cháy CPU
                        }
                        return false;
                    }

                    // ==========================================
                    // NHÓM 110%: THIẾT LẬP TÙY CHỌN LOW-LEVEL
                    // ==========================================
                    case "socketSetOption": {
                        String id = stringify(args.get(0));
                        String opt = stringify(args.get(1)).toUpperCase();
                        int val = (int) toDouble(args.get(2));

                        Socket s = tcpClients.get(id);
                        if (s != null) {
                            switch (opt) {
                                case "SO_REUSEADDR": s.setReuseAddress(val != 0); break;
                                case "SO_RCVBUF": s.setReceiveBufferSize(val); break;
                                case "SO_SNDBUF": s.setSendBufferSize(val); break;
                                case "SO_KEEPALIVE": s.setKeepAlive(val != 0); break;
                                case "SO_LINGER": if(val > 0) s.setSoLinger(true, val); else s.setSoLinger(false, 0); break;
                                default: throw new RuntimeException("Option chưa hỗ trợ: " + opt);
                            }
                        }
                        return true;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi HQL.SOCKET: " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.schedule")) {
            try {
                // --- HELPER NỘI BỘ CHO SCHEDULE ---
                java.util.function.Consumer<HqlTask> runTaskLogic = (task) -> {
                    if (task.isPaused) return;
                    if (task.maxRuns != -1 && task.runCount >= task.maxRuns) {
                        if (task.future != null) task.future.cancel(false);
                        schedulerMemory.remove(task.name);
                        return;
                    }
                    task.lastRunStr = LocalDateTime.now().toString();
                    task.runCount++;

                    /*
                     * Moi me la MOT LUOT CHAY DOC LAP, giong het mot request web.
                     * Me truoc no loi thi khung goi ham co tinh duoc giu lai (cho
                     * bao loi in duoc duong di), khong xoa la no dinh sang me sau.
                     */
                    vet().nganXep.clear();
                    vet().cuoi = null;

                    try {
                        executeFunctionCall(task.funcName, new ArrayList<>());
                        if (task.onSuccessFunc != null) executeFunctionCall(task.onSuccessFunc, new ArrayList<>());

                        // Kích hoạt Task Chain (Chuỗi tác vụ) nếu có
                        if (!task.onCompleteChain.isEmpty() && !task.type.startsWith("repeat") && !task.type.startsWith("cron")) {
                            for (String nextFunc : task.onCompleteChain) {
                                executeFunctionCall(nextFunc, new ArrayList<>());
                            }
                        }

                        // Persistence auto-save
                        if (task.stateFile != null) {
                            Files.writeString(Paths.get(task.stateFile), "{\"runs\": " + task.runCount + ", \"last\": \"" + task.lastRunStr + "\"}", StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        /*
                         * IN NGUYEN BAO CAO, DUNG BOP LAI CON MOI CAU getMessage().
                         *
                         * Viec nen hong luc 3 gio sang thi khong ai ngoi nhin man hinh.
                         * Chi mot dong "Task x failed: ..." thi hom sau doc nhat ky van
                         * khong biet hong o file nao, dong nao. In ca file + dong + trich
                         * doan ma nguon nhu moi loi khac moi lan ra duoc.
                         */
                        Throwable goc = BaoLoi.goc(e);
                        printLog("error", "Task " + task.name + " failed: " + goc.getMessage());
                        System.err.println(BaoLoi.banChu(goc, vet().cuoi, vet().nganXep, true));
                        vet().nganXep.clear();
                        vet().cuoi = null;
                        if (task.onFailureFunc != null) {
                            try { executeFunctionCall(task.onFailureFunc, new ArrayList<>()); } catch (Exception ex) {}
                        }
                    }
                    if (task.type.equals("once") || task.type.equals("runAt")) schedulerMemory.remove(task.name);
                };

                // Hàm check Cron siêu tốc (hỗ trợ dạng chuẩn: min hour day month dow)
                java.util.function.BiPredicate<String, LocalDateTime> checkCron = (expr, time) -> {
                    if (expr.equals("* * * * *")) return true;
                    String[] parts = expr.split("\\s+");
                    if (parts.length < 5) return false;
                    try {
                        boolean mMatch = parts[0].equals("*") || parts[0].contains(String.valueOf(time.getMinute())) || (parts[0].startsWith("*/") && time.getMinute() % Integer.parseInt(parts[0].substring(2)) == 0);
                        boolean hMatch = parts[1].equals("*") || parts[1].contains(String.valueOf(time.getHour())) || (parts[1].startsWith("*/") && time.getHour() % Integer.parseInt(parts[1].substring(2)) == 0);
                        boolean dMatch = parts[2].equals("*") || parts[2].contains(String.valueOf(time.getDayOfMonth()));
                        boolean moMatch = parts[3].equals("*") || parts[3].contains(String.valueOf(time.getMonthValue()));
                        boolean dowMatch = parts[4].equals("*") || parts[4].contains(String.valueOf(time.getDayOfWeek().getValue()));
                        return mMatch && hMatch && dMatch && moMatch && dowMatch;
                    } catch (Exception e) { return false; }
                };

                switch (name) {
                    // ==========================================
                    // NHÓM 1: TẠO VÀ HỦY CƠ BẢN (7 hàm)
                    // ==========================================
                    case "taskRunOnce": {
                        String tName = stringify(args.get(0)); String fName = stringify(args.get(1)); long delay = (long) toDouble(args.get(2));
                        if (schedulerMemory.containsKey(tName)) throw new RuntimeException("Task tồn tại!");
                        HqlTask task = new HqlTask(); task.name = tName; task.type = "once"; task.funcName = fName;
                        task.future = masterScheduler.schedule(() -> runTaskLogic.accept(task), delay, TimeUnit.MILLISECONDS);
                        schedulerMemory.put(tName, task); return true;
                    }
                    case "taskRunAt": {
                        String tName = stringify(args.get(0)); String fName = stringify(args.get(1));
                        LocalDateTime target = LocalDateTime.parse(stringify(args.get(2)).replace(" ", "T"));
                        long delay = Duration.between(LocalDateTime.now(), target).toMillis();
                        if (delay < 0) delay = 0;
                        if (schedulerMemory.containsKey(tName)) throw new RuntimeException("Task tồn tại!");
                        HqlTask task = new HqlTask(); task.name = tName; task.type = "runAt"; task.funcName = fName;
                        task.future = masterScheduler.schedule(() -> runTaskLogic.accept(task), delay, TimeUnit.MILLISECONDS);
                        schedulerMemory.put(tName, task); return true;
                    }
                    case "taskRepeat": {
                        String tName = stringify(args.get(0)); String fName = stringify(args.get(1)); long interval = (long) toDouble(args.get(2));
                        if (schedulerMemory.containsKey(tName)) throw new RuntimeException("Task tồn tại!");
                        HqlTask task = new HqlTask(); task.name = tName; task.type = "repeat"; task.funcName = fName;
                        task.future = masterScheduler.scheduleAtFixedRate(() -> runTaskLogic.accept(task), 0, interval, TimeUnit.MILLISECONDS);
                        schedulerMemory.put(tName, task); return true;
                    }
                    case "taskRepeatFixedDelay": {
                        String tName = stringify(args.get(0)); String fName = stringify(args.get(1)); long delay = (long) toDouble(args.get(2));
                        if (schedulerMemory.containsKey(tName)) throw new RuntimeException("Task tồn tại!");
                        HqlTask task = new HqlTask(); task.name = tName; task.type = "repeat_delay"; task.funcName = fName;
                        task.future = masterScheduler.scheduleWithFixedDelay(() -> runTaskLogic.accept(task), 0, delay, TimeUnit.MILLISECONDS);
                        schedulerMemory.put(tName, task); return true;
                    }
                    case "taskCancel": {
                        HqlTask t = schedulerMemory.remove(stringify(args.get(0)));
                        if (t != null && t.future != null) t.future.cancel(false);
                        return t != null;
                    }
                    case "taskCancelAll": {
                        for (HqlTask t : schedulerMemory.values()) if (t.future != null) t.future.cancel(false);
                        schedulerMemory.clear(); return true;
                    }
                    case "taskExists": return schedulerMemory.containsKey(stringify(args.get(0)));

                    // ==========================================
                    // NHÓM 2: CRON EXPRESSION (5 hàm)
                    // ==========================================
                    case "taskCron":
                    case "taskCronAt":
                    case "taskCronUntil": {
                        String tName = stringify(args.get(0)); String fName = stringify(args.get(1)); String cron = stringify(args.get(2));
                        LocalDateTime start = name.equals("taskCronAt") ? LocalDateTime.parse(stringify(args.get(3)).replace(" ", "T")) : null;
                        LocalDateTime end = name.equals("taskCronUntil") ? LocalDateTime.parse(stringify(args.get(3)).replace(" ", "T")) : null;

                        if (schedulerMemory.containsKey(tName)) throw new RuntimeException("Task tồn tại!");
                        HqlTask task = new HqlTask(); task.name = tName; task.type = "cron"; task.funcName = fName; task.cronExpr = cron;

                        // Tạo thread chạy kiểm tra mỗi giây
                        task.future = masterScheduler.scheduleAtFixedRate(() -> {
                            LocalDateTime now = LocalDateTime.now();
                            if (start != null && now.isBefore(start)) return;
                            if (end != null && now.isAfter(end)) { task.future.cancel(false); schedulerMemory.remove(tName); return; }

                            // Chỉ trigger ở giây 00 để tránh chạy trùng nhiều lần trong 1 phút
                            if (now.getSecond() == 0 && checkCron.test(cron, now)) {
                                runTaskLogic.accept(task);
                            }
                        }, 0, 1, TimeUnit.SECONDS);
                        schedulerMemory.put(tName, task); return true;
                    }
                    case "cronValidate": return stringify(args.get(0)).split("\\s+").length >= 5;
                    case "cronNextRun": {
                        // Tính toán mô phỏng next run
                        String cron = stringify(args.get(0));
                        LocalDateTime testTime = LocalDateTime.now().withSecond(0).plusMinutes(1);
                        for(int i=0; i<60*24*365; i++) { // Quét tối đa 1 năm
                            if (checkCron.test(cron, testTime)) return testTime.toString();
                            testTime = testTime.plusMinutes(1);
                        }
                        return "Không xác định";
                    }

                    // ==========================================
                    // NHÓM 3: ĐIỀU KHIỂN LUỒNG (5 hàm)
                    // ==========================================
                    case "taskPause": { HqlTask t = schedulerMemory.get(stringify(args.get(0))); if (t != null) t.isPaused = true; return true; }
                    case "taskResume": { HqlTask t = schedulerMemory.get(stringify(args.get(0))); if (t != null) t.isPaused = false; return true; }
                    case "taskFireNow": {
                        HqlTask t = schedulerMemory.get(stringify(args.get(0)));
                        if (t != null) masterScheduler.submit(() -> runTaskLogic.accept(t)); // Bắn ngay lập tức trên 1 Thread lẻ
                        return true;
                    }
                    case "taskSetMaxRuns": {
                        HqlTask t = schedulerMemory.get(stringify(args.get(0)));
                        if (t != null) t.maxRuns = (int) toDouble(args.get(1));
                        return true;
                    }
                    case "taskReschedule": {
                        // Hủy lịch cũ nhưng giữ nguyên trạng thái
                        HqlTask t = schedulerMemory.get(stringify(args.get(0)));
                        if (t != null && t.future != null) {
                            t.future.cancel(false);
                            String newCron = stringify(args.get(1));
                            t.cronExpr = newCron; t.type = "cron";
                            t.future = masterScheduler.scheduleAtFixedRate(() -> {
                                LocalDateTime now = LocalDateTime.now();
                                if (now.getSecond() == 0 && checkCron.test(newCron, now)) runTaskLogic.accept(t);
                            }, 0, 1, TimeUnit.SECONDS);
                        }
                        return true;
                    }

                    // ==========================================
                    // NHÓM 4: CHUỖI & PHỤ THUỘC (4 hàm)
                    // ==========================================
                    case "taskChain": {
                        HqlTask t = schedulerMemory.get(stringify(args.get(0)));
                        if (t != null) {
                            ArrayList<?> list = (ArrayList<?>) args.get(1);
                            for(Object o : list) t.onCompleteChain.add(stringify(o));
                        }
                        return true;
                    }
                    case "taskDepend": {
                        String parent = stringify(args.get(0)); String child = stringify(args.get(1));
                        masterScheduler.scheduleAtFixedRate(new Runnable() {
                            ScheduledFuture<?> checker;
                            @Override public void run() {
                                if (!schedulerMemory.containsKey(parent)) {
                                    try { executeFunctionCall(child, new ArrayList<>()); } catch (Exception e){}
                                    throw new RuntimeException("Tự hủy Checker"); // Mẹo văng lỗi để dừng luồng check
                                }
                            }
                        }, 0, 2, TimeUnit.SECONDS);
                        return true;
                    }
                    case "taskOnSuccess": { HqlTask t = schedulerMemory.get(stringify(args.get(0))); if (t != null) t.onSuccessFunc = stringify(args.get(1)); return true; }
                    case "taskOnFailure": { HqlTask t = schedulerMemory.get(stringify(args.get(0))); if (t != null) t.onFailureFunc = stringify(args.get(1)); return true; }

                    // ==========================================
                    // NHÓM 5: CALENDAR (4 hàm)
                    // ==========================================
                    case "taskWeekly":
                    case "taskMonthly":
                    case "taskLastDayOfMonth":
                    case "taskHoliday": {
                        String tName = stringify(args.get(0)); String fName = stringify(args.get(1));
                        if (schedulerMemory.containsKey(tName)) throw new RuntimeException("Task tồn tại!");
                        HqlTask task = new HqlTask(); task.name = tName; task.type = "calendar"; task.funcName = fName;

                        task.future = masterScheduler.scheduleAtFixedRate(() -> {
                            LocalDateTime now = LocalDateTime.now();
                            if (now.getSecond() != 0) return; // Chỉ check ở giây 00

                            boolean timeMatch = false;
                            if (name.equals("taskWeekly")) {
                                String dows = stringify(args.get(2)); String timeStr = stringify(args.get(3));
                                timeMatch = dows.contains(now.getDayOfWeek().toString().substring(0,3).toUpperCase()) && now.format(DateTimeFormatter.ofPattern("HH:mm")).equals(timeStr);
                            } else if (name.equals("taskMonthly")) {
                                int dom = (int) toDouble(args.get(2)); String timeStr = stringify(args.get(3));
                                timeMatch = (now.getDayOfMonth() == dom) && now.format(DateTimeFormatter.ofPattern("HH:mm")).equals(timeStr);
                            } else if (name.equals("taskLastDayOfMonth")) {
                                String timeStr = stringify(args.get(2));
                                int lastDay = YearMonth.from(now).lengthOfMonth();
                                timeMatch = (now.getDayOfMonth() == lastDay) && now.format(DateTimeFormatter.ofPattern("HH:mm")).equals(timeStr);
                            } else if (name.equals("taskHoliday")) {
                                ArrayList<?> hList = (ArrayList<?>) args.get(2); String timeStr = stringify(args.get(3));
                                String todayStr = now.toLocalDate().toString();
                                timeMatch = hList.contains(todayStr) && now.format(DateTimeFormatter.ofPattern("HH:mm")).equals(timeStr);
                            }

                            if (timeMatch) runTaskLogic.accept(task);
                        }, 0, 1, TimeUnit.SECONDS);
                        schedulerMemory.put(tName, task); return true;
                    }

                    // ==========================================
                    // NHÓM 6: PERSISTENCE (5 hàm)
                    // ==========================================
                    case "taskSaveState": {
                        StringBuilder sb = new StringBuilder("[\n");
                        for (HqlTask t : schedulerMemory.values()) {
                            sb.append("  {\"name\":\"").append(t.name).append("\", \"runs\":").append(t.runCount).append(", \"lastRun\":\"").append(t.lastRunStr).append("\"},\n");
                        }
                        if (sb.length() > 2) sb.setLength(sb.length() - 2); // Xóa phẩy cuối
                        sb.append("\n]");
                        Files.writeString(Paths.get(stringify(args.get(0))), sb.toString(), StandardCharsets.UTF_8);
                        return true;
                    }
                    case "taskEnablePersistence": {
                        HqlTask t = schedulerMemory.get(stringify(args.get(0)));
                        if (t != null) t.stateFile = stringify(args.get(1));
                        return true;
                    }
                    case "taskGetRuns": return schedulerMemory.containsKey(stringify(args.get(0))) ? (double) schedulerMemory.get(stringify(args.get(0))).runCount : 0.0;
                    case "taskResetStats": { HqlTask t = schedulerMemory.get(stringify(args.get(0))); if (t != null) t.runCount = 0; return true; }
                    case "taskLoadState": return true; // Giả lập load state (cần parse JSON, đã có hàm toJson/fromJson)

                    // ==========================================
                    // NHÓM 7: SONG SONG & PHÂN TÁN (5 hàm)
                    // ==========================================
                    case "taskRunParallel": {
                        ArrayList<?> funcList = (ArrayList<?>) args.get(0);
                        CountDownLatch latch = new CountDownLatch(funcList.size());
                        for (Object o : funcList) {
                            String f = stringify(o);
                            masterScheduler.submit(() -> {
                                try { executeFunctionCall(f, new ArrayList<>()); } catch(Exception e){} finally { latch.countDown(); }
                            });
                        }
                        try { latch.await(); } catch (InterruptedException e) {} // Chờ tất cả xong
                        return true;
                    }
                    case "taskAcquireLock": {
                        File lock = new File(stringify(args.get(1)));
                        if (lock.exists()) return false; // Lock thất bại
                        lock.createNewFile(); return true;
                    }
                    case "taskReleaseLock": return new File(stringify(args.get(1))).delete();
                    case "taskWaitFor": {
                        String other = stringify(args.get(1));
                        while (schedulerMemory.containsKey(other)) { Thread.sleep(100); } // Đợi tới khi task kia bay màu
                        return true;
                    }
                    case "taskBroadcastEvent": return true; // Đánh thức (Giả lập kiến trúc kiện)

                    // ==========================================
                    // NHÓM 8: GIÁM SÁT & ĐIỀU KHIỂN (7 hàm)
                    // ==========================================
                    case "taskList": {
                        ArrayList<String> list = new ArrayList<>();
                        for (HqlTask t : schedulerMemory.values()) {
                            list.add(t.name + " [" + t.type + "] - Paused: " + t.isPaused + " - Runs: " + t.runCount);
                        }
                        return list;
                    }
                    case "taskDetails": {
                        HqlTask t = schedulerMemory.get(stringify(args.get(0)));
                        if (t == null) return new HashMap<>();
                        HashMap<String, Object> map = new HashMap<>();
                        map.put("name", t.name); map.put("type", t.type);
                        map.put("isPaused", t.isPaused); map.put("runCount", (double)t.runCount);
                        map.put("lastRun", t.lastRunStr); map.put("func", t.funcName);
                        map.put("cron", t.cronExpr != null ? t.cronExpr : "N/A");
                        return map;
                    }
                    case "taskCount": return (double) schedulerMemory.size();
                    case "taskActiveCount": {
                        long active = 0;
                        for (HqlTask t : schedulerMemory.values()) {
                            if (!t.isPaused && t.future != null && !t.future.isDone()) active++;
                        }
                        return (double) active;
                    }
                    case "taskPauseAll": { for (HqlTask t : schedulerMemory.values()) t.isPaused = true; return true; }
                    case "taskResumeAll": { for (HqlTask t : schedulerMemory.values()) t.isPaused = false; return true; }
                    case "taskShutdown": {
                        masterScheduler.shutdownNow();
                        schedulerMemory.clear();
                        masterScheduler = Executors.newScheduledThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
                        return true;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Lỗi HQL.SCHEDULE: " + e.getMessage(), e);
            }
        }
        if (usedLibraries.contains("hql.web")) {
            try {
                switch (name) {
                    // ================= 1. MÁY CHỦ & ĐỊNH TUYẾN =================
                    case "webStart": {
                        // Cú pháp: webStart(cong, "/tenProject")  hoặc  webStart(cong, "/tenProject", "thuMucGoc")
                        if (args.size() < 2) {
                            throw new RuntimeException("Lỗi HQL.WEB: webStart() phải có tên project. "
                                    + "Ví dụ: webStart(8080, \"/QuanLyKho\", \"web\"); -> http://localhost:8080/QuanLyKho");
                        }
                        int port = (int) toDouble(args.get(0));
                        String ctxName = stringify(args.get(1)).trim();
                        if (!ctxName.startsWith("/")) ctxName = "/" + ctxName;
                        while (ctxName.endsWith("/")) ctxName = ctxName.substring(0, ctxName.length() - 1);
                        if (ctxName.isEmpty()) {
                            throw new RuntimeException("Lỗi HQL.WEB: Tên project không được để trống. Ví dụ: webStart(8080, \"/QuanLyKho\");");
                        }
                        if (webContainerMode) {
                            // Trong Tomcat thì máy chủ đã có sẵn, tên project lấy theo thư mục trong webapps/
                            System.out.println("[HQL.WEB] Đang chạy trong Tomcat — bỏ qua webStart(), giữ nguyên thư mục webapp.");
                            return true;
                        }
                        webAppContext = ctxName;
                        webRootDir = new File(args.size() > 2 ? stringify(args.get(2)) : "web").getAbsoluteFile();
                        startWebServer(port);
                        return true;
                    }
                    // ---- WEBSOCKET: máy chủ đẩy dữ liệu xuống trang ----
                    //
                    // wsStart(cong)      mở ổ cắm riêng (KHÔNG dùng chung cổng
                    //                    với webStart — xem chú thích ở lớp)
                    // wsGuiTatCa(chuoi)  đẩy một chuỗi xuống mọi trang đang mở,
                    //                    trả về số nơi nhận được
                    // wsSoKetNoi()       hiện có bao nhiêu trang đang nối
                    // wsStop()           đóng hết
                    case "wsStart": {
                        if (args.isEmpty()) {
                            throw new RuntimeException("Lỗi HQL.WS: wsStart() cần số cổng. Ví dụ: wsStart(8013);");
                        }
                        startWsServer((int) toDouble(args.get(0)));
                        return true;
                    }
                    case "wsGuiTatCa": return (double) wsGuiTatCa(stringify(args.get(0)));
                    case "wsSoKetNoi": return (double) wsClients.size();
                    case "wsStop":     stopWsServer(); return null;

                    case "webContext": return webCtx != null ? webCtx.contextPath : webAppContext;
                    case "webStop": stopWebServer(); return null;
                    case "webWait": {
                        // Giữ chương trình sống để phục vụ request (bấm Ctrl+C để dừng).
                        // Trong Tomcat thì bỏ qua, nếu không sẽ treo luôn lúc khởi động webapp.
                        if (webContainerMode) return null;
                        while (webHttpServer != null) Thread.sleep(500);
                        return null;
                    }
                    case "webRoot": {
                        if (!args.isEmpty() && !webContainerMode) webRootDir = new File(stringify(args.get(0))).getAbsoluteFile();
                        return webRootDir.getPath();
                    }
                    case "webRoute": {
                        webRoutes.put(stringify(args.get(0)), stringify(args.get(1)));
                        return null;
                    }
                    case "webServlet": {
                        webServletMap.put(stringify(args.get(0)), stringify(args.get(1)));
                        return null;
                    }
                    case "webExportTomcat": {
                        // webExportTomcat("TenProject")  hoặc  webExportTomcat("TenProject", "web.hql")
                        String dir = args.isEmpty() ? "webapp" : stringify(args.get(0));
                        String mainScript = args.size() > 1 ? stringify(args.get(1)) : entryScriptPath;
                        return exportTomcatWebapp(dir, mainScript);
                    }

                    // ================= 2. ĐỌC YÊU CẦU (REQUEST) =================
                    case "param": {
                        WebContext ctx = requireWeb("param");
                        String key = stringify(args.get(0));
                        String def = args.size() > 1 ? stringify(args.get(1)) : "";
                        String v = ctx.params.get(key);
                        return v == null || v.isEmpty() ? def : v;
                    }
                    case "params": return new LinkedHashMap<>(requireWeb("params").params);
                    case "webMethod": return requireWeb("webMethod").method;
                    case "webPath": return requireWeb("webPath").path;
                    case "webQuery": return requireWeb("webQuery").query;
                    case "webBody": return requireWeb("webBody").body;
                    case "webIP": return requireWeb("webIP").ip;
                    case "reqHeader": {
                        String v = requireWeb("reqHeader").headers.get(stringify(args.get(0)).toLowerCase());
                        return v == null ? "" : v;
                    }
                    case "cookieGet": {
                        String v = requireWeb("cookieGet").cookies.get(stringify(args.get(0)));
                        return v == null ? "" : v;
                    }

                    // ================= 3. TRẢ VỀ (RESPONSE) =================
                    case "printWeb": {
                        String text = args.isEmpty() ? "" : stringify(args.get(0));
                        if (webCtx != null) webCtx.out.append(text);
                        else System.out.print(text);   // Chạy ngoài web thì in ra màn hình cho dễ thử
                        return null;
                    }
                    case "webStatus": requireWeb("webStatus").status = (int) toDouble(args.get(0)); return null;

                    /*
                     * webHienLoiChiTiet(true|false)
                     *
                     * true  — trang lỗi bày đủ file, số dòng, trích đoạn mã nguồn và
                     *         ngăn xếp gọi hàm. Đang code ở máy nhà thì để true.
                     * false — chỉ hiện một câu chung chung cho người dùng cuối.
                     *         ĐƯA TRANG RA INTERNET LÀ PHẢI ĐẶT false: mã nguồn và
                     *         đường dẫn thật trên máy chủ không phải thứ để khoe.
                     * Báo cáo đầy đủ vẫn luôn được in ra nhật ký máy chủ.
                     */
                    case "webHienLoiChiTiet": {
                        webHienLoiChiTiet = args.isEmpty() || toBoolean(args.get(0));
                        return webHienLoiChiTiet;
                    }

                    /*
                     * webBaoLoi("câu báo lỗi")
                     *
                     * Chủ động dựng trang lỗi giống hệt lúc động cơ tự nổ lỗi — dùng
                     * khi mã HQL tự phát hiện ra tình huống không đi tiếp được.
                     */
                    case "webBaoLoi": {
                        WebContext c = requireWeb("webBaoLoi");
                        RuntimeException loi = new RuntimeException(stringify(args.get(0)));
                        System.err.println(BaoLoi.banChu(loi, vet().cuoi, vet().nganXep, true));
                        c.status = 500;
                        c.rawOut = null;
                        c.out.setLength(0);
                        c.contentType = "text/html; charset=UTF-8";
                        c.out.append(webHienLoiChiTiet
                                ? BaoLoi.banHtml(loi, vet().cuoi, vet().nganXep, c.path)
                                : BaoLoi.banHtmlGon(loi));
                        return null;
                    }
                    case "webType": requireWeb("webType").contentType = stringify(args.get(0)); return null;
                    case "resHeader": requireWeb("resHeader").resHeaders.put(stringify(args.get(0)), stringify(args.get(1))); return null;
                    case "webRedirect": {
                        WebContext ctx = requireWeb("webRedirect");
                        ctx.redirect = stringify(args.get(0));
                        ctx.status = 302;
                        return null;
                    }
                    case "cookieSet": {
                        WebContext ctx = requireWeb("cookieSet");
                        String cookie = stringify(args.get(0)) + "=" + URLEncoder.encode(stringify(args.get(1)), StandardCharsets.UTF_8) + "; Path=/";
                        if (args.size() > 2) cookie += "; Max-Age=" + (long) toDouble(args.get(2));
                        ctx.resCookies.add(cookie);
                        return null;
                    }
                    case "webJson": {
                        WebContext ctx = requireWeb("webJson");
                        ctx.contentType = "application/json; charset=UTF-8";
                        ctx.out.append(webToJson(args.isEmpty() ? null : args.get(0)));
                        return null;
                    }
                    // webJsonText("{...}") — tự dựng chuỗi JSON rồi trả về cho trình duyệt
                    case "webJsonText": {
                        WebContext ctx = requireWeb("webJsonText");
                        ctx.contentType = "application/json; charset=UTF-8";
                        ctx.resHeaders.put("Cache-Control", "no-store");
                        ctx.out.append(stringify(args.get(0)));
                        return null;
                    }
                    // webAnh(chuoiBase64[, kieu]) — giải mã chuỗi trong CSDL rồi trả về ảnh THẬT
                    case "webAnh":
                    case "webImage": {
                        WebContext ctx = requireWeb("webAnh");
                        String b64 = anhBoTienTo(stringify(args.get(0)));
                        byte[] raw = anhGiaiMa(b64);
                        ctx.contentType = args.size() > 1 && !stringify(args.get(1)).isEmpty()
                                ? stringify(args.get(1)) : anhDoanMime(b64);
                        ctx.resHeaders.put("Cache-Control", "public, max-age=86400");
                        ctx.rawOut = raw;
                        return (double) raw.length;
                    }
                    // webNhiPhan(chuoiBase64, kieu) — trả về file bất kỳ (PDF, zip...) từ chuỗi base64
                    case "webNhiPhan":
                    case "webBinary": {
                        WebContext ctx = requireWeb("webNhiPhan");
                        byte[] raw = anhGiaiMa(stringify(args.get(0)));
                        ctx.contentType = args.size() > 1 ? stringify(args.get(1)) : "application/octet-stream";
                        if (args.size() > 2) {
                            ctx.resHeaders.put("Content-Disposition",
                                    "attachment; filename=\"" + stringify(args.get(2)).replace("\"", "") + "\"");
                        }
                        ctx.rawOut = raw;
                        return (double) raw.length;
                    }
                    case "webInclude": {
                        requireWeb("webInclude");
                        File f = resolveWebFile(stringify(args.get(0)));
                        if (f == null || !f.isFile()) throw new RuntimeException("Không tìm thấy file để nhúng: " + stringify(args.get(0)));
                        if (f.getName().toLowerCase().endsWith(".hqlsp")) renderHqlsp(f, webCtx);
                        else webCtx.out.append(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                        return null;
                    }
                    case "webRender": {
                        // Chạy 1 file .hqlsp rồi trả về chuỗi HTML thay vì in thẳng ra
                        WebContext ctx = requireWeb("webRender");
                        File f = resolveWebFile(stringify(args.get(0)));
                        if (f == null || !f.isFile()) throw new RuntimeException("Không tìm thấy file: " + stringify(args.get(0)));
                        StringBuilder saved = ctx.out;
                        ctx.out = new StringBuilder();
                        try {
                            if (f.getName().toLowerCase().endsWith(".hqlsp")) renderHqlsp(f, ctx);
                            else ctx.out.append(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                            return ctx.out.toString();
                        } finally {
                            ctx.out = saved;
                        }
                    }

                    // ================= 4. PHIÊN LÀM VIỆC (SESSION) =================
                    case "sessionSet": ensureWebSession().put(stringify(args.get(0)), args.get(1)); return null;
                    case "sessionGet": {
                        WebContext ctx = requireWeb("sessionGet");
                        if (ctx.session == null) return null;
                        return ctx.session.get(stringify(args.get(0)));
                    }
                    case "sessionDel": {
                        WebContext ctx = requireWeb("sessionDel");
                        if (ctx.session != null) ctx.session.remove(stringify(args.get(0)));
                        return null;
                    }
                    case "sessionId": { ensureWebSession(); return webCtx.sessionId; }
                    case "sessionKill": {
                        WebContext ctx = requireWeb("sessionKill");
                        ctx.sessionKilled = true;
                        if (ctx.session != null) ctx.session.clear();
                        return null;
                    }

                    // ================= 5. TIỆN ÍCH =================
                    case "htmlEscape": return htmlEscape(args.isEmpty() ? "" : stringify(args.get(0)));
                    case "urlEncode": return URLEncoder.encode(stringify(args.get(0)), StandardCharsets.UTF_8);
                    case "urlDecode": return URLDecoder.decode(stringify(args.get(0)), StandardCharsets.UTF_8);
                    case "webMime": return webMimeType(stringify(args.get(0)));
                }
            } catch (RuntimeException e) {
                throw e;   // Giữ nguyên lỗi HQL bên trong servlet để báo đúng dòng
            } catch (Exception e) {
                throw new RuntimeException("Lỗi HQL.WEB: " + e.getMessage(), e);
            }
        }
        // ==========================================================================================
        //   THƯ VIỆN HQL.MAIL — GỬI EMAIL TỰ ĐỘNG (SMTP / Gmail App Password)
        // ==========================================================================================
        if (usedLibraries.contains("hql.mail")) {
            switch (name) {
                // --- 1. CẤU HÌNH HỘP THƯ GỬI ĐI ---
                case "mailConfig": {
                    // mailConfig("abc@gmail.com", "matKhauUngDung")  hoặc  (..., "smtp.gmail.com", 587)
                    mailer.configure(stringify(args.get(0)), stringify(args.get(1)),
                            args.size() > 2 ? stringify(args.get(2)) : null,
                            args.size() > 3 ? Integer.valueOf((int) toDouble(args.get(3))) : null);
                    return true;
                }
                case "mailConfigEnv": {
                    // Cấu hình theo tệp .henv: MAIL_EMAIL, MAIL_PASSWORD, MAIL_HOST, MAIL_PORT, MAIL_NAME
                    return mailApDungHenv();
                }
                case "mailWarmUp": {
                    // Mở sẵn kết nối SMTP ở luồng nền (gọi 1 lần khi khởi động) → thư đầu tiên gửi ngay, không chờ bắt tay TLS
                    mailTuHenvNeuChuaCoCauHinh();
                    mailer.warmUp();
                    return null;
                }
                case "mailSenderName": mailSenderName = stringify(args.get(0)); mailer.setSenderName(mailSenderName); return null;
                case "mailIsConfigured": mailTuHenvNeuChuaCoCauHinh(); return mailer.isConfigured();
                case "mailError": return mailer.lastError();

                // --- 2. TỆP ĐÍNH KÈM ---
                case "mailAttach": synchronized (mailAttachments) { mailAttachments.add(stringify(args.get(0))); } return true;
                case "mailClearAttach": synchronized (mailAttachments) { mailAttachments.clear(); } return null;

                // --- 3. TIỆN ÍCH ---
                case "mailOtp": {
                    int soChuSo = args.isEmpty() ? 6 : (int) toDouble(args.get(0));
                    if (soChuSo < 1 || soChuSo > 12) soChuSo = 6;
                    SecureRandom rnd = new SecureRandom();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < soChuSo; i++) sb.append(rnd.nextInt(10));
                    return sb.toString();
                }
                case "mailPassword": {
                    // Sinh mật khẩu ngẫu nhiên dễ đọc (có chữ hoa, chữ thường và số)
                    int doDai = args.isEmpty() ? 8 : (int) toDouble(args.get(0));
                    if (doDai < 6) doDai = 6;
                    String chuHoa = "ABCDEFGHJKLMNPQRSTUVWXYZ", chuThuong = "abcdefghijkmnpqrstuvwxyz", so = "23456789";
                    String tatCa = chuHoa + chuThuong + so;
                    SecureRandom rnd = new SecureRandom();
                    StringBuilder sb = new StringBuilder();
                    sb.append(chuHoa.charAt(rnd.nextInt(chuHoa.length())));
                    sb.append(chuThuong.charAt(rnd.nextInt(chuThuong.length())));
                    sb.append(so.charAt(rnd.nextInt(so.length())));
                    for (int i = 3; i < doDai; i++) sb.append(tatCa.charAt(rnd.nextInt(tatCa.length())));
                    return sb.toString();
                }
                case "mailIsValid": return stringify(args.get(0)).trim()
                        .matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

                // --- 4. GỬI THƯ ---
                // Thư viện KHÔNG tự thêm bất cứ thứ gì: tiêu đề, nội dung, HTML đều do người viết script quyết định.
                // Tiêu đề và nội dung có thể bỏ trống (để trống thì thư gửi đi đúng là trống).
                //   mailSend(den, tieuDe, htmlTuViet)      → nội dung được gửi NGUYÊN VĂN dạng HTML
                //   mailSendText(den, tieuDe, chuThuan)    → nội dung gửi dạng chữ thuần
                case "mailSend":
                    return sendHqlMail(mailArg(args, 0), mailArg(args, 1), mailArg(args, 2), true);
                case "mailSendText":
                    return sendHqlMail(mailArg(args, 0), mailArg(args, 1), mailArg(args, 2), false);
                case "mailSendAsync": {
                    // Xếp vào hàng đợi, một luồng nền gửi lần lượt trên kết nối SMTP dùng lại → trang web không đứng chờ Gmail
                    return sendHqlMailAsync(mailArg(args, 0), mailArg(args, 1), mailArg(args, 2), true);
                }
                case "mailSendAsyncText":
                    return sendHqlMailAsync(mailArg(args, 0), mailArg(args, 1), mailArg(args, 2), false);
            }
        }
        throw new RuntimeException("Lỗi: Hàm không có sẵn hoặc thư viện chưa được 'use'.");
    }

    // ==========================================================================================
    //   BỘ MÁY MÃ HOÁ ẢNH THÀNH CHUỖI KÝ TỰ (để cất vào cơ sở dữ liệu)
    // ==========================================================================================

    /** Bỏ phần đầu "data:image/png;base64," và mọi ký tự xuống dòng/khoảng trắng thừa. */
    private String anhBoTienTo(String s) {
        if (s == null) return "";
        String t = s.trim();
        int dau = t.indexOf("base64,");
        if (t.startsWith("data:") && dau > 0) t = t.substring(dau + 7);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '\n' && c != '\r' && c != ' ' && c != '\t') sb.append(c);
        }
        return sb.toString();
    }

    /** Chuỗi base64 (có hoặc không có data URI) -> mảng byte gốc của ảnh. */
    private byte[] anhGiaiMa(String s) {
        String b64 = anhBoTienTo(s);
        if (b64.isEmpty()) return new byte[0];
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return Base64.getMimeDecoder().decode(b64);   // Dự phòng cho chuỗi có ký tự lạ
        }
    }

    /** Nhận dạng định dạng ảnh bằng vài byte đầu tiên (magic number). */
    private String anhDoanMime(String base64) {
        byte[] b = anhGiaiMa(base64);
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        if (b.length >= 5 && b[0] == '<' && (b[1] == 's' || b[1] == '?')) return "image/svg+xml";
        return "image/png";
    }

    /** Thu nhỏ ảnh về trong khung rongToiDa x caoToiDa, giữ nguyên tỷ lệ. */
    private String anhThuNhoBase64(String base64, int maxW, int maxH) throws IOException {
        byte[] raw = anhGiaiMa(base64);
        if (raw.length == 0) return "";
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) return anhBoTienTo(base64);
        if (maxW < 1) maxW = 1;
        if (maxH < 1) maxH = 1;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW && h <= maxH) return anhBoTienTo(base64);   // Đã đủ nhỏ, khỏi động vào

        double ty = Math.min((double) maxW / w, (double) maxH / h);
        int nw = Math.max(1, (int) Math.round(w * ty));
        int nh = Math.max(1, (int) Math.round(h * ty));

        boolean coNenTrong = src.getColorModel().hasAlpha();
        BufferedImage ra = new BufferedImage(nw, nh, coNenTrong ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = ra.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!coNenTrong) { g.setColor(Color.WHITE); g.fillRect(0, 0, nw, nh); }
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(ra, coNenTrong ? "png" : "jpg", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    // ==========================================================================================
    //   BỘ MÁY GỬI EMAIL (dùng jakarta.mail trong lib/)
    // ==========================================================================================

    /** Bộ gửi thư dùng chung: giữ sẵn 1 kết nối SMTP, gửi nền bằng hàng đợi (xem HqlMailer). */
    private final HqlMailer mailer = new HqlMailer();
    private String mailSenderName = "";   // trống = hiện địa chỉ email; người dùng tự đặt bằng mailSenderName()
    { mailer.setSenderName(mailSenderName); }
    private final ArrayList<String> mailAttachments = new ArrayList<>();

    /** Lấy danh sách tệp đính kèm đang chờ rồi xoá (đính kèm chỉ áp dụng cho MỘT lần gửi kế tiếp). */
    private List<String> mailLayTepDinhKem() {
        synchronized (mailAttachments) {
            ArrayList<String> ds = new ArrayList<>(mailAttachments);
            mailAttachments.clear();
            return ds;
        }
    }

    /** Chưa gọi mailConfig mà .henv có MAIL_EMAIL / MAIL_PASSWORD thì tự cấu hình theo .henv. */
    private synchronized void mailTuHenvNeuChuaCoCauHinh() {
        if (mailer.isConfigured()) return;
        mailApDungHenv();
    }

    /** Đọc MAIL_* (hoặc khoá kiểu mail.sender.email) trong .henv. Trả về true nếu có đủ email + mật khẩu. */
    private synchronized boolean mailApDungHenv() {
        String email = HqlEnv.first(null, "MAIL_EMAIL", "MAIL_USER", "mail.sender.email");
        String pass  = HqlEnv.first(null, "MAIL_PASSWORD", "mail.sender.password");
        if (email == null || pass == null) return false;
        String host = HqlEnv.first(null, "MAIL_HOST", "mail.smtp.host");
        Integer port = null;
        String portText = HqlEnv.first(null, "MAIL_PORT", "mail.smtp.port");
        if (portText != null) { try { port = Integer.parseInt(portText.trim()); } catch (NumberFormatException ignore) { } }
        mailer.configure(email, pass, host, port);

        String ten = HqlEnv.first(null, "MAIL_NAME", "mail.sender.name");
        if (ten != null) { mailSenderName = ten; mailer.setSenderName(ten); }
        return true;
    }

    /** Gửi 1 email. Trả về true nếu thành công, false nếu lỗi (xem chi tiết bằng mailError()). */
    private boolean sendHqlMail(String toEmail, String subject, String content, boolean isHtml) {
        mailTuHenvNeuChuaCoCauHinh();
        return mailer.send(toEmail, subject, content, isHtml, mailLayTepDinhKem());
    }

    /** Xếp thư vào hàng đợi gửi nền, trả về ngay (true = đã nhận vào hàng đợi). */
    private boolean sendHqlMailAsync(String toEmail, String subject, String content, boolean isHtml) {
        mailTuHenvNeuChuaCoCauHinh();
        return mailer.sendAsync(toEmail, subject, content, isHtml, mailLayTepDinhKem());
    }

    /** Lấy tham số thứ i dạng chữ; thiếu tham số thì coi như chuỗi rỗng (cho phép thư không tiêu đề / không nội dung). */
    private String mailArg(List<Object> args, int i) {
        return (i < args.size() && args.get(i) != null) ? stringify(args.get(i)) : "";
    }

    private boolean evaluateBooleanExpression(List<Token> tokens) {
        tokens = trimExpressionNoise(trimWrappingParentheses(tokens));
        if (tokens.isEmpty()) return false;

        // 1. Tách theo OR và AND TRƯỚC TIÊN (Để giữ đúng nguyên tắc ưu tiên toán học)
        int logicIndex = findTopLevelOperator(tokens, "LOGIC", Arrays.asList("||"));
        if (logicIndex == -1) logicIndex = findTopLevelOperator(tokens, "LOGIC", Arrays.asList("&&"));

        if (logicIndex != -1) {
            boolean left = evaluateBooleanExpression(tokens.subList(0, logicIndex));
            String op = tokens.get(logicIndex).value;
            if (op.equals("&&") && !left) return false;
            if (op.equals("||") && left) return true;
            boolean right = evaluateBooleanExpression(tokens.subList(logicIndex + 1, tokens.size()));
            return op.equals("&&") ? left && right : left || right;
        }

        // 2. XỬ LÝ 'NOT' Ở ĐÂY (Chỉ xử lý sau khi đã băm nhỏ các vế AND, OR)
        if (tokens.get(0).type.equals("NOT") || (tokens.get(0).type.equals("KEYWORD") && tokens.get(0).value.equals("not"))) {
            return !evaluateBooleanExpression(tokens.subList(1, tokens.size()));
        }

        // 3. Xử lý so sánh
        int compareIndex = findTopLevelOperator(tokens, "COMPARE");
        if (compareIndex != -1) {
            Object left = resolveExpression(tokens.subList(0, compareIndex));
            Object right = resolveExpression(tokens.subList(compareIndex + 1, tokens.size()));
            return switch (tokens.get(compareIndex).value) {
                case "==" -> isEqual(left, right);
                case "!=" -> !isEqual(left, right);
                case ">" -> toDouble(left) > toDouble(right);
                case "<" -> toDouble(left) < toDouble(right);
                case ">=" -> toDouble(left) >= toDouble(right);
                case "<=" -> toDouble(left) <= toDouble(right);
                default -> false;
            };
        }
        return toBoolean(resolveExpression(tokens));
    }

    /**
     * Cắt một dãy token thành từng câu lệnh.
     * Ngoài dấu chấm phẩy / xuống dòng, còn cắt NGAY SAU dấu '}' đóng khối ngoài cùng.
     * Nhờ vậy viết dồn trên một dòng vẫn chạy đúng:
     *     if (a) { b(); } c();      -> trước đây c() bị nuốt mất
     * (Không cắt nếu ngay sau '}' là else / catch / finally vì chúng thuộc cùng một câu lệnh.)
     */
    private List<List<Token>> splitCommands(List<Token> tokens) {
        List<List<Token>> ketQua = new ArrayList<>();
        List<Token> cauLenh = new ArrayList<>();
        int paren = 0, bracket = 0, brace = 0;

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.type) {
                case "L_PAREN": paren++; break;
                case "R_PAREN": paren--; break;
                case "L_BRACKET": bracket++; break;
                case "R_BRACKET": bracket--; break;
                case "L_BRACE": brace++; break;
                case "R_BRACE": brace--; break;
            }

            // Dấu xuống dòng nằm TRONG dấu ngoặc ( ) hoặc [ ] không bao giờ là dấu kết thúc câu lệnh
            // -> bỏ hẳn đi, nhờ vậy viết một biểu thức dài trải trên nhiều dòng vẫn chạy:
            //     var kc = sqrt((a - b) * (a - b)
            //                 + (c - d) * (c - d));
            if (t.type.equals("END") && (paren > 0 || bracket > 0)) {
                continue;
            }

            if (t.type.equals("END") && paren == 0 && bracket == 0 && brace == 0) {
                // --- CHO PHÉP VIẾT MỘT BIỂU THỨC TRẢI TRÊN NHIỀU DÒNG ---
                // Nếu câu lệnh đang dở (kết thúc bằng toán tử) hoặc dòng kế tiếp mở đầu
                // bằng toán tử (+ - * / . && ...) thì KHÔNG cắt, coi như còn tiếp.
                boolean conTiep = false;
                if (!cauLenh.isEmpty()) {
                    Token cuoi = cauLenh.get(cauLenh.size() - 1);
                    if (cuoi.type.equals("OPERATOR") || cuoi.type.equals("COMPARE")
                            || cuoi.type.equals("LOGIC") || cuoi.type.equals("COMMA")
                            || cuoi.type.equals("ASSIGN") || cuoi.type.equals("ASSIGN_OP")
                            || cuoi.type.equals("DOT")) {
                        conTiep = true;
                    }
                }
                if (!conTiep) {
                    int j2 = i + 1;
                    while (j2 < tokens.size() && tokens.get(j2).type.equals("END")) j2++;
                    if (j2 < tokens.size()) {
                        Token sau = tokens.get(j2);
                        if (sau.type.equals("OPERATOR") || sau.type.equals("COMPARE")
                                || sau.type.equals("LOGIC") || sau.type.equals("DOT")) {
                            // Dấu +/- đứng đầu dòng chỉ là "nối tiếp" khi câu lệnh trước đó chưa trọn vẹn
                            if (!cauLenh.isEmpty()) conTiep = true;
                        }
                    }
                }
                if (conTiep) continue;

                ketQua.add(new ArrayList<>(cauLenh));
                cauLenh.clear();
                continue;
            }

            cauLenh.add(t);

            if (t.type.equals("R_BRACE") && brace == 0 && paren == 0 && bracket == 0) {
                int j = i + 1;
                while (j < tokens.size() && tokens.get(j).type.equals("END")) j++;
                String keTiep = (j < tokens.size()) ? tokens.get(j).value : "";
                if (!keTiep.equals("else") && !keTiep.equals("catch") && !keTiep.equals("finally")) {
                    ketQua.add(new ArrayList<>(cauLenh));
                    cauLenh.clear();
                }
            }
        }
        if (!cauLenh.isEmpty()) ketQua.add(cauLenh);
        return ketQua;
    }

    private Object getObjectValue(Token token) {
        return switch (token.type) {
            case "STRING" -> token.value;
            case "NUMBER" -> Double.parseDouble(token.value);
            case "IDENTIFIER" -> {
                // 1. Tự động kiểm tra xem có phải là biến trong bộ nhớ (Scope) không
                if (scope().has(token.value)) {
                    yield scope().get(token.value);
                }
                // 2. CƠ CHẾ THÔNG MINH: Nếu không phải biến user tạo, tự động check xem có phải Hằng Số Hệ Thống không!
                yield switch (token.value) {
                    case "PI" -> Math.PI;
                    case "E" -> Math.E;
                    case "TAU" -> Math.PI * 2.0;
                    case "INF" -> Double.POSITIVE_INFINITY;
                    case "NAN" -> Double.NaN;
                    case "undefined" -> "undefined";
                    default -> throw new RuntimeException("Lỗi: Không tìm thấy biến '" + token.value + "'");
                };
                // Chặn đầu ngay trước khi tìm trong Scope
            }
            case "KEYWORD" -> switch (token.value) {
                case "true" -> true;
                case "false" -> false;
                case "null" -> null;
                case "undefined" -> "undefined"; // Thêm dòng này
                default -> throw new IllegalStateException("Keyword không mong đợi: " + token.value);
            };
            default -> throw new IllegalStateException("Loại token không mong đợi: " + token.type);
        };
    }

    private String detectType(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return "string";
        if (obj instanceof Double) return "number";
        if (obj instanceof Boolean) return "bool";
        if (obj instanceof ArrayList) return "list";
        if (obj instanceof HqlObject) return ((HqlObject)obj).hqlClass.name;
        return "unknown";
    }

    private String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof ArrayList) return ((ArrayList<?>) value).stream().map(this::stringify).collect(Collectors.joining(", ", "[", "]"));
        // In Map (dòng dữ liệu SQL, JSON...) qua stringify để số 35.0 hiện đúng thành 35
        if (value instanceof Map) {
            return ((Map<?, ?>) value).entrySet().stream()
                    .map(e -> e.getKey() + "=" + stringify(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof Double) {
            double d = (Double) value;
            if (d == (long) d) return String.valueOf((long) d);
        }
        if (value instanceof HqlObject) return ((HqlObject) value).hqlClass.name + "@" + Integer.toHexString(value.hashCode());
        return String.valueOf(value);
    }

    private double toDouble(Object value) {
        if (value instanceof Double) return (Double) value;
        if (value instanceof String) {
            try { return Double.parseDouble((String) value); } catch (NumberFormatException e) { return 0.0; }
        }
        if (value instanceof Boolean) return (Boolean) value ? 1.0 : 0.0;
        return 0.0;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Double) return (Double) value != 0;
        return value != null;
    }

    private boolean isEqual(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Double && b instanceof String) return a.toString().equals(b);
        if (a instanceof String && b instanceof Double) return a.equals(b.toString());
        return a.equals(b);
    }

    private List<String> parseParamNames(List<Token> paramTokens) {
        if (paramTokens.isEmpty()) return new ArrayList<>();
        return splitTopLevel(paramTokens, "COMMA").stream().map(tokens -> tokens.get(0).value).collect(Collectors.toList());
    }

    private List<List<Token>> splitTopLevel(List<Token> tokens, String separatorType) {
        List<List<Token>> groups = new ArrayList<>();
        if (tokens.isEmpty()) return groups;
        List<Token> current = new ArrayList<>();
        int parenDepth = 0, bracketDepth = 0, braceDepth = 0;
        for (Token token : tokens) {
            if (token.type.equals("L_PAREN")) parenDepth++;
            if (token.type.equals("R_PAREN")) parenDepth--;
            if (token.type.equals("L_BRACKET")) bracketDepth++;
            if (token.type.equals("R_BRACKET")) bracketDepth--;
            if (token.type.equals("L_BRACE")) braceDepth++;
            if (token.type.equals("R_BRACE")) braceDepth--;
            if (token.type.equals(separatorType) && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                groups.add(new ArrayList<>(current));
                current.clear();
            } else {
                current.add(token);
            }
        }
        groups.add(current);
        return groups;
    }

    /**
     * Kiểm tra dấu +/- tại vị trí i có phải DẤU MỘT NGÔI hay không (ví dụ số -1 trong "x * -1").
     * Nguyên tắc: nếu ngay trước nó không có toán hạng nào kết thúc thì nó là dấu của con số.
     */
    private boolean laDauMotNgoi(List<Token> tokens, int i) {
        if (i == 0) return true;
        Token truoc = tokens.get(i - 1);
        switch (truoc.type) {
            case "OPERATOR": case "COMPARE": case "LOGIC": case "NOT":
            case "L_PAREN": case "L_BRACKET": case "L_BRACE":
            case "COMMA": case "ASSIGN": case "ASSIGN_OP":
            case "COLON": case "TERNARY_Q": case "END": case "RANGE": case "DOT":
                return true;
        }
        if (truoc.type.equals("KEYWORD")) {
            return Arrays.asList("return", "new", "not", "and", "or", "in", "to", "print",
                    "if", "while", "case", "throw").contains(truoc.value);
        }
        return false;
    }

    /**
     * Tìm toán tử HAI NGÔI ngoài cùng bên phải, tự động bỏ qua dấu một ngôi.
     * Nhờ hàm này mà "x * -1", "a - -3", "f(-2) + 1" đều chạy đúng.
     */
    private int findBinaryOperator(List<Token> tokens, List<String> values) {
        int parenDepth = 0, bracketDepth = 0, braceDepth = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.type.equals("R_PAREN")) parenDepth++;
            if (token.type.equals("L_PAREN")) parenDepth--;
            if (token.type.equals("R_BRACKET")) bracketDepth++;
            if (token.type.equals("L_BRACKET")) bracketDepth--;
            if (token.type.equals("R_BRACE")) braceDepth++;
            if (token.type.equals("L_BRACE")) braceDepth--;

            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0
                    && token.type.equals("OPERATOR") && values.contains(token.value)) {
                if (!laDauMotNgoi(tokens, i)) return i;
            }
        }
        return -1;
    }

    private int findTopLevelOperator(List<Token> tokens, String type) { return findTopLevelOperator(tokens, type, null); }
    private int findTopLevelOperator(List<Token> tokens, String type, List<String> values) {
        int parenDepth = 0, bracketDepth = 0, braceDepth = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.type.equals("R_PAREN")) parenDepth++;
            if (token.type.equals("L_PAREN")) parenDepth--;
            if (token.type.equals("R_BRACKET")) bracketDepth++;
            if (token.type.equals("L_BRACKET")) bracketDepth--;
            if (token.type.equals("R_BRACE")) braceDepth++;
            if (token.type.equals("L_BRACE")) braceDepth--;

            if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && token.type.equals(type) && (values == null || values.contains(token.value))) {
                return i;
            }
        }
        return -1;
    }

    private int findTokenIndex(List<Token> tokens, String type, int from) {
        for (int i = from; i < tokens.size(); i++) if (tokens.get(i).type.equals(type)) return i;
        return -1;
    }
    private int findMatchingBrace(List<Token> tokens, int openIndex) { return findMatching(tokens, openIndex, "L_BRACE", "R_BRACE"); }
    private int findMatchingBracket(List<Token> tokens, int openIndex) { return findMatching(tokens, openIndex, "L_BRACKET", "R_BRACKET"); }
    private int findMatching(List<Token> tokens, int openIndex, String open, String close) {
        int depth = 1;
        for (int i = openIndex + 1; i < tokens.size(); i++) {
            if (tokens.get(i).type.equals(open)) depth++;
            if (tokens.get(i).type.equals(close)) depth--;
            if (depth == 0) return i;
        }
        return -1;
    }

    private List<Token> trimWrappingParentheses(List<Token> tokens) {
        while (tokens.size() >= 2 && tokens.get(0).type.equals("L_PAREN") && findMatching(tokens, 0, "L_PAREN", "R_PAREN") == tokens.size() - 1) {
            tokens = tokens.subList(1, tokens.size() - 1);
        }
        return tokens;
    }

    private List<Token> extractCondition(List<Token> tokens, int start, int end) {
        List<Token> raw = tokens.subList(start, end);
        List<Token> filtered = new ArrayList<>();
        for (Token t : raw) {
            // Bỏ qua token END (xuống dòng) và các token không cần thiết khác nếu có
            if (!t.type.equals("END")) {
                filtered.add(t);
            }
        }
        return trimWrappingParentheses(filtered);
    }

    // --- CÁC HÀM TIỆN ÍCH XỬ LÝ TOÁN HỌC (THUẬT TOÁN BỔ SUNG) ---

    private long factorial(long n) {
        if (n < 0) throw new RuntimeException("Lỗi: Không thể tính giai thừa số âm.");
        long fact = 1;
        for (long i = 2; i <= n; i++) fact *= i;
        return fact;
    }

    private long gcd(long a, long b) {
        a = Math.abs(a); b = Math.abs(b);
        while (b != 0) {
            long temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    private long lcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return Math.abs(a * b) / gcd(a, b);
    }

    private long comb(long n, long k) {
        if (k < 0 || k > n) return 0;
        if (k == 0 || k == n) return 1;
        k = Math.min(k, n - k); // Tối ưu hóa nhờ tính chất đối xứng
        long c = 1;
        for (long i = 0; i < k; i++) {
            c = c * (n - i) / (i + 1);
        }
        return c;
    }

    // --- THÊM XUỐNG CUỐI FILE INTERPRETER.JAVA ---

    private long permHelper(long n, long k) {
        if (k < 0 || k > n) return 0;
        if (k == 0) return 1;
        long result = 1;
        for (long i = n; i > n - k; i--) {
            result *= i;
        }
        return result;
    }

    private boolean isCloseHelper(double a, double b) {
        double tolerance = 1e-9; // 0.000000001
        return Math.abs(a - b) <= tolerance;
    }

    // Thuật toán Kahan Summation triệt tiêu hoàn toàn sai số dấu phẩy động
    private double fsumHelper(ArrayList<?> list) {
        double sum = 0.0;
        double c = 0.0;
        for (Object obj : list) {
            double value = toDouble(obj);
            double y = value - c;
            double t = sum + y;
            c = (t - sum) - y;
            sum = t;
        }
        return sum;
    }

    // --- THUẬT TOÁN HỖ TRỢ NÉN VÀ GIẢI NÉN (ZIP) ---

    private void zipFolderHelper(Path source, Path target) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target.toFile()))) {
            Files.walk(source).filter(path -> !Files.isDirectory(path)).forEach(path -> {
                // Sửa đường dẫn để tương thích với cấu trúc nén của ZIP
                ZipEntry zipEntry = new ZipEntry(source.relativize(path).toString().replace("\\", "/"));
                try {
                    zos.putNextEntry(zipEntry);
                    Files.copy(path, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void unzipFolderHelper(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(source.toFile()))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                Path newPath = target.resolve(zipEntry.getName());
                if (zipEntry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    if (newPath.getParent() != null) Files.createDirectories(newPath.getParent());
                    Files.copy(zis, newPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zipEntry = zis.getNextEntry();
            }
        }
    }

    // --- THUẬT TOÁN HỖ TRỢ MẠNG (HQL.NET) ---
    private HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(netTimeoutMs));
        if (netProxy != null) builder.proxy(netProxy);
        if (netIgnoreSSL) {
            try {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return null; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                builder.sslContext(sc);
            } catch (Exception e) {}
        }
        return builder.build();
    }

    private void updateNetState(HttpResponse<?> res) {
        this.lastStatusCode = res.statusCode();
        this.lastHeaders = res.headers();
        this.lastContentType = res.headers().firstValue("Content-Type").orElse("");
    }

    // --- THUẬT TOÁN ĐỌC PHẦN CỨNG TỪ TERMINAL (WMI WINDOWS) ---
    // --- THUẬT TOÁN ĐỌC PHẦN CỨNG BẰNG POWERSHELL (HIỆN ĐẠI NHẤT) ---
    private String getSysInfo(String psCommand) {
        try {
            // Dùng ProcessBuilder để gọi PowerShell, bỏ qua Profile để chạy siêu tốc
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", psCommand);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            StringBuilder result = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    result.append(line).append(" ");
                }
            }
            return result.length() > 0 ? result.toString().trim() : "N/A";
        } catch (Exception e) {
            return "Lỗi: " + e.getMessage();
        }
    }

    // --- THUẬT TOÁN HỖ TRỢ HQL.UTIL ---
    private String hashHelper(String input, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) { return "Lỗi: " + e.getMessage(); }
    }

    private String toJsonHelper(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return "\"" + ((String)obj).replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        if (obj instanceof Double || obj instanceof Boolean) return obj.toString();
        if (obj instanceof ArrayList) {
            ArrayList<?> list = (ArrayList<?>) obj;
            return "[" + list.stream().map(this::toJsonHelper).collect(Collectors.joining(",")) + "]";
        }
        return "\"" + stringify(obj) + "\"";
    }

    // --- THUẬT TOÁN HỖ TRỢ HQL.CRYPTO ---
    private SecretKeySpec getAESKey(String myKey) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = sha.digest(myKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private LocalDateTime parseHqlDate(Object dt) {
        String s = stringify(dt);
        if (s.length() == 10) return LocalDate.parse(s).atStartOfDay();
        return LocalDateTime.parse(s.replace(" ", "T"));
    }

    // --- THUẬT TOÁN HỖ TRỢ HQL.LOGGING ---
    private int getLogLevelInt(String level) {
        switch(level.toLowerCase()) {
            case "debug": return 1;
            case "info": return 2;
            case "warning": return 3;
            case "error": return 4;
            case "off": return 5;
            default: return 2; // Mặc định là info
        }
    }

    private void printLog(String level, String msg) {
        int currentLvl = getLogLevelInt(loggingLevel);
        int msgLvl = getLogLevelInt(level);
        if (msgLvl < currentLvl) return; // Bỏ qua nếu mức log thấp hơn cấu hình hiện tại

        // Định dạng tin nhắn log
        String timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String formattedMsg = "[" + timeStamp + "] [" + level.toUpperCase() + "] " + msg;

        // Nếu có cài đặt file thì ghi ra file, ngược lại in ra console
        if (loggingFilePath != null) {
            try {
                Files.writeString(Paths.get(loggingFilePath), formattedMsg + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Lỗi ghi file log: " + e.getMessage());
            }
        } else {
            // FIX THỨ TỰ LOG: Đẩy tất cả ra luồng System.out để đồng bộ thời gian
            if (level.equals("error")) {
                // Thêm mã màu đỏ (\u001B[31m) và reset màu (\u001B[0m)
                System.out.println("\u001B[31m" + formattedMsg + "\u001B[0m");
            } else if (level.equals("warning")) {
                // Tặng thêm cho sếp mã màu vàng cho Warning cho xịn xò (\u001B[33m)
                System.out.println("\u001B[93m" + formattedMsg + "\u001B[0m");
            }else if (level.equals("debug")) {
                // Tặng thêm sếp màu Xanh Cyan (\u001B[36m) cho Debug nhìn cho khác bọt
                System.out.println("\u001B[36m" + formattedMsg + "\u001B[0m");
            } else {
                System.out.println(formattedMsg);
            }
        }
    }
    // 1. Hàm vẽ Rect cho chiều Raster -> SVG
    private void appendSvgRect(StringBuilder sb, int x, int y, int w, int h, int argb) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        double a = ((argb >> 24) & 0xff) / 255.0;
        sb.append(String.format("  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"rgb(%d,%d,%d)\" fill-opacity=\"%.2f\"/>\n", x, y, w, h, r, g, b, a));
    }

    // 2. Hàm set màu cho thẻ SVG (Hỗ trợ HEX và Tên màu)
    private void applySvgStyle(Graphics2D g, Element el) {
        if (el.hasAttribute("fill") && !el.getAttribute("fill").equals("none")) {
            g.setColor(parseColor(el.getAttribute("fill")));
        }
        if (el.hasAttribute("stroke") && !el.getAttribute("stroke").equals("none")) {
            Color strokeColor = parseColor(el.getAttribute("stroke"));
            float sw = el.hasAttribute("stroke-width") ? Float.parseFloat(el.getAttribute("stroke-width")) : 1.0f;
            g.setColor(strokeColor);
            g.setStroke(new java.awt.BasicStroke(sw));
        }
    }

    // 3. THUẬT TOÁN HẠT NHÂN: Regex dịch đường cong SVG sang Java Path
    private Path2D parseSvgPath(String d) {
        Path2D.Double path = new Path2D.Double();
        Matcher m = Pattern.compile("([a-zA-Z])|([-+]?[0-9]*\\.?[0-9]+)").matcher(d);
        char cmd = 'M';
        double curX = 0, curY = 0;
        double startX = 0, startY = 0;
        ArrayList<Double> args = new ArrayList<>();

        while (m.find()) {
            String token = m.group();
            if (token.matches("[a-zA-Z]")) {
                processPathArgs(path, cmd, args, curX, curY, startX, startY);
                // Cập nhật curX, curY sau khi xử lý (sẽ làm trong processPathArgs)
                cmd = token.charAt(0);
                args.clear();
            } else {
                args.add(Double.parseDouble(token));
            }
        }
        processPathArgs(path, cmd, args, curX, curY, startX, startY);
        return path;
    }

    private void processPathArgs(Path2D.Double path, char cmd, ArrayList<Double> args,
                                 double curX, double curY, double startX, double startY) {
        if (args.isEmpty()) return;
        int i = 0;
        while (i < args.size()) {
            switch (cmd) {
                case 'M': case 'm':
                    double x1 = (cmd == 'm') ? curX + args.get(i) : args.get(i);
                    double y1 = (cmd == 'm') ? curY + args.get(i+1) : args.get(i+1);
                    if (i == 0) { path.moveTo(x1, y1); startX = x1; startY = y1; }
                    else path.lineTo(x1, y1);
                    curX = x1; curY = y1;
                    i += 2;
                    break;
                case 'L': case 'l':
                    x1 = (cmd == 'l') ? curX + args.get(i) : args.get(i);
                    y1 = (cmd == 'l') ? curY + args.get(i+1) : args.get(i+1);
                    path.lineTo(x1, y1);
                    curX = x1; curY = y1;
                    i += 2;
                    break;
                case 'H': case 'h':
                    x1 = (cmd == 'h') ? curX + args.get(i) : args.get(i);
                    path.lineTo(x1, curY);
                    curX = x1;
                    i += 1;
                    break;
                case 'V': case 'v':
                    y1 = (cmd == 'v') ? curY + args.get(i) : args.get(i);
                    path.lineTo(curX, y1);
                    curY = y1;
                    i += 1;
                    break;
                case 'C': case 'c':
                    double cx1 = (cmd == 'c') ? curX + args.get(i) : args.get(i);
                    double cy1 = (cmd == 'c') ? curY + args.get(i+1) : args.get(i+1);
                    double cx2 = (cmd == 'c') ? curX + args.get(i+2) : args.get(i+2);
                    double cy2 = (cmd == 'c') ? curY + args.get(i+3) : args.get(i+3);
                    x1 = (cmd == 'c') ? curX + args.get(i+4) : args.get(i+4);
                    y1 = (cmd == 'c') ? curY + args.get(i+5) : args.get(i+5);
                    path.curveTo(cx1, cy1, cx2, cy2, x1, y1);
                    curX = x1; curY = y1;
                    i += 6;
                    break;
                case 'Q': case 'q':
                    cx1 = (cmd == 'q') ? curX + args.get(i) : args.get(i);
                    cy1 = (cmd == 'q') ? curY + args.get(i+1) : args.get(i+1);
                    x1 = (cmd == 'q') ? curX + args.get(i+2) : args.get(i+2);
                    y1 = (cmd == 'q') ? curY + args.get(i+3) : args.get(i+3);
                    path.quadTo(cx1, cy1, x1, y1);
                    curX = x1; curY = y1;
                    i += 4;
                    break;
                case 'S': case 's':
                    cx2 = (cmd == 's') ? curX + args.get(i) : args.get(i);
                    cy2 = (cmd == 's') ? curY + args.get(i+1) : args.get(i+1);
                    x1 = (cmd == 's') ? curX + args.get(i+2) : args.get(i+2);
                    y1 = (cmd == 's') ? curY + args.get(i+3) : args.get(i+3);
                    // Phản chiếu control point trước đó (đơn giản: dùng curX,curY làm control point 1)
                    double prevCx = curX + (curX - cx2); // xấp xỉ
                    double prevCy = curY + (curY - cy2);
                    path.curveTo(prevCx, prevCy, cx2, cy2, x1, y1);
                    curX = x1; curY = y1;
                    i += 4;
                    break;
                case 'Z': case 'z':
                    path.closePath();
                    curX = startX; curY = startY;
                    i = args.size(); // thoát
                    break;
                default:
                    i = args.size(); // bỏ qua lệnh không hỗ trợ
            }
        }
    }

    // --- THUẬT TOÁN HỖ TRỢ VẼ BIỂU ĐỒ (HQL.CHART) ---
    private void renderAndSaveChart(HqlChart chart, String path) throws Exception {
        BufferedImage img = new BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 1. Phủ nền trắng (Clear background)
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, chart.width, chart.height);

        // 2. Căn giữa tiêu đề (Title)
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 22));
        int titleWidth = g.getFontMetrics().stringWidth(chart.title);
        g.drawString(chart.title, (chart.width - titleWidth) / 2, 40);

        int padding = 70;
        int graphWidth = chart.width - padding * 2;
        int graphHeight = chart.height - padding * 2;

        if (chart.dataY.isEmpty() && !chart.type.equals("scatter")) {
            g.dispose();
            ImageIO.write(img, "png", new File(path));
            return;
        }

        switch (chart.type) {
            case "bar": {
                double maxVal = chart.dataY.stream().max(Double::compare).orElse(1.0);
                if (maxVal <= 0) maxVal = 1;
                int n = chart.dataY.size();
                int barWidth = Math.max(graphWidth / (n * 2), 10);

                // Trục X và Y
                g.setColor(Color.LIGHT_GRAY);
                g.drawLine(padding, padding, padding, chart.height - padding);
                g.drawLine(padding, chart.height - padding, chart.width - padding, chart.height - padding);

                for (int i = 0; i < n; i++) {
                    int barHeight = (int) ((chart.dataY.get(i) / maxVal) * graphHeight);
                    int x = padding + i * (barWidth * 2) + barWidth / 2;
                    int y = chart.height - padding - barHeight;

                    g.setColor(chart.color);
                    g.fillRect(x, y, barWidth, barHeight);
                    g.setColor(Color.DARK_GRAY);
                    g.drawRect(x, y, barWidth, barHeight);

                    // In Label xoay nhẹ hoặc in thẳng
                    if (i < chart.labels.size()) {
                        g.setColor(Color.BLACK);
                        g.setFont(new Font("Arial", Font.PLAIN, 12));
                        g.drawString(chart.labels.get(i), x, chart.height - padding + 20);
                    }
                }
                break;
            }
            case "pie": {
                double total = chart.dataY.stream().mapToDouble(Double::doubleValue).sum();
                int startAngle = 0;
                int r = Math.min(graphWidth, graphHeight);
                int cx = chart.width / 2 - r / 2;
                int cy = chart.height / 2 - r / 2 + 30;

                // Palette tự sinh dựa trên màu gốc (Làm mờ dần)
                Color[] palette = {
                        chart.color, new Color(255, 99, 132), new Color(54, 162, 235),
                        new Color(255, 206, 86), new Color(75, 192, 192), new Color(153, 102, 255)
                };

                for (int i = 0; i < chart.dataY.size(); i++) {
                    int arcAngle = (int) Math.round((chart.dataY.get(i) / total) * 360.0);
                    g.setColor(palette[i % palette.length]);
                    g.fillArc(cx, cy, r, r, startAngle, arcAngle);
                    startAngle += arcAngle;

                    // Vẽ chú giải (Legend)
                    if (i < chart.labels.size()) {
                        g.fillRect(padding, padding + i * 25, 12, 12);
                        g.setColor(Color.BLACK);
                        g.setFont(new Font("Arial", Font.PLAIN, 14));
                        g.drawString(chart.labels.get(i) + " (" + chart.dataY.get(i) + ")", padding + 20, padding + i * 25 + 11);
                    }
                }
                break;
            }
            case "line": {
                double maxVal = chart.dataY.stream().max(Double::compare).orElse(1.0);
                if (maxVal <= 0) maxVal = 1;
                int n = chart.dataY.size();
                int stepX = n > 1 ? graphWidth / (n - 1) : graphWidth;

                g.setColor(Color.LIGHT_GRAY);
                g.drawLine(padding, padding, padding, chart.height - padding);
                g.drawLine(padding, chart.height - padding, chart.width - padding, chart.height - padding);

                g.setColor(chart.color);
                g.setStroke(new java.awt.BasicStroke(3)); // Nét vẽ đậm
                for (int i = 0; i < n - 1; i++) {
                    int x1 = padding + i * stepX;
                    int y1 = chart.height - padding - (int) ((chart.dataY.get(i) / maxVal) * graphHeight);
                    int x2 = padding + (i + 1) * stepX;
                    int y2 = chart.height - padding - (int) ((chart.dataY.get(i + 1) / maxVal) * graphHeight);

                    g.drawLine(x1, y1, x2, y2);
                    g.fillOval(x1 - 4, y1 - 4, 8, 8);
                    if (i == n - 2) g.fillOval(x2 - 4, y2 - 4, 8, 8); // Điểm chốt cuối

                    if (i < chart.labels.size()) {
                        g.setColor(Color.BLACK);
                        g.setFont(new Font("Arial", Font.PLAIN, 12));
                        g.drawString(chart.labels.get(i), x1 - 10, chart.height - padding + 20);
                        g.setColor(chart.color);
                    }
                }
                if (n == 1 && !chart.labels.isEmpty()) {
                    g.setColor(Color.BLACK);
                    g.drawString(chart.labels.get(0), padding - 10, chart.height - padding + 20);
                }
                break;
            }
            case "scatter": {
                if (chart.dataX.isEmpty() || chart.dataY.isEmpty()) break;

                // Tìm Min và Max
                double minX = chart.dataX.stream().min(Double::compare).orElse(0.0);
                double maxX = chart.dataX.stream().max(Double::compare).orElse(1.0);
                double minY = chart.dataY.stream().min(Double::compare).orElse(0.0);
                double maxY = chart.dataY.stream().max(Double::compare).orElse(1.0);

                if (maxX == minX) { maxX += 10; minX -= 10; }
                if (maxY == minY) { maxY += 10; minY -= 10; }

                double rangeX = maxX - minX;
                double rangeY = maxY - minY;

                // --- NÂNG CẤP: VẼ LƯỚI (GRID) VÀ ĐÁNH SỐ TRỤC ---
                g.setFont(new Font("Arial", Font.PLAIN, 12));
                int gridSteps = 5; // Chia làm 5 khoảng lưới

                // Vẽ trục Y (Ví dụ: Cân nặng)
                for (int i = 0; i <= gridSteps; i++) {
                    int y = chart.height - padding - (i * graphHeight / gridSteps);
                    double valY = minY + (i * rangeY / gridSteps);

                    g.setColor(new Color(230, 230, 230)); // Đường lưới mờ
                    g.drawLine(padding, y, chart.width - padding, y);

                    g.setColor(Color.BLACK); // Ghi số
                    g.drawString(String.format("%.1f", valY), padding - 35, y + 5);
                }

                // Vẽ trục X (Ví dụ: Chiều cao)
                for (int i = 0; i <= gridSteps; i++) {
                    int x = padding + (i * graphWidth / gridSteps);
                    double valX = minX + (i * rangeX / gridSteps);

                    g.setColor(new Color(230, 230, 230)); // Đường lưới dọc mờ
                    g.drawLine(x, padding, x, chart.height - padding);

                    g.setColor(Color.BLACK); // Ghi số
                    g.drawString(String.format("%.1f", valX), x - 15, chart.height - padding + 20);
                }

                // Vẽ trục toạ độ chính đậm hơn
                g.setColor(Color.DARK_GRAY);
                g.drawLine(padding, padding, padding, chart.height - padding);
                g.drawLine(padding, chart.height - padding, chart.width - padding, chart.height - padding);

                // Vẽ các điểm dữ liệu
                g.setColor(chart.color);
                for (int i = 0; i < Math.min(chart.dataX.size(), chart.dataY.size()); i++) {
                    int x = padding + (int) (((chart.dataX.get(i) - minX) / rangeX) * graphWidth);
                    int y = chart.height - padding - (int) (((chart.dataY.get(i) - minY) / rangeY) * graphHeight);

                    g.fillOval(x - 5, y - 5, 10, 10);
                }
                break;
            }
            case "histogram": {
                double min = chart.dataY.stream().min(Double::compare).orElse(0.0);
                double max = chart.dataY.stream().max(Double::compare).orElse(1.0);
                if (max == min) max = min + 1;

                int[] freqs = new int[chart.bins];
                double binSize = (max - min) / chart.bins;
                for (double val : chart.dataY) {
                    int bin = (int) ((val - min) / binSize);
                    if (bin >= chart.bins) bin = chart.bins - 1;
                    freqs[bin]++;
                }

                int maxFreq = 0;
                for (int f : freqs) if (f > maxFreq) maxFreq = f;
                if (maxFreq == 0) maxFreq = 1;

                // --- TÍNH TOÁN KHOẢNG CÁCH (GAP) ---
                int barGap = 10; // Sếp có thể tăng giảm số này để dời các cột ra xa/gần
                int totalGapWidth = barGap * (chart.bins - 1);
                int barWidth = (graphWidth - totalGapWidth) / chart.bins;

                // Vẽ trục Y (Tần suất)
                g.setFont(new Font("Arial", Font.PLAIN, 12));
                int ySteps = Math.min(maxFreq, 5);
                for (int i = 0; i <= ySteps; i++) {
                    int y = chart.height - padding - (i * graphHeight / ySteps);
                    int valY = i * maxFreq / ySteps;
                    g.setColor(new Color(230, 230, 230));
                    g.drawLine(padding, y, chart.width - padding, y);
                    g.setColor(Color.BLACK);
                    g.drawString(String.valueOf(valY), padding - 25, y + 5);
                }

                // Trục tọa độ chính
                g.setColor(Color.DARK_GRAY);
                g.drawLine(padding, padding, padding, chart.height - padding);
                g.drawLine(padding, chart.height - padding, chart.width - padding, chart.height - padding);

                for (int i = 0; i < chart.bins; i++) {
                    int barHeight = (int) (((double) freqs[i] / maxFreq) * graphHeight);
                    // Cộng thêm barGap vào vị trí X để đẩy các cột ra
                    int x = padding + i * (barWidth + barGap);
                    int y = chart.height - padding - barHeight;

                    g.setColor(chart.color);
                    g.fillRect(x, y, barWidth, barHeight);

                    // Vẽ viền cột đậm hơn cho rõ khối
                    g.setColor(chart.color.darker());
                    g.drawRect(x, y, barWidth, barHeight);

                    // Ghi nhãn khoảng giá trị
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Arial", Font.PLAIN, 11));
                    String rangeLabel = String.format("%.1f-%.1f", min + i * binSize, min + (i + 1) * binSize);
                    int textWidth = g.getFontMetrics().stringWidth(rangeLabel);
                    // Căn giữa nhãn theo cột
                    g.drawString(rangeLabel, x + (barWidth - textWidth) / 2, chart.height - padding + 20);
                }
                break;
            }
        }

        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    // --- THUẬT TOÁN PARSE CSV CHUẨN QUỐC TẾ (HỖ TRỢ DẤU PHẨY TRONG NGOẶC KÉP) ---
    private ArrayList<String> parseCsvLineHelper(String line, String delimiter) {
        ArrayList<String> result = new ArrayList<>();
        if (line == null || line.isEmpty()) return result;
        char delim = delimiter.charAt(0);
        StringBuilder curVal = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '\"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                        curVal.append('\"'); i++; // Bỏ qua nháy kép thừa
                    } else {
                        inQuotes = false;
                    }
                } else curVal.append(ch);
            } else {
                if (ch == '\"') inQuotes = true;
                else if (ch == delim) {
                    result.add(curVal.toString());
                    curVal.setLength(0);
                } else curVal.append(ch);
            }
        }
        result.add(curVal.toString());
        return result;
    }

    private String toCsvFieldHelper(String field, String delimiter) {
        if (field == null) return "";
        if (field.contains(delimiter) || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    // --- HELPER CHO THỐNG KÊ & MA TRẬN ---
    private double meanHelper(ArrayList<?> list) {
        double sum = 0;
        for (Object o : list) sum += toDouble(o);
        return sum / list.size();
    }

    @SuppressWarnings("unchecked")
    private ArrayList<ArrayList<Double>> convertToMatrix(Object obj) {
        ArrayList<ArrayList<?>> input = (ArrayList<ArrayList<?>>) obj;
        ArrayList<ArrayList<Double>> mat = new ArrayList<>();
        for (ArrayList<?> row : input) {
            ArrayList<Double> newRow = new ArrayList<>();
            for (Object o : row) newRow.add(toDouble(o));
            mat.add(newRow);
        }
        return mat;
    }

    private double determinantHelper(ArrayList<ArrayList<Double>> M) {
        int n = M.size();
        if (n == 1) return M.get(0).get(0);
        if (n == 2) return M.get(0).get(0) * M.get(1).get(1) - M.get(0).get(1) * M.get(1).get(0);
        double det = 0;
        for (int j = 0; j < n; j++) {
            det += Math.pow(-1, j) * M.get(0).get(j) * determinantHelper(minorMatrix(M, 0, j));
        }
        return det;
    }

    private ArrayList<ArrayList<Double>> minorMatrix(ArrayList<ArrayList<Double>> M, int rowToRemove, int colToRemove) {
        int n = M.size();
        ArrayList<ArrayList<Double>> minor = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i == rowToRemove) continue;
            ArrayList<Double> newRow = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (j == colToRemove) continue;
                newRow.add(M.get(i).get(j));
            }
            minor.add(newRow);
        }
        return minor;
    }

    // --- HELPER CHO XML ---
    private HashMap<String, Object> xmlParseHelper(String xmlStr) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xmlStr)));
        return domToMapHelper(doc.getDocumentElement());
    }

    private HashMap<String, Object> domToMapHelper(org.w3c.dom.Element element) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("@name", element.getTagName());
        HashMap<String, String> attrs = new HashMap<>();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            org.w3c.dom.Node attr = element.getAttributes().item(i);
            attrs.put(attr.getNodeName(), attr.getNodeValue());
        }
        map.put("@attrs", attrs);
        ArrayList<HashMap<String, Object>> children = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        org.w3c.dom.NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            org.w3c.dom.Node child = childNodes.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                children.add(domToMapHelper((org.w3c.dom.Element) child));
            } else if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
                text.append(child.getNodeValue().trim());
            }
        }
        map.put("@children", children);
        map.put("@text", text.toString());
        return map;
    }

    private String xmlToStringHelper(HashMap<String, Object> node, String rootName, int indent) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        String indentStr = "  ".repeat(indent);
        String name = node.containsKey("@name") ? stringify(node.get("@name")) : rootName;

        sb.append(indentStr).append("<").append(name);
        if (node.containsKey("@attrs")) {
            HashMap<String, String> attrs = (HashMap<String, String>) node.get("@attrs");
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                sb.append(" ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
            }
        }

        ArrayList<HashMap<String, Object>> children = (ArrayList<HashMap<String, Object>>) node.get("@children");
        String text = node.containsKey("@text") ? stringify(node.get("@text")) : "";

        if ((children == null || children.isEmpty()) && text.isEmpty()) {
            sb.append("/>\n");
        } else {
            sb.append(">");
            if (children != null && !children.isEmpty()) {
                sb.append("\n");
                for (HashMap<String, Object> child : children) {
                    sb.append(xmlToStringHelper(child, "node", indent + 1));
                }
                sb.append(indentStr);
            } else {
                sb.append(text);
            }
            sb.append("</").append(name).append(">\n");
        }
        return sb.toString();
    }

    private ArrayList<String> xmlXpathHelper(String pathOrXml, String expression, boolean isAttr, String attrName) throws Exception {
        String xmlStr = pathOrXml;
        if (Files.exists(Paths.get(pathOrXml))) xmlStr = Files.readString(Paths.get(pathOrXml));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xmlStr)));
        javax.xml.xpath.XPath xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        org.w3c.dom.NodeList nodes = (org.w3c.dom.NodeList) xpath.evaluate(expression, doc, javax.xml.xpath.XPathConstants.NODESET);
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Node n = nodes.item(i);
            if (isAttr && n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                result.add(((org.w3c.dom.Element)n).getAttribute(attrName));
            } else {
                result.add(n.getTextContent().trim());
            }
        }
        return result;
    }

    private HashMap<String, Object> xmlGetNodeHelper(HashMap<String, Object> root, String path) {
        if (path.equals(".") || path.isEmpty()) return root;
        String[] parts = path.split("/");
        HashMap<String, Object> current = root;
        for (String part : parts) {
            if (current == null) return null;
            String tagName = part;
            int index = 0;
            if (part.contains("[")) {
                tagName = part.substring(0, part.indexOf("["));
                index = Integer.parseInt(part.substring(part.indexOf("[") + 1, part.indexOf("]")));
            }
            ArrayList<HashMap<String, Object>> children = (ArrayList<HashMap<String, Object>>) current.get("@children");
            if (children == null) return null;
            int count = 0;
            HashMap<String, Object> found = null;
            for (HashMap<String, Object> child : children) {
                if (stringify(child.get("@name")).equals(tagName)) {
                    if (count == index) { found = child; break; }
                    count++;
                }
            }
            current = found;
        }
        return current;
    }

    private void xmlFindNodesHelper(HashMap<String, Object> node, String tagName, ArrayList<HashMap<String, Object>> result) {
        if (node == null) return;
        if (stringify(node.get("@name")).equals(tagName)) result.add(node);
        ArrayList<HashMap<String, Object>> children = (ArrayList<HashMap<String, Object>>) node.get("@children");
        if (children != null) {
            for (HashMap<String, Object> child : children) xmlFindNodesHelper(child, tagName, result);
        }
    }

    private void xmlRemoveNodeHelper(HashMap<String, Object> root, String path) {
        if (path.equals(".") || path.isEmpty()) return;
        int lastSlash = path.lastIndexOf("/");
        String parentPath = lastSlash == -1 ? "." : path.substring(0, lastSlash);
        String targetPart = lastSlash == -1 ? path : path.substring(lastSlash + 1);

        HashMap<String, Object> parent = xmlGetNodeHelper(root, parentPath);
        if (parent == null || !parent.containsKey("@children")) return;

        String tagName = targetPart;
        int index = 0;
        if (targetPart.contains("[")) {
            tagName = targetPart.substring(0, targetPart.indexOf("["));
            index = Integer.parseInt(targetPart.substring(targetPart.indexOf("[") + 1, targetPart.indexOf("]")));
        }

        ArrayList<HashMap<String, Object>> children = (ArrayList<HashMap<String, Object>>) parent.get("@children");
        int count = 0;
        for (int i = 0; i < children.size(); i++) {
            if (stringify(children.get(i).get("@name")).equals(tagName)) {
                if (count == index) { children.remove(i); break; }
                count++;
            }
        }
    }

    private HashMap<String, Object> jsonToXmlHelper(String jsonStr, String rootName) {
        // Tái sử dụng Parser đơn giản từ HQL.CSV
        HashMap<String, Object> root = new HashMap<>();
        root.put("@name", rootName);
        root.put("@attrs", new HashMap<String, String>());
        root.put("@text", "");
        ArrayList<HashMap<String, Object>> children = new ArrayList<>();

        Matcher m = Pattern.compile("\"(.*?)\"\\s*:\\s*(\".*?\"|[0-9.]+|true|false|null)").matcher(jsonStr);
        while (m.find()) {
            String k = m.group(1);
            String v = m.group(2);
            if (v.startsWith("\"")) v = v.substring(1, v.length() - 1);

            HashMap<String, Object> child = new HashMap<>();
            child.put("@name", k);
            child.put("@attrs", new HashMap<String, String>());
            child.put("@text", v);
            child.put("@children", new ArrayList<>());
            children.add(child);
        }
        root.put("@children", children);
        return root;
    }

    // --- HELPER CHO BINARY ---
    private byte[] hexToBytesHelper(String hex) {
        hex = hex.replaceAll("\\s+", "");
        if (hex.length() % 2 != 0) hex = "0" + hex;
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private String bytesToHexHelper(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for(byte b: bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private String hashBytesHelper(byte[] data, String algo) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
        return bytesToHexHelper(md.digest(data));
    }

    private String hexDumpHelper(byte[] data, int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += width) {
            sb.append(String.format("%08X  ", i)); // Offset
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < width; j++) {
                if (i + j < data.length) {
                    byte b = data[i + j];
                    sb.append(String.format("%02X ", b));
                    if (b >= 32 && b <= 126) ascii.append((char) b);
                    else ascii.append(".");
                } else {
                    sb.append("   ");
                }
                if (j == width / 2 - 1) sb.append(" ");
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        return sb.toString();
    }

    private byte[] packHelper(String format, ArrayList<?> values) throws Exception {
        boolean isBigEndian = !format.startsWith("<");
        if (format.startsWith("<") || format.startsWith(">")) format = format.substring(1);
        String[] tokens = format.trim().split("\\s+");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int valIdx = 0;

        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            int count = 1;
            char type = tok.charAt(tok.length() - 1);
            if (tok.length() > 1) {
                try { count = Integer.parseInt(tok.substring(0, tok.length() - 1)); } catch (Exception ignored) {}
            }

            if (type == 's') { // Xử lý chuỗi
                String str = valIdx < values.size() ? stringify(values.get(valIdx++)) : "";
                byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
                byte[] out = new byte[count];
                System.arraycopy(strBytes, 0, out, 0, Math.min(strBytes.length, count));
                baos.write(out, 0, out.length);
                continue;
            }

            for (int i = 0; i < count; i++) {
                Object v = valIdx < values.size() ? values.get(valIdx++) : 0.0;
                java.nio.ByteBuffer bb;
                switch(type) {
                    case 'b': bb = java.nio.ByteBuffer.allocate(1); bb.put((byte)toDouble(v)); break;
                    case '?': bb = java.nio.ByteBuffer.allocate(1); bb.put((byte)(toBoolean(v) ? 1 : 0)); break;
                    case 'h': bb = java.nio.ByteBuffer.allocate(2); bb.order(isBigEndian ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN); bb.putShort((short)toDouble(v)); break;
                    case 'i': bb = java.nio.ByteBuffer.allocate(4); bb.order(isBigEndian ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN); bb.putInt((int)toDouble(v)); break;
                    case 'q': bb = java.nio.ByteBuffer.allocate(8); bb.order(isBigEndian ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN); bb.putLong((long)toDouble(v)); break;
                    case 'f': bb = java.nio.ByteBuffer.allocate(4); bb.order(isBigEndian ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN); bb.putFloat((float)toDouble(v)); break;
                    case 'd': bb = java.nio.ByteBuffer.allocate(8); bb.order(isBigEndian ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN); bb.putDouble((double)toDouble(v)); break;
                    default: throw new RuntimeException("Format Pack không hợp lệ: " + type);
                }
                baos.write(bb.array(), 0, bb.capacity());
            }
        }
        return baos.toByteArray();
    }

    private ArrayList<Object> unpackHelper(String format, byte[] data) {
        boolean isBigEndian = !format.startsWith("<");
        if (format.startsWith("<") || format.startsWith(">")) format = format.substring(1);
        String[] tokens = format.trim().split("\\s+");
        ArrayList<Object> res = new ArrayList<>();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data);
        bb.order(isBigEndian ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN);

        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            int count = 1;
            char type = tok.charAt(tok.length() - 1);
            if (tok.length() > 1) {
                try { count = Integer.parseInt(tok.substring(0, tok.length() - 1)); } catch (Exception ignored) {}
            }
            if (type == 's') {
                byte[] sBytes = new byte[count];
                bb.get(sBytes);
                // Loại bỏ byte null (0x00) thừa ở cuối chuỗi
                int len = 0;
                while (len < sBytes.length && sBytes[len] != 0) len++;
                res.add(new String(sBytes, 0, len, StandardCharsets.UTF_8));
                continue;
            }
            for (int i = 0; i < count; i++) {
                switch(type) {
                    case 'b': res.add((double)(bb.get() & 0xFF)); break;
                    case '?': res.add(bb.get() != 0); break;
                    case 'h': res.add((double)bb.getShort()); break;
                    case 'i': res.add((double)bb.getInt()); break;
                    case 'q': res.add((double)bb.getLong()); break;
                    case 'f': res.add((double)bb.getFloat()); break;
                    case 'd': res.add(bb.getDouble()); break;
                    default: throw new RuntimeException("Format Unpack không hợp lệ: " + type);
                }
            }
        }
        return res;
    }

    private int calcSizeHelper(String format) {
        if (format.startsWith("<") || format.startsWith(">")) format = format.substring(1);
        String[] tokens = format.trim().split("\\s+");
        int size = 0;
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            int count = 1;
            char type = tok.charAt(tok.length() - 1);
            if (tok.length() > 1) {
                try { count = Integer.parseInt(tok.substring(0, tok.length() - 1)); } catch (Exception ignored) {}
            }
            switch(type) {
                case 'b': case '?': size += count * 1; break;
                case 'h': size += count * 2; break;
                case 'i': case 'f': size += count * 4; break;
                case 'q': case 'd': size += count * 8; break;
                case 's': size += count; break; // Chuỗi s chiếm đúng count byte
            }
        }
        return size;
    }

    private double getUnitFactor(String unit) {
        switch(unit) {
            // Chiều dài (chuẩn: meter)
            case "m": return 1.0;
            case "km": return 1000.0;
            case "cm": return 0.01;
            case "mm": return 0.001;
            case "inch": return 0.0254;
            case "ft": return 0.3048;
            case "yard": return 0.9144;
            case "mile": return 1609.34;
            // Khối lượng (chuẩn: kg)
            case "kg": return 1.0;
            case "g": return 0.001;
            case "lb": return 0.453592;
            case "oz": return 0.0283495;
            // Thể tích (chuẩn: liter)
            case "l": return 1.0;
            case "ml": return 0.001;
            case "gal": return 3.78541;
            default: throw new RuntimeException("Lỗi: Không hỗ trợ đơn vị quy đổi '" + unit + "'. (Dùng listUnits() để xem các đơn vị được hỗ trợ)");
        }
    }

    // ==========================================
    // HELPER METHODS CHO HQL.COLOR
    // ==========================================
    private String colorRgbToHexHelper(int r, int g, int b) {
        return String.format("#%02X%02X%02X", Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
    }

    private int[] colorHexToRgbHelper(String hex) {
        hex = hex.replace("#", "").trim();
        if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        if (hex.length() >= 6) {
            return new int[]{Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16)};
        }
        return new int[]{0,0,0};
    }

    private ArrayList<Object> colorRgbToHsvHelper(int r, int g, int b) {
        float[] hsv = java.awt.Color.RGBtoHSB(r, g, b, null);
        return new ArrayList<>(java.util.Arrays.asList((double)(hsv[0]*360), (double)hsv[1], (double)hsv[2]));
    }

    private ArrayList<Object> colorHsvToRgbHelper(double h, double s, double v) {
        int rgb = java.awt.Color.HSBtoRGB((float)(h/360.0), (float)s, (float)v);
        return new ArrayList<>(java.util.Arrays.asList((double)((rgb>>16)&0xFF), (double)((rgb>>8)&0xFF), (double)(rgb&0xFF)));
    }

    private ArrayList<Object> colorRgbToHslHelper(int r, int g, int b) {
        double rD = r/255.0, gD = g/255.0, bD = b/255.0;
        double max = Math.max(rD, Math.max(gD, bD)), min = Math.min(rD, Math.min(gD, bD));
        double h = 0, s = 0, l = (max + min) / 2.0;
        if (max != min) {
            double d = max - min;
            s = l > 0.5 ? d / (2.0 - max - min) : d / (max + min);
            if (max == rD) h = (gD - bD) / d + (gD < bD ? 6 : 0);
            else if (max == gD) h = (bD - rD) / d + 2;
            else if (max == bD) h = (rD - gD) / d + 4;
            h /= 6.0;
        }
        return new ArrayList<>(java.util.Arrays.asList(h*360.0, s, l));
    }

    private ArrayList<Object> colorHslToRgbHelper(double h, double s, double l) {
        h /= 360.0;
        double r, g, b;
        if (s == 0) r = g = b = l;
        else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = hueToRgb(p, q, h + 1.0/3);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1.0/3);
        }
        return new ArrayList<>(java.util.Arrays.asList((double)Math.round(r*255), (double)Math.round(g*255), (double)Math.round(b*255)));
    }
    private double hueToRgb(double p, double q, double t) {
        if(t < 0) t += 1;
        if(t > 1) t -= 1;
        if(t < 1.0/6) return p + (q - p) * 6 * t;
        if(t < 1.0/2) return q;
        if(t < 2.0/3) return p + (q - p) * (2.0/3 - t) * 6;
        return p;
    }

    private ArrayList<Object> colorRgbToCmykHelper(int r, int g, int b) {
        double c = 1 - (r/255.0), m = 1 - (g/255.0), y = 1 - (b/255.0);
        double k = Math.min(c, Math.min(m, y));
        if (k == 1.0) return new ArrayList<>(java.util.Arrays.asList(0.0,0.0,0.0,1.0));
        return new ArrayList<>(java.util.Arrays.asList((c-k)/(1-k), (m-k)/(1-k), (y-k)/(1-k), k));
    }

    private ArrayList<Object> colorCmykToRgbHelper(double c, double m, double y, double k) {
        int r = (int)(255 * (1-c) * (1-k));
        int g = (int)(255 * (1-m) * (1-k));
        int b = (int)(255 * (1-y) * (1-k));
        return new ArrayList<>(java.util.Arrays.asList((double)r, (double)g, (double)b));
    }

    private String colorBlendHelper(String hex1, String hex2, double ratio) {
        ratio = Math.max(0, Math.min(1, ratio));
        int[] rgb1 = colorHexToRgbHelper(hex1);
        int[] rgb2 = colorHexToRgbHelper(hex2);
        int r = (int)(rgb1[0] * (1-ratio) + rgb2[0] * ratio);
        int g = (int)(rgb1[1] * (1-ratio) + rgb2[1] * ratio);
        int b = (int)(rgb1[2] * (1-ratio) + rgb2[2] * ratio);
        return colorRgbToHexHelper(r, g, b);
    }

    private String colorMultiBlendHelper(ArrayList<Object> hexList, ArrayList<Object> ratios) {
        if (hexList.isEmpty()) return "#000000";
        double r=0, g=0, b=0, totalRatio=0;
        for(int i=0; i<hexList.size(); i++){
            double ratio = i < ratios.size() ? toDouble(ratios.get(i)) : 1.0;
            int[] rgb = colorHexToRgbHelper(stringify(hexList.get(i)));
            r += rgb[0]*ratio; g += rgb[1]*ratio; b += rgb[2]*ratio;
            totalRatio += ratio;
        }
        if(totalRatio==0) totalRatio=1;
        return colorRgbToHexHelper((int)(r/totalRatio), (int)(g/totalRatio), (int)(b/totalRatio));
    }

    private ArrayList<Object> colorGradientHelper(String hex1, String hex2, int steps) {
        ArrayList<Object> grad = new ArrayList<>();
        if(steps <= 1) { grad.add(hex1); return grad; }
        for(int i=0; i<steps; i++) grad.add(colorBlendHelper(hex1, hex2, (double)i/(steps-1)));
        return grad;
    }

    private ArrayList<Object> colorMultiGradientHelper(ArrayList<Object> hexList, int steps) {
        ArrayList<Object> grad = new ArrayList<>();
        if(hexList.size() < 2 || steps < 2) return hexList;
        int segments = hexList.size() - 1;
        int stepsPerSegment = steps / segments;
        for(int i=0; i<segments; i++) {
            int currentSteps = (i == segments-1) ? (steps - (stepsPerSegment * i)) : stepsPerSegment;
            ArrayList<Object> seg = colorGradientHelper(stringify(hexList.get(i)), stringify(hexList.get(i+1)), currentSteps);
            if(i>0) seg.remove(0); // Tránh trùng màu ở điểm nối
            grad.addAll(seg);
        }
        return grad;
    }

    private ArrayList<Object> colorRandomGradientHelper(int steps, long seed) {
        java.util.Random rand = new java.util.Random(seed);
        String hex1 = colorRgbToHexHelper(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256));
        String hex2 = colorRgbToHexHelper(rand.nextInt(256), rand.nextInt(256), rand.nextInt(256));
        return colorGradientHelper(hex1, hex2, steps);
    }

    private String colorAdjustHslHelper(String hex, int channelIndex, double amount) {
        int[] rgb = colorHexToRgbHelper(hex);
        ArrayList<Object> hsl = colorRgbToHslHelper(rgb[0], rgb[1], rgb[2]);
        if(channelIndex == 0) { // Hue
            double h = (toDouble(hsl.get(0)) + amount) % 360.0;
            if (h < 0) h += 360;
            hsl.set(0, h);
        } else { // Saturation hoặc Lightness
            double val = toDouble(hsl.get(channelIndex)) + amount;
            hsl.set(channelIndex, Math.max(0, Math.min(1.0, val)));
        }
        ArrayList<Object> newRgb = colorHslToRgbHelper(toDouble(hsl.get(0)), toDouble(hsl.get(1)), toDouble(hsl.get(2)));
        return colorRgbToHexHelper((int)toDouble(newRgb.get(0)), (int)toDouble(newRgb.get(1)), (int)toDouble(newRgb.get(2)));
    }

    private double colorLuminanceHelper(String hex) {
        int[] rgb = colorHexToRgbHelper(hex);
        double[] a = new double[3];
        for(int i=0; i<3; i++) {
            double c = rgb[i] / 255.0;
            a[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return a[0]*0.2126 + a[1]*0.7152 + a[2]*0.0722;
    }

    private double colorContrastHelper(String hex1, String hex2) {
        double l1 = colorLuminanceHelper(hex1) + 0.05;
        double l2 = colorLuminanceHelper(hex2) + 0.05;
        return Math.max(l1, l2) / Math.min(l1, l2);
    }

    private double colorDistanceHelper(String hex1, String hex2) {
        int[] c1 = colorHexToRgbHelper(hex1), c2 = colorHexToRgbHelper(hex2);
        return Math.sqrt(Math.pow(c1[0]-c2[0], 2) + Math.pow(c1[1]-c2[1], 2) + Math.pow(c1[2]-c2[2], 2));
    }

    private ArrayList<Object> colorRandomPaletteHelper(int count, String scheme) {
        ArrayList<Object> palette = new ArrayList<>();
        double h = Math.random() * 360, s = 0.6 + Math.random()*0.4, l = 0.4 + Math.random()*0.4;
        palette.add(colorRgbToHexHelper((int)toDouble(colorHslToRgbHelper(h,s,l).get(0)),(int)toDouble(colorHslToRgbHelper(h,s,l).get(1)),(int)toDouble(colorHslToRgbHelper(h,s,l).get(2))));

        for(int i=1; i<count; i++) {
            switch(scheme.toLowerCase()) {
                case "complementary": h = (h + 180) % 360; break;
                case "analogous": h = (h + 30) % 360; break;
                case "triadic": h = (h + 120) % 360; break;
                case "monochromatic": l = Math.max(0.1, (l + 0.2) % 1.0); break;
                default: // random
                    h = Math.random() * 360; s = Math.random(); l = Math.random();
            }
            ArrayList<Object> rgb = colorHslToRgbHelper(h,s,l);
            palette.add(colorRgbToHexHelper((int)toDouble(rgb.get(0)), (int)toDouble(rgb.get(1)), (int)toDouble(rgb.get(2))));
        }
        return palette;
    }

    private static final java.util.Map<String, String> CSS_COLORS = new java.util.HashMap<>();
    static {
        CSS_COLORS.put("red", "#FF0000"); CSS_COLORS.put("green", "#008000"); CSS_COLORS.put("blue", "#0000FF");
        CSS_COLORS.put("white", "#FFFFFF"); CSS_COLORS.put("black", "#000000"); CSS_COLORS.put("yellow", "#FFFF00");
        CSS_COLORS.put("cyan", "#00FFFF"); CSS_COLORS.put("magenta", "#FF00FF"); CSS_COLORS.put("gray", "#808080");
        CSS_COLORS.put("orange", "#FFA500"); CSS_COLORS.put("purple", "#800080"); CSS_COLORS.put("pink", "#FFC0CB");
        CSS_COLORS.put("brown", "#A52A2A"); CSS_COLORS.put("skyblue", "#87CEEB"); CSS_COLORS.put("tomato", "#FF6347");
        CSS_COLORS.put("teal", "#008080"); CSS_COLORS.put("navy", "#000080"); CSS_COLORS.put("gold", "#FFD700");
    }

    private ArrayList<Object> colorNameToRgbHelper(String name) {
        String hex = CSS_COLORS.getOrDefault(name.toLowerCase(), "#000000");
        int[] rgb = colorHexToRgbHelper(hex);
        return new ArrayList<>(java.util.Arrays.asList((double)rgb[0], (double)rgb[1], (double)rgb[2]));
    }

    private String colorRgbToNameHelper(int r, int g, int b) {
        String bestName = "unknown";
        double minDistance = Double.MAX_VALUE;
        String targetHex = colorRgbToHexHelper(r, g, b);
        for(java.util.Map.Entry<String, String> entry : CSS_COLORS.entrySet()) {
            double dist = colorDistanceHelper(targetHex, entry.getValue());
            if(dist < minDistance) { minDistance = dist; bestName = entry.getKey(); }
        }
        return bestName;
    }

    private ArrayList<Object> colorExtractPaletteHelper(String imageId, int count) {
        if (!imageLibrary.containsKey(imageId)) throw new RuntimeException("Lỗi: Không tìm thấy ảnh có ID " + imageId);
        BufferedImage img = imageLibrary.get(imageId);
        java.util.HashMap<String, Integer> colorCounts = new java.util.HashMap<>();

        // Thu nhỏ ảnh để tăng tốc độ quét (quantization cơ bản)
        int step = Math.max(1, (img.getWidth() * img.getHeight()) / 10000);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int rgb = img.getRGB(x, y);
                String hex = colorRgbToHexHelper((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
                colorCounts.put(hex, colorCounts.getOrDefault(hex, 0) + 1);
            }
        }

        java.util.List<java.util.Map.Entry<String, Integer>> list = new ArrayList<>(colorCounts.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue())); // Xếp theo tần suất

        ArrayList<Object> result = new ArrayList<>();
        for(int i=0; i<Math.min(count, list.size()); i++) result.add(list.get(i).getKey());
        return result;
    }
}