package me.hql.script;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.util.ArrayList;

public class TrayManager {
    public static boolean init(String iconPath, String onFunc, String offFunc, String exitFunc, Interpreter interpreter) {
        if (!SystemTray.isSupported()) {
            System.err.println("SystemTray not supported!");
            return false;
        }

        final TrayIcon trayIcon = new TrayIcon(new ImageIcon(iconPath).getImage(), "VN Checker");
        trayIcon.setImageAutoSize(true);

        // --- TẠO MENU THEO PHONG CÁCH RIOT GAMES ---
        JPopupMenu popup = new JPopupMenu();
        popup.setBackground(new Color(28, 28, 31));
        popup.setBorder(BorderFactory.createLineBorder(new Color(50, 50, 50), 1));

        // 1. Tiêu đề (Có logo)
        JMenuItem titleItem = createRiotMenuItem("Riot Games", new ImageIcon(iconPath));
        titleItem.setEnabled(false); // Vô hiệu hóa click
        UIManager.put("MenuItem.disabledForeground", new Color(240, 240, 240));
        popup.add(titleItem);

        // 2. Kẻ ngang phân cách
        JSeparator sep = new JSeparator();
        sep.setBackground(new Color(28, 28, 31));
        sep.setForeground(new Color(45, 45, 50));
        popup.add(sep);

        // 3. Nút Toggle (Bật / Tắt)
        JMenuItem toggleItem = createRiotMenuItem("Stopped", null);
        toggleItem.addActionListener(e -> {
            if (toggleItem.getText().equals("Running")) {
                interpreter.executeFunctionCall(offFunc, new ArrayList<>());
                toggleItem.setText("Stopped");
            } else {
                interpreter.executeFunctionCall(onFunc, new ArrayList<>());
                toggleItem.setText("Running");
            }
        });
        popup.add(toggleItem);

        // 4. Nút Thoát
        JMenuItem exitItem = createRiotMenuItem("Exit", null);
        exitItem.addActionListener(e -> {
            interpreter.executeFunctionCall(exitFunc, new ArrayList<>());
            System.exit(0);
        });
        popup.add(exitItem);

        // --- KHUNG ẨN ĐỂ MENU HOẠT ĐỘNG HOÀN HẢO ---
        // Tạo một cửa sổ vô hình để JPopupMenu bám vào, giúp nó tự tắt khi bạn click ra ngoài
        JDialog hiddenFrame = new JDialog();
        hiddenFrame.setUndecorated(true);
        hiddenFrame.setSize(0, 0);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { hiddenFrame.setVisible(false); }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { hiddenFrame.setVisible(false); }
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}
        });

        // --- BẮT SỰ KIỆN CLICK VÀO KHAY HỆ THỐNG ---
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                // Hỗ trợ cả chuột trái và chuột phải
                if (e.getButton() == MouseEvent.BUTTON1 || e.getButton() == MouseEvent.BUTTON3) {
                    hiddenFrame.setLocation(e.getX(), e.getY());
                    hiddenFrame.setVisible(true);
                    // Hiển thị menu nhô lên phía trên con trỏ chuột một chút
                    popup.show(hiddenFrame, 0, -popup.getPreferredSize().height);
                }
            }
        });

        try {
            SystemTray.getSystemTray().add(trayIcon);
            return true;
        } catch (AWTException e) {
            System.err.println("Lỗi tạo khay hệ thống: " + e.getMessage());
            return false;
        }
    }

    // Hàm hỗ trợ vẽ nút bấm Custom
    private static JMenuItem createRiotMenuItem(String text, Icon icon) {
        JMenuItem item = new JMenuItem(text, icon);
        item.setBackground(new Color(28, 28, 31));
        item.setForeground(new Color(200, 200, 200));
        item.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        item.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 40));
        item.setOpaque(true);

        // Hiệu ứng hover chuột phát sáng nhẹ
        item.addChangeListener(e -> {
            if (item.isArmed()) {
                item.setBackground(new Color(45, 45, 50));
                item.setForeground(Color.WHITE);
            } else {
                item.setBackground(new Color(28, 28, 31));
                item.setForeground(new Color(200, 200, 200));
            }
        });
        return item;
    }
}