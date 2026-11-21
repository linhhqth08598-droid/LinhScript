package me.hql.script;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TỆP CẤU HÌNH MÔI TRƯỜNG LINHSCRIPT (.henv)
 * (tiếng Anh: HQL Environment Configuration File)
 *
 * Cùng ý tưởng với .env của Node.js (dotenv) và .properties của Java:
 * mỗi dòng một cặp KHOÁ=GIÁ TRỊ, dùng để cất mật khẩu, địa chỉ máy chủ, cổng...
 * ra khỏi mã nguồn.
 *
 * ┌─ CÚ PHÁP ─────────────────────────────────────────────────────────────┐
 * │  # dòng chú thích                      (cũng nhận ; ở đầu dòng)        │
 * │  DB_HOST=127.0.0.1                     giá trị trần                    │
 * │  DB_NAME = "GearZone"                  nháy kép: hiểu \n \t \" \\      │
 * │  MAIL_NAME='GEAR ZONE'                 nháy đơn: giữ nguyên từng chữ   │
 * │  export DB_PORT=1433                   chữ export ở đầu được bỏ qua    │
 * │  DB_URL=jdbc:sqlserver://${DB_HOST}:${DB_PORT}   nối biến               │
 * │  DB_USER=${DB_USER_MAC_DINH:-sa}       giá trị dự phòng                 │
 * │  db.hostname=localhost                 khoá có dấu chấm (kiểu .properties)│
 * │  GHI_CHU="nhiều                        giá trị nhiều dòng:              │
 * │  dòng"                                  mở nháy kép, đóng ở dòng sau    │
 * │  PASS=abc # chú thích cuối dòng        " #" cắt phần phía sau (giá trị  │
 * │                                         trần); muốn giữ dấu # thì bọc nháy│
 * └────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ THỨ TỰ ƯU TIÊN (cao → thấp) ─────────────────────────────────────────┐
 * │  1. Biến môi trường thật của hệ điều hành (giống dotenv: không bị ghi đè)│
 * │  2. .henv.local        (riêng máy này — KHÔNG đưa lên git)             │
 * │  3. .henv.<chế độ>     (vd .henv.production, chọn bằng HQL_ENV=...)    │
 * │  4. .henv              (giá trị chung của dự án)                       │
 * └────────────────────────────────────────────────────────────────────────┘
 *
 * Lớp này thread-safe và không phụ thuộc thư viện ngoài.
 */
public final class HqlEnv {

    private HqlEnv() {}

    /** Giá trị đọc từ các tệp .henv (chưa tính biến môi trường hệ điều hành). */
    private static final Map<String, String> VALUES = new ConcurrentHashMap<>();
    /** Danh sách tệp đã nạp, theo thứ tự nạp (để hiện cho người dùng biết). */
    private static final List<String> LOADED_FILES = new ArrayList<>();
    /** Đã tự động dò tệp .henv chưa (mỗi thư mục gốc chỉ dò một lần). */
    private static final java.util.Set<String> AUTO_DONE = ConcurrentHashMap.newKeySet();

    private static final int MAX_LEVELS_UP = 6;

    // ======================================================================
    //   NẠP TỆP
    // ======================================================================

    /**
     * Tự động dò và nạp .henv cho một thư mục dự án: tìm ngược lên tối đa 6 cấp cha
     * cho tới khi gặp thư mục có .henv, rồi nạp theo thứ tự ưu tiên ở trên.
     * Gọi nhiều lần cùng một thư mục là vô hại (chỉ nạp lần đầu).
     *
     * @return true nếu nạp được ít nhất một tệp
     */
    public static boolean autoLoad(File startDir) {
        if (startDir == null) return false;
        File dir = startDir.getAbsoluteFile();
        if (!AUTO_DONE.add(dir.getPath())) return false;

        for (int i = 0; dir != null && i <= MAX_LEVELS_UP; i++, dir = dir.getParentFile()) {
            if (new File(dir, ".henv").isFile() || new File(dir, ".henv.local").isFile()) {
                boolean any = false;
                any |= loadFile(new File(dir, ".henv"), false);
                String mode = getRaw("HQL_ENV");
                if (mode != null && !mode.isEmpty()) {
                    any |= loadFile(new File(dir, ".henv." + mode), true);
                }
                any |= loadFile(new File(dir, ".henv.local"), true);
                return any;
            }
        }
        return false;
    }

