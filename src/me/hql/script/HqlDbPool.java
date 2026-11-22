package me.hql.script;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private Entry open() throws SQLException {
        Connection c = (cfg.user == null)
                ? DriverManager.getConnection(cfg.url)
                : DriverManager.getConnection(cfg.url, cfg.user, cfg.pass);
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
}
