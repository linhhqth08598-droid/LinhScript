package me.hql.script;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * BỂ KẾT NỐI CSDL CỦA LINHSCRIPT (HqlDbPool)
 *
 * Vì sao cần: trước đây Interpreter giữ DUY NHẤT một Connection dùng chung cho mọi luồng.
 * Máy chủ web có nhiều request chạy cùng lúc nên:
 *   - hai request giành nhau một kết nối (JDBC không an toàn khi dùng chung);
 *   - beginTransaction() của request này kéo luôn request kia vào cùng giao dịch;
 *   - kết nối bị SQL Server cắt vì để lâu thì mọi truy vấn sau đó đều lỗi.
 *
 * Bể này giống HikariCP ở những điểm quan trọng nhưng nằm gọn trong 1 file:
 *   - mượn/trả kết nối: con.close() chỉ là TRẢ VỀ BỂ, không đóng thật;
 *   - nhiều luồng mượn song song, tối đa {@code maxSize} kết nối, hết chỗ thì chờ có thời hạn;
 *   - kiểm tra kết nối còn sống trước khi giao (chỉ kiểm tra khi để nhàn rỗi lâu → gần như không tốn thời gian);
 *   - tự dọn kết nối nhàn rỗi quá lâu / quá tuổi thọ, tự bù lại cho đủ {@code minIdle};
 *   - tự rollback + bật lại autoCommit khi trả về (kết nối sạch cho người sau);
 *   - khởi động nóng: mở sẵn {@code minIdle} kết nối ở luồng nền.
 *
 * Không dùng thư viện ngoài nên chạy ngay cả khi thư mục lib/ chỉ có driver JDBC.
 *
 * ┌─ TOÀN BỘ THƯ VIỆN DATABASE NẰM TRONG FILE NÀY (không tách file) ───────────────────────────┐
 * │  HqlDbPool            bể kết nối SQL (SQL Server / MySQL / PostgreSQL / SQLite)              │
 * │  HqlDbPool.OnlineUrl  nối SQL online qua URL: postgresql://, mysql://, sqlserver://  (+SSL)  │
 * │  HqlDbPool.NoSql      cửa ngõ hàm nosqlXxx cho script — NoSQL = MongoDB / Atlas              │
 * │  HqlDbPool.Mongo      giao thức MongoDB tự viết (OP_MSG + BSON + SCRAM + TLS + SRV)          │
 * │  HqlDbPool.Bson/Json  bộ mã hoá BSON và đọc JSON nhỏ                                         │
 * │  Cấu hình: tệp db.henv  (vd  db.name=namedb) — xem HqlEnv                                   │
 * └──────────────────────────────────────────────────────────────────────────────────────────┘
 */
public final class HqlDbPool implements AutoCloseable {

    // ----------------------- CẤU HÌNH -----------------------
    public static final class Config {
        public String url, user, pass;
        public int maxSize = 10;                       // số kết nối tối đa
        public int minIdle = 2;                        // số kết nối giữ sẵn
        public long connectionTimeoutMs = 15_000;      // chờ mượn kết nối tối đa
        public long idleTimeoutMs = 300_000;           // nhàn rỗi quá 5 phút thì đóng (khi vượt minIdle)
        public long maxLifetimeMs = 1_200_000;         // tuổi thọ tối đa 20 phút (< thời hạn cắt của máy chủ)
        public long validateAfterIdleMs = 20_000;      // nhàn rỗi quá 20 giây thì kiểm tra lại trước khi giao
    }

    private static final class Entry {
        final Connection raw;
        final long createdAt = System.currentTimeMillis();
        volatile long lastReturned = System.currentTimeMillis();
        Entry(Connection raw) { this.raw = raw; }
    }

    private final Config cfg;
    private final ConcurrentLinkedDeque<Entry> idle = new ConcurrentLinkedDeque<>();
    private final Semaphore permits;                    // số chỗ còn có thể giao đi
    private final AtomicInteger total = new AtomicInteger();   // tổng số kết nối thật đang tồn tại
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService maintenance;

