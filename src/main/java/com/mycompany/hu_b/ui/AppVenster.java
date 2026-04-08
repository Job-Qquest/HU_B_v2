/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.ui;

import com.mycompany.hu_b.controller.ChatController;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import static javax.swing.WindowConstants.EXIT_ON_CLOSE;

// Deze class bouwt en beheert de volledige gebruikersinterface van de chatbot.
// Hier worden het hoofdvenster, het chatgedeelte, het invoerveld, de verzendknop en de chatbubbels aangemaakt.
// De class toont berichten in beeld en geeft gebruikersacties door aan de ChatController.

public class AppVenster extends JFrame {

    private static final String PERSONEELSGIDS_VERSIE =
            "Personeelsgids BU Talentclass versie 2024.1"
            + "Disclaimer: De informatie die HU-B geeft is mogelijk niet volledig of niet actueel. De informatie die gegeven is, is niet juridisch bindend. Raadpleeg bij twijfel altijd HR.";

    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private JButton sendButton;
    private Image backgroundImage;

    private final ChatController controller;

    public AppVenster() throws Exception {
        setTitle("HU-B – HR Chatbot");
        setSize(1100, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        backgroundImage = new ImageIcon("qquestlogoHoe gaa.png").getImage();

        setupChatPanel();
        setupInputPanel();

        controller = new ChatController(this);

        setVisible(true);

        addBubble("Welkom! Ik ben HU-B, jouw HR-assistent.", false);
        addBubble("Gebruikte bron: " + PERSONEELSGIDS_VERSIE, false);
        addBubble("Ik laad nu de personeelsgids. Een moment geduld...", false);

        controller.startKnowledgeLoading();
    }

    private void setupChatPanel() {
        chatPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.drawImage(backgroundImage, 0, 0, null);
            }
        };

        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        chatPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        scrollPane = new JScrollPane(chatPanel);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        add(scrollPane, BorderLayout.CENTER);
    }

    private void setupInputPanel() {
        inputField = new JTextField();
        sendButton = new JButton("Verstuur");
        sendButton.setEnabled(false);

        sendButton.setFocusPainted(false);
        sendButton.setBackground(new Color(0, 90, 160));
        sendButton.setForeground(Color.WHITE);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(new EmptyBorder(12, 12, 12, 12));
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);

        add(bottomPanel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> controller.send(inputField.getText().trim()));
        inputField.addActionListener(e -> controller.send(inputField.getText().trim()));
    }

    public void setSendEnabled(boolean enabled) {
        sendButton.setEnabled(enabled);
    }

    public void clearInput() {
        inputField.setText("");
    }

    public void addUserBubble(String text) {
        addBubble(text, true);
    }

    public void addAssistantBubble(String text) {
        addBubble(text, false);
    }

    public void addBubble(String text, boolean user) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        String antwoord = text;
        String disclaimer = "";

        if (!user && text.contains("Disclaimer:")) {
            int index = text.indexOf("Disclaimer:");
            antwoord = text.substring(0, index).trim();
            disclaimer = text.substring(index).trim();
        }

        String htmlText;

        if (!user && !disclaimer.isEmpty()) {
            htmlText =
                "<html>" +
                "<div style='font-family:Segoe UI; font-size:13px; width:650px'>" +
                antwoord.replace("\n", "<br>") +
                "</div>" +
                "<div style='margin-top:20px; font-size:10px; color:gray; text-align:left;'>" +
                disclaimer.replace("\n", "<br>") +
                "</div>" +
                "</html>";
        } else {
            htmlText =
                "<html>" +
                "<div style='font-family:Segoe UI; font-size:13px; width: 650px'>" +
                text.replace("\n", "<br>") +
                "</div>" +
                "</html>";
        }

        JTextPane bubble = new JTextPane();
        bubble.setContentType("text/html");
        bubble.setText(htmlText);
        bubble.setEditable(false);
        bubble.setBorder(new EmptyBorder(14, 20, 14, 20));
        bubble.setMaximumSize(new Dimension(700, Integer.MAX_VALUE));
        bubble.setPreferredSize(null);
        bubble.setSize(new Dimension(700, Short.MAX_VALUE));

        if (user) {
            bubble.setBackground(new Color(0, 90, 160));
            bubble.setForeground(Color.WHITE);
        } else {
            bubble.setBackground(new Color(255, 255, 255, 235));
            bubble.setForeground(Color.BLACK);
        }

        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);
        container.setBorder(new EmptyBorder(8, 40, 8, 40));
        container.add(bubble, BorderLayout.CENTER);

        row.add(container, BorderLayout.CENTER);
        chatPanel.add(row);
        chatPanel.revalidate();

        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }
}