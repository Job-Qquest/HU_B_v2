package com.mycompany.hu_b.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class InputPanel {

    private JPanel panel;
    private JTextField inputField;
    private JButton sendButton;

    private Consumer<String> onSend;

    public InputPanel() {
        setup();
    }

    // maakt input veld + knop
    private void setup() {
        panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        inputField = new JTextField();
        sendButton = new JButton("Verstuur");
        sendButton.setEnabled(false);

        panel.add(inputField, BorderLayout.CENTER);
        panel.add(sendButton, BorderLayout.EAST);

        sendButton.addActionListener(e -> send());
        inputField.addActionListener(e -> send());
    }

    private void send() {
        if (onSend != null) {
            String text = inputField.getText().trim();
            if (!text.isEmpty()) {
                onSend.accept(text);
            }
        }
    }

    // AppVenster koppelt controller hieraan
    public void setOnSend(Consumer<String> onSend) {
        this.onSend = onSend;
    }

    public void setSendEnabled(boolean enabled) {
        sendButton.setEnabled(enabled);
    }

    public void clearInput() {
        inputField.setText("");
    }

    public JPanel getPanel() {
        return panel;
    }
}