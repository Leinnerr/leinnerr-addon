package com.leinnerr.addon.bots;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class BotConsole {
    private static JFrame frame;
    private static JTextArea area;

    private BotConsole() {
    }

    /** @return esto se pone en false si el jdk no puede abrir ventanas (modo headless) */
    public static boolean open() {
        if (GraphicsEnvironment.isHeadless()) return false;
        SwingUtilities.invokeLater(BotConsole::openOnEdt);
        return true;
    }

    private static void openOnEdt() {
        if (frame != null) {
            frame.setVisible(true);
            frame.toFront();
            return;
        }

        area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JTextField input = new JTextField();
        input.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        input.setToolTipText("Nombre: mensaje   |   all: /comando   |   !list   |   !leave [nombre|all]");
        input.addActionListener(e -> {
            String text = input.getText();
            input.setText("");
            if (!text.isBlank()) BotManager.get().dispatch(text);
        });

        frame = new JFrame("Leinnerr - Bots");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(area), BorderLayout.CENTER);
        frame.add(input, BorderLayout.SOUTH);
        frame.setSize(720, 420);
        frame.setAlwaysOnTop(true);
        frame.setLocationByPlatform(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                
            }
        });

        BotManager manager = BotManager.get();
        for (String line : manager.history()) area.append(line + "\n");
        manager.setSink(BotConsole::append);
        manager.log(null, "Consola lista. Escribe  Nombre: mensaje  |  all: /comando  |  !list  |  !leave");

        frame.setVisible(true);
        input.requestFocusInWindow();
    }

    private static void append(String line) {
        SwingUtilities.invokeLater(() -> {
            if (area == null) return;
            area.append(line + "\n");
            area.setCaretPosition(area.getDocument().getLength());
        });
    }
}
