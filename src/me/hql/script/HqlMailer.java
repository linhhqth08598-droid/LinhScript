package me.hql.script;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.File;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * BỘ GỬI EMAIL CỦA LINHSCRIPT (HqlMailer)
 *
 * Bản cũ gọi Transport.send() cho MỖI lá thư: mỗi lần như vậy phải dựng lại Session,
 * mở kết nối TCP + bắt tay TLS + đăng nhập Gmail (thường mất 1–3 giây), còn mailSendAsync
 * thì cứ gọi là đẻ thêm một Thread mới.
 *
 * Bản này:
 *   1. Giữ MỘT kết nối SMTP mở sẵn và dùng lại cho mọi lá thư → lá thứ hai trở đi chỉ tốn vài chục ms.
 *   2. Chỉ dựng Session một lần (dựng lại khi mailConfig đổi).
 *   3. mailSendAsync xếp thư vào hàng đợi, MỘT luồng nền gửi lần lượt (không đẻ luồng tuỳ tiện,
 *      không tranh nhau kết nối, hàng đợi đầy thì báo lỗi chứ không ăn hết bộ nhớ).
 *   4. Kết nối bị Gmail cắt vì để lâu → tự nối lại và gửi lại đúng 1 lần.
 *   5. Thư HTML kèm bản chữ thuần (multipart/alternative) → ít bị vào hòm Spam hơn, đọc được trên mọi ứng dụng.
 *   6. warmUp(): mở sẵn kết nối khi khởi động để người dùng đầu tiên khỏi phải chờ.
 *   7. Lỗi được ghi theo TỪNG LUỒNG, nên mailError() của request này không bị request khác ghi đè.
 */
public final class HqlMailer {

    // ----------------------- CẤU HÌNH -----------------------
    private volatile String fromEmail = "";
    private volatile String password = "";
    private volatile String fromName = "";
    private volatile String host = "smtp.gmail.com";
    private volatile int port = 587;

    private volatile Session session;
    private Transport transport;                      // chỉ đụng vào khi đang giữ khoá LOCK
    private long transportLastUsed;
    private final Object LOCK = new Object();

    /** Sau chừng này mili-giây không gửi thì coi như kết nối đã nguội: nối lại trước khi gửi. */
    private static final long STALE_AFTER_MS = 45_000;

