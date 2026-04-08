package com.mycompany.hu_b.ui;

import com.mycompany.hu_b.controller.ChatController;

import javax.swing.*;
import java.awt.*;

public class AppVenster extends JFrame {

    private BerichtenTonen berichtenTonen;
    private InputPanel inputPanel;
    private ChatController controller;

    public AppVenster() throws Exception {
        setTitle("HU-B – HR Chatbot");
        setSize(1100, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        setupUI();

        controller = new ChatController(this);

        // koppel input → controller
        inputPanel.setOnSend(text -> controller.send(text));

        setVisible(true);

        // welkomst berichten
        addAssistantBubble("Welkom! Ik ben HU-B, jouw HR-assistent.");
        addAssistantBubble("Ik laad nu de personeelsgids. Een moment geduld...");

        controller.startKnowledgeLoading();
    }

    // bouwt het scherm
    private void setupUI() {
        setLayout(new BorderLayout());

        berichtenTonen = new BerichtenTonen();
        inputPanel = new InputPanel();

        add(berichtenTonen.getScrollPane(), BorderLayout.CENTER);
        add(inputPanel.getPanel(), BorderLayout.SOUTH);
    }

    // ===== UI acties =====

    public void addUserBubble(String text) {
        berichtenTonen.addBubble(text, true);
    }

    public void addAssistantBubble(String text) {
        berichtenTonen.addBubble(text, false);
    }

    public void setSendEnabled(boolean enabled) {
        inputPanel.setSendEnabled(enabled);
    }

    public void clearInput() {
        inputPanel.clearInput();
    }
}