    /**
     * Nạp một tệp cụ thể.
     *
     * @param override true: khoá trùng sẽ bị ghi đè bởi tệp này; false: giữ giá trị cũ
     * @return true nếu tệp tồn tại và đọc được
     */
    public static boolean loadFile(File f, boolean override) {
        if (f == null || !f.isFile()) return false;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Map<String, String> parsed = parse(text);
            synchronized (LOADED_FILES) {
                for (Map.Entry<String, String> e : parsed.entrySet()) {
                    if (override || !VALUES.containsKey(e.getKey())) VALUES.put(e.getKey(), e.getValue());
                }
                String duongDan = f.getAbsolutePath();
                if (!LOADED_FILES.contains(duongDan)) LOADED_FILES.add(duongDan);   // không ghi trùng cùng một tệp
            }
            return true;
        } catch (IOException ex) {
            System.err.println("[HQL.ENV] Không đọc được " + f.getAbsolutePath() + ": " + ex.getMessage());
            return false;
        }
    }

    // ======================================================================
    //   PHÂN TÍCH CÚ PHÁP
    // ======================================================================

    /** Phân tích nội dung văn bản .henv thành bảng khoá → giá trị (giữ thứ tự dòng). */
    public static Map<String, String> parse(String text) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return out;
        if (text.charAt(0) == '\uFEFF') text = text.substring(1);     // bỏ BOM của Notepad
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        int n = text.length();
        int i = 0;
        while (i < n) {
            // --- bỏ khoảng trắng đầu dòng ---
            while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
            if (i >= n) break;
            char c = text.charAt(i);
            if (c == '\n') { i++; continue; }
            if (c == '#' || c == ';') { i = skipLine(text, i); continue; }

            // --- chữ "export " ở đầu ---
            if (text.startsWith("export", i) && i + 6 < n
                    && (text.charAt(i + 6) == ' ' || text.charAt(i + 6) == '\t')) {
                i += 6;
                while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
            }

            // --- khoá ---
            int ks = i;
            while (i < n && isKeyChar(text.charAt(i))) i++;
            String key = text.substring(ks, i);
            while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
            if (key.isEmpty() || i >= n || (text.charAt(i) != '=' && text.charAt(i) != ':')) {
                i = skipLine(text, i);          // dòng hỏng: bỏ qua cho an toàn
                continue;
            }
            i++;                                // qua dấu = hoặc :
            while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;

            // --- giá trị ---
            String value;
            if (i < n && text.charAt(i) == '"') {
                int[] pos = {i + 1};
                value = expand(readDoubleQuoted(text, pos), out);
                i = skipLine(text, pos[0]);
            } else if (i < n && text.charAt(i) == '\'') {
                int end = text.indexOf('\'', i + 1);
                if (end < 0) end = n;
                value = text.substring(i + 1, end);
                i = skipLine(text, Math.min(end + 1, n));
            } else {
                int le = text.indexOf('\n', i);
                if (le < 0) le = n;
                String raw = text.substring(i, le);
                int hash = indexOfInlineComment(raw);
                if (hash >= 0) raw = raw.substring(0, hash);
                value = expand(raw.trim(), out);
                i = le;
            }
            out.put(key, value);
        }
        return out;
    }

    private static boolean isKeyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static int skipLine(String s, int from) {
        int e = s.indexOf('\n', from);
        return e < 0 ? s.length() : e + 1;
    }

    /** Vị trí của " #" (dấu # đứng sau khoảng trắng) — bắt đầu chú thích cuối dòng. */
    private static int indexOfInlineComment(String raw) {
        for (int k = 0; k < raw.length(); k++) {
            if (raw.charAt(k) == '#' && (k == 0 || raw.charAt(k - 1) == ' ' || raw.charAt(k - 1) == '\t')) return k;
        }
        return -1;
    }

    /** Đọc chuỗi trong nháy kép (có thể nhiều dòng); pos[0] chạy tới sau dấu nháy đóng. */
    private static String readDoubleQuoted(String s, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int k = pos[0];
        while (k < s.length()) {
            char c = s.charAt(k++);
            if (c == '"') break;
            if (c == '\\' && k < s.length()) {
                char nx = s.charAt(k++);
                switch (nx) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '$': sb.append("\u0000"); break;   // \$ -> giữ nguyên dấu $, không nối biến
                    default: sb.append('\\').append(nx);
                }
            } else {
                sb.append(c);
            }
        }
        pos[0] = k;
        return sb.toString();
    }

    /** Thay ${TEN} và ${TEN:-macDinh} bằng giá trị đã khai báo trước đó / biến môi trường. */
    private static String expand(String v, Map<String, String> sameFile) {
        if (v.indexOf('$') < 0) return v.replace("\u0000", "$");
        StringBuilder sb = new StringBuilder();
        int k = 0;
        while (k < v.length()) {
            int s = v.indexOf("${", k);
            if (s < 0) { sb.append(v, k, v.length()); break; }
            int e = v.indexOf('}', s + 2);
            if (e < 0) { sb.append(v, k, v.length()); break; }
            sb.append(v, k, s);
            String expr = v.substring(s + 2, e);
            String def = "";
            int d = expr.indexOf(":-");
            if (d >= 0) { def = expr.substring(d + 2); expr = expr.substring(0, d); }
            String found = sameFile.get(expr.trim());
            if (found == null) found = get(expr.trim(), null);
            sb.append(found == null || found.isEmpty() ? def : found);
            k = e + 1;
        }
        return sb.toString().replace("\u0000", "$");
    }

    // ======================================================================
    //   TRUY XUẤT
    // ======================================================================

    private static String getRaw(String key) {
        String sys = System.getenv(key);
        if (sys != null) return sys;
        return VALUES.get(key);
    }

    /** Lấy giá trị theo khoá (biến hệ điều hành thắng .henv). Trả về def nếu không có. */
    public static String get(String key, String def) {
        if (key == null) return def;
        String v = getRaw(key);
        return v == null ? def : v;
    }

    /** Thử lần lượt nhiều tên khoá (vd "DB_HOST", "db.hostname"), lấy cái đầu tiên có giá trị. */
    public static String first(String def, String... keys) {
        for (String k : keys) {
            String v = getRaw(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return def;
    }

    public static boolean has(String key) { return getRaw(key) != null; }

    /** Đặt giá trị tạm trong bộ nhớ (không ghi ra tệp). */
    public static void set(String key, String value) {
        if (key == null) return;
        if (value == null) VALUES.remove(key); else VALUES.put(key, value);
    }

    public static int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** true với: true, 1, yes, on, y, co, có (không phân biệt hoa thường). */
    public static boolean getBool(String key, boolean def) {
        String v = get(key, null);
        if (v == null) return def;
        switch (v.trim().toLowerCase()) {
            case "true": case "1": case "yes": case "on": case "y": case "co": case "có": return true;
            case "false": case "0": case "no": case "off": case "n": case "khong": case "không": return false;
            default: return def;
        }
    }

    /** Bản sao toàn bộ khoá đã nạp từ tệp .henv (không gồm biến hệ điều hành), xếp A-Z. */
    public static Map<String, String> all() {
        return new java.util.TreeMap<>(VALUES);
    }

    public static List<String> loadedFiles() {
        synchronized (LOADED_FILES) { return new ArrayList<>(LOADED_FILES); }
    }

    /** Che mật khẩu khi in ra màn hình/log: giữ 2 ký tự đầu, còn lại thành *. */
    public static String mask(String key, String value) {
        if (value == null) return "";
        String k = key.toLowerCase();
        if (k.contains("pass") || k.contains("secret") || k.contains("token") || k.contains("key")) {
            return value.length() <= 2 ? "***" : value.substring(0, 2) + "*".repeat(Math.min(value.length() - 2, 10));
        }
        return value;
    }
}