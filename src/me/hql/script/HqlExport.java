package me.hql.script;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * XUẤT DỮ LIỆU RA XLSX / DOCX / PDF / CSV CHO LINHSCRIPT (HqlExport)
 *
 * Không cần thư viện ngoài (không Apache POI, không iText):
 *   - XLSX, DOCX: ghép tệp zip + XML bằng java.util.zip.
 *   - PDF: tự ghi cú pháp PDF, nhúng font TrueType của máy để hiện đúng tiếng Việt.
 *
 * Dữ liệu vào : danh sách dòng. Mỗi dòng là Map (cột = khoá) hoặc danh sách (dòng đầu = tiêu đề).
 * Tuỳ chọn    : Map hoặc chuỗi JSON. Mọi thứ đều TUỲ CHỌN - không truyền gì vẫn ra bảng đẹp.
 *
 * Kiểu chữ / màu của MỘT phần tử gồm các khoá (đều không bắt buộc):
 *   font, size, bold, italic, underline, strike, color (màu chữ), bg (màu nền ô),
 *   align (left/center/right), valign (top/middle/bottom), wrap, border (true/false/màu), format (số)
 * Màu: "#RRGGBB", "#RGB", "rgb(r,g,b)" hoặc tên (red, blue, do, xanh...).
 *
 * Tuỳ chọn cấp cao nhất:
 *   sheet, title, subtitle, titleStyle, subtitleStyle, font, size, header, body, zebra,
 *   columns {tên|số thứ tự: {kiểu..., width}}, rules [{col,op,value,scope,kiểu..}],
 *   cells [{row,col,kiểu..}], only [..], labels {..}, headers [..], freeze, filter, gridlines,
 *   orientation, pageSize, margin, fontFile, pageNumbers, footer,
 *   numberLocale ('vi' mặc định: 1.234,5 | 'us': 1,234.5 - áp dụng cho DOCX/PDF), delimiter + bom (chỉ CSV)
 */
public final class HqlExport {

    private HqlExport() { }

    // ==========================================================================================
    //  CỔNG VÀO
    // ==========================================================================================

    public static void toXlsx(Object data, String path, Object options) throws IOException { save(path, buildXlsx(data, options)); }
    public static void toDocx(Object data, String path, Object options) throws IOException { save(path, buildDocx(data, options)); }
    public static void toPdf(Object data, String path, Object options) throws IOException { save(path, buildPdf(data, options)); }

    public static byte[] buildXlsx(Object data, Object options) throws IOException { return new XlsxWriter(new Ctx(data, options)).build(); }
    public static byte[] buildDocx(Object data, Object options) throws IOException { return new DocxWriter(new Ctx(data, options)).build(); }
    public static byte[] buildPdf(Object data, Object options) throws IOException { return new PdfWriter(new Ctx(data, options)).build(); }

    // ---- CSV: cùng dữ liệu, cùng tuỳ chọn only/labels/headers; thêm delimiter (mặc định ","), bom (mặc định true)
    public static void toCsv(Object data, String path, Object options) throws IOException { save(path, buildCsv(data, options)); }