    private final ThreadLocal<String> errorOfThread = ThreadLocal.withInitial(() -> "");
    private volatile String errorOfBackground = "";

    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(500),
            r -> { Thread t = new Thread(r, "hql-mail"); t.setDaemon(true); return t; });

    public HqlMailer() {
        System.setProperty("java.net.preferIPv4Stack", "true");     // tránh treo do IPv6 trên một số mạng
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "hql-mail-stop"));
    }

    // ----------------------- CẤU HÌNH -----------------------

    public void configure(String email, String appPassword, String smtpHost, Integer smtpPort) {
        synchronized (LOCK) {
            this.fromEmail = email == null ? "" : email.trim();
            this.password = appPassword == null ? "" : appPassword.replace(" ", "");
            if (smtpHost != null && !smtpHost.isBlank()) this.host = smtpHost.trim();
            if (smtpPort != null && smtpPort > 0) this.port = smtpPort;
            this.session = null;
            closeTransportQuietly();
        }
        errorOfThread.set("");
    }

    public void setSenderName(String name) { this.fromName = name == null ? "" : name; }

    public boolean isConfigured() { return !fromEmail.isEmpty() && !password.isEmpty(); }

    public String lastError() {
        String t = errorOfThread.get();
        return (t != null && !t.isEmpty()) ? t : errorOfBackground;
    }

    // ----------------------- GỬI -----------------------

    /** Gửi đồng bộ. Trả về true nếu thành công; nếu lỗi thì xem lastError(). */
    public boolean send(String to, String subject, String content, boolean html, List<String> attachments) {
        String err = doSend(to, subject, content, html, attachments);
        errorOfThread.set(err == null ? "" : err);
        return err == null;
    }

    /** Xếp vào hàng đợi, trả về ngay. true = đã nhận vào hàng đợi (chưa chắc đã gửi xong). */
    public boolean sendAsync(String to, String subject, String content, boolean html, List<String> attachments) {
        if (!isConfigured()) {
            errorOfThread.set(notConfiguredMessage());
            return false;
        }
        final List<String> files = attachments == null ? List.of() : List.copyOf(attachments);
        try {
            queue.execute(() -> {
                String err = doSend(to, subject, content, html, files);
                errorOfBackground = err == null ? "" : err;
            });
            return true;
        } catch (RejectedExecutionException e) {
            errorOfThread.set("Hàng đợi gửi thư đang đầy (500 thư chờ). Thử lại sau.");
            return false;
        }
    }

    /** Mở sẵn kết nối SMTP ở luồng nền (gọi khi khởi động máy chủ). */
    public void warmUp() {
        if (!isConfigured()) return;
        try {
            queue.execute(() -> {
                synchronized (LOCK) {
                    try { ensureConnected(); }
                    catch (Exception e) { System.err.println("[HQL.MAIL] Khởi động nóng thất bại: " + e.getMessage()); }
                }
            });
        } catch (RejectedExecutionException ignore) { }
    }

    public void shutdown() {
        queue.shutdown();
        try { queue.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        synchronized (LOCK) { closeTransportQuietly(); }
    }

    // ----------------------- BÊN TRONG -----------------------

    private String notConfiguredMessage() {
        return "Chưa cấu hình hộp thư: gọi mailConfig(email, matKhauUngDung) hoặc khai báo MAIL_EMAIL / MAIL_PASSWORD trong tệp .henv.";
    }

    /** Trả về null nếu gửi thành công, ngược lại là câu báo lỗi. */
    private String doSend(String to, String subject, String content, boolean html, List<String> attachments) {
        if (!isConfigured()) {
            System.err.println("[HQL.MAIL] " + notConfiguredMessage());
            return notConfiguredMessage();
        }
        long t0 = System.currentTimeMillis();
        synchronized (LOCK) {
            try {
                MimeMessage msg = build(to, subject, content, html, attachments);
                try {
                    sendOnce(msg);
                } catch (SendFailedException bad) {
                    throw bad;                                   // sai địa chỉ người nhận: thử lại cũng vô ích
                } catch (MessagingException first) {
                    closeTransportQuietly();                     // kết nối cũ chết -> nối lại, gửi lại 1 lần
                    msg = build(to, subject, content, html, attachments);
                    sendOnce(msg);
                }
                System.out.println("[HQL.MAIL] Đã gửi tới " + to + " (" + (System.currentTimeMillis() - t0) + " ms)");
                return null;
            } catch (Exception e) {
                String err = e.getMessage() == null ? e.toString() : e.getMessage();
                System.err.println("[HQL.MAIL] Gửi thất bại tới " + to + ": " + err);
                return err;
            }
        }
    }

    private void sendOnce(MimeMessage msg) throws MessagingException {
        ensureConnected();
        transport.sendMessage(msg, msg.getAllRecipients());
        transportLastUsed = System.currentTimeMillis();
    }

    /** Phải gọi khi đang giữ LOCK. */
    private void ensureConnected() throws MessagingException {
        long now = System.currentTimeMillis();
        if (transport != null && transport.isConnected() && now - transportLastUsed > STALE_AFTER_MS) {
            closeTransportQuietly();                             // nguội rồi: bỏ luôn, đỡ gửi hỏng lần đầu
        }
        if (transport == null || !transport.isConnected()) {
            Session s = session();
            transport = s.getTransport("smtp");
            transport.connect(host, port, fromEmail, password);
            transportLastUsed = System.currentTimeMillis();
        }
    }

    private void closeTransportQuietly() {
        try { if (transport != null) transport.close(); } catch (Exception ignore) { }
        transport = null;
    }

    private Session session() {
        Session s = session;
        if (s != null) return s;
        Properties p = new Properties();
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.localhost", "localhost");              // tránh JavaMail phải dò tên máy (chậm trên Windows)
        if (port == 465) {
            p.put("mail.smtp.ssl.enable", "true");
        } else {
            p.put("mail.smtp.starttls.enable", "true");
        }
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");
        final String u = fromEmail, pw = password;
        s = Session.getInstance(p, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(u, pw); }
        });
        session = s;
        return s;
    }

    private MimeMessage build(String to, String subject, String content, boolean html, List<String> attachments)
            throws Exception {
        MimeMessage m = new MimeMessage(session());
        try {
            m.setFrom(new InternetAddress(fromEmail, fromName.isEmpty() ? fromEmail : fromName, "UTF-8"));
        } catch (Exception e) {
            m.setFrom(new InternetAddress(fromEmail));
        }
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        m.setSubject(subject, "UTF-8");

        // Phần thân: HTML thì kèm bản chữ thuần (alternative)
        MimeMultipart body = null;
        if (html) {
            body = new MimeMultipart("alternative");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(htmlToText(content), "UTF-8");
            MimeBodyPart rich = new MimeBodyPart();
            rich.setContent(content, "text/html; charset=UTF-8");
            body.addBodyPart(plain);
            body.addBodyPart(rich);                                // phần cuối cùng là phần ưu tiên hiển thị
        }

        boolean hasFiles = false;
        MimeMultipart mixed = new MimeMultipart("mixed");
        if (attachments != null) {
            for (String path : attachments) {
                File f = new File(path);
                if (!f.isFile()) continue;
                MimeBodyPart a = new MimeBodyPart();
                a.attachFile(f);
                mixed.addBodyPart(a);
                hasFiles = true;
            }
        }

        if (!hasFiles) {
            if (html) m.setContent(body); else m.setText(content, "UTF-8");
        } else {
            MimeBodyPart main = new MimeBodyPart();
            if (html) main.setContent(body); else main.setText(content, "UTF-8");
            mixed.addBodyPart(main, 0);
            m.setContent(mixed);
        }
        m.saveChanges();
        return m;
    }

    /** Biến HTML thành chữ thuần đủ đọc được (cho phần "alternative"). */
    static String htmlToText(String html) {
        if (html == null) return "";
        String t = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</(p|div|tr|h[1-6]|li)>", "\n")
                .replaceAll("(?i)</t[dh]>", "  ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&copy;", "(c)");
        return t.replaceAll("[ \\t]+", " ").replaceAll("(?m)^ +| +$", "").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