    public HqlDbPool(Config cfg) throws SQLException {
        if (cfg.url == null || cfg.url.isEmpty()) throw new SQLException("Thiếu chuỗi kết nối (JDBC URL).");
        if (cfg.maxSize < 1) cfg.maxSize = 1;
        if (cfg.minIdle > cfg.maxSize) cfg.minIdle = cfg.maxSize;
        if (cfg.minIdle < 0) cfg.minIdle = 0;
        this.cfg = cfg;
        this.permits = new Semaphore(cfg.maxSize, true);

        // Mở thử 1 kết nối NGAY: sai mật khẩu/máy chủ tắt thì báo lỗi lập tức, không để tới lúc chạy truy vấn mới biết.
        Entry first = open();
        idle.addFirst(first);

        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hql-db-pool");
            t.setDaemon(true);
            return t;
        });
        maintenance.scheduleWithFixedDelay(this::maintain, 5, 30, TimeUnit.SECONDS);
        maintenance.execute(this::topUp);               // khởi động nóng ở luồng nền
    }

    // ----------------------- MƯỢN / TRẢ -----------------------

    /** Mượn một kết nối. Gọi close() lên kết nối này để TRẢ về bể. */
    public Connection borrow() throws SQLException {
        if (closed.get()) throw new SQLException("Bể kết nối đã đóng.");
        try {
            if (!permits.tryAcquire(cfg.connectionTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new SQLException("Hết kết nối trong bể (" + stats() + ") — đã chờ "
                        + cfg.connectionTimeoutMs + "ms. Kiểm tra xem có giao dịch nào bị bỏ quên chưa commit/rollback không.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Bị ngắt khi đang chờ kết nối.");
        }

        Entry e = null;
        try {
            long now = System.currentTimeMillis();
            while ((e = idle.pollFirst()) != null) {          // LIFO: ưu tiên kết nối vừa dùng (còn nóng)
                if (isUsable(e, now)) break;
                discard(e);
                e = null;
            }
            if (e == null) e = open();
            return wrap(e);
        } catch (SQLException | RuntimeException ex) {
            if (e != null) discard(e);
            permits.release();
            throw ex;
        }
    }

    private boolean isUsable(Entry e, long now) {
        try {
            if (now - e.createdAt > cfg.maxLifetimeMs) return false;
            if (e.raw.isClosed()) return false;
            if (now - e.lastReturned > cfg.validateAfterIdleMs) return e.raw.isValid(2);
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * Load the JDBC driver for the URL explicitly with a constant class name.
     * In a GraalVM native image DriverManager's ServiceLoader scan finds nothing ("No suitable driver"),
     * but Class.forName("literal") is detected at build time, so the driver gets compiled in and registers itself.
     * On a normal JVM this is harmless: it just loads the driver a little earlier.
     */
    private static volatile String driverLoadNote = "";

    private static void loadDriver(String url) {
        if (url == null) return;
        driverLoadNote = "";
        try {
            if (url.startsWith("jdbc:sqlserver:")) Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            else if (url.startsWith("jdbc:mysql:")) Class.forName("com.mysql.cj.jdbc.Driver");
            else if (url.startsWith("jdbc:postgresql:")) Class.forName("org.postgresql.Driver");
            else if (url.startsWith("jdbc:sqlite:")) Class.forName("org.sqlite.JDBC");
            else if (url.startsWith("jdbc:mariadb:")) Class.forName("org.mariadb.jdbc.Driver");
        } catch (Throwable ex) {
            // driver jar missing or its static init failed: remember the real reason to show it below
            StringBuilder why = new StringBuilder(" [driver load failed: ").append(ex);
            for (Throwable c = ex.getCause(); c != null && c != c.getCause(); c = c.getCause()) why.append(" <- ").append(c);
            driverLoadNote = why.append("]").toString();
        }
    }

    private Entry open() throws SQLException {
        loadDriver(cfg.url);
        Connection c;
        try {
            c = (cfg.user == null)
                    ? DriverManager.getConnection(cfg.url)
                    : DriverManager.getConnection(cfg.url, cfg.user, cfg.pass);
        } catch (SQLException ex) {
            if (!driverLoadNote.isEmpty()) throw new SQLException(ex.getMessage() + driverLoadNote, ex.getSQLState(), ex.getErrorCode(), ex);
            throw ex;
        }
        total.incrementAndGet();
        return new Entry(c);
    }

    private void discard(Entry e) {
        try { e.raw.close(); } catch (Exception ignore) { }
        total.decrementAndGet();
    }

    /** Trả kết nối về bể: dọn trạng thái dở dang rồi cất vào hàng chờ. */
    private void giveBack(Entry e) {
        try {
            if (!e.raw.getAutoCommit()) {           // giao dịch bỏ dở -> hủy, đừng để rò sang người sau
                try { e.raw.rollback(); } catch (SQLException ignore) { }
                e.raw.setAutoCommit(true);
            }
            e.raw.clearWarnings();
            if (closed.get() || e.raw.isClosed()) {
                discard(e);
            } else {
                e.lastReturned = System.currentTimeMillis();
                idle.addFirst(e);
            }
        } catch (SQLException ex) {
            discard(e);                              // kết nối hỏng -> bỏ, người sau sẽ nhận kết nối mới
        } finally {
            permits.release();
        }
    }

    // ----------------------- BỌC KẾT NỐI -----------------------

    private Connection wrap(Entry e) {
        final AtomicBoolean returned = new AtomicBoolean();
        InvocationHandler h = (proxy, method, args) -> {
            String m = method.getName();
            if (m.equals("close")) {
                if (returned.compareAndSet(false, true)) giveBack(e);
                return null;
            }
            if (m.equals("isClosed")) return returned.get() || e.raw.isClosed();
            if (m.equals("hashCode")) return System.identityHashCode(proxy);
            if (m.equals("equals")) return proxy == args[0];
            if (m.equals("toString")) return "HqlPooled[" + e.raw + "]";
            if (returned.get()) throw new SQLException("Kết nối đã được trả về bể, không dùng lại được nữa.");
            try {
                return method.invoke(e.raw, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                HqlDbPool.class.getClassLoader(), new Class<?>[]{Connection.class}, h);
    }

    // ----------------------- DỌN DẸP ĐỊNH KỲ -----------------------

    private void maintain() {
        if (closed.get()) return;
        try {
            long now = System.currentTimeMillis();
            for (Entry e : idle) {
                boolean tooOld = now - e.createdAt > cfg.maxLifetimeMs;
                boolean tooIdle = now - e.lastReturned > cfg.idleTimeoutMs && total.get() > cfg.minIdle;
                if ((tooOld || tooIdle) && idle.remove(e)) discard(e);
            }
            topUp();
        } catch (Throwable ignore) { /* luồng dọn dẹp không được phép chết */ }
    }

    /** Bù cho đủ minIdle (nhưng không vượt maxSize). */
    private void topUp() {
        try {
            while (!closed.get() && idle.size() < cfg.minIdle && total.get() < cfg.maxSize) {
                idle.addLast(open());
            }
        } catch (SQLException ignore) { /* máy chủ CSDL đang tắt: lần dọn sau thử lại */ }
    }

    // ----------------------- TIỆN ÍCH -----------------------

    public boolean isClosed() { return closed.get(); }

    /** Ví dụ: "dang dung 1 / nhan roi 4 / tong 5 / toi da 10". */
    public String stats() {
        int tot = total.get(), id = idle.size();
        return "in use " + Math.max(0, tot - id) + " / idle " + id + " / total " + tot + " / max " + cfg.maxSize;
    }

    public int activeCount() { return Math.max(0, total.get() - idle.size()); }
    public int idleCount() { return idle.size(); }
    public int totalCount() { return total.get(); }
    public int maxSize() { return cfg.maxSize; }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        maintenance.shutdownNow();
        Entry e;
        while ((e = idle.pollFirst()) != null) discard(e);
        // Kết nối đang cho mượn sẽ tự đóng khi được trả về (giveBack thấy closed=true).
    }

    // =====================================================================================
    //  A. BỘ ĐỌC JSON NHỎ (không dùng thư viện ngoài)
    //     object → LinkedHashMap, array → ArrayList, số → Double, chuỗi → String, true/false → Boolean, null → null
    //     LinhScript KHÔNG có map literal, nên bộ lọc NoSQL được viết thành chuỗi JSON "dễ tính":
    //        nosqlFind("sv", "{tuoi: {$gt: 18}, ten: 'An'}")      ← khoá không cần nháy, chuỗi dùng được nháy đơn
    //        nosqlDoc("{ten: ?, tuoi: ?}", [ten, tuoi])            ← dấu ? được thay bằng giá trị trong mảng (an toàn, không cần tự escape)
    // =====================================================================================
    static final class Json {
        private final String s;
        private final List<Object> params;
        private int i = 0, pi = 0;

        private Json(String s, List<Object> params) { this.s = s; this.params = params; }

        static Object parse(String text) { return parse(text, null); }

        /** params: giá trị thay cho các dấu ? (theo thứ tự xuất hiện); null nếu không dùng. */
        static Object parse(String text, List<Object> params) {
            if (text == null) return null;
            Json p = new Json(text, params);
            p.ws();
            if (p.i >= text.length()) return null;
            Object v = p.value();
            p.ws();
            if (p.i != text.length()) throw new IllegalArgumentException("JSON thừa ký tự ở vị trí " + p.i);
            if (params != null && p.pi < params.size()) throw new IllegalArgumentException("Truyền thừa tham số: có " + params.size() + " giá trị nhưng chỉ có " + p.pi + " dấu ?");
            return v;
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("JSON kết thúc đột ngột");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': case '\'': return string();
                case '?': {
                    i++;
                    if (params == null || pi >= params.size()) throw new IllegalArgumentException("Dấu ? thứ " + (pi + 1) + " không có giá trị tương ứng (truyền mảng tham số làm đối số cuối).");
                    return params.get(pi++);
                }
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private void expect(String w) {
            if (!s.startsWith(w, i)) throw new IllegalArgumentException("JSON sai tại vị trí " + i);
            i += w.length();
        }

        private Map<String, Object> object() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; }               // dấu phẩy thừa cuối cùng
                if (i >= s.length()) throw new IllegalArgumentException("JSON kết thúc đột ngột");
                String k;
                char kc = s.charAt(i);
                if (kc == '"' || kc == '\'') k = string();
                else {                                                                    // khoá trần: ten, $gt, dc.tp
                    int st = i;
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || "_$.-".indexOf(s.charAt(i)) >= 0)) i++;
                    if (st == i) throw new IllegalArgumentException("JSON: thiếu tên khoá tại " + i);
                    k = s.substring(st, i);
                }
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("JSON: thiếu dấu ':' tại " + i);
                i++;
                m.put(k, value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("JSON kết thúc đột ngột");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("JSON: cần ',' hoặc '}' tại " + (i - 1));
            }
        }

        private List<Object> array() {
            ArrayList<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                if (i < s.length() && s.charAt(i) == ']') { i++; return l; }               // dấu phẩy thừa cuối cùng
                l.add(value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("JSON kết thúc đột ngột");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("JSON: cần ',' hoặc ']' tại " + (i - 1));
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            char q = s.charAt(i++);                      // nháy mở: " hoặc '
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("JSON: chuỗi chưa đóng nháy");
                char c = s.charAt(i++);
                if (c == q) return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw new IllegalArgumentException("JSON kết thúc đột ngột");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("JSON: \\u thiếu chữ số");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e);          // \" \' \\ \/
                }
            }
        }

        private Double number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("JSON: ký tự lạ '" + s.charAt(i) + "' tại " + i);
            return Double.parseDouble(s.substring(st, i));
        }
    }

    // =====================================================================================
    //  B. NỐI DATABASE SQL ONLINE QUA ĐỊA CHỈ DẠNG URL
    //     postgresql://user:matkhau@host/db?sslmode=require   (Neon, Supabase, Render, Railway...)
    //     mysql://user:matkhau@host:3306/db                    (Aiven, PlanetScale, Railway...)
    //     sqlserver://user:matkhau@host:1433/db                (Azure SQL, AWS RDS...)
    //  → đổi thành chuỗi JDBC; tự bật SSL/TLS khi máy chủ nằm trên mạng (không phải localhost / LAN).
    // =====================================================================================
    public static final class Target {
        public String url, user, pass;
        public boolean sqlite;
    }

    public static final class OnlineUrl {
        private OnlineUrl() {}

        /** true nếu host là máy này / mạng nội bộ (không cần ép SSL). */
        public static boolean isLocalHost(String h) {
            if (h == null || h.isEmpty()) return true;
            String x = h.toLowerCase(Locale.ROOT);
            if (x.equals("localhost") || x.equals("::1") || x.equals("[::1]") || x.endsWith(".local") || x.endsWith(".internal")) return true;
            if (x.indexOf('.') < 0 && x.indexOf(':') < 0) return true;                 // tên máy trong LAN / docker (vd "db", "MAYTINH\\SQLEXPRESS")
            if (x.startsWith("127.") || x.startsWith("10.") || x.startsWith("192.168.")) return true;
            if (x.startsWith("172.")) {
                try { int b = Integer.parseInt(x.split("\\.")[1]); if (b >= 16 && b <= 31) return true; } catch (Exception ignore) { }
            }
            return false;
        }

        private static boolean hasParam(String query, String name) {
            if (query == null || query.isEmpty()) return false;
            String q = "&" + query.toLowerCase(Locale.ROOT);
            return q.contains("&" + name.toLowerCase(Locale.ROOT) + "=");
        }

        private static String stripParam(String query, String name) {
            if (query == null || query.isEmpty()) return "";
            StringBuilder b = new StringBuilder();
            for (String kv : query.split("&")) {
                if (kv.isEmpty() || kv.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + "=")) continue;
                if (b.length() > 0) b.append('&');
                b.append(kv);
            }
            return b.toString();
        }

        /**
         * Ghép chuỗi JDBC.
         * @param kind  mysql | mariadb | postgres | postgresql | sqlserver | mssql
         * @param ssl   true/false ép bật/tắt; null = tự quyết (bật khi host là máy chủ trên mạng)
         */
        public static String jdbcUrl(String kind, String host, int port, String db, String extraQuery, Boolean ssl) throws SQLException {
            boolean useSsl = ssl != null ? ssl : !isLocalHost(host);
            String ex = extraQuery == null ? "" : extraQuery;
            switch (kind.toLowerCase(Locale.ROOT)) {
                case "mysql":
                case "mariadb": {
                    if (hasParam(ex, "ssl-mode")) {                // Aiven ghi ssl-mode=REQUIRED → đổi sang tên của Connector/J
                        String v = "REQUIRED";
                        for (String kv : ex.split("&")) if (kv.toLowerCase(Locale.ROOT).startsWith("ssl-mode=")) v = kv.substring(9);
                        ex = stripParam(ex, "ssl-mode");
                        ex = (ex.isEmpty() ? "" : ex + "&") + "sslMode=" + v;
                    }
                    StringBuilder q = new StringBuilder("characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true");
                    if (!hasParam(ex, "sslMode") && !hasParam(ex, "useSSL")) q.append(useSsl ? "&sslMode=REQUIRED" : "&useSSL=false");
                    if (!ex.isEmpty()) q.append('&').append(ex);
                    return "jdbc:mysql://" + host + ":" + (port <= 0 ? 3306 : port) + "/" + db + "?" + q;
                }
                case "postgres":
                case "postgresql": {
                    ex = stripParam(ex, "channel_binding");        // Neon thêm tham số này, driver JDBC không cần
                    StringBuilder q = new StringBuilder(ex);
                    if (useSsl && !hasParam(ex, "sslmode") && !hasParam(ex, "ssl")) {
                        if (q.length() > 0) q.append('&');
                        q.append("sslmode=require");
                    }
                    return "jdbc:postgresql://" + host + ":" + (port <= 0 ? 5432 : port) + "/" + db + (q.length() > 0 ? "?" + q : "");
                }
                case "sqlserver":
                case "mssql": {
                    boolean named = host.contains("\\");           // MAYTINH\SQLEXPRESS: không gắn cổng
                    StringBuilder u = new StringBuilder("jdbc:sqlserver://").append(host);
                    if (!named && port > 0) u.append(':').append(port);
                    u.append(";databaseName=").append(db);
                    if (!hasParam(ex, "encrypt")) u.append(";encrypt=").append(HqlEnv.getBool(HqlEnv.has("db.encrypt") ? "db.encrypt" : "DB_ENCRYPT", true));
                    if (!hasParam(ex, "trustServerCertificate")) u.append(";trustServerCertificate=true");
                    for (String kv : ex.split("&")) if (!kv.isEmpty()) u.append(';').append(kv);
                    return u.toString();
                }
                default:
                    throw new SQLException("Loại database không hỗ trợ: " + kind);
            }
        }

        private static String dec(String s) {
            try { return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); }
            catch (Exception e) { return s; }
        }

        public static boolean isUrlForm(String s) {
            if (s == null) return false;
            String x = s.trim().toLowerCase(Locale.ROOT);
            return x.startsWith("postgres://") || x.startsWith("postgresql://") || x.startsWith("mysql://")
                    || x.startsWith("mariadb://") || x.startsWith("sqlserver://") || x.startsWith("mssql://");
        }

        /**
         * Đổi địa chỉ URL (hoặc chuỗi jdbc:...) thành Target.
         * user/pass truyền riêng (khác null) sẽ thắng user/pass nằm trong URL.
         */
        public static Target fromUrl(String raw, String user, String pass, Boolean ssl) throws SQLException {
            Target t = new Target();
            String u = raw.trim();
            if (u.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
                t.url = u; t.user = user; t.pass = pass;
                t.sqlite = u.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:");
                return t;
            }
            int se = u.indexOf("://");
            if (se < 0) throw new SQLException("Địa chỉ database không hợp lệ (thiếu '://').");
            String scheme = u.substring(0, se).toLowerCase(Locale.ROOT);
            String rest = u.substring(se + 3);
            String query = "";
            int q = rest.indexOf('?');
            if (q >= 0) { query = rest.substring(q + 1); rest = rest.substring(0, q); }
            String db = "";
            int sl = rest.indexOf('/');
            if (sl >= 0) { db = dec(rest.substring(sl + 1)); rest = rest.substring(0, sl); }
            String uUser = null, uPass = null;
            int at = rest.lastIndexOf('@');
            if (at >= 0) {
                String ui = rest.substring(0, at);
                rest = rest.substring(at + 1);
                int c = ui.indexOf(':');
                uUser = dec(c < 0 ? ui : ui.substring(0, c));
                uPass = c < 0 ? null : dec(ui.substring(c + 1));
            }
            String host = rest;
            int port = -1;
            int colon = rest.lastIndexOf(':');
            if (colon >= 0 && !rest.endsWith("]")) {
                host = rest.substring(0, colon);
                try { port = Integer.parseInt(rest.substring(colon + 1)); }
                catch (NumberFormatException e) { throw new SQLException("Cổng không hợp lệ trong địa chỉ database: " + rest.substring(colon + 1)); }
            }
            if (host.isEmpty()) throw new SQLException("Địa chỉ database thiếu tên máy chủ.");
            if (db.isEmpty()) throw new SQLException("Địa chỉ database thiếu tên database (phần sau dấu / ).");
            t.url = jdbcUrl(scheme, host, port, db, query, ssl);
            t.user = user != null ? user : uUser;
            t.pass = pass != null ? pass : uPass;
            return t;
        }
    }

    // =====================================================================================
    //  C. NOSQL — MONGODB (MongoDB Atlas, MongoDB tự dựng, Atlas Local...)
    //     Tự viết giao thức gốc OP_MSG + BSON, đăng nhập SCRAM-SHA-256 / SCRAM-SHA-1, TLS, mongodb+srv://
    //     → KHÔNG cần mongodb-driver-sync.jar. Yêu cầu máy chủ MongoDB 3.6 trở lên.
    // =====================================================================================

    /** Lỗi do thư viện NoSQL báo (đã có câu giải thích tiếng Việt). */
    public static final class NoSqlException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final boolean authFailure;
        NoSqlException(String msg) { this(msg, false); }
        NoSqlException(String msg, boolean authFailure) { super(msg); this.authFailure = authFailure; }
        NoSqlException(String msg, Throwable cause) { super(msg, cause); this.authFailure = false; }
    }

    /** Khoá ObjectId 12 byte của MongoDB. */
    static final class Oid {
        private static final AtomicInteger COUNTER = new AtomicInteger(new SecureRandom().nextInt());
        private static final byte[] RANDOM5 = new byte[5];
        static { new SecureRandom().nextBytes(RANDOM5); }
        final byte[] b;

        Oid(byte[] b) { this.b = b; }

        static Oid create() {
            byte[] x = new byte[12];
            int t = (int) (System.currentTimeMillis() / 1000);
            x[0] = (byte) (t >> 24); x[1] = (byte) (t >> 16); x[2] = (byte) (t >> 8); x[3] = (byte) t;
            System.arraycopy(RANDOM5, 0, x, 4, 5);
            int c = COUNTER.getAndIncrement();
            x[9] = (byte) (c >> 16); x[10] = (byte) (c >> 8); x[11] = (byte) c;
            return new Oid(x);
        }

        static boolean isHex24(String s) {
            if (s == null || s.length() != 24) return false;
            for (int i = 0; i < 24; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
            return true;
        }

        static Oid parse(String hex) {
            byte[] x = new byte[12];
            for (int i = 0; i < 12; i++) x[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return new Oid(x);
        }

        String hex() {
            StringBuilder sb = new StringBuilder(24);
            for (byte v : b) sb.append(Character.forDigit((v >> 4) & 15, 16)).append(Character.forDigit(v & 15, 16));
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------------- BSON
    static final class Bson {
        private Bson() {}

        static final class Buf {
            byte[] a = new byte[256];
            int n = 0;
            void ensure(int k) { if (n + k > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + k)); }
            void b(int v) { ensure(1); a[n++] = (byte) v; }
            void i32(int v) { ensure(4); a[n++] = (byte) v; a[n++] = (byte) (v >> 8); a[n++] = (byte) (v >> 16); a[n++] = (byte) (v >> 24); }
            void i64(long v) { i32((int) v); i32((int) (v >> 32)); }
            void bytes(byte[] d, int off, int len) { ensure(len); System.arraycopy(d, off, a, n, len); n += len; }
            void cstr(String s) {
                byte[] d = s.getBytes(StandardCharsets.UTF_8);
                for (byte x : d) if (x == 0) throw new NoSqlException("Tên khoá không được chứa ký tự NUL: " + s);
                bytes(d, 0, d.length); b(0);
            }
            void str(String s) { byte[] d = s.getBytes(StandardCharsets.UTF_8); i32(d.length + 1); bytes(d, 0, d.length); b(0); }
            void patch32(int at, int v) { a[at] = (byte) v; a[at + 1] = (byte) (v >> 8); a[at + 2] = (byte) (v >> 16); a[at + 3] = (byte) (v >> 24); }
        }

        static void writeDoc(Buf b, Map<?, ?> m) {
            int start = b.n;
            b.i32(0);
            for (Map.Entry<?, ?> e : m.entrySet()) elem(b, String.valueOf(e.getKey()), e.getValue());
            b.b(0);
            b.patch32(start, b.n - start);
        }

        private static void writeArray(Buf b, List<?> l) {
            int start = b.n;
            b.i32(0);
            for (int i = 0; i < l.size(); i++) elem(b, Integer.toString(i), l.get(i));
            b.b(0);
            b.patch32(start, b.n - start);
        }

        private static void elem(Buf b, String k, Object v) {
            if (v == null) { b.b(0x0A); b.cstr(k); }
            else if (v instanceof String) { b.b(0x02); b.cstr(k); b.str((String) v); }
            else if (v instanceof Boolean) { b.b(0x08); b.cstr(k); b.b(((Boolean) v) ? 1 : 0); }
            else if (v instanceof Integer) { b.b(0x10); b.cstr(k); b.i32((Integer) v); }
            else if (v instanceof Long) { b.b(0x12); b.cstr(k); b.i64((Long) v); }
            else if (v instanceof Number) { b.b(0x01); b.cstr(k); b.i64(Double.doubleToLongBits(((Number) v).doubleValue())); }
            else if (v instanceof Map) { b.b(0x03); b.cstr(k); writeDoc(b, (Map<?, ?>) v); }
            else if (v instanceof List) { b.b(0x04); b.cstr(k); writeArray(b, (List<?>) v); }
            else if (v instanceof Oid) { b.b(0x07); b.cstr(k); b.bytes(((Oid) v).b, 0, 12); }
            else if (v instanceof Instant) { b.b(0x09); b.cstr(k); b.i64(((Instant) v).toEpochMilli()); }
            else if (v instanceof byte[]) { byte[] d = (byte[]) v; b.b(0x05); b.cstr(k); b.i32(d.length); b.b(0); b.bytes(d, 0, d.length); }
            else { b.b(0x02); b.cstr(k); b.str(String.valueOf(v)); }
        }

        static final class Rd {
            final byte[] a;
            int p;
            Rd(byte[] a, int p) { this.a = a; this.p = p; }

            int i32() { int v = (a[p] & 0xFF) | (a[p + 1] & 0xFF) << 8 | (a[p + 2] & 0xFF) << 16 | (a[p + 3] & 0xFF) << 24; p += 4; return v; }
            long i64() { long lo = i32() & 0xFFFFFFFFL; long hi = i32(); return lo | (hi << 32); }
            String cstr() { int s = p; while (a[p] != 0) p++; String r = new String(a, s, p - s, StandardCharsets.UTF_8); p++; return r; }
            String str() { int len = i32(); String r = new String(a, p, len - 1, StandardCharsets.UTF_8); p += len; return r; }

            Map<String, Object> doc() {
                int start = p, end = start + i32();
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int t = a[p++] & 0xFF;
                    if (t == 0) break;
                    String k = cstr();
                    m.put(k, val(t));
                }
                p = end;
                return m;
            }

            List<Object> arr() { return new ArrayList<>(doc().values()); }

            Object val(int t) {
                switch (t) {
                    case 0x01: return Double.longBitsToDouble(i64());
                    case 0x02: return str();
                    case 0x03: return doc();
                    case 0x04: return arr();
                    case 0x05: {
                        int len = i32(); int sub = a[p++] & 0xFF;
                        if (sub == 2) { i32(); len -= 4; }
                        byte[] d = Arrays.copyOfRange(a, p, p + len); p += len;
                        if (sub == 4 && d.length == 16) {
                            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(d);
                            return new java.util.UUID(bb.getLong(), bb.getLong()).toString();
                        }
                        return d;
                    }
                    case 0x06: return null;
                    case 0x07: { byte[] d = Arrays.copyOfRange(a, p, p + 12); p += 12; return new Oid(d); }
                    case 0x08: return a[p++] != 0;
                    case 0x09: return Instant.ofEpochMilli(i64());
                    case 0x0A: return null;
                    case 0x0B: { String pat = cstr(), fl = cstr(); return "/" + pat + "/" + fl; }
                    case 0x0C: { String s = str(); p += 12; return s; }
                    case 0x0D: case 0x0E: return str();
                    case 0x10: return i32();
                    case 0x11: return i64();
                    case 0x12: return i64();
                    case 0x13: { long lo = i64(), hi = i64(); return dec128(lo, hi); }
                    case 0x7F: return "MaxKey";
                    case 0xFF: return "MinKey";
                    default: throw new NoSqlException("Kiểu dữ liệu BSON 0x" + Integer.toHexString(t) + " chưa được hỗ trợ.");
                }
            }
        }

        /** Decimal128 (dạng BID) → Double. */
        private static Object dec128(long lo, long hi) {
            boolean neg = hi < 0;
            if (((hi >>> 58) & 0x1F) == 0x1F) return Double.NaN;
            if (((hi >>> 58) & 0x1F) == 0x1E) return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            int exp = (int) ((hi >>> 49) & 0x3FFF);
            java.math.BigInteger coef = java.math.BigInteger.valueOf(hi & ((1L << 49) - 1)).shiftLeft(64)
                    .add(new java.math.BigInteger(Long.toUnsignedString(lo)));
            if (neg) coef = coef.negate();
            return new java.math.BigDecimal(coef, 6176 - exp).doubleValue();
        }

        // ---------- đổi giữa kiểu của LinhScript (HQL) và kiểu BSON ----------

        /** Giá trị trong script → giá trị ghi được vào BSON. idCtx=true: chuỗi 24 ký tự hex được hiểu là ObjectId. */
        static Object fromHql(Object v, boolean idCtx) {
            if (v == null || v instanceof Boolean || v instanceof Integer || v instanceof Long
                    || v instanceof Oid || v instanceof Instant || v instanceof byte[]) return v;
            if (v instanceof String) return (idCtx && Oid.isHex24((String) v)) ? Oid.parse((String) v) : v;
            if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (d == Math.rint(d) && Math.abs(d) < 9.0e18) {
                    long l = (long) d;
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                    return l;
                }
                return d;
            }
            if (v instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) v;
                if (m.size() == 1) {                                   // JSON mở rộng: {"$oid":..} {"$date":..} {"$numberLong":..}
                    Map.Entry<?, ?> e = m.entrySet().iterator().next();
                    String k = String.valueOf(e.getKey());
                    Object x = e.getValue();
                    if (k.equals("$oid") && x instanceof String && Oid.isHex24((String) x)) return Oid.parse((String) x);
                    if (k.equals("$date")) return toInstant(x);
                    if (k.equals("$numberLong")) return Long.parseLong(String.valueOf(x));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    out.put(k, fromHql(e.getValue(), k.equals("_id") || (idCtx && k.startsWith("$"))));
                }
                return out;
            }
            if (v instanceof List) {
                ArrayList<Object> out = new ArrayList<>();
                for (Object x : (List<?>) v) out.add(fromHql(x, idCtx));
                return out;
            }
            return String.valueOf(v);
        }

        private static Instant toInstant(Object x) {
            if (x instanceof Number) return Instant.ofEpochMilli(((Number) x).longValue());
            String s = String.valueOf(x).trim();
            try { return Instant.parse(s); }
            catch (Exception e1) {
                try { return java.time.OffsetDateTime.parse(s).toInstant(); }
                catch (Exception e2) {
                    try { return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC); }
                    catch (Exception e3) {
                        try { return java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
                        catch (Exception e4) { throw new NoSqlException("Ngày giờ không hợp lệ: \"" + s + "\" (dạng đúng: 2026-11-23T10:30:00Z hoặc 2026-11-23)."); }
                    }
                }
            }
        }

        /** Giá trị đọc từ BSON → kiểu của script: số → Double, ObjectId → chuỗi hex, ngày → chuỗi ISO-8601. */
        static Object toHql(Object v) {
            if (v == null || v instanceof String || v instanceof Boolean || v instanceof Double) return v;
            if (v instanceof Integer) return ((Integer) v).doubleValue();
            if (v instanceof Long) { long l = (Long) v; return Math.abs(l) <= (1L << 53) ? (Object) (double) l : (Object) Long.toString(l); }
            if (v instanceof Oid) return ((Oid) v).hex();
            if (v instanceof Instant) return v.toString();
            if (v instanceof byte[]) return Base64.getEncoder().encodeToString((byte[]) v);
            if (v instanceof Map) {
                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) out.put(String.valueOf(e.getKey()), toHql(e.getValue()));
                return out;
            }
            if (v instanceof List) {
                ArrayList<Object> out = new ArrayList<>();
                for (Object x : (List<?>) v) out.add(toHql(x));
                return out;
            }
            return String.valueOf(v);
        }
    }

    // ------------------------------------------------------------------------- KẾT NỐI MONGODB
    static final class Mongo implements AutoCloseable {

        /** Cấu hình lấy từ địa chỉ mongodb://... */
        static final class Cfg {
            final List<String[]> seeds = new ArrayList<>();       // {host, cổng}
            boolean tls, tlsInsecure, direct, authSourceExplicit;
            String user, pass, authSource = "admin", authMech, defaultDb, appName = "LinhScript";
            int maxPool = 10, connectTimeoutMs = 10_000, socketTimeoutMs = 30_000;
        }

        static final class Conn {
            final Socket sock;
            final InputStream in;
            final OutputStream out;
            final int epoch;
            int reqId = 1, uses = 0;
            volatile long lastUsed = System.currentTimeMillis();
            Conn(Socket s, InputStream in, OutputStream out, int epoch) { this.sock = s; this.in = in; this.out = out; this.epoch = epoch; }
            void close() { try { sock.close(); } catch (Exception ignore) { } }
        }

        private final Cfg cfg;
        private final ConcurrentLinkedDeque<Conn> idle = new ConcurrentLinkedDeque<>();
        private final Semaphore permits;
        private final AtomicInteger total = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile String primaryHost;
        private volatile int primaryPort;
        private volatile int epoch = 0;
        private volatile int maxWire = 0;

        private Mongo(Cfg cfg) {
            this.cfg = cfg;
            this.permits = new Semaphore(Math.max(1, cfg.maxPool), true);
        }

        static Mongo open(Cfg cfg) {
            Mongo m = new Mongo(cfg);
            try { m.discover(); } catch (RuntimeException e) { m.close(); throw e; }
            return m;
        }

        String defaultDb() { return cfg.defaultDb; }
        boolean isClosed() { return closed.get(); }

        // ---------------------------------------------------------------- phân tích địa chỉ
        private static String dec(String s) {
            try { return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
        }

        static Cfg parseUrl(String url) {
            if (url == null || url.trim().isEmpty()) throw new NoSqlException("Thiếu địa chỉ MongoDB (mongodb://... hoặc mongodb+srv://...).");
            String u = url.trim();
            boolean srv;
            if (u.regionMatches(true, 0, "mongodb+srv://", 0, 14)) { srv = true; u = u.substring(14); }
            else if (u.regionMatches(true, 0, "mongodb://", 0, 10)) { srv = false; u = u.substring(10); }
            else throw new NoSqlException("Địa chỉ MongoDB phải bắt đầu bằng mongodb:// hoặc mongodb+srv://");

            String query = "";
            int q = u.indexOf('?');
            if (q >= 0) { query = u.substring(q + 1); u = u.substring(0, q); }
            String path = "";
            int sl = u.indexOf('/');
            if (sl >= 0) { path = u.substring(sl + 1); u = u.substring(0, sl); }

            Cfg c = new Cfg();
            String authority = u;
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                String ui = authority.substring(0, at);
                authority = authority.substring(at + 1);
                int col = ui.indexOf(':');
                c.user = dec(col < 0 ? ui : ui.substring(0, col));
                c.pass = col < 0 ? "" : dec(ui.substring(col + 1));
            }
            if (!path.isEmpty()) c.defaultDb = dec(path);

            List<String[]> hosts = new ArrayList<>();
            for (String h : authority.split(",")) {
                h = h.trim();
                if (h.isEmpty()) continue;
                String host = h, port = "27017";
                int colon = h.lastIndexOf(':');
                if (colon >= 0 && !h.endsWith("]")) { host = h.substring(0, colon); port = h.substring(colon + 1); }
                try { Integer.parseInt(port); } catch (NumberFormatException e) { throw new NoSqlException("Cổng MongoDB không hợp lệ: " + port); }
                hosts.add(new String[]{host, port});
            }
            if (hosts.isEmpty()) throw new NoSqlException("Địa chỉ MongoDB thiếu tên máy chủ.");

            Map<String, String> opt = parseOptions(query);
            if (srv) {
                if (hosts.size() != 1) throw new NoSqlException("mongodb+srv:// chỉ nhận đúng một tên máy chủ.");
                c.tls = true;
                Map<String, String> txt = new LinkedHashMap<>();
                resolveSrv(c, hosts.get(0)[0], txt);
                applyOptions(c, txt);
            } else {
                c.seeds.addAll(hosts);
            }
            applyOptions(c, opt);                                // tuỳ chọn trong địa chỉ thắng bản ghi TXT
            if (!c.authSourceExplicit) c.authSource = (c.defaultDb != null && !c.defaultDb.isEmpty()) ? c.defaultDb : "admin";
            return c;
        }

        private static Map<String, String> parseOptions(String query) {
            Map<String, String> m = new LinkedHashMap<>();
            if (query == null || query.isEmpty()) return m;
            for (String kv : query.split("&")) {
                int e = kv.indexOf('=');
                if (e <= 0) continue;
                m.put(kv.substring(0, e).toLowerCase(Locale.ROOT), dec(kv.substring(e + 1)));
            }
            return m;
        }

        private static boolean truthy(String s) { return s != null && (s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes")); }

        private static void applyOptions(Cfg c, Map<String, String> o) {
            for (Map.Entry<String, String> e : o.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                try {
                    switch (k) {
                        case "tls": case "ssl": c.tls = truthy(v); break;
                        case "tlsallowinvalidcertificates": case "tlsinsecure": c.tlsInsecure = truthy(v); break;
                        case "authsource": c.authSource = v; c.authSourceExplicit = true; break;
                        case "authmechanism":
                            if (!v.equalsIgnoreCase("SCRAM-SHA-256") && !v.equalsIgnoreCase("SCRAM-SHA-1") && !v.equalsIgnoreCase("DEFAULT"))
                                throw new NoSqlException("authMechanism=" + v + " chưa được hỗ trợ (chỉ có SCRAM-SHA-256 và SCRAM-SHA-1).");
                            if (!v.equalsIgnoreCase("DEFAULT")) c.authMech = v.toUpperCase(Locale.ROOT);
                            break;
                        case "directconnection": c.direct = truthy(v); break;
                        case "appname": c.appName = v; break;
                        case "maxpoolsize": c.maxPool = Integer.parseInt(v); break;
                        case "connecttimeoutms": c.connectTimeoutMs = Integer.parseInt(v); break;
                        case "sockettimeoutms": c.socketTimeoutMs = Integer.parseInt(v); break;
                        default: break;                                // replicaSet, retryWrites, w... — bỏ qua
                    }
                } catch (NumberFormatException ex) {
                    throw new NoSqlException("Tuỳ chọn " + k + " phải là số: " + v);
                }
            }
        }

        private static void resolveSrv(Cfg c, String host, Map<String, String> txtOpts) {
            DirContext ctx = null;
            try {
                Hashtable<String, String> env = new Hashtable<>();
                env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
                env.put("com.sun.jndi.dns.timeout.initial", "5000");
                env.put("com.sun.jndi.dns.timeout.retries", "2");
                ctx = new InitialDirContext(env);
                Attributes attrs = ctx.getAttributes("_mongodb._tcp." + host, new String[]{"SRV"});
                Attribute srv = attrs == null ? null : attrs.get("SRV");
                if (srv == null || srv.size() == 0) throw new NamingException("No SRV");
                for (NamingEnumeration<?> en = srv.getAll(); en.hasMore(); ) {
                    String[] p = String.valueOf(en.next()).trim().split("\\s+");
                    String t = p[3].endsWith(".") ? p[3].substring(0, p[3].length() - 1) : p[3];
                    c.seeds.add(new String[]{t, p[2]});
                }
                try {
                    Attribute txt = ctx.getAttributes(host, new String[]{"TXT"}).get("TXT");
                    if (txt != null && txt.size() > 0) {
                        txtOpts.putAll(parseOptions(String.valueOf(txt.get(0)).replace("\"", "").trim()));
                    }
                } catch (Throwable ignore) { }
            } catch (Throwable e) {
                // Native Image không hỗ trợ JNDI DNS -> dùng truy vấn DNS UDP trực tiếp
                try {
                    resolveSrvUdp(c, host, txtOpts);
                } catch (Exception udpEx) {
                    throw new NoSqlException("Không tra được DNS SRV cho " + host + " (" + msg(udpEx) + ").");
                }
            } finally {
                if (ctx != null) try { ctx.close(); } catch (Throwable ignore) { }
            }
        }

        private static void resolveSrvUdp(Cfg c, String host, Map<String, String> txtOpts) throws IOException {
            byte[] srvResp = queryDnsUdp("_mongodb._tcp." + host, 33); // 33 = SRV
            int anCount = ((srvResp[6] & 0xFF) << 8) | (srvResp[7] & 0xFF);
            int pos = skipDnsName(srvResp, 12) + 4;
            for (int i = 0; i < anCount && pos < srvResp.length; i++) {
                pos = skipDnsName(srvResp, pos);
                int type = ((srvResp[pos] & 0xFF) << 8) | (srvResp[pos + 1] & 0xFF);
                int rdLen = ((srvResp[pos + 8] & 0xFF) << 8) | (srvResp[pos + 9] & 0xFF);
                pos += 10;
                if (type == 33 && rdLen > 6) {
                    int port = ((srvResp[pos + 4] & 0xFF) << 8) | (srvResp[pos + 5] & 0xFF);
                    String target = readDnsName(srvResp, pos + 6);
                    c.seeds.add(new String[]{target, String.valueOf(port)});
                }
                pos += rdLen;
            }
            if (c.seeds.isEmpty()) throw new IOException("Không tìm thấy bản ghi SRV.");

            try {
                byte[] txtResp = queryDnsUdp(host, 16); // 16 = TXT
                int txtAn = ((txtResp[6] & 0xFF) << 8) | (txtResp[7] & 0xFF);
                int tp = skipDnsName(txtResp, 12) + 4;
                for (int i = 0; i < txtAn && tp < txtResp.length; i++) {
                    tp = skipDnsName(txtResp, tp);
                    int type = ((txtResp[tp] & 0xFF) << 8) | (txtResp[tp + 1] & 0xFF);
                    int rdLen = ((txtResp[tp + 8] & 0xFF) << 8) | (txtResp[tp + 9] & 0xFF);
                    tp += 10;
                    if (type == 16) {
                        StringBuilder sb = new StringBuilder();
                        int end = tp + rdLen, cur = tp;
                        while (cur < end) {
                            int len = txtResp[cur++] & 0xFF;
                            sb.append(new String(txtResp, cur, len, StandardCharsets.UTF_8));
                            cur += len;
                        }
                        txtOpts.putAll(parseOptions(sb.toString()));
                    }
                    tp += rdLen;
                }
            } catch (Exception ignore) { }
        }

        private static byte[] queryDnsUdp(String domain, int qtype) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(new byte[]{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
            for (String label : domain.split("\\.")) {
                byte[] lb = label.getBytes(StandardCharsets.UTF_8);
                out.write(lb.length);
                out.write(lb, 0, lb.length);
            }
            out.write(0);
            out.write(new byte[]{(byte) (qtype >> 8), (byte) qtype, 0x00, 0x01});
            byte[] pkt = out.toByteArray();

            IOException last = null;
            for (String dns : new String[]{"8.8.8.8", "1.1.1.1"}) {
                try (java.net.DatagramSocket sock = new java.net.DatagramSocket()) {
                    sock.setSoTimeout(3000);
                    java.net.InetAddress addr = java.net.InetAddress.getByName(dns);
                    sock.send(new java.net.DatagramPacket(pkt, pkt.length, addr, 53));
                    byte[] buf = new byte[2048];
                    java.net.DatagramPacket resp = new java.net.DatagramPacket(buf, buf.length);
                    sock.receive(resp);
                    return Arrays.copyOf(buf, resp.getLength());
                } catch (IOException e) { last = e; }
            }
            throw last != null ? last : new IOException("DNS timeout");
        }

        private static int skipDnsName(byte[] buf, int off) {
            int p = off;
            while (p < buf.length) {
                int len = buf[p] & 0xFF;
                if (len == 0) return p + 1;
                if ((len & 0xC0) == 0xC0) return p + 2;
                p += 1 + len;
            }
            return p;
        }

        private static String readDnsName(byte[] buf, int off) {
            StringBuilder sb = new StringBuilder();
            int p = off, jumps = 0;
            while (p < buf.length && jumps++ < 20) {
                int len = buf[p] & 0xFF;
                if (len == 0) break;
                if ((len & 0xC0) == 0xC0) { p = ((len & 0x3F) << 8) | (buf[p + 1] & 0xFF); continue; }
                p++;
                if (sb.length() > 0) sb.append('.');
                sb.append(new String(buf, p, len, StandardCharsets.UTF_8));
                p += len;
            }
            return sb.toString();
        }

        // ---------------------------------------------------------------- ổ cắm + TLS
        private static volatile SSLSocketFactory insecureFactory;

        private static SSLSocketFactory insecure() {
            if (insecureFactory == null) {
                try {
                    TrustManager[] tm = {new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) { }
                        public void checkServerTrusted(X509Certificate[] c, String a) { }
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }};
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, tm, new SecureRandom());
                    insecureFactory = sc.getSocketFactory();
                } catch (GeneralSecurityException e) { throw new NoSqlException("Không tạo được ngữ cảnh TLS: " + e.getMessage()); }
            }
            return insecureFactory;
        }

        private Conn openSocket(String host, int port) throws IOException {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), cfg.connectTimeoutMs);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.setSoTimeout(cfg.socketTimeoutMs);
                if (cfg.tls) {
                    SSLSocketFactory f = cfg.tlsInsecure ? insecure() : (SSLSocketFactory) SSLSocketFactory.getDefault();
                    SSLSocket ss = (SSLSocket) f.createSocket(s, host, port, true);
                    if (!cfg.tlsInsecure) {
                        SSLParameters sp = ss.getSSLParameters();
                        sp.setEndpointIdentificationAlgorithm("HTTPS");       // kiểm tra tên máy chủ khớp chứng chỉ
                        ss.setSSLParameters(sp);
                    }
                    ss.startHandshake();
                    s = ss;
                }
                return new Conn(s, new BufferedInputStream(s.getInputStream(), 16384), new BufferedOutputStream(s.getOutputStream(), 16384), epoch);
            } catch (IOException | RuntimeException e) {
                try { s.close(); } catch (IOException ignore) { }
                throw e;
            }
        }

        // ---------------------------------------------------------------- OP_MSG
        private static byte[] readN(InputStream in, int n) throws IOException {
            byte[] b = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(b, off, n - off);
                if (r < 0) throw new EOFException("Máy chủ MongoDB đóng kết nối.");
                off += r;
            }
            return b;
        }

        private static int le32(byte[] a, int p) { return (a[p] & 0xFF) | (a[p + 1] & 0xFF) << 8 | (a[p + 2] & 0xFF) << 16 | (a[p + 3] & 0xFF) << 24; }

        private Map<String, Object> exchange(Conn c, String db, Map<String, Object> cmd) throws IOException {
            LinkedHashMap<String, Object> doc = new LinkedHashMap<>(cmd);
            doc.put("$db", db);
            Bson.Buf m = new Bson.Buf();
            m.i32(0);                       // độ dài — điền sau
            m.i32(++c.reqId);
            m.i32(0);
            m.i32(2013);                    // OP_MSG
            m.i32(0);                       // flagBits
            m.b(0);                         // section kiểu 0: một tài liệu
            Bson.writeDoc(m, doc);
            m.patch32(0, m.n);
            if (m.n > 48_000_000) throw new NoSqlException("Dữ liệu gửi đi quá lớn (" + m.n + " byte). Hãy chia nhỏ thành nhiều lần gửi.");
            c.out.write(m.a, 0, m.n);
            c.out.flush();

            byte[] hdr = readN(c.in, 16);
            int len = le32(hdr, 0), op = le32(hdr, 12);
            if (len < 21 || len > 100_000_000) throw new IOException("Gói tin MongoDB có độ dài lạ: " + len);
            byte[] rest = readN(c.in, len - 16);
            if (op != 2013) throw new IOException("Máy chủ trả opcode không mong đợi: " + op);
            int flags = le32(rest, 0);
            int end = rest.length - ((flags & 1) != 0 ? 4 : 0);
            int p = 4;
            Map<String, Object> result = null;
            try {
                while (p < end) {
                    int kind = rest[p++];
                    if (kind == 0) { Bson.Rd r = new Bson.Rd(rest, p); result = r.doc(); p = r.p; }
                    else if (kind == 1) p += le32(rest, p);
                    else throw new IOException("Section MongoDB lạ: " + kind);
                }
            } catch (IndexOutOfBoundsException e) { throw new IOException("Gói tin MongoDB bị hỏng."); }
            if (result == null) throw new IOException("Máy chủ MongoDB trả gói rỗng.");
            c.lastUsed = System.currentTimeMillis();
            return result;
        }

        private static Map<String, Object> cmd(Object... kv) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            return m;
        }

        private static double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0; }
        private static boolean isOk(Map<String, Object> r) { return num(r.get("ok")) == 1.0; }
        private static boolean flag(Object o) { return Boolean.TRUE.equals(o); }

        private NoSqlException serverError(Map<String, Object> r) {
            int code = (int) num(r.get("code"));
            String msg = String.valueOf(r.get("errmsg"));
            String hint = "";
            switch (code) {
                case 18: return new NoSqlException("Sai tên đăng nhập hoặc mật khẩu MongoDB (authSource=" + cfg.authSource + "). "
                        + "Nếu mật khẩu có ký tự đặc biệt (@ : / ? # %) hãy mã hoá %-encode trong địa chỉ.", true);
                case 13: hint = " — tài khoản không đủ quyền thực hiện lệnh này."; break;
                case 11000: hint = " — trùng giá trị ở khoá duy nhất (unique/_id)."; break;
                case 8000: hint = " — Atlas từ chối: kiểm tra Network Access (thêm IP máy này) và tên/mật khẩu user."; break;
                default: break;
            }
            return new NoSqlException("MongoDB báo lỗi [" + code + "]: " + msg + hint);
        }

        // ---------------------------------------------------------------- bắt tay + đăng nhập
        private Map<String, Object> handshake(Conn c) throws IOException {
            Map<String, Object> client = cmd("application", cmd("name", cfg.appName),
                    "driver", cmd("name", "LinhScript", "version", "1.0"),
                    "os", cmd("type", System.getProperty("os.name", "unknown")));
            for (String name : new String[]{"hello", "isMaster"}) {
                Map<String, Object> h = cmd(name, 1, "client", client);
                if (cfg.user != null && !cfg.user.isEmpty()) h.put("saslSupportedMechs", cfg.authSource + "." + cfg.user);
                Map<String, Object> r = exchange(c, "admin", h);
                if (isOk(r)) return r;
                if ((int) num(r.get("code")) != 59) throw serverError(r);       // 59 = máy chủ cũ chưa có lệnh hello
            }
            throw new NoSqlException("Máy chủ không trả lời lệnh hello/isMaster.");
        }

        private void authenticate(Conn c, Map<String, Object> info) throws IOException {
            if (cfg.user == null || cfg.user.isEmpty()) return;
            String mech = cfg.authMech;
            if (mech == null) {
                Object mechs = info.get("saslSupportedMechs");
                mech = (mechs instanceof List && ((List<?>) mechs).contains("SCRAM-SHA-256")) ? "SCRAM-SHA-256" : "SCRAM-SHA-1";
                if (mechs instanceof List && !((List<?>) mechs).isEmpty() && !((List<?>) mechs).contains("SCRAM-SHA-256")
                        && !((List<?>) mechs).contains("SCRAM-SHA-1"))
                    throw new NoSqlException("Máy chủ chỉ cho đăng nhập bằng " + mechs + " — LinhScript chưa hỗ trợ cách đó.");
            }
            List<String> sources = new ArrayList<>();
            sources.add(cfg.authSource);
            if (!cfg.authSourceExplicit && !"admin".equals(cfg.authSource)) sources.add("admin");
            NoSqlException last = null;
            for (String src : sources) {
                try { scram(c, src, mech); return; }
                catch (NoSqlException e) { last = e; if (!e.authFailure) throw e; }
            }
            throw last;
        }

        private static byte[] hmac(String alg, byte[] key, byte[] data) throws GeneralSecurityException {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key, alg));
            return m.doFinal(data);
        }

        private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

        private void scram(Conn c, String src, String mech) throws IOException {
            boolean s256 = mech.equals("SCRAM-SHA-256");
            String hashAlg = s256 ? "SHA-256" : "SHA-1", macAlg = s256 ? "HmacSHA256" : "HmacSHA1";
            try {
                String pwd = cfg.pass == null ? "" : cfg.pass;
                if (!s256) {                                    // SCRAM-SHA-1 của MongoDB băm mật khẩu bằng MD5 trước
                    byte[] md5 = MessageDigest.getInstance("MD5").digest(utf8(cfg.user + ":mongo:" + pwd));
                    StringBuilder hx = new StringBuilder();
                    for (byte b : md5) hx.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
                    pwd = hx.toString();
                }
                String user = cfg.user.replace("=", "=3D").replace(",", "=2C");
                byte[] nonceBytes = new byte[24];
                new SecureRandom().nextBytes(nonceBytes);
                String nonce = Base64.getEncoder().encodeToString(nonceBytes);
                String clientFirstBare = "n=" + user + ",r=" + nonce;

                Map<String, Object> r1 = exchange(c, src, cmd("saslStart", 1, "mechanism", mech,
                        "payload", utf8("n,," + clientFirstBare), "autoAuthorize", 1, "options", cmd("skipEmptyExchange", true)));
                if (!isOk(r1)) throw serverError(r1);
                String serverFirst = new String((byte[]) r1.get("payload"), StandardCharsets.UTF_8);
                Map<String, String> sf = new HashMap<>();
                for (String kv : serverFirst.split(",")) if (kv.length() > 2) sf.put(kv.substring(0, 1), kv.substring(2));
                String snonce = sf.get("r");
                if (snonce == null || !snonce.startsWith(nonce) || sf.get("s") == null || sf.get("i") == null)
                    throw new NoSqlException("Máy chủ trả dữ liệu đăng nhập SCRAM không hợp lệ.");
                int iter = Integer.parseInt(sf.get("i"));
                byte[] salt = Base64.getDecoder().decode(sf.get("s"));

                SecretKeyFactory f = SecretKeyFactory.getInstance(s256 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1");
                byte[] salted = f.generateSecret(new PBEKeySpec(pwd.toCharArray(), salt, iter, s256 ? 256 : 160)).getEncoded();
                byte[] clientKey = hmac(macAlg, salted, utf8("Client Key"));
                byte[] storedKey = MessageDigest.getInstance(hashAlg).digest(clientKey);
                String withoutProof = "c=biws,r=" + snonce;
                String authMessage = clientFirstBare + "," + serverFirst + "," + withoutProof;
                byte[] sig = hmac(macAlg, storedKey, utf8(authMessage));
                byte[] proof = new byte[clientKey.length];
                for (int i = 0; i < proof.length; i++) proof[i] = (byte) (clientKey[i] ^ sig[i]);
                String clientFinal = withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);

                Map<String, Object> r2 = exchange(c, src, cmd("saslContinue", 1, "conversationId", r1.get("conversationId"), "payload", utf8(clientFinal)));
                if (!isOk(r2)) throw serverError(r2);
                String serverFinal = new String((byte[]) r2.get("payload"), StandardCharsets.UTF_8);
                byte[] serverKey = hmac(macAlg, salted, utf8("Server Key"));
                String expect = "v=" + Base64.getEncoder().encodeToString(hmac(macAlg, serverKey, utf8(authMessage)));
                if (!serverFinal.equals(expect)) throw new NoSqlException("Máy chủ không chứng minh được danh tính (chữ ký SCRAM sai) — dừng để an toàn.");

                Map<String, Object> r3 = r2;
                int guard = 0;
                while (!flag(r3.get("done")) && guard++ < 3) {
                    r3 = exchange(c, src, cmd("saslContinue", 1, "conversationId", r1.get("conversationId"), "payload", new byte[0]));
                    if (!isOk(r3)) throw serverError(r3);
                }
            } catch (GeneralSecurityException e) {
                throw new NoSqlException("Lỗi mã hoá khi đăng nhập MongoDB: " + e.getMessage());
            }
        }

        // ---------------------------------------------------------------- tìm máy chủ chính + bể kết nối
        private static String msg(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

        /** Dò các máy chủ (seed) để tìm node chính (primary) của replica set; xong thì cất sẵn 1 kết nối đã đăng nhập. */
        private synchronized void discover() {
            epoch++;
            Conn old;
            while ((old = idle.pollFirst()) != null) { old.close(); total.decrementAndGet(); }
            List<String> errs = new ArrayList<>();
            for (String[] sd : cfg.seeds) {
                String h = sd[0];
                int p = Integer.parseInt(sd[1]);
                Conn c = null;
                try {
                    c = openSocket(h, p);
                    Map<String, Object> info = handshake(c);
                    boolean writable = flag(info.get("isWritablePrimary")) || flag(info.get("ismaster"));
                    boolean mongos = "isdbgrid".equals(info.get("msg"));
                    Object primary = info.get("primary");
                    if (!writable && !cfg.direct && !mongos && primary instanceof String && !primary.equals(h + ":" + p)) {
                        c.close(); c = null;
                        String pr = (String) primary;
                        int colon = pr.lastIndexOf(':');
                        h = pr.substring(0, colon);
                        p = Integer.parseInt(pr.substring(colon + 1));
                        c = openSocket(h, p);
                        info = handshake(c);
                    }
                    maxWire = (int) num(info.get("maxWireVersion"));
                    if (maxWire < 6) throw new NoSqlException("Máy chủ MongoDB quá cũ (cần phiên bản 3.6 trở lên).");
                    authenticate(c, info);
                    primaryHost = h; primaryPort = p;
                    idle.addFirst(c);
                    total.incrementAndGet();
                    return;
                } catch (NoSqlException e) {
                    if (c != null) c.close();
                    if (e.authFailure || e.getMessage().contains("quá cũ")) throw e;
                    errs.add(h + ":" + p + " — " + msg(e));
                } catch (IOException | RuntimeException e) {
                    if (c != null) c.close();
                    errs.add(h + ":" + p + " — " + msg(e));
                }
            }
            String hint = (cfg.tls ? " Nếu dùng MongoDB Atlas: vào Network Access và thêm địa chỉ IP của máy này (hoặc 0.0.0.0/0 để thử)." : "");
            throw new NoSqlException("Không nối được MongoDB: " + String.join("; ", errs) + "." + hint);
        }

        private Conn newConn() throws IOException {
            Conn c = openSocket(primaryHost, primaryPort);
            try {
                Map<String, Object> info = handshake(c);
                authenticate(c, info);
                return c;
            } catch (IOException | RuntimeException e) { c.close(); throw e; }
        }

        private Conn borrow() throws IOException {
            if (closed.get()) throw new NoSqlException("Kết nối MongoDB đã đóng.");
            try {
                if (!permits.tryAcquire(15, TimeUnit.SECONDS)) throw new NoSqlException("Hết kết nối trong bể MongoDB (" + total.get() + "/" + cfg.maxPool + "), đã chờ 15 giây.");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new NoSqlException("Bị ngắt khi chờ kết nối MongoDB."); }
            try {
                long now = System.currentTimeMillis();
                Conn c;
                while ((c = idle.pollFirst()) != null) {
                    if (c.epoch == epoch && now - c.lastUsed < 120_000) return c;       // cũ quá thì bỏ: Atlas/NAT hay cắt kết nối nhàn rỗi
                    c.close(); total.decrementAndGet();
                }
                c = newConn();
                total.incrementAndGet();
                return c;
            } catch (IOException | RuntimeException e) { permits.release(); throw e; }
        }

        private void release(Conn c, boolean healthy) {
            try {
                if (healthy && !closed.get() && c.epoch == epoch) { c.lastUsed = System.currentTimeMillis(); idle.addFirst(c); }
                else { c.close(); total.decrementAndGet(); }
            } finally { permits.release(); }
        }

        /** Chạy một lệnh. Tự thử lại 1 lần khi kết nối nhàn rỗi đã chết hoặc node chính vừa đổi. Lỗi máy chủ (ok != 1) ném NoSqlException. */
        Map<String, Object> run(String db, Map<String, Object> command) {
            for (int attempt = 0; ; attempt++) {
                Conn c;
                try { c = borrow(); }
                catch (IOException e) {
                    if (attempt == 0) { try { discover(); continue; } catch (NoSqlException d) { throw d; } }
                    throw new NoSqlException("Không nối được MongoDB: " + msg(e), e);
                }
                boolean healthy = false, reused = c.uses++ > 0;
                try {
                    Map<String, Object> r = exchange(c, db, command);
                    healthy = true;
                    if (isOk(r)) {
                        Object we = r.get("writeErrors");
                        if (we instanceof List && !((List<?>) we).isEmpty()) {
                            Object first = ((List<?>) we).get(0);
                            if (first instanceof Map) {
                                Map<?, ?> w = (Map<?, ?>) first;
                                Map<String, Object> er = new LinkedHashMap<>();
                                er.put("code", w.get("code")); er.put("errmsg", w.get("errmsg"));
                                throw serverError(er);
                            }
                        }
                        return r;
                    }
                    int code = (int) num(r.get("code"));
                    boolean notPrimary = code == 10107 || code == 13435 || code == 11602 || code == 91 || code == 189;
                    if (notPrimary && attempt == 0) { release(c, true); c = null; discover(); continue; }
                    throw serverError(r);
                } catch (IOException e) {
                    // chỉ thử lại khi kết nối cũ bị cắt (chưa chắc lệnh đã chạy thì mới không thử lại với timeout)
                    boolean stale = reused && !(e instanceof SocketTimeoutException);
                    release(c, false); c = null;
                    if (stale && attempt == 0) continue;
                    if (e instanceof SocketTimeoutException) throw new NoSqlException("MongoDB không trả lời kịp (quá " + cfg.socketTimeoutMs / 1000 + " giây).", e);
                    throw new NoSqlException("Mất kết nối tới MongoDB: " + msg(e), e);
                } catch (NoSqlException e) {
                    throw e;
                } catch (RuntimeException e) {
                    healthy = false;
                    throw new NoSqlException("Lỗi khi làm việc với MongoDB: " + msg(e), e);
                } finally {
                    if (c != null) release(c, healthy);
                }
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            Conn c;
            while ((c = idle.pollFirst()) != null) { c.close(); total.decrementAndGet(); }
        }
    }

    // =====================================================================================
    //  D. CỬA NGÕ NOSQL CHO SCRIPT  (hàm nosqlXxx của thư viện hql.database / hql.nosql)
    //     Mỗi Interpreter giữ một đối tượng NoSql: kết nối MongoDB đang dùng + tên database mặc định.
    // =====================================================================================
    public static final class NoSql {

        public static final Set<String> NAMES = new HashSet<>(Arrays.asList(
                "nosqlConnect", "nosqlConnectEnv", "nosqlClose", "nosqlIsOpen", "nosqlError", "nosqlPing",
                "nosqlDatabase", "nosqlDatabases", "nosqlCollections",
                "nosqlInsert", "nosqlInsertMany", "nosqlFind", "nosqlFindOne",
                "nosqlUpdate", "nosqlUpdateMany", "nosqlDelete", "nosqlDeleteMany",
                "nosqlCount", "nosqlAggregate", "nosqlIndex", "nosqlDrop", "nosqlCommand", "nosqlDate", "nosqlDoc"));

        public static boolean handles(String fn) { return NAMES.contains(fn); }

        private volatile Mongo mongo;
        private volatile String dbName = "test";
        private volatile String lastError = "";

        // ------------------------------------------------------------------ đọc cấu hình từ db.henv
        private static boolean isMongoType(String t) {
            if (t == null) return false;
            t = t.trim().toLowerCase(Locale.ROOT);
            return t.equals("mongodb") || t.equals("mongo") || t.equals("nosql") || t.equals("atlas");
        }

        /** Có khai báo NoSQL trong db.henv / .henv không (nosql.url, MONGODB_URI, hoặc db.type=mongodb). */
        public static boolean configuredInEnv() {
            return HqlEnv.first(null, "nosql.url", "NOSQL_URL", "MONGODB_URI", "MONGO_URL", "nosql.host", "NOSQL_HOST") != null
                    || (isMongoType(HqlEnv.first(null, "db.type", "DB_TYPE"))
                        && HqlEnv.first(null, "db.url", "DB_URL", "db.host", "DB_HOST") != null);
        }

        /** true nếu db.type trong db.henv là MongoDB — khi đó KHÔNG tự nối SQL. */
        public static boolean envSaysMongo() { return isMongoType(HqlEnv.first(null, "db.type", "DB_TYPE")); }

        private static String urlEncode(String s) {
            try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
        }

        private static String urlFromEnv() {
            boolean mongoType = isMongoType(HqlEnv.first(null, "db.type", "DB_TYPE"));
            String url = HqlEnv.first(null, "nosql.url", "NOSQL_URL", "MONGODB_URI", "MONGO_URL");
            if (url == null && mongoType) url = HqlEnv.first(null, "db.url", "DB_URL");
            if (url != null) return url;

            String host = HqlEnv.first(null, "nosql.host", "NOSQL_HOST");
            String port = HqlEnv.first(null, "nosql.port", "NOSQL_PORT");
            String user = HqlEnv.first(null, "nosql.user", "NOSQL_USER");
            String pass = HqlEnv.first(null, "nosql.password", "NOSQL_PASSWORD");
            String ssl = HqlEnv.first(null, "nosql.ssl", "NOSQL_SSL");
            if (host == null && mongoType) {
                host = HqlEnv.first(null, "db.host", "DB_HOST");
                port = HqlEnv.first(null, "db.port", "DB_PORT");
                user = HqlEnv.first(null, "db.user", "DB_USER", "db.username");
                pass = HqlEnv.first(null, "db.password", "DB_PASSWORD", "DB_PASS");
                ssl = HqlEnv.first(null, "db.ssl", "DB_SSL");
            }
            if (host == null) return null;
            boolean srv = port == null && host.toLowerCase(Locale.ROOT).endsWith(".mongodb.net");    // Atlas
            StringBuilder b = new StringBuilder(srv ? "mongodb+srv://" : "mongodb://");
            if (user != null && !user.isEmpty()) b.append(urlEncode(user)).append(':').append(urlEncode(pass == null ? "" : pass)).append('@');
            b.append(host);
            if (!srv) b.append(':').append(port == null ? "27017" : port.trim());
            b.append('/');
            if (!srv && "true".equalsIgnoreCase(ssl)) b.append("?tls=true");
            return b.toString();
        }

        private static String dbNameFromEnv() {
            String n = HqlEnv.first(null, "nosql.name", "NOSQL_NAME", "nosql.database", "MONGODB_DB");
            if (n == null && isMongoType(HqlEnv.first(null, "db.type", "DB_TYPE"))) n = HqlEnv.first(null, "db.name", "DB_NAME", "db.dbname", "DB_DATABASE");
            return n;
        }

        // ------------------------------------------------------------------ kết nối
        public synchronized boolean connect(String url, String dbOverride) {
            close();
            lastError = "";
            try {
                Mongo.Cfg cfg = Mongo.parseUrl(url);
                if (HqlEnv.has("nosql.pool.max")) cfg.maxPool = HqlEnv.getInt("nosql.pool.max", cfg.maxPool);
                Mongo m = Mongo.open(cfg);
                mongo = m;
                String n = dbOverride != null && !dbOverride.isEmpty() ? dbOverride : cfg.defaultDb;
                if (n == null || n.isEmpty()) n = dbNameFromEnv();
                dbName = (n == null || n.isEmpty()) ? "test" : n;
                return true;
            } catch (RuntimeException e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                System.err.println("[HQL.NOSQL] Kết nối thất bại: " + lastError);
                return false;
            }
        }

        public synchronized boolean connectEnv() {
            String url = urlFromEnv();
            if (url == null) {
                lastError = "Không thấy cấu hình MongoDB trong db.henv. Cần nosql.url=mongodb+srv://... (hoặc db.type=mongodb kèm db.url / db.host).";
                System.err.println("[HQL.NOSQL] " + lastError);
                return false;
            }
            return connect(url, dbNameFromEnv());
        }

        public synchronized void close() {
            Mongo m = mongo;
            mongo = null;
            if (m != null) m.close();
        }

        private Mongo ensure() {
            Mongo m = mongo;
            if (m != null && !m.isClosed()) return m;
            if (configuredInEnv()) {
                synchronized (this) {
                    if (mongo == null || mongo.isClosed()) connectEnv();
                    if (mongo != null) return mongo;
                }
                throw new NoSqlException("Tự nối MongoDB theo db.henv thất bại: " + lastError);
            }
            throw new NoSqlException("Chưa kết nối NoSQL! Hãy gọi nosqlConnect(\"mongodb+srv://...\") hoặc nosqlConnectEnv(), "
                    + "hoặc khai báo nosql.url trong tệp db.henv.");
        }

        // ------------------------------------------------------------------ tiện ích đổi kiểu
        @SuppressWarnings("unchecked")
        private static Map<String, Object> asDoc(Object o, String what) {
            if (o == null) return new LinkedHashMap<>();
            if (o instanceof String) {
                String t = ((String) o).trim();
                if (t.isEmpty()) return new LinkedHashMap<>();
                Object p;
                try { p = Json.parse(t); } catch (RuntimeException e) { throw new NoSqlException(what + " không phải JSON hợp lệ: " + e.getMessage()); }
                o = p;
            }
            if (!(o instanceof Map)) throw new NoSqlException(what + " phải là map { ... } hoặc chuỗi JSON.");
            return (Map<String, Object>) Bson.fromHql(o, false);
        }

        private static Object asAny(Object o, String what) {
            if (o instanceof String) {
                try { o = Json.parse(((String) o).trim()); } catch (RuntimeException e) { throw new NoSqlException(what + " không phải JSON hợp lệ: " + e.getMessage()); }
            }
            return Bson.fromHql(o, false);
        }

        private static String str(List<Object> a, int i, String what) {
            if (a.size() <= i || a.get(i) == null || String.valueOf(a.get(i)).isEmpty()) throw new NoSqlException("Thiếu tham số: " + what + ".");
            return String.valueOf(a.get(i));
        }

        private static int intOf(Object o, int def) { return o instanceof Number ? ((Number) o).intValue() : def; }

        private static Map<String, Object> m(Object... kv) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
            return r;
        }

        @SuppressWarnings("unchecked")
        private static List<Object> drain(Mongo mg, String db, String col, Map<String, Object> reply, int max) {
            ArrayList<Object> out = new ArrayList<>();
            Map<String, Object> cur = (Map<String, Object>) reply.get("cursor");
            long id = 0;
            while (cur != null) {
                Object batch = cur.containsKey("firstBatch") ? cur.get("firstBatch") : cur.get("nextBatch");
                if (batch instanceof List) for (Object d : (List<Object>) batch) { if (max > 0 && out.size() >= max) break; out.add(Bson.toHql(d)); }
                Object cid = cur.get("id");
                id = cid instanceof Number ? ((Number) cid).longValue() : 0;
                if (id == 0 || (max > 0 && out.size() >= max)) break;
                Map<String, Object> next = mg.run(db, m("getMore", id, "collection", col, "batchSize", 1000));
                cur = (Map<String, Object>) next.get("cursor");
            }
            if (id != 0) {                                         // dừng sớm vì đủ limit → đóng con trỏ bên máy chủ
                try { mg.run(db, m("killCursors", col, "cursors", new ArrayList<Object>(Collections.singletonList(id)))); } catch (RuntimeException ignore) { }
            }
            return out;
        }

        private List<Object> find(Mongo mg, String col, Object filter, Object opts, boolean one) {
            Map<String, Object> o = asDoc(opts, "Tuỳ chọn");
            Map<String, Object> c = m("find", col, "filter", asDoc(filter, "Bộ lọc"));
            if (o.get("sort") != null) c.put("sort", asDoc(o.get("sort"), "sort"));
            Object proj = o.get("fields") != null ? o.get("fields") : (o.get("projection") != null ? o.get("projection") : o.get("select"));
            if (proj != null) c.put("projection", asDoc(proj, "fields"));
            if (o.get("skip") != null) c.put("skip", intOf(o.get("skip"), 0));
            int limit = one ? 1 : intOf(o.get("limit"), 0);
            if (limit > 0) { c.put("limit", limit); if (limit <= 1000) c.put("singleBatch", true); }
            c.put("batchSize", 1000);
            return drain(mg, dbName, col, mg.run(dbName, c), limit);
        }

        private static Map<String, Object> wrapUpdate(Object u) {
            Object x = asAny(u, "Dữ liệu cập nhật");
            if (!(x instanceof Map)) throw new NoSqlException("Dữ liệu cập nhật phải là map, vd {\"$set\": {\"ten\": \"An\"}} hoặc {\"ten\": \"An\"}.");
            @SuppressWarnings("unchecked") Map<String, Object> doc = (Map<String, Object>) x;
            if (doc.isEmpty()) throw new NoSqlException("Dữ liệu cập nhật đang rỗng.");
            boolean hasOp = false, hasPlain = false;
            for (String k : doc.keySet()) { if (k.startsWith("$")) hasOp = true; else hasPlain = true; }
            if (hasOp && hasPlain) throw new NoSqlException("Dữ liệu cập nhật không được trộn khoá thường với toán tử $set/$inc...");
            return hasOp ? doc : m("$set", doc);              // không có toán tử → tự bọc trong $set (khỏi ghi đè cả tài liệu)
        }

        private Object update(Mongo mg, List<Object> a, boolean many) {
            String col = str(a, 0, "tên collection");
            Map<String, Object> q = asDoc(a.size() > 1 ? a.get(1) : null, "Bộ lọc");
            if (a.size() < 3) throw new NoSqlException("Thiếu tham số: dữ liệu cập nhật.");
            boolean upsert = false;
            if (a.size() > 3) {
                Object u = a.get(3);
                upsert = u instanceof Map ? Boolean.TRUE.equals(((Map<?, ?>) u).get("upsert")) : Boolean.TRUE.equals(u);
            }
            Object up = asAny(a.get(2), "Dữ liệu cập nhật");
            Object uDoc = up instanceof List ? up : wrapUpdate(a.get(2));       // mảng = pipeline cập nhật
            Map<String, Object> spec = m("q", q, "u", uDoc, "multi", many, "upsert", upsert);
            Map<String, Object> r = mg.run(dbName, m("update", col, "updates", new ArrayList<Object>(Collections.singletonList(spec)), "ordered", true));
            return (double) intOf(r.get("n"), 0);
        }

        private Object delete(Mongo mg, List<Object> a, boolean many) {
            String col = str(a, 0, "tên collection");
            if (a.size() < 2) throw new NoSqlException("Thiếu bộ lọc. Muốn xoá hết hãy truyền {} (map rỗng) cho rõ ràng.");
            Map<String, Object> spec = m("q", asDoc(a.get(1), "Bộ lọc"), "limit", many ? 0 : 1);
            Map<String, Object> r = mg.run(dbName, m("delete", col, "deletes", new ArrayList<Object>(Collections.singletonList(spec)), "ordered", true));
            return (double) intOf(r.get("n"), 0);
        }

        private List<Object> insert(Mongo mg, String col, List<Object> docs) {
            ArrayList<Object> ids = new ArrayList<>();
            ArrayList<Object> batch = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Map<String, Object> d = asDoc(docs.get(i), "Tài liệu thứ " + (i + 1));
                if (!d.containsKey("_id")) {                        // tự sinh _id (ObjectId) để trả về cho người dùng
                    LinkedHashMap<String, Object> w = new LinkedHashMap<>();
                    w.put("_id", Oid.create());
                    w.putAll(d);
                    d = w;
                }
                ids.add(Bson.toHql(d.get("_id")));
                batch.add(d);
                if (batch.size() >= 500 || i == docs.size() - 1) {
                    mg.run(dbName, m("insert", col, "documents", batch, "ordered", true));
                    batch = new ArrayList<>();
                }
            }
            return ids;
        }

        // ------------------------------------------------------------------ ĐIỂM VÀO TỪ SCRIPT
        @SuppressWarnings("unchecked")
        public Object call(String fn, List<Object> a) {
            switch (fn) {
                // --- kết nối ---
                case "nosqlConnect":
                    return connect(str(a, 0, "địa chỉ mongodb://..."), a.size() > 1 && a.get(1) != null ? String.valueOf(a.get(1)) : null);
                case "nosqlConnectEnv": return connectEnv();
                case "nosqlClose": close(); return null;
                case "nosqlIsOpen": { Mongo mg = mongo; return mg != null && !mg.isClosed(); }
                case "nosqlError": return lastError;
                case "nosqlPing": {
                    try { ensure().run("admin", m("ping", 1)); return true; }
                    catch (RuntimeException e) { lastError = e.getMessage(); return false; }
                }
                case "nosqlDatabase": {
                    if (!a.isEmpty() && a.get(0) != null) dbName = str(a, 0, "tên database");
                    return dbName;
                }
                case "nosqlDoc": {
                    // nosqlDoc("{ten: ?, tuoi: {$gt: ?}}", [ten, 18]) → map (hoặc mảng) dùng làm bộ lọc / tài liệu
                    String text = str(a, 0, "chuỗi JSON");
                    Object ps = a.size() > 1 ? a.get(1) : null;
                    if (ps != null && !(ps instanceof List)) throw new NoSqlException("Tham số thứ 2 của nosqlDoc phải là mảng giá trị, vd [ten, 18].");
                    try { return Json.parse(text, (List<Object>) ps); }
                    catch (IllegalArgumentException e) { throw new NoSqlException("nosqlDoc: " + e.getMessage()); }
                }
                case "nosqlDate": {
                    return m("$date", a.isEmpty() || a.get(0) == null ? Instant.now().toString() : a.get(0));
                }
                default: break;
            }

            Mongo mg = ensure();
            switch (fn) {
                case "nosqlDatabases": {
                    Map<String, Object> r = mg.run("admin", m("listDatabases", 1, "nameOnly", true));
                    ArrayList<Object> out = new ArrayList<>();
                    for (Object d : (List<Object>) r.get("databases")) out.add(((Map<String, Object>) d).get("name"));
                    Collections.sort((List) out);
                    return out;
                }
                case "nosqlCollections": {
                    Map<String, Object> r = mg.run(dbName, m("listCollections", 1, "nameOnly", true));
                    ArrayList<Object> out = new ArrayList<>();
                    for (Object d : drain(mg, dbName, "$cmd.listCollections", r, 0)) out.add(((Map<String, Object>) d).get("name"));
                    Collections.sort((List) out);
                    return out;
                }
                case "nosqlInsert": {
                    String col = str(a, 0, "tên collection");
                    if (a.size() < 2) throw new NoSqlException("Thiếu tham số: tài liệu cần thêm.");
                    return insert(mg, col, new ArrayList<Object>(Collections.singletonList(a.get(1)))).get(0);
                }
                case "nosqlInsertMany": {
                    String col = str(a, 0, "tên collection");
                    Object l = a.size() > 1 ? a.get(1) : null;
                    if (l instanceof String) l = Json.parse((String) l);
                    if (!(l instanceof List)) throw new NoSqlException("nosqlInsertMany cần một mảng các tài liệu: [ {...}, {...} ].");
                    if (((List<?>) l).isEmpty()) return new ArrayList<Object>();
                    return insert(mg, col, (List<Object>) l);
                }
                case "nosqlFind": return find(mg, str(a, 0, "tên collection"), a.size() > 1 ? a.get(1) : null, a.size() > 2 ? a.get(2) : null, false);
                case "nosqlFindOne": {
                    List<Object> r = find(mg, str(a, 0, "tên collection"), a.size() > 1 ? a.get(1) : null, a.size() > 2 ? a.get(2) : null, true);
                    return r.isEmpty() ? null : r.get(0);
                }
                case "nosqlUpdate": return update(mg, a, false);
                case "nosqlUpdateMany": return update(mg, a, true);
                case "nosqlDelete": return delete(mg, a, false);
                case "nosqlDeleteMany": return delete(mg, a, true);
                case "nosqlCount": {
                    String col = str(a, 0, "tên collection");
                    ArrayList<Object> pipe = new ArrayList<>();
                    pipe.add(m("$match", asDoc(a.size() > 1 ? a.get(1) : null, "Bộ lọc")));
                    pipe.add(m("$count", "n"));
                    List<Object> r = drain(mg, dbName, col, mg.run(dbName, m("aggregate", col, "pipeline", pipe, "cursor", m())), 0);
                    return r.isEmpty() ? 0.0 : ((Map<String, Object>) r.get(0)).get("n");
                }
                case "nosqlAggregate": {
                    String col = str(a, 0, "tên collection");
                    Object p = asAny(a.size() > 1 ? a.get(1) : null, "Pipeline");
                    if (!(p instanceof List)) throw new NoSqlException("nosqlAggregate cần một mảng các bước: [ {\"$match\": {...}}, {\"$group\": {...}} ].");
                    return drain(mg, dbName, col, mg.run(dbName, m("aggregate", col, "pipeline", p, "cursor", m("batchSize", 1000))), 0);
                }
                case "nosqlIndex": {
                    String col = str(a, 0, "tên collection");
                    Map<String, Object> keys = asDoc(a.size() > 1 ? a.get(1) : null, "Khoá chỉ mục");
                    if (keys.isEmpty()) throw new NoSqlException("Thiếu khoá chỉ mục, vd {\"email\": 1}.");
                    StringBuilder name = new StringBuilder();
                    for (Map.Entry<String, Object> e : keys.entrySet()) {
                        if (name.length() > 0) name.append('_');
                        name.append(e.getKey()).append('_').append(e.getValue());
                    }
                    Map<String, Object> idx = m("key", keys, "name", name.toString());
                    if (a.size() > 2) {
                        Object u = a.get(2);
                        if (Boolean.TRUE.equals(u) || (u instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) u).get("unique")))) idx.put("unique", true);
                    }
                    mg.run(dbName, m("createIndexes", col, "indexes", new ArrayList<Object>(Collections.singletonList(idx))));
                    return name.toString();
                }
                case "nosqlDrop": {
                    String col = str(a, 0, "tên collection");
                    try { mg.run(dbName, m("drop", col)); return true; }
                    catch (NoSqlException e) { if (e.getMessage().contains("[26]")) return false; throw e; }   // 26 = collection không tồn tại
                }
                case "nosqlCommand": {
                    Map<String, Object> c = asDoc(a.isEmpty() ? null : a.get(0), "Lệnh");
                    if (c.isEmpty()) throw new NoSqlException("Thiếu lệnh, vd nosqlCommand({\"ping\": 1}).");
                    return Bson.toHql(mg.run(a.size() > 1 && a.get(1) != null ? String.valueOf(a.get(1)) : dbName, c));
                }
                default: throw new NoSqlException("Hàm NoSQL không có: " + fn);
            }
        }
    }
}