    public static byte[] buildCsv(Object data, Object options) throws IOException {
        Ctx c = new Ctx(data, options);
        String d = str(g(c.opt, "delimiter", "ngancach"));
        if (d.isEmpty()) d = ",";
        StringBuilder sb = new StringBuilder();
        if (c.flag("bom", true)) sb.append('\ufeff');          // BOM để Excel đọc đúng tiếng Việt
        for (int i = 0; i < c.labels.size(); i++) sb.append(i > 0 ? d : "").append(csvQuote(c.labels.get(i), d));
        sb.append("\r\n");
        for (Object[] row : c.rows) {
            for (int i = 0; i < row.length; i++) sb.append(i > 0 ? d : "").append(csvQuote(textOf(row[i]), d));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvQuote(String s, String d) {
        if (s.contains(d) || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ---- Chọn định dạng theo tên: "xlsx" | "docx" | "pdf" | "csv" (hoặc theo đuôi tệp)
    public static byte[] build(String kind, Object data, Object options) throws IOException {
        switch (normKind(kind)) {
            case "xlsx": return buildXlsx(data, options);
            case "docx": return buildDocx(data, options);
            case "pdf": return buildPdf(data, options);
            case "csv": return buildCsv(data, options);
            default: throw new IllegalArgumentException("Định dạng xuất không hỗ trợ: '" + kind + "' (dùng xlsx, docx, pdf hoặc csv).");
        }
    }

    public static void to(String kind, Object data, String path, Object options) throws IOException { save(path, build(kind, data, options)); }

    /** Đoán định dạng từ đuôi tệp: "bao-cao.xlsx" -> "xlsx". Không nhận ra thì trả chuỗi rỗng. */
    public static String kindOfPath(String path) {
        String n = path == null ? "" : path.toLowerCase();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : normKind(n.substring(dot + 1));
    }

    private static String normKind(String k) {
        String t = k == null ? "" : k.trim().toLowerCase();
        if (t.startsWith(".")) t = t.substring(1);
        switch (t) {
            case "xlsx": case "xls": case "excel": return "xlsx";
            case "docx": case "doc": case "word": return "docx";
            case "pdf": return "pdf";
            case "csv": return "csv";
            default: return "";
        }
    }

    public static String mimeOf(String kind) {
        switch (normKind(kind)) {
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pdf": return "application/pdf";
            case "csv": return "text/csv; charset=UTF-8";
            default: return "application/octet-stream";
        }
    }

    private static void save(String path, byte[] bytes) throws IOException {
        Path p = Paths.get(path);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    // ==========================================================================================
    //  KIỂU DÁNG
    // ==========================================================================================

    /** Một bộ kiểu dáng. Trường null = "chưa quy định", để lớp sau ghi đè lên lớp trước. */
    static final class Style {
        String font, fontFile, align, valign, format;
        Double size;
        Boolean bold, italic, underline, strike, wrap, border;
        Integer color, bg, borderColor;

        Style copy() {
            Style s = new Style();
            s.font = font; s.fontFile = fontFile; s.align = align; s.valign = valign; s.format = format;
            s.size = size; s.bold = bold; s.italic = italic; s.underline = underline; s.strike = strike;
            s.wrap = wrap; s.border = border; s.color = color; s.bg = bg; s.borderColor = borderColor;
            return s;
        }

        /** Bỏ cỡ chữ (dùng cho tiêu đề: cỡ chữ toàn cục không được đè cỡ chữ riêng của tiêu đề). */
        Style sizeless() { size = null; return this; }

        /** Phủ kiểu o lên this: chỗ nào o có quy định thì thắng. */
        Style over(Style o) {
            if (o == null) return this;
            if (o.font != null) font = o.font;
            if (o.fontFile != null) fontFile = o.fontFile;
            if (o.align != null) align = o.align;
            if (o.valign != null) valign = o.valign;
            if (o.format != null) format = o.format;
            if (o.size != null) size = o.size;
            if (o.bold != null) bold = o.bold;
            if (o.italic != null) italic = o.italic;
            if (o.underline != null) underline = o.underline;
            if (o.strike != null) strike = o.strike;
            if (o.wrap != null) wrap = o.wrap;
            if (o.border != null) border = o.border;
            if (o.color != null) color = o.color;
            if (o.bg != null) bg = o.bg;
            if (o.borderColor != null) borderColor = o.borderColor;
            return this;
        }

        boolean b() { return Boolean.TRUE.equals(bold); }
        boolean i() { return Boolean.TRUE.equals(italic); }
        boolean u() { return Boolean.TRUE.equals(underline); }
        boolean st() { return Boolean.TRUE.equals(strike); }
        boolean hasBorder() { return !Boolean.FALSE.equals(border); }
        double sizeOr(double def) { return size == null ? def : size; }
        int colorOr(int def) { return color == null ? def : color; }
        int borderColorOr(int def) { return borderColor == null ? def : borderColor; }

        static Style from(Object o) {
            Style s = new Style();
            if (!(o instanceof Map)) return s;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            Object v;
            if ((v = g(m, "font", "fontname", "fontfamily", "phongchu")) != null) s.font = str(v);
            if ((v = g(m, "fontfile")) != null) s.fontFile = str(v);
            if ((v = g(m, "size", "fontsize", "codechu")) != null) s.size = toNum(v);
            if ((v = g(m, "bold", "b", "dam")) != null) s.bold = bool(v);
            if ((v = g(m, "italic", "i", "nghieng")) != null) s.italic = bool(v);
            if ((v = g(m, "underline", "u", "gachchan")) != null) s.underline = bool(v);
            if ((v = g(m, "strike", "strikethrough", "gachngang")) != null) s.strike = bool(v);
            if ((v = g(m, "color", "textcolor", "fg", "mauchu")) != null) s.color = parseColor(v);
            if ((v = g(m, "bg", "background", "backgroundcolor", "fill", "cellcolor", "maunen")) != null) s.bg = parseColor(v);
            if ((v = g(m, "align", "halign", "canle")) != null) s.align = alignOf(str(v));
            if ((v = g(m, "valign")) != null) s.valign = valignOf(str(v));
            if ((v = g(m, "wrap", "wraptext")) != null) s.wrap = bool(v);
            if ((v = g(m, "format", "numfmt", "numberformat")) != null) s.format = str(v);
            if ((v = g(m, "bordercolor")) != null) { s.borderColor = parseColor(v); if (s.border == null) s.border = Boolean.TRUE; }
            if ((v = g(m, "border", "vien")) != null) {
                if (v instanceof Boolean || (v instanceof String && isBoolWord((String) v))) s.border = bool(v);
                else { Integer c = parseColor(v); if (c != null) { s.border = Boolean.TRUE; s.borderColor = c; } }
            }
            return s;
        }
    }

    // ==========================================================================================
    //  NGỮ CẢNH: dữ liệu + tuỳ chọn đã được chuẩn hoá, dùng chung cho cả 3 định dạng
    // ==========================================================================================

    static final class Rule {
        int col = -1; String op = "=="; Object value; boolean rowScope; Style style;
    }
    static final class CellDef {
        int row = -1, col = -1; Style style;
    }

    static final class Ctx {
        final Map<String, Object> opt;
        final List<String> keys = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Object[]> rows = new ArrayList<>();

        String title, subtitle;
        Style titleStyle, subtitleStyle, headerStyle, bodyStyle, zebraStyle;
        Style[] colStyle;
        double[] colWidth;                       // đơn vị "ký tự"; 0 = tự đo
        final List<Rule> rules = new ArrayList<>();
        final List<CellDef> cells = new ArrayList<>();

        Ctx(Object data, Object options) {
            opt = parseOptions(options);
            buildTable(data);
            int n = keys.size();
            colStyle = new Style[n];
            colWidth = new double[n];
            for (int c = 0; c < n; c++) colStyle[c] = new Style();

            Style globalFont = new Style();
            Object gf = g(opt, "font", "fontname");
            if (gf instanceof String) globalFont.font = (String) gf;
            Object gs = g(opt, "size", "fontsize");
            if (gs != null) globalFont.size = toNum(gs);
            Object gff = g(opt, "fontfile");
            if (gff instanceof String) globalFont.fontFile = (String) gff;

            // --- Mặc định có sẵn (người dùng ghi đè được từng chi tiết) ---
            bodyStyle = new Style();
            bodyStyle.font = "Calibri"; bodyStyle.color = 0x1F2937; bodyStyle.border = true; bodyStyle.borderColor = 0xD1D5DB;
            bodyStyle.over(globalFont).over(Style.from(g(opt, "body", "dulieu")));

            headerStyle = new Style();
            headerStyle.font = "Calibri"; headerStyle.bold = true; headerStyle.color = 0xFFFFFF; headerStyle.bg = 0x1D4ED8;
            headerStyle.align = "center"; headerStyle.valign = "middle"; headerStyle.border = true; headerStyle.borderColor = 0xD1D5DB;
            headerStyle.over(globalFont).over(Style.from(g(opt, "header", "tieude")));

            // --- Tiêu đề và phụ đề: có thể là chuỗi, hoặc {text: "...", + kiểu} ---
            titleStyle = new Style();
            titleStyle.font = "Calibri"; titleStyle.bold = true; titleStyle.size = 16.0; titleStyle.color = 0x111827;
            titleStyle.align = "center"; titleStyle.valign = "middle"; titleStyle.border = false;
            titleStyle.over(globalFont.copy().sizeless());
            Object t = g(opt, "title", "tenbang");
            if (t instanceof Map) { title = textOf(g(castMap(t), "text", "noidung")); titleStyle.over(Style.from(t)); }
            else if (t != null) title = str(t);
            titleStyle.over(Style.from(g(opt, "titlestyle")));

            subtitleStyle = new Style();
            subtitleStyle.font = "Calibri"; subtitleStyle.italic = true; subtitleStyle.size = 10.0; subtitleStyle.color = 0x6B7280;
            subtitleStyle.align = "center"; subtitleStyle.valign = "middle"; subtitleStyle.border = false;
            subtitleStyle.over(globalFont.copy().sizeless());
            Object st = g(opt, "subtitle", "phude");
            if (st instanceof Map) { subtitle = textOf(g(castMap(st), "text", "noidung")); subtitleStyle.over(Style.from(st)); }
            else if (st != null) subtitle = str(st);
            subtitleStyle.over(Style.from(g(opt, "subtitlestyle")));

            // --- Dòng xen kẽ ---
            Object z = g(opt, "zebra", "xenke");
            if (z instanceof Map) zebraStyle = Style.from(z);
            else if (z instanceof Boolean) { if ((Boolean) z) { zebraStyle = new Style(); zebraStyle.bg = 0xF3F4F6; } }
            else if (z != null) { Integer c = parseColor(z); if (c != null) { zebraStyle = new Style(); zebraStyle.bg = c; } }

            // --- Theo cột ---
            Object cols = g(opt, "columns", "cot");
            if (cols instanceof Map) {
                for (Map.Entry<String, Object> e : castMap(cols).entrySet()) {
                    int c = colIndex(e.getKey());
                    if (c < 0) continue;
                    colStyle[c] = Style.from(e.getValue());
                    Object w = g(castMap(e.getValue()), "width", "rong");
                    if (w != null && toNum(w) != null) colWidth[c] = toNum(w);
                }
            }

            // --- Luật tô màu theo điều kiện ---
            Object ru = g(opt, "rules", "luat");
            if (ru instanceof List) {
                for (Object o : (List<?>) ru) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = castMap(o);
                    Rule r = new Rule();
                    Object col = g(m, "col", "column", "cot");
                    r.col = (col == null || "*".equals(str(col))) ? -1 : colIndex(col);
                    if (col != null && !"*".equals(str(col)) && r.col < 0) r.col = -3;      // cột không tồn tại: luật không bao giờ khớp
                    Object op = g(m, "op", "operator");
                    if (op != null) r.op = str(op);
                    r.value = g(m, "value", "giatri");
                    Object sc = g(m, "scope", "phamvi");
                    r.rowScope = sc != null && (str(sc).equalsIgnoreCase("row") || str(sc).equalsIgnoreCase("dong"));
                    r.style = Style.from(m);
                    rules.add(r);
                }
            }

            // --- Tô riêng từng ô / dòng / cột ---
            Object ce = g(opt, "cells", "o");
            if (ce instanceof List) {
                for (Object o : (List<?>) ce) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = castMap(o);
                    CellDef d = new CellDef();
                    Object row = g(m, "row", "dong");
                    if (row != null && !"*".equals(str(row))) { Double rn = toNum(row); d.row = rn == null ? -2 : rn.intValue(); }
                    Object col = g(m, "col", "column", "cot");
                    if (col != null && !"*".equals(str(col))) { d.col = colIndex(col); if (d.col < 0) d.col = -3; }
                    d.style = Style.from(m);
                    cells.add(d);
                }
            }
        }

        // ---------------------------------------------------------------- bảng
        @SuppressWarnings("unchecked")
        private void buildTable(Object data) {
            List<Object> list = new ArrayList<>();
            if (data instanceof Map) list.add(data);
            else if (data instanceof List) list.addAll((List<Object>) data);
            else if (data instanceof Object[]) { for (Object o : (Object[]) data) list.add(o); }
            else if (data != null) list.add(data);

            List<String> k = new ArrayList<>();
            List<Object[]> rs = new ArrayList<>();

            Object first = null;
            for (Object o : list) { if (o != null) { first = o; break; } }

            if (first instanceof Map) {
                LinkedHashMap<String, Integer> idx = new LinkedHashMap<>();
                for (Object o : list) {
                    if (!(o instanceof Map)) continue;
                    for (Object key : ((Map<Object, Object>) o).keySet()) idx.putIfAbsent(String.valueOf(key), idx.size());
                }
                k.addAll(idx.keySet());
                for (Object o : list) {
                    if (!(o instanceof Map)) continue;
                    Map<Object, Object> m = (Map<Object, Object>) o;
                    Map<String, Object> flat = new HashMap<>();
                    for (Map.Entry<Object, Object> e : m.entrySet()) flat.put(String.valueOf(e.getKey()), e.getValue());
                    Object[] row = new Object[k.size()];
                    for (int c = 0; c < k.size(); c++) row[c] = flat.get(k.get(c));
                    rs.add(row);
                }
            } else if (first instanceof List || first instanceof Object[]) {
                List<List<Object>> grid = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof List) grid.add(new ArrayList<>((List<Object>) o));
                    else if (o instanceof Object[]) { List<Object> r = new ArrayList<>(); for (Object x : (Object[]) o) r.add(x); grid.add(r); }
                }
                int width = 0;
                for (List<Object> r : grid) width = Math.max(width, r.size());
                Object hdr = g(opt, "headers", "tieudecot");
                int start = 0;
                if (hdr instanceof List) { for (Object h : (List<?>) hdr) k.add(textOf(h)); }
                else if (!grid.isEmpty()) { for (Object h : grid.get(0)) k.add(textOf(h)); start = 1; }
                while (k.size() < width) k.add("Cột " + (k.size() + 1));
                for (int r = start; r < grid.size(); r++) {
                    Object[] row = new Object[k.size()];
                    List<Object> src = grid.get(r);
                    for (int c = 0; c < k.size() && c < src.size(); c++) row[c] = src.get(c);
                    rs.add(row);
                }
            } else if (first != null) {
                k.add("Giá trị");
                for (Object o : list) rs.add(new Object[]{o});
            } else {
                Object hdr = g(opt, "headers", "tieudecot");
                if (hdr instanceof List) { for (Object h : (List<?>) hdr) k.add(textOf(h)); }
            }

            // only: chọn / sắp xếp lại cột
            Object only = g(opt, "only", "chicot");
            if (only instanceof List && !k.isEmpty()) {
                List<Integer> pick = new ArrayList<>();
                for (Object ref : (List<?>) only) {
                    int c = indexIn(k, null, ref);
                    if (c >= 0) pick.add(c);
                }
                if (!pick.isEmpty()) {
                    List<String> nk = new ArrayList<>();
                    for (int c : pick) nk.add(k.get(c));
                    List<Object[]> nr = new ArrayList<>();
                    for (Object[] row : rs) {
                        Object[] r2 = new Object[pick.size()];
                        for (int i = 0; i < pick.size(); i++) r2[i] = row[pick.get(i)];
                        nr.add(r2);
                    }
                    k = nk; rs = nr;
                }
            }

            keys.addAll(k);
            rows.addAll(rs);
            Object lb = g(opt, "labels", "nhan");
            for (String key : keys) {
                String label = key;
                if (lb instanceof Map) { Object v = g(castMap(lb), key); if (v != null) label = str(v); }
                labels.add(label);
            }
        }

        int colIndex(Object ref) { return indexIn(keys, labels, ref); }

        // ---------------------------------------------------------------- kiểu cuối cùng của 1 ô
        /** r = -1: dòng tiêu đề; r >= 0: dòng dữ liệu thứ r (bắt đầu 0). */
        Style styleFor(int r, int c) {
            if (r < 0) {
                Style s = headerStyle.copy();
                for (CellDef d : cells) if (d.row == 0 && (d.col == -1 || d.col == c)) s.over(d.style);
                return s;
            }
            Style s = bodyStyle.copy();
            if (zebraStyle != null && r % 2 == 1) s.over(zebraStyle);
            s.over(colStyle[c]);
            Object[] row = rows.get(r);
            for (Rule rule : rules) {
                if (rule.col == -3) continue;
                boolean hit = false;
                if (rule.col >= 0) hit = test(row[rule.col], rule.op, rule.value);
                else { for (Object v : row) { if (test(v, rule.op, rule.value)) { hit = true; break; } } }
                if (!hit) continue;
                if (rule.rowScope || rule.col < 0 || rule.col == c) s.over(rule.style);
            }
            for (CellDef d : cells) {
                if (d.row == 0 || d.row == -2 || d.col == -3) continue;
                if ((d.row == -1 || d.row == r + 1) && (d.col == -1 || d.col == c)) s.over(d.style);
            }
            return s;
        }

        /** Độ rộng cột (đơn vị ký tự): người dùng đặt hoặc tự đo theo nội dung. */
        double widthChars(int c) {
            if (colWidth[c] > 0) return colWidth[c];
            int max = longestLine(labels.get(c));
            int limit = Math.min(rows.size(), 300);
            for (int r = 0; r < limit; r++) max = Math.max(max, longestLine(textOf(rows.get(r)[c])));
            return Math.max(8, Math.min(max, 50)) + 3;
        }

        boolean flag(String name, boolean def) {
            Object v = g(opt, name);
            return v == null ? def : bool(v);
        }
    }

    // ==========================================================================================
    //  TIỆN ÍCH CHUNG
    // ==========================================================================================

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : new TreeMap<>(String.CASE_INSENSITIVE_ORDER); }

    /** Lấy giá trị theo nhiều tên khoá khả dĩ (không phân biệt hoa thường, bỏ qua dấu _ và -). */
    static Object g(Map<String, Object> m, String... names) {
        if (m == null) return null;
        for (String n : names) { Object v = m.get(n); if (v != null) return v; }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = e.getKey().replace("_", "").replace("-", "");
            for (String n : names) if (k.equalsIgnoreCase(n)) return e.getValue();
        }
        return null;
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static boolean isBoolWord(String s) {
        String t = s.trim().toLowerCase();
        return t.equals("true") || t.equals("false") || t.equals("yes") || t.equals("no") || t.equals("1") || t.equals("0")
                || t.equals("co") || t.equals("khong") || t.equals("on") || t.equals("off");
    }

    static boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0;
        String t = str(o).trim().toLowerCase();
        return t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("co") || t.equals("on") || t.equals("y");
    }

    static Double toNum(Object o) {
        if (o instanceof Number) { double d = ((Number) o).doubleValue(); return Double.isNaN(d) ? null : d; }
        if (o instanceof String) {
            String t = ((String) o).trim();
            if (t.isEmpty()) return null;
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static String alignOf(String s) {
        String t = s.trim().toLowerCase();
        if (t.equals("center") || t.equals("giua") || t.equals("middle")) return "center";
        if (t.equals("right") || t.equals("phai")) return "right";
        if (t.equals("justify")) return "justify";
        return "left";
    }

    static String valignOf(String s) {
        String t = s.trim().toLowerCase();
        if (t.equals("top") || t.equals("tren")) return "top";
        if (t.equals("bottom") || t.equals("duoi")) return "bottom";
        return "middle";
    }

    static int indexIn(List<String> keys, List<String> labels, Object ref) {
        if (ref instanceof Number) {
            int n = ((Number) ref).intValue();
            return (n >= 1 && n <= keys.size()) ? n - 1 : -1;
        }
        String s = str(ref).trim();
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).equalsIgnoreCase(s)) return i;
        if (labels != null) for (int i = 0; i < labels.size(); i++) if (labels.get(i).equalsIgnoreCase(s)) return i;
        Double d = toNum(s);
        if (d != null) { int n = d.intValue(); if (n >= 1 && n <= keys.size()) return n - 1; }
        return -1;
    }

    static String plainNum(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    /** Chữ hiển thị của một giá trị (chưa áp định dạng số). */
    static String textOf(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof Double || v instanceof Float) s = plainNum(((Number) v).doubleValue());
        else s = String.valueOf(v);
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** Chữ hiển thị cho DOCX/PDF: áp định dạng số nếu có. */
    static String shown(Object v, Style s, Ctx c) {
        if (v instanceof Number && s != null && s.format != null && !s.format.isEmpty()) {
            try {
                DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
                // Mặc định số kiểu Việt Nam 1.234.567,50; đặt numberLocale:'us' để ra 1,234,567.50
                String nl = c == null ? "vi" : str(g(c.opt, "numberlocale", "locale", "soquoctich"));
                if (nl.isEmpty() || nl.equalsIgnoreCase("vi")) { sym.setGroupingSeparator('.'); sym.setDecimalSeparator(','); }
                return new DecimalFormat(s.format, sym).format(((Number) v).doubleValue());
            } catch (RuntimeException ignore) { /* định dạng của Excel không hợp lệ với số Java: dùng chữ thường */ }
        }
        return textOf(v);
    }

    static int longestLine(String s) {
        int max = 0;
        for (String line : s.split("\n", -1)) max = Math.max(max, line.length());
        return max;
    }

    static boolean test(Object v, String op, Object ref) {
        String o = op.trim().toLowerCase();
        String sv = textOf(v);
        if (o.equals("empty") || o.equals("trong")) return sv.isEmpty();
        if (o.equals("notempty") || o.equals("khongtrong")) return !sv.isEmpty();
        Double a = toNum(v), b = toNum(ref);
        if (a != null && b != null) {
            switch (o) {
                case "<": return a < b;
                case "<=": return a <= b;
                case ">": return a > b;
                case ">=": return a >= b;
                case "==": case "=": return a.doubleValue() == b.doubleValue();
                case "!=": case "<>": return a.doubleValue() != b.doubleValue();
                default: break;
            }
        }
        String x = sv.toLowerCase(), y = textOf(ref).toLowerCase();
        switch (o) {
            case "==": case "=": return x.equals(y);
            case "!=": case "<>": return !x.equals(y);
            case "contains": case "chua": return x.contains(y);
            case "startswith": return x.startsWith(y);
            case "endswith": return x.endsWith(y);
            case "<": return x.compareTo(y) < 0;
            case "<=": return x.compareTo(y) <= 0;
            case ">": return x.compareTo(y) > 0;
            case ">=": return x.compareTo(y) >= 0;
            default: return false;
        }
    }

    // ---------------------------------------------------------------- màu
    private static final Map<String, Integer> COLOR_NAMES = new HashMap<>();
    static {
        Object[][] t = {
                {"black", 0x000000}, {"den", 0x000000}, {"white", 0xFFFFFF}, {"trang", 0xFFFFFF},
                {"red", 0xE53935}, {"do", 0xE53935}, {"green", 0x2E7D32}, {"xanhla", 0x2E7D32}, {"lime", 0x84CC16},
                {"blue", 0x1D4ED8}, {"xanhduong", 0x1D4ED8}, {"xanh", 0x1D4ED8}, {"navy", 0x1E3A8A}, {"cyan", 0x06B6D4},
                {"yellow", 0xFACC15}, {"vang", 0xFACC15}, {"orange", 0xF97316}, {"cam", 0xF97316},
                {"purple", 0x7C3AED}, {"tim", 0x7C3AED}, {"pink", 0xEC4899}, {"hong", 0xEC4899},
                {"gray", 0x6B7280}, {"grey", 0x6B7280}, {"xam", 0x6B7280}, {"lightgray", 0xE5E7EB}, {"lightgrey", 0xE5E7EB},
                {"brown", 0x92400E}, {"nau", 0x92400E}, {"gold", 0xD4AF37}, {"teal", 0x0D9488}
        };
        for (Object[] e : t) COLOR_NAMES.put((String) e[0], (Integer) e[1]);
    }

    /** "#RRGGBB" | "#RGB" | "RRGGBB" | "rgb(r,g,b)" | tên màu. Trả null nếu không hiểu. */
    static Integer parseColor(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue() & 0xFFFFFF;
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.isEmpty() || s.equals("none") || s.equals("transparent") || s.equals("khong")) return null;
        if (s.startsWith("rgb")) {
            int a = s.indexOf('('), b = s.indexOf(')');
            if (a > 0 && b > a) {
                String[] p = s.substring(a + 1, b).split(",");
                if (p.length >= 3) {
                    try {
                        int r = Math.max(0, Math.min(255, Integer.parseInt(p[0].trim())));
                        int g = Math.max(0, Math.min(255, Integer.parseInt(p[1].trim())));
                        int bl = Math.max(0, Math.min(255, Integer.parseInt(p[2].trim())));
                        return (r << 16) | (g << 8) | bl;
                    } catch (NumberFormatException ignore) { return null; }
                }
            }
            return null;
        }
        String h = s.startsWith("#") ? s.substring(1) : s;
        if (h.matches("[0-9a-f]{3}")) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        if (h.matches("[0-9a-f]{6}") && (s.startsWith("#") || !COLOR_NAMES.containsKey(s.replace(" ", "")))) return Integer.parseInt(h, 16);
        return COLOR_NAMES.get(s.replace(" ", "").replace("_", ""));
    }

    static String hex6(int rgb) { return String.format("%06X", rgb & 0xFFFFFF); }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c == 9 || c == 10 || c == 13 || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF)) sb.append(c);
            }
        }
        return sb.toString();
    }

    static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ==========================================================================================
    //  ĐỌC TUỲ CHỌN (Map hoặc chuỗi JSON)
    // ==========================================================================================

    static Map<String, Object> parseOptions(Object options) {
        if (options == null) return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (options instanceof String) {
            String s = ((String) options).trim();
            if (s.isEmpty()) return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Object v = new Json(s).parse();
            if (!(v instanceof Map)) throw new IllegalArgumentException("Tuỳ chọn xuất file phải là một đối tượng JSON dạng { ... }");
            return castMap(norm(v));
        }
        if (options instanceof Map) return castMap(norm(options));
        throw new IllegalArgumentException("Tuỳ chọn xuất file phải là Map hoặc chuỗi JSON.");
    }

    @SuppressWarnings("unchecked")
    private static Object norm(Object o) {
        if (o instanceof Map) {
            TreeMap<String, Object> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) m.put(String.valueOf(e.getKey()), norm(e.getValue()));
            return m;
        }
        if (o instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object x : (List<Object>) o) l.add(norm(x));
            return l;
        }
        return o;
    }

    /** Bộ đọc JSON tối giản (đủ cho tuỳ chọn): đối tượng, mảng, chuỗi, số, true/false/null. */
    private static final class Json {
        final String s; int i = 0;
        Json(String s) { this.s = s; }

        Object parse() {
            ws();
            Object v = val();
            ws();
            if (i != s.length()) throw err("Còn ký tự thừa sau JSON");
            return v;
        }
        RuntimeException err(String m) { return new IllegalArgumentException("JSON tuỳ chọn không hợp lệ tại vị trí " + i + ": " + m); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object val() {
            ws();
            if (i >= s.length()) throw err("Hết dữ liệu");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"' || c == '\'') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("Không hiểu ký tự '" + c + "'");
            return Double.valueOf(s.substring(st, i));
        }

        Object obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; }       // cho phép dấu phẩy thừa cuối cùng
                String k = key();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("Thiếu dấu ':'");
                i++;
                m.put(k, val());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
                throw err("Thiếu ',' hoặc '}'");
            }
        }

        /** Khoá: chuỗi trong nháy, hoặc viết trần (bold, bg, font_size...) cho gõ nhanh. */
        String key() {
            if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) return string();
            int st = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '-' || s.charAt(i) == '$')) i++;
            if (st == i) throw err("Cần tên khoá");
            return s.substring(st, i);
        }

        Object arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
                l.add(val());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
                throw err("Thiếu ',' hoặc ']'");
            }
        }

        String string() {
            if (i >= s.length() || (s.charAt(i) != '"' && s.charAt(i) != '\'')) throw err("Cần một chuỗi trong nháy");
            char q = s.charAt(i++);
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != q) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'u':
                            if (i + 4 <= s.length()) { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                            break;
                        default: sb.append(n);
                    }
                } else sb.append(c);
            }
            if (i >= s.length()) throw err("Chuỗi không đóng nháy");
            i++;
            return sb.toString();
        }
    }

    // ==========================================================================================
    //  XLSX
    // ==========================================================================================

    static final class XlsxWriter {
        final Ctx c;
        final List<String> fonts = new ArrayList<>(), fills = new ArrayList<>(), borders = new ArrayList<>(), numFmts = new ArrayList<>(), xfs = new ArrayList<>();
        final Map<String, Integer> fontIdx = new HashMap<>(), fillIdx = new HashMap<>(), borderIdx = new HashMap<>(), fmtIdx = new HashMap<>(), xfIdx = new HashMap<>();

        XlsxWriter(Ctx c) { this.c = c; }

        static String col(int i) {
            StringBuilder sb = new StringBuilder();
            for (i++; i > 0; i = (i - 1) / 26) sb.insert(0, (char) ('A' + (i - 1) % 26));
            return sb.toString();
        }

        int intern(List<String> list, Map<String, Integer> idx, String xml) {
            Integer k = idx.get(xml);
            if (k != null) return k;
            list.add(xml);
            idx.put(xml, list.size() - 1);
            return list.size() - 1;
        }

        /** Đăng ký một kiểu, trả về chỉ số xf (s="..." của ô). */
        int xf(Style s, boolean numeric) {
            StringBuilder f = new StringBuilder("<font>");
            if (s.b()) f.append("<b/>");
            if (s.i()) f.append("<i/>");
            if (s.st()) f.append("<strike/>");
            if (s.u()) f.append("<u/>");
            f.append("<sz val=\"").append(plainNum(s.sizeOr(11))).append("\"/>");
            f.append("<color rgb=\"FF").append(hex6(s.colorOr(0x000000))).append("\"/>");
            f.append("<name val=\"").append(esc(s.font == null ? "Calibri" : s.font)).append("\"/></font>");
            int fontId = intern(fonts, fontIdx, f.toString());

            int fillId = 0;
            if (s.bg != null) {
                fillId = intern(fills, fillIdx, "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + hex6(s.bg)
                        + "\"/><bgColor indexed=\"64\"/></patternFill></fill>");
            }
            int borderId = 0;
            if (s.hasBorder() && Boolean.TRUE.equals(s.border)) {
                String bc = "<color rgb=\"FF" + hex6(s.borderColorOr(0xD1D5DB)) + "\"/>";
                borderId = intern(borders, borderIdx, "<border><left style=\"thin\">" + bc + "</left><right style=\"thin\">" + bc
                        + "</right><top style=\"thin\">" + bc + "</top><bottom style=\"thin\">" + bc + "</bottom><diagonal/></border>");
            }
            int fmtId = 0;
            if (s.format != null && !s.format.isEmpty() && numeric) {
                String key = s.format;
                Integer k = fmtIdx.get(key);
                if (k == null) { k = 164 + fmtIdx.size(); fmtIdx.put(key, k); numFmts.add("<numFmt numFmtId=\"" + k + "\" formatCode=\"" + esc(s.format) + "\"/>"); }
                fmtId = k;
            }
            String al = s.align != null ? s.align : (numeric ? "right" : "left");
            String va = s.valign != null ? s.valign : "center";
            if (va.equals("middle")) va = "center";
            StringBuilder x = new StringBuilder("<xf numFmtId=\"").append(fmtId).append("\" fontId=\"").append(fontId)
                    .append("\" fillId=\"").append(fillId).append("\" borderId=\"").append(borderId).append("\" xfId=\"0\"")
                    .append(" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">")
                    .append("<alignment horizontal=\"").append(al).append("\" vertical=\"").append(va).append("\"");
            if (Boolean.TRUE.equals(s.wrap)) x.append(" wrapText=\"1\"");
            x.append("/></xf>");
            return intern(xfs, xfIdx, x.toString());
        }

        static String safeSheetName(String name) {
            String n = name == null ? "" : name.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
            if (n.isEmpty()) n = "Sheet1";
            return n.length() > 31 ? n.substring(0, 31) : n;
        }

        String cell(String ref, Object raw, Style s) {
            boolean numeric = raw instanceof Number && !Double.isNaN(((Number) raw).doubleValue()) && !Double.isInfinite(((Number) raw).doubleValue());
            int sid = xf(s, numeric);
            if (numeric) return "<c r=\"" + ref + "\" s=\"" + sid + "\"><v>" + plainNum(((Number) raw).doubleValue()) + "</v></c>";
            String t = textOf(raw);
            if (t.isEmpty()) return "<c r=\"" + ref + "\" s=\"" + sid + "\"/>";
            return "<c r=\"" + ref + "\" s=\"" + sid + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + esc(t) + "</t></is></c>";
        }

        byte[] build() throws IOException {
            int n = Math.max(1, c.keys.size());
            xf(new Style().over(defaultPlain()), false);          // xf 0 = kiểu mặc định của sổ làm việc
            fills.add("<fill><patternFill patternType=\"none\"/></fill>");
            fills.add("<fill><patternFill patternType=\"gray125\"/></fill>");
            fillIdx.put(fills.get(0), 0); fillIdx.put(fills.get(1), 1);
            borders.add(0, "<border><left/><right/><top/><bottom/><diagonal/></border>");
            // Sắp xếp lại: các chỉ số đã cấp trước đó phải khớp -> dựng lại từ đầu cho chắc
            fonts.clear(); fontIdx.clear(); xfs.clear(); xfIdx.clear(); fmtIdx.clear(); numFmts.clear();
            fills.clear(); fillIdx.clear(); borders.clear(); borderIdx.clear();
            fills.add("<fill><patternFill patternType=\"none\"/></fill>");
            fills.add("<fill><patternFill patternType=\"gray125\"/></fill>");
            fillIdx.put(fills.get(0), 0); fillIdx.put(fills.get(1), 1);
            borders.add("<border><left/><right/><top/><bottom/><diagonal/></border>");
            borderIdx.put(borders.get(0), 0);
            xf(defaultPlain(), false);

            StringBuilder sd = new StringBuilder(16384);
            List<String> merges = new ArrayList<>();
            int rowNum = 1;

            if (c.title != null && !c.title.isEmpty()) {
                Style ts = c.titleStyle;
                sd.append("<row r=\"").append(rowNum).append("\" ht=\"").append(plainNum(ts.sizeOr(16) * 1.7 + 6)).append("\" customHeight=\"1\">");
                for (int i = 0; i < n; i++) sd.append(cell(col(i) + rowNum, i == 0 ? c.title : null, ts));
                sd.append("</row>");
                if (n > 1) merges.add("A" + rowNum + ":" + col(n - 1) + rowNum);
                rowNum++;
            }
            if (c.subtitle != null && !c.subtitle.isEmpty()) {
                Style ss = c.subtitleStyle;
                sd.append("<row r=\"").append(rowNum).append("\" ht=\"").append(plainNum(ss.sizeOr(10) * 1.7 + 6)).append("\" customHeight=\"1\">");
                for (int i = 0; i < n; i++) sd.append(cell(col(i) + rowNum, i == 0 ? c.subtitle : null, ss));
                sd.append("</row>");
                if (n > 1) merges.add("A" + rowNum + ":" + col(n - 1) + rowNum);
                rowNum++;
            }

            int headerRow = rowNum;
            if (!c.keys.isEmpty()) {
                double hs = c.headerStyle.sizeOr(11);
                sd.append("<row r=\"").append(rowNum).append("\" ht=\"").append(plainNum(Math.max(22, hs * 1.7 + 6))).append("\" customHeight=\"1\">");
                for (int i = 0; i < c.keys.size(); i++) sd.append(cell(col(i) + rowNum, c.labels.get(i), c.styleFor(-1, i)));
                sd.append("</row>");
                rowNum++;
            }
            for (int r = 0; r < c.rows.size(); r++) {
                sd.append("<row r=\"").append(rowNum).append("\">");
                Object[] row = c.rows.get(r);
                for (int i = 0; i < c.keys.size(); i++) sd.append(cell(col(i) + rowNum, row[i], c.styleFor(r, i)));
                sd.append("</row>");
                rowNum++;
            }
            int lastRow = rowNum - 1;

            StringBuilder ws = new StringBuilder(sd.length() + 2048);
            ws.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                    .append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
                    .append("<dimension ref=\"A1:").append(col(n - 1)).append(Math.max(1, lastRow)).append("\"/>")
                    .append("<sheetViews><sheetView workbookViewId=\"0\"").append(c.flag("gridlines", true) ? "" : " showGridLines=\"0\"").append(">");
            if (c.flag("freeze", true) && !c.keys.isEmpty()) {
                ws.append("<pane ySplit=\"").append(headerRow).append("\" topLeftCell=\"A").append(headerRow + 1)
                        .append("\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A")
                        .append(headerRow + 1).append("\" sqref=\"A").append(headerRow + 1).append("\"/>");
            }
            ws.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>");
            for (int i = 0; i < c.keys.size(); i++) {
                ws.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                        .append(plainNum(c.widthChars(i))).append("\" customWidth=\"1\"/>");
            }
            ws.append("</cols><sheetData>").append(sd).append("</sheetData>");
            if (c.flag("filter", false) && !c.keys.isEmpty() && lastRow >= headerRow) {
                ws.append("<autoFilter ref=\"A").append(headerRow).append(":").append(col(n - 1)).append(lastRow).append("\"/>");
            }
            if (!merges.isEmpty()) {
                ws.append("<mergeCells count=\"").append(merges.size()).append("\">");
                for (String m : merges) ws.append("<mergeCell ref=\"").append(m).append("\"/>");
                ws.append("</mergeCells>");
            }
            boolean land = !"portrait".equalsIgnoreCase(str(g(c.opt, "orientation", "huong")));
            ws.append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>")
                    .append("<pageSetup paperSize=\"9\" orientation=\"").append(land ? "landscape" : "portrait")
                    .append("\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
                put(zip, "[Content_Types].xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                                + "</Types>");
                put(zip, "_rels/.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                                + "</Relationships>");
                String sheetName = safeSheetName(str(g(c.opt, "sheet", "sheetname", "tensheet")));
                put(zip, "xl/workbook.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                                + "<sheets><sheet name=\"" + esc(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
                put(zip, "xl/_rels/workbook.xml.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                                + "</Relationships>");

                StringBuilder st = new StringBuilder(4096);
                st.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                        .append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
                if (!numFmts.isEmpty()) {
                    st.append("<numFmts count=\"").append(numFmts.size()).append("\">");
                    for (String x : numFmts) st.append(x);
                    st.append("</numFmts>");
                }
                st.append("<fonts count=\"").append(fonts.size()).append("\">"); for (String x : fonts) st.append(x); st.append("</fonts>");
                st.append("<fills count=\"").append(fills.size()).append("\">"); for (String x : fills) st.append(x); st.append("</fills>");
                st.append("<borders count=\"").append(borders.size()).append("\">"); for (String x : borders) st.append(x); st.append("</borders>");
                st.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
                st.append("<cellXfs count=\"").append(xfs.size()).append("\">"); for (String x : xfs) st.append(x); st.append("</cellXfs>");
                st.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
                put(zip, "xl/styles.xml", st.toString());
                put(zip, "xl/worksheets/sheet1.xml", ws.toString());
            }
            return bos.toByteArray();
        }

        private Style defaultPlain() {
            Style s = new Style();
            s.font = c.bodyStyle.font; s.size = c.bodyStyle.size; s.border = false;
            return s;
        }
    }

    // ==========================================================================================
    //  DOCX
    // ==========================================================================================

    static final class DocxWriter {
        final Ctx c;
        DocxWriter(Ctx c) { this.c = c; }

        String runs(String text, Style s, double defSize) {
            String font = s.font == null ? "Calibri" : s.font;
            StringBuilder rpr = new StringBuilder("<w:rPr><w:rFonts w:ascii=\"").append(esc(font)).append("\" w:hAnsi=\"").append(esc(font))
                    .append("\" w:eastAsia=\"").append(esc(font)).append("\" w:cs=\"").append(esc(font)).append("\"/>");
            if (s.b()) rpr.append("<w:b/><w:bCs/>");
            if (s.i()) rpr.append("<w:i/><w:iCs/>");
            if (s.st()) rpr.append("<w:strike/>");
            if (s.color != null) rpr.append("<w:color w:val=\"").append(hex6(s.color)).append("\"/>");
            int half = (int) Math.round(s.sizeOr(defSize) * 2);
            rpr.append("<w:sz w:val=\"").append(half).append("\"/><w:szCs w:val=\"").append(half).append("\"/>");
            if (s.u()) rpr.append("<w:u w:val=\"single\"/>");
            rpr.append("</w:rPr>");

            StringBuilder sb = new StringBuilder("<w:r>").append(rpr);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append("<w:br/>");
                sb.append("<w:t xml:space=\"preserve\">").append(esc(lines[i].replace("\r", ""))).append("</w:t>");
            }
            return sb.append("</w:r>").toString();
        }

        static String jc(String align) {
            if (align == null) return "left";
            switch (align) {
                case "center": return "center";
                case "right": return "right";
                case "justify": return "both";
                default: return "left";
            }
        }

        String paragraph(String text, Style s, double defSize, String align, int before, int after) {
            return "<w:p><w:pPr><w:spacing w:before=\"" + before + "\" w:after=\"" + after + "\"/><w:jc w:val=\"" + jc(align) + "\"/></w:pPr>"
                    + (text.isEmpty() ? "" : runs(text, s, defSize)) + "</w:p>";
        }

        byte[] build() throws IOException {
            boolean land = !"portrait".equalsIgnoreCase(str(g(c.opt, "orientation", "huong")));
            int pageW = land ? 16838 : 11906, pageH = land ? 11906 : 16838, margin = 850;
            int content = pageW - 2 * margin;
            int n = c.keys.size();

            StringBuilder b = new StringBuilder(16384);
            b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");

            if (c.title != null && !c.title.isEmpty()) b.append(paragraph(c.title, c.titleStyle, 16, c.titleStyle.align, 0, 120));
            if (c.subtitle != null && !c.subtitle.isEmpty()) b.append(paragraph(c.subtitle, c.subtitleStyle, 10, c.subtitleStyle.align, 0, 160));

            if (n > 0) {
                double[] wc = new double[n];
                double sum = 0;
                for (int i = 0; i < n; i++) { wc[i] = c.widthChars(i); sum += wc[i]; }
                int[] tw = new int[n];
                int used = 0;
                for (int i = 0; i < n; i++) { tw[i] = (int) Math.floor(wc[i] / sum * content); used += tw[i]; }
                tw[n - 1] += content - used;

                b.append("<w:tbl><w:tblPr><w:tblW w:w=\"").append(content).append("\" w:type=\"dxa\"/><w:jc w:val=\"center\"/>")
                        .append("<w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:top w:w=\"50\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/>")
                        .append("<w:bottom w:w=\"50\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>");
                for (int i = 0; i < n; i++) b.append("<w:gridCol w:w=\"").append(tw[i]).append("\"/>");
                b.append("</w:tblGrid>");

                b.append("<w:tr><w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>");
                for (int i = 0; i < n; i++) b.append(tc(c.labels.get(i), null, c.styleFor(-1, i), tw[i]));
                b.append("</w:tr>");
                for (int r = 0; r < c.rows.size(); r++) {
                    b.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>");
                    Object[] row = c.rows.get(r);
                    for (int i = 0; i < n; i++) b.append(tc(null, row[i], c.styleFor(r, i), tw[i]));
                    b.append("</w:tr>");
                }
                b.append("</w:tbl>");
            }
            b.append("<w:p/>");
            b.append("<w:sectPr><w:pgSz w:w=\"").append(pageW).append("\" w:h=\"").append(pageH).append("\"")
                    .append(land ? " w:orient=\"landscape\"" : "").append("/><w:pgMar w:top=\"").append(margin).append("\" w:right=\"").append(margin)
                    .append("\" w:bottom=\"").append(margin).append("\" w:left=\"").append(margin).append("\" w:header=\"500\" w:footer=\"500\" w:gutter=\"0\"/></w:sectPr>")
                    .append("</w:body></w:document>");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
                put(zip, "[Content_Types].xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                                + "</Types>");
                put(zip, "_rels/.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                                + "</Relationships>");
                put(zip, "word/document.xml", b.toString());
            }
            return bos.toByteArray();
        }

        String tc(String header, Object raw, Style s, int width) {
            String text = header != null ? header : shown(raw, s, c);
            String align = s.align != null ? s.align : (raw instanceof Number ? "right" : "left");
            StringBuilder b = new StringBuilder("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>");
            if (Boolean.TRUE.equals(s.border)) {
                String col = hex6(s.borderColorOr(0xD1D5DB));
                b.append("<w:tcBorders>");
                for (String side : new String[]{"top", "left", "bottom", "right"})
                    b.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"").append(col).append("\"/>");
                b.append("</w:tcBorders>");
            }
            if (s.bg != null) b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(hex6(s.bg)).append("\"/>");
            String va = s.valign == null ? "center" : (s.valign.equals("middle") ? "center" : s.valign);
            b.append("<w:vAlign w:val=\"").append(va).append("\"/></w:tcPr>");
            b.append(paragraph(text, s, 11, align, 0, 0));
            return b.append("</w:tc>").toString();
        }
    }

    // ==========================================================================================
    //  PDF (tự ghi, nhúng font TrueType)
    // ==========================================================================================

    /** Bộ đọc font TrueType tối giản: bảng ký tự (cmap), độ rộng chữ (hmtx), số liệu chung (head/hhea). */
    static final class Ttf {
        final byte[] data;
        int unitsPerEm = 1000, numGlyphs, numHMetrics, ascent, descent, xMin, yMin, xMax, yMax;
        int hmtxOff, cmapSubOff = -1, cmapFormat;

        Ttf(byte[] d) { data = d; }

        int u16(int o) { return ((data[o] & 0xFF) << 8) | (data[o + 1] & 0xFF); }
        int s16(int o) { return (short) u16(o); }
        long u32(int o) { return ((long) u16(o) << 16) | u16(o + 2); }

        static Ttf load(File f) {
            try {
                byte[] d = Files.readAllBytes(f.toPath());
                if (d.length < 12) return null;
                Ttf t = new Ttf(d);
                long tag = t.u32(0);
                if (tag != 0x00010000L && tag != 0x74727565L) return null;       // chỉ nhận .ttf (không nhận .otf CFF, .ttc)
                int num = t.u16(4);
                Map<String, int[]> tab = new HashMap<>();
                for (int i = 0; i < num; i++) {
                    int o = 12 + i * 16;
                    String name = new String(d, o, 4, StandardCharsets.ISO_8859_1);
                    tab.put(name, new int[]{(int) t.u32(o + 8), (int) t.u32(o + 12)});
                }
                int[] head = tab.get("head"), hhea = tab.get("hhea"), maxp = tab.get("maxp"), hmtx = tab.get("hmtx"), cmap = tab.get("cmap");
                if (head == null || hhea == null || maxp == null || hmtx == null || cmap == null || !tab.containsKey("glyf")) return null;
                t.unitsPerEm = t.u16(head[0] + 18);
                t.xMin = t.s16(head[0] + 36); t.yMin = t.s16(head[0] + 38); t.xMax = t.s16(head[0] + 40); t.yMax = t.s16(head[0] + 42);
                t.ascent = t.s16(hhea[0] + 4); t.descent = t.s16(hhea[0] + 6); t.numHMetrics = t.u16(hhea[0] + 34);
                t.numGlyphs = t.u16(maxp[0] + 4);
                t.hmtxOff = hmtx[0];

                int cm = cmap[0], nTab = t.u16(cm + 2), best = -1, bestRank = -1;
                for (int i = 0; i < nTab; i++) {
                    int pid = t.u16(cm + 4 + i * 8), eid = t.u16(cm + 6 + i * 8);
                    int off = cm + (int) t.u32(cm + 8 + i * 8);
                    int fmt = t.u16(off);
                    int rank = -1;
                    if (pid == 3 && eid == 10 && fmt == 12) rank = 4;
                    else if (pid == 3 && eid == 1 && fmt == 4) rank = 3;
                    else if (pid == 0 && fmt == 12) rank = 2;
                    else if (pid == 0 && fmt == 4) rank = 1;
                    if (rank > bestRank) { bestRank = rank; best = off; }
                }
                if (best < 0) return null;
                t.cmapSubOff = best;
                t.cmapFormat = t.u16(best);
                return t;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        int glyph(int cp) {
            if (cmapFormat == 4) {
                if (cp > 0xFFFF) return 0;
                int o = cmapSubOff, segX2 = u16(o + 6), seg = segX2 / 2;
                int endO = o + 14, startO = endO + segX2 + 2, deltaO = startO + segX2, rangeO = deltaO + segX2;
                int lo = 0, hi = seg - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int end = u16(endO + mid * 2);
                    if (cp > end) lo = mid + 1;
                    else {
                        int start = u16(startO + mid * 2);
                        if (cp < start) hi = mid - 1;
                        else {
                            int delta = u16(deltaO + mid * 2), ro = u16(rangeO + mid * 2);
                            if (ro == 0) return (cp + delta) & 0xFFFF;
                            int gi = rangeO + mid * 2 + ro + (cp - start) * 2;
                            if (gi + 1 >= data.length) return 0;
                            int g = u16(gi);
                            return g == 0 ? 0 : (g + delta) & 0xFFFF;
                        }
                    }
                }
                return 0;
            }
            long n = u32(cmapSubOff + 12);
            long lo = 0, hi = n - 1;
            while (lo <= hi) {
                long mid = (lo + hi) >>> 1;
                int o = cmapSubOff + 16 + (int) mid * 12;
                long s = u32(o), e = u32(o + 4);
                if (cp > e) lo = mid + 1;
                else if (cp < s) hi = mid - 1;
                else return (int) (u32(o + 8) + (cp - s));
            }
            return 0;
        }

        int advance(int gid) {
            int i = Math.min(gid, numHMetrics - 1);
            return u16(hmtxOff + i * 4);
        }

        double width(int cp, double size) {
            int g = glyph(cp);
            return advance(g) * size / unitsPerEm;
        }
    }

    // ---------------------------------------------------------------- tìm font trong máy
    private static final String[][] FAMILIES = {
            {"arial", "arial.ttf", "arialbd.ttf", "ariali.ttf", "arialbi.ttf"},
            {"timesnewroman", "times.ttf", "timesbd.ttf", "timesi.ttf", "timesbi.ttf"},
            {"couriernew", "cour.ttf", "courbd.ttf", "couri.ttf", "courbi.ttf"},
            {"tahoma", "tahoma.ttf", "tahomabd.ttf", null, null},
            {"segoeui", "segoeui.ttf", "segoeuib.ttf", "segoeuii.ttf", "segoeuiz.ttf"},
            {"calibri", "calibri.ttf", "calibrib.ttf", "calibrii.ttf", "calibriz.ttf"},
            {"verdana", "verdana.ttf", "verdanab.ttf", "verdanai.ttf", "verdanaz.ttf"},
            {"georgia", "georgia.ttf", "georgiab.ttf", "georgiai.ttf", "georgiaz.ttf"},
            {"consolas", "consola.ttf", "consolab.ttf", "consolai.ttf", "consolaz.ttf"},
            {"liberationsans", "LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf", "LiberationSans-BoldItalic.ttf"},
            {"liberationserif", "LiberationSerif-Regular.ttf", "LiberationSerif-Bold.ttf", "LiberationSerif-Italic.ttf", "LiberationSerif-BoldItalic.ttf"},
            {"liberationmono", "LiberationMono-Regular.ttf", "LiberationMono-Bold.ttf", "LiberationMono-Italic.ttf", "LiberationMono-BoldItalic.ttf"},
            {"dejavusans", "DejaVuSans.ttf", "DejaVuSans-Bold.ttf", "DejaVuSans-Oblique.ttf", "DejaVuSans-BoldOblique.ttf"},
    };
    private static final Map<String, String> FAMILY_ALIAS = new HashMap<>();
    static {
        FAMILY_ALIAS.put("helvetica", "arial"); FAMILY_ALIAS.put("sans", "arial"); FAMILY_ALIAS.put("sansserif", "arial");
        FAMILY_ALIAS.put("times", "timesnewroman"); FAMILY_ALIAS.put("serif", "timesnewroman");
        FAMILY_ALIAS.put("courier", "couriernew"); FAMILY_ALIAS.put("monospace", "couriernew"); FAMILY_ALIAS.put("mono", "couriernew");
        FAMILY_ALIAS.put("segoe", "segoeui"); FAMILY_ALIAS.put("arialunicodems", "arial");
    }

    private static Map<String, File> fontIndex;
    private static final Map<String, Ttf> FONT_CACHE = new HashMap<>();

    private static synchronized Map<String, File> fontIndex() {
        if (fontIndex != null) return fontIndex;
        Map<String, File> idx = new HashMap<>();
        List<String> dirs = new ArrayList<>();
        String win = System.getenv("windir");
        if (win == null) win = System.getenv("SystemRoot");
        if (win != null) dirs.add(win + File.separator + "Fonts");
        dirs.add("C:\\Windows\\Fonts");
        dirs.add("/usr/share/fonts"); dirs.add("/usr/local/share/fonts");
        dirs.add("/Library/Fonts"); dirs.add("/System/Library/Fonts"); dirs.add("/System/Library/Fonts/Supplemental");
        dirs.add(System.getProperty("user.home", ".") + File.separator + ".fonts");
        for (String d : dirs) scan(new File(d), 0, idx);
        fontIndex = idx;
        return idx;
    }

    private static void scan(File dir, int depth, Map<String, File> idx) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 3) return;
        for (File f : fs) {
            if (f.isDirectory()) scan(f, depth + 1, idx);
            else if (f.getName().toLowerCase().endsWith(".ttf")) idx.putIfAbsent(f.getName().toLowerCase(), f);
        }
    }

    private static synchronized Ttf loadTtf(File f) {
        String key = f.getAbsolutePath();
        if (FONT_CACHE.containsKey(key)) return FONT_CACHE.get(key);
        Ttf t = Ttf.load(f);
        FONT_CACHE.put(key, t);
        return t;
    }

    /** Font đã chọn + có cần giả lập in đậm / in nghiêng hay không (khi máy không có bản đậm/nghiêng thật). */
    static final class Picked {
        final Ttf ttf; final String key; final boolean fakeBold, fakeItalic;
        Picked(Ttf t, String k, boolean fb, boolean fi) { ttf = t; key = k; fakeBold = fb; fakeItalic = fi; }
    }

    private static final Map<String, Picked> PICK_CACHE = new HashMap<>();

    static synchronized Picked pick(String family, boolean bold, boolean italic, String fontFile) {
        String ck = family + "|" + bold + "|" + italic + "|" + fontFile;
        Picked cached = PICK_CACHE.get(ck);
        if (cached != null) return cached;
        Picked p = pickUncached(family, bold, italic, fontFile);
        PICK_CACHE.put(ck, p);
        return p;
    }

    private static Picked pickUncached(String family, boolean bold, boolean italic, String fontFile) {
        if (fontFile != null && !fontFile.isEmpty()) {
            File f = new File(fontFile);
            Ttf t = f.isFile() ? loadTtf(f) : null;
            if (t != null) return new Picked(t, f.getAbsolutePath(), bold, italic);
        }
        String fam = family == null ? "arial" : family.toLowerCase().replace(" ", "").replace("-", "");
        if (FAMILY_ALIAS.containsKey(fam)) fam = FAMILY_ALIAS.get(fam);
        List<String> chain = new ArrayList<>();
        chain.add(fam); chain.add("arial"); chain.add("liberationsans"); chain.add("dejavusans");
        Map<String, File> idx = fontIndex();
        for (String name : chain) {
            String[] row = null;
            for (String[] r : FAMILIES) if (r[0].equals(name)) { row = r; break; }
            if (row == null) continue;
            // thứ tự thử: đúng kiểu -> kiểu gần đúng (kèm giả lập phần thiếu) -> thường
            int[][] tries;
            if (bold && italic) tries = new int[][]{{4, 0, 0}, {2, 0, 1}, {3, 1, 0}, {1, 1, 1}};
            else if (bold) tries = new int[][]{{2, 0, 0}, {1, 1, 0}};
            else if (italic) tries = new int[][]{{3, 0, 0}, {1, 0, 1}};
            else tries = new int[][]{{1, 0, 0}};
            for (int[] t : tries) {
                String fn = row[t[0]];
                if (fn == null) continue;
                File f = idx.get(fn.toLowerCase());
                if (f == null) continue;
                Ttf ttf = loadTtf(f);
                if (ttf != null) return new Picked(ttf, f.getAbsolutePath(), t[1] == 1, t[2] == 1);
            }
        }
        throw new IllegalStateException("Không tìm thấy font TrueType (.ttf) nào trên máy để ghi PDF tiếng Việt. "
                + "Hãy cài font Arial/DejaVu hoặc truyền đường dẫn font bằng tuỳ chọn \"fontFile\".");
    }

    // ---------------------------------------------------------------- bộ ghi PDF
    static final class PdfWriter {
        final Ctx c;
        PdfWriter(Ctx c) { this.c = c; }

        /** Một font được nhúng vào tệp: nhớ những glyph đã dùng để lập bảng độ rộng và bảng Unicode. */
        static final class FontRes {
            final Ttf ttf; final String name; final TreeMap<Integer, Integer> gidToCp = new TreeMap<>();
            FontRes(Ttf t, String n) { ttf = t; name = n; }
        }

        final Map<String, FontRes> fonts = new LinkedHashMap<>();
        final List<StringBuilder> pages = new ArrayList<>();
        StringBuilder cur;
        double pageW, pageH, margin, y, contentW, bottom;

        FontRes res(Picked p) {
            FontRes f = fonts.get(p.key);
            if (f == null) { f = new FontRes(p.ttf, "F" + (fonts.size() + 1)); fonts.put(p.key, f); }
            return f;
        }

        static String rg(int rgb) {
            return fmt(((rgb >> 16) & 0xFF) / 255.0) + " " + fmt(((rgb >> 8) & 0xFF) / 255.0) + " " + fmt((rgb & 0xFF) / 255.0);
        }
        static String fmt(double d) {
            if (d == Math.rint(d)) return Long.toString((long) d);
            String s = String.format(Locale.US, "%.3f", d);
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
            return s;
        }

        /** Mỗi mảnh chữ đã được đo + bẻ dòng sẵn. */
        static final class CellLayout {
            Style style; Picked pk; FontRes fr; double size, lineH; List<String> lines; double height; Object raw;
        }

        double measure(Picked pk, double size, String s) {
            double w = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                w += pk.ttf.width(cp == '\t' ? ' ' : cp, size);
            }
            return w;
        }

        List<String> wrap(String text, Picked pk, double size, double maxW) {
            List<String> out = new ArrayList<>();
            for (String para : text.replace("\r", "").split("\n", -1)) {
                if (para.isEmpty()) { out.add(""); continue; }
                StringBuilder line = new StringBuilder();
                for (String word : para.split(" ", -1)) {
                    String trial = line.length() == 0 ? word : line + " " + word;
                    if (measure(pk, size, trial) <= maxW || line.length() == 0 && measure(pk, size, word) <= maxW) {
                        line = new StringBuilder(trial);
                        continue;
                    }
                    if (line.length() > 0) { out.add(line.toString()); line = new StringBuilder(); }
                    if (measure(pk, size, word) <= maxW) { line.append(word); continue; }
                    // từ dài hơn cả ô: cắt theo ký tự
                    StringBuilder piece = new StringBuilder();
                    for (int i = 0; i < word.length(); ) {
                        int cp = word.codePointAt(i);
                        i += Character.charCount(cp);
                        String t = piece + new String(Character.toChars(cp));
                        if (measure(pk, size, t) > maxW && piece.length() > 0) { out.add(piece.toString()); piece = new StringBuilder(); }
                        piece.append(Character.toChars(cp));
                    }
                    line.append(piece);
                }
                out.add(line.toString());
            }
            return out;
        }

        CellLayout layout(Object raw, String header, Style s, double width, double defSize) {
            CellLayout L = new CellLayout();
            L.style = s; L.raw = raw;
            L.pk = pick(s.font == null ? "Calibri" : s.font, s.b(), s.i(), s.fontFile);
            L.fr = res(L.pk);
            L.size = s.sizeOr(defSize);
            L.lineH = L.size * 1.25;
            String text = header != null ? header : shown(raw, s, c);
            L.lines = wrap(text, L.pk, L.size, Math.max(8, width - 8));
            L.height = L.lines.size() * L.lineH + 6;
            return L;
        }

        void newPage() {
            cur = new StringBuilder(8192);
            pages.add(cur);
            y = pageH - margin;
        }

        static final double[][] SIZES = {{595.28, 841.89}, {841.89, 1190.55}, {419.53, 595.28}, {612, 792}, {612, 1008}};
        static final String[] SIZE_NAMES = {"a4", "a3", "a5", "letter", "legal"};

        byte[] build() throws IOException {
            String ps = str(g(c.opt, "pagesize", "khogiay")).toLowerCase();
            double[] sz = SIZES[0];
            for (int i = 0; i < SIZE_NAMES.length; i++) if (SIZE_NAMES[i].equals(ps)) sz = SIZES[i];
            boolean land = !"portrait".equalsIgnoreCase(str(g(c.opt, "orientation", "huong")));
            pageW = land ? sz[1] : sz[0];
            pageH = land ? sz[0] : sz[1];
            Double mg = toNum(g(c.opt, "margin", "le"));
            margin = mg == null ? 36 : Math.max(10, mg);
            contentW = pageW - 2 * margin;
            boolean numbers = c.flag("pagenumbers", true);
            String footerText = str(g(c.opt, "footer", "chantrang"));
            bottom = margin + ((numbers || !footerText.isEmpty()) ? 16 : 0);

            int n = c.keys.size();
            double[] colW = new double[n];
            if (n > 0) {
                double sum = 0;
                for (int i = 0; i < n; i++) { colW[i] = c.widthChars(i); sum += colW[i]; }
                for (int i = 0; i < n; i++) colW[i] = colW[i] / sum * contentW;
            }

            newPage();
            if (c.title != null && !c.title.isEmpty()) drawBanner(c.title, c.titleStyle, 16);
            if (c.subtitle != null && !c.subtitle.isEmpty()) drawBanner(c.subtitle, c.subtitleStyle, 10);
            if (c.title != null || c.subtitle != null) y -= 6;

            if (n > 0) {
                CellLayout[] head = layoutRow(-1, null, colW);
                double headH = rowHeight(head);
                if (y - headH < bottom) newPage();
                drawRow(head, colW, headH);
                for (int r = 0; r < c.rows.size(); r++) {
                    CellLayout[] row = layoutRow(r, c.rows.get(r), colW);
                    double h = rowHeight(row);
                    if (y - h < bottom) {
                        newPage();
                        drawRow(head, colW, headH);               // lặp lại dòng tiêu đề ở trang mới
                    }
                    drawRow(row, colW, h);
                }
            }

            // ---- chân trang (cần biết tổng số trang nên vẽ sau cùng) ----
            if (numbers || !footerText.isEmpty()) {
                Style fs = new Style();
                fs.font = c.bodyStyle.font; fs.color = 0x6B7280; fs.fontFile = c.bodyStyle.fontFile;
                Picked pk = pick(fs.font == null ? "Calibri" : fs.font, false, false, fs.fontFile);
                FontRes fr = res(pk);
                for (int i = 0; i < pages.size(); i++) {
                    String t = footerText;
                    if (numbers) t = (t.isEmpty() ? "" : t + "   |   ") + "Trang " + (i + 1) + "/" + pages.size();
                    double w = measure(pk, 8, t);
                    text(pages.get(i), fr, pk, 8, (pageW - w) / 2, margin - 2, t, fs, false, false);
                }
            }
            return assemble();
        }

        CellLayout[] layoutRow(int r, Object[] row, double[] colW) {
            int n = c.keys.size();
            CellLayout[] L = new CellLayout[n];
            for (int i = 0; i < n; i++) {
                Style s = c.styleFor(r, i);
                L[i] = r < 0 ? layout(null, c.labels.get(i), s, colW[i], 9) : layout(row[i], null, s, colW[i], 9);
            }
            return L;
        }

        double rowHeight(CellLayout[] row) {
            double h = 0;
            for (CellLayout l : row) h = Math.max(h, l.height);
            return h;
        }

        void drawBanner(String text, Style s, double defSize) {
            Picked pk = pick(s.font == null ? "Calibri" : s.font, s.b(), s.i(), s.fontFile);
            FontRes fr = res(pk);
            double size = s.sizeOr(defSize), lh = size * 1.3;
            List<String> lines = wrap(Normalizer.normalize(text, Normalizer.Form.NFC), pk, size, contentW);
            if (y - lines.size() * lh < bottom) newPage();
            for (String line : lines) {
                double w = measure(pk, size, line);
                String al = s.align == null ? "center" : s.align;
                double x = al.equals("left") ? margin : al.equals("right") ? pageW - margin - w : margin + (contentW - w) / 2;
                y -= lh;
                text(cur, fr, pk, size, x, y + size * 0.28, line, s, s.u(), s.st());
            }
        }

        void drawRow(CellLayout[] row, double[] colW, double h) {
            double x = margin, top = y, bot = y - h;
            for (int i = 0; i < row.length; i++) {
                CellLayout l = row[i];
                Style s = l.style;
                if (s.bg != null) cur.append(rg(s.bg)).append(" rg ").append(fmt(x)).append(' ').append(fmt(bot)).append(' ')
                        .append(fmt(colW[i])).append(' ').append(fmt(h)).append(" re f\n");

                double textH = l.lines.size() * l.lineH;
                String va = s.valign == null ? "middle" : s.valign;
                double startTop = va.equals("top") ? top - 3 : va.equals("bottom") ? bot + 3 + textH : top - (h - textH) / 2;
                String al = s.align != null ? s.align : (l.raw instanceof Number ? "right" : (l.style.align == null ? "left" : l.style.align));
                for (int k = 0; k < l.lines.size(); k++) {
                    String line = l.lines.get(k);
                    double w = measure(l.pk, l.size, line);
                    double tx = al.equals("center") ? x + (colW[i] - w) / 2 : al.equals("right") ? x + colW[i] - 4 - w : x + 4;
                    double base = startTop - k * l.lineH - l.size * 1.0;
                    text(cur, l.fr, l.pk, l.size, tx, base, line, s, s.u(), s.st());
                }
                if (Boolean.TRUE.equals(s.border)) {
                    cur.append(rg(s.borderColorOr(0xD1D5DB))).append(" RG 0.5 w ").append(fmt(x)).append(' ').append(fmt(bot)).append(' ')
                            .append(fmt(colW[i])).append(' ').append(fmt(h)).append(" re S\n");
                }
                x += colW[i];
            }
            y -= h;
        }

        /** Ghi một dòng chữ tại (x, baseline). Giả lập đậm = viền chữ; nghiêng = xiên ma trận chữ. */
        void text(StringBuilder out, FontRes fr, Picked pk, double size, double x, double base, String line, Style s, boolean underline, boolean strike) {
            if (line.isEmpty()) return;
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                i += Character.charCount(cp);
                if (cp == '\t') cp = ' ';
                if (cp == 0xA0) cp = ' ';
                if (cp < 0x20) continue;
                int gid = pk.ttf.glyph(cp);
                if (gid == 0) { cp = '?'; gid = pk.ttf.glyph('?'); }
                fr.gidToCp.putIfAbsent(gid, cp);
                hex.append(String.format("%04X", gid));
            }
            int rgb = s.colorOr(0x000000);
            out.append("BT /").append(fr.name).append(' ').append(fmt(size)).append(" Tf ").append(rg(rgb)).append(" rg ");
            if (pk.fakeBold) out.append(rg(rgb)).append(" RG ").append(fmt(Math.max(0.2, size * 0.035))).append(" w 2 Tr ");
            out.append("1 0 ").append(pk.fakeItalic ? "0.2" : "0").append(" 1 ").append(fmt(x)).append(' ').append(fmt(base)).append(" Tm <")
                    .append(hex).append("> Tj");
            if (pk.fakeBold) out.append(" 0 Tr");
            out.append(" ET\n");
            double w = measure(pk, size, line);
            if (underline) out.append(rg(rgb)).append(" rg ").append(fmt(x)).append(' ').append(fmt(base - size * 0.14)).append(' ')
                    .append(fmt(w)).append(' ').append(fmt(Math.max(0.5, size * 0.06))).append(" re f\n");
            if (strike) out.append(rg(rgb)).append(" rg ").append(fmt(x)).append(' ').append(fmt(base + size * 0.3)).append(' ')
                    .append(fmt(w)).append(' ').append(fmt(Math.max(0.5, size * 0.06))).append(" re f\n");
        }

        // ------------------------------------------------------------ lắp ráp tệp PDF
        final List<byte[]> objs = new ArrayList<>();

        int add(byte[] body) { objs.add(body); return objs.size(); }
        int add(String body) { return add(body.getBytes(StandardCharsets.ISO_8859_1)); }

        byte[] stream(String dict, byte[] raw) throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Deflater df = new Deflater(Deflater.BEST_COMPRESSION);
            try (DeflaterOutputStream d = new DeflaterOutputStream(bo, df)) { d.write(raw); }
            df.end();
            byte[] z = bo.toByteArray();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(("<< " + dict + " /Length " + z.length + " /Filter /FlateDecode >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
            o.write(z);
            o.write("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
            return o.toByteArray();
        }

        static String uni16(int cp) {
            if (cp > 0xFFFF) {
                char[] ch = Character.toChars(cp);
                return String.format("%04X%04X", (int) ch[0], (int) ch[1]);
            }
            return String.format("%04X", cp);
        }

        byte[] assemble() throws IOException {
            objs.add(null);                       // 1: Catalog
            objs.add(null);                       // 2: Pages
            Map<String, Integer> fontObj = new LinkedHashMap<>();

            for (Map.Entry<String, FontRes> e : fonts.entrySet()) {
                FontRes f = e.getValue();
                Ttf t = f.ttf;
                double k = 1000.0 / t.unitsPerEm;
                String base = "HQLFont" + f.name.substring(1);

                int ff = add(stream("/Length1 " + t.data.length, t.data));
                int fd = add("<< /Type /FontDescriptor /FontName /" + base + " /Flags 32 /FontBBox [" + Math.round(t.xMin * k) + " "
                        + Math.round(t.yMin * k) + " " + Math.round(t.xMax * k) + " " + Math.round(t.yMax * k) + "] /ItalicAngle 0 /Ascent "
                        + Math.round(t.ascent * k) + " /Descent " + Math.round(t.descent * k) + " /CapHeight " + Math.round(t.ascent * k * 0.7)
                        + " /StemV 80 /FontFile2 " + ff + " 0 R >>");
                StringBuilder w = new StringBuilder("[");
                for (int gid : f.gidToCp.keySet()) w.append(gid).append(" [").append(Math.round(t.advance(gid) * k)).append("] ");
                w.append("]");
                int cid = add("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /" + base + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
                        + "/FontDescriptor " + fd + " 0 R /DW 1000 /W " + w + " /CIDToGIDMap /Identity >>");

                StringBuilder cm = new StringBuilder("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
                        + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n"
                        + "1 begincodespacerange <0000> <FFFF> endcodespacerange\n");
                List<Map.Entry<Integer, Integer>> es = new ArrayList<>(f.gidToCp.entrySet());
                for (int i = 0; i < es.size(); i += 100) {
                    int end = Math.min(es.size(), i + 100);
                    cm.append(end - i).append(" beginbfchar\n");
                    for (int j = i; j < end; j++) cm.append('<').append(String.format("%04X", es.get(j).getKey())).append("> <").append(uni16(es.get(j).getValue())).append(">\n");
                    cm.append("endbfchar\n");
                }
                cm.append("endcmap CMapName currentdict /CMap defineresource pop end end");
                int tu = add(stream("", cm.toString().getBytes(StandardCharsets.ISO_8859_1)));

                int type0 = add("<< /Type /Font /Subtype /Type0 /BaseFont /" + base + " /Encoding /Identity-H /DescendantFonts [" + cid + " 0 R] /ToUnicode " + tu + " 0 R >>");
                fontObj.put(f.name, type0);
            }

            StringBuilder fontDict = new StringBuilder("<< ");
            for (Map.Entry<String, Integer> e : fontObj.entrySet()) fontDict.append('/').append(e.getKey()).append(' ').append(e.getValue()).append(" 0 R ");
            fontDict.append(">>");

            StringBuilder kids = new StringBuilder();
            for (StringBuilder pg : pages) {
                int cont = add(stream("", pg.toString().getBytes(StandardCharsets.ISO_8859_1)));
                int page = add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + fmt(pageW) + " " + fmt(pageH) + "] /Resources << /Font " + fontDict
                        + " >> /Contents " + cont + " 0 R >>");
                kids.append(page).append(" 0 R ");
            }
            objs.set(0, "<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.ISO_8859_1));
            objs.set(1, ("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>").getBytes(StandardCharsets.ISO_8859_1));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("%PDF-1.5\n%\u00e2\u00e3\u00cf\u00d3\n".getBytes(StandardCharsets.ISO_8859_1));
            int[] off = new int[objs.size()];
            for (int i = 0; i < objs.size(); i++) {
                off[i] = out.size();
                out.write(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(objs.get(i));
                out.write("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
            }
            int xref = out.size();
            StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
            for (int o : off) x.append(String.format("%010d 00000 n \n", o));
            x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
            out.write(x.toString().getBytes(StandardCharsets.ISO_8859_1));
            return out.toByteArray();
        }
    }
}