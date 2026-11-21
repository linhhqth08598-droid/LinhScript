package me.hql.script;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class LinhScriptRunner {

    /**
     * Dò ngược lên từ vị trí file chạy (jpackage.app-path hoặc Native Image) để tìm một file
     * theo đường dẫn tương đối. Trả về null nếu không thấy.
     */
    private static String timCanhFileChay(String duongDanTuongDoi) {
        String duAn = System.getProperty("jpackage.app-path");
        if (duAn == null) {
            duAn = ProcessHandle.current().info().command().orElse(null);
        }
        if (duAn == null) { return null; }
        java.io.File thuMuc = new java.io.File(duAn).getAbsoluteFile().getParentFile();
        for (int i = 0; thuMuc != null && i < 12; i++) {
            java.io.File thu = new java.io.File(thuMuc, duongDanTuongDoi);
            if (thu.exists()) { return thu.getAbsolutePath(); }
            thuMuc = thuMuc.getParentFile();
        }
        return null;
    }

    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_RESET = "\u001B[0m";

    public static void main(String[] args) {
        // --- ÉP HIỂN THỊ TIẾNG VIỆT CHUẨN UTF-8 TRÊN TERMINAL WINDOWS ---
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                // Chuyển code page sang 65001 (UTF-8) và ẩn dòng thông báo "Active code page: 65001"
                new ProcessBuilder("cmd", "/c", "chcp 65001 > nul").inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }
        try {
            System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}

        if (args.length == 0) {
            System.err.println(ANSI_RED + "Lỗi: Sếp chưa đưa file .hql cho hệ thống!" + ANSI_RESET);
            return;
        }

        Interpreter interpreter = new Interpreter();
        try {
            String entryPoint = args[0];

            // --- BẪY LỖI SAI ĐƯỜNG DẪN FILE ---
            if (!Files.exists(Paths.get(entryPoint))) {
                String lai = timCanhFileChay(entryPoint);
                if (lai == null) {
                    System.err.println(ANSI_RED + "Lỗi hệ thống: Không tìm thấy file '" + entryPoint + "'" + ANSI_RESET);
                    return;
                }
                entryPoint = lai;
            }
            interpreter.runFile(entryPoint);
        } catch (Exception e) {
            Throwable rootCause = BaoLoi.goc(e);
            Token errToken = interpreter.getLastExecutedToken();

            System.err.println(BaoLoi.banChu(rootCause, errToken, interpreter.getNganXepGoi(), true));

            if (errToken == null) {
                inViTriTuCauBao(rootCause.getMessage(), args[0]);
            }
            System.exit(1);
        }
    }

    /** Dựng dòng "at <đường dẫn>:<số dòng>" từ câu báo lỗi của Lexer. */
    private static void inViTriTuCauBao(String errorMessage, String entryPoint) {
        try {
            String absolutePath = Paths.get(entryPoint).toAbsolutePath().normalize().toString();
            String lineSuffix = "";
            if (errorMessage != null) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("tại dòng (\\d+)").matcher(errorMessage);
                if (m.find()) { lineSuffix = ":" + m.group(1); }
            }
            System.err.println("\tat " + absolutePath + lineSuffix);
        } catch (Exception ex) {
            System.err.println("\tat " + entryPoint);
        }
    }

    // ==========================================================================================
    //   CẦU NỐI TOMCAT — CHO PHÉP CHẠY .hqlsp VÀ <Ten>Servlet.hql NGAY TRÊN TOMCAT 10.1
    //   Khai báo trong WEB-INF/web.xml:  me.hql.script.LinhScriptRunner$HqlServlet
    //   (Lệnh webExportTomcat("webapp") trong HQL sẽ tự sinh file web.xml này ra.)
    // ==========================================================================================
    public static class HqlServlet extends jakarta.servlet.http.HttpServlet {

        private Interpreter interpreter;

        @Override
        public void init() {
            interpreter = new Interpreter();
            interpreter.webInitForContainer(getServletContext().getRealPath("/"));
            log("[HQL] Động cơ HQL Web đã khởi động tại: " + getServletContext().getRealPath("/"));
        }

        @Override
        protected void service(jakarta.servlet.http.HttpServletRequest req,
                               jakarta.servlet.http.HttpServletResponse resp) throws java.io.IOException {

            Interpreter.WebContext ctx = new Interpreter.WebContext();
            ctx.method = req.getMethod().toUpperCase();
            ctx.query = req.getQueryString() == null ? "" : req.getQueryString();

            String clientIp = req.getRemoteAddr() == null ? "" : req.getRemoteAddr();
            if (clientIp.isEmpty() || clientIp.startsWith("127.") || clientIp.equals("0:0:0:0:0:0:0:1") || clientIp.equals("::1")) {
                try {
                    clientIp = java.net.InetAddress.getLocalHost().getHostAddress();
                } catch (java.net.UnknownHostException e) {
                    clientIp = "Lỗi lấy IP";
                }
            }
            ctx.ip = clientIp;

            String ctxPath = req.getContextPath() == null ? "" : req.getContextPath();
            ctx.contextPath = ctxPath;
            String path = req.getRequestURI();
            if (!ctxPath.isEmpty() && path.startsWith(ctxPath)) path = path.substring(ctxPath.length());
            ctx.path = path.isEmpty() ? "/" : path;

            req.setCharacterEncoding("UTF-8");
            for (java.util.Enumeration<String> e = req.getHeaderNames(); e.hasMoreElements(); ) {
                String h = e.nextElement();
                ctx.headers.put(h.toLowerCase(), req.getHeader(h));
            }
            if (req.getCookies() != null) {
                for (jakarta.servlet.http.Cookie c : req.getCookies()) ctx.cookies.put(c.getName(), c.getValue());
            }
            for (java.util.Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
                if (e.getValue().length > 0) ctx.params.put(e.getKey(), e.getValue()[0]);
            }
            if (!"GET".equals(ctx.method)) {
                try (java.io.BufferedReader r = req.getReader()) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    ctx.body = sb.toString();
                } catch (Exception ignore) {}
            }

            jakarta.servlet.http.HttpSession session = req.getSession(false);
            if (session != null) {
                ctx.sessionId = session.getId();
                ctx.session = new java.util.LinkedHashMap<>();
                for (java.util.Enumeration<String> e = session.getAttributeNames(); e.hasMoreElements(); ) {
                    String k = e.nextElement();
                    ctx.session.put(k, session.getAttribute(k));
                }
            }

            interpreter.webDispatch(ctx);

            if (ctx.sessionKilled && session != null) {
                session.invalidate();
            } else if (ctx.session != null) {
                jakarta.servlet.http.HttpSession s = req.getSession(true);
                for (java.util.Map.Entry<String, Object> e : ctx.session.entrySet()) s.setAttribute(e.getKey(), e.getValue());
            }

            for (java.util.Map.Entry<String, String> h : ctx.resHeaders.entrySet()) resp.setHeader(h.getKey(), h.getValue());
            for (String c : ctx.resCookies) resp.addHeader("Set-Cookie", c);

            if (ctx.redirect != null) {
                resp.sendRedirect(Interpreter.withContextPath(ctx.redirect, ctx.contextPath));
                return;
            }

            resp.setStatus(ctx.status);
            resp.setContentType(ctx.contentType);
            byte[] payload = ctx.rawOut != null
                    ? ctx.rawOut
                    : ctx.out.toString().getBytes(StandardCharsets.UTF_8);
            resp.setContentLength(payload.length);
            resp.getOutputStream().write(payload);
        }
    }
}
