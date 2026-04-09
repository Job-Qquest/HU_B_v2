package com.mycompany.hu_b.ui;

import com.mycompany.hu_b.controller.ChatController;

import javax.swing.*;
import java.awt.*;

//Dit is de 'regiseur' van de hele User Interface.
public class AppVenster extends JFrame {

    private BerichtenTonen berichtenTonen;
    private InputPanel inputPanel;
    private ChatController controller;

    // Initialiseert het hoofdvenster van de chatbot.
    // Bouwt de UI, koppelt de controller en start het laden van de kennisbron (.pdf)
    public AppVenster() throws Exception {
        setTitle("HU-B – HR Chatbot");
        setSize(1100, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        setupUI();

        controller = new ChatController(this);

        // Verbindt input van de gebruiker met de controller (verstuurt vragen)
        inputPanel.setOnSend(text -> controller.send(text));

        setVisible(true);

        // Toont eerste berichten bij opstarten
        addAssistantBubble("Welkom! Ik ben HU-B, jouw HR-assistent.");
        addAssistantBubble("Ik laad nu de personeelsgids. Een moment geduld...");
        
        // Start laden van de kennisbron (PDF)
        controller.startKnowledgeLoading();
    }

    // Bouwt de layout van het scherm.
    // Plaatst het chatgedeelte in het midden en het inputgedeelte onderaan.
    private void setupUI() {
        setLayout(new BorderLayout());

        berichtenTonen = new BerichtenTonen();
        inputPanel = new InputPanel();

        add(berichtenTonen.getScrollPane(), BorderLayout.CENTER);
        add(inputPanel.getPanel(), BorderLayout.SOUTH);
    }

    // ===== UI acties =====
    // Toont een bericht van de gebruiker in het chatvenster.
    // Wordt aangeroepen door de controller wanneer de gebruiker een vraag stelt.
    public void addUserBubble(String text) {
        berichtenTonen.addBubble(text, true);
    }

    // Toont een antwoord van de chatbot in het chatvenster.
    // Wordt aangeroepen door de controller na het genereren van een antwoord.
    public void addAssistantBubble(String text) {
        berichtenTonen.addBubble(text, false);
    }

    // Stuurt een bericht naar 'inputPanel' om de verzendknop aan of uit te zetten.
    // Wordt gebruikt om input tijdelijk te blokkeren (bijv. tijdens laden van data).
    public void setSendEnabled(boolean enabled) {
        inputPanel.setSendEnabled(enabled);
    }

    //Stuurt een bericht naar 'inputPanel' om het invoerveld leeg te maken.
    public void clearInput() {
        inputPanel.clearInput();
    }
}