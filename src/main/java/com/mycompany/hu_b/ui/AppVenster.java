package com.mycompany.hu_b.ui;

import com.mycompany.hu_b.controller.ChatController;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

//Dit is de 'regiseur' van de hele User Interface.
public class AppVenster extends JFrame {

    private static final int DEFAULT_REMEMBERED_MESSAGE_LIMIT = 20;
    private static final Color DARK_NAVY = new Color(0x091E38);

    private BerichtenTonen berichtenTonen;
    private InputPanel inputPanel;
    private ChatController controller;
    private int rememberedMessageLimit = DEFAULT_REMEMBERED_MESSAGE_LIMIT;
     private static final String PERSONEELSGIDS_VERSIE =
            "Personeelsgids BU Talentclass versie 2024.1 en gelinkte bronnen"
            + "Disclaimer: De informatie die HU-B geeft is mogelijk niet volledig of niet actueel. De informatie die gegeven is, is niet juridisch bindend. Raadpleeg bij twijfel altijd HR.";

    // Initialiseert het hoofdvenster van de chatbot.
    // Bouwt de UI, koppelt de controller en start het laden van de kennisbron (.pdf)
    public AppVenster() throws Exception {
        setTitle("HU-B – HR Chatbot");
        setSize(1100, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        setupUI();
        setupCloseConfirmation();

        controller = new ChatController(this);

        // Verbindt input van de gebruiker met de controller (verstuurt vragen)
        inputPanel.setOnSend(text -> controller.send(text));

        setVisible(true);

        // Toont eerste berichten bij opstarten
        addAssistantBubble("Welkom! Ik ben HU-B, jouw HR-assistent.", false);
        addAssistantBubble("Gebruikte bron: " + PERSONEELSGIDS_VERSIE, false);
        addAssistantBubble("Ik laad nu de personeelsgids. Een moment geduld...", false);
        
        // Start laden van de kennisbron (PDF)
        controller.startKnowledgeLoading();
    }

    private void setupCloseConfirmation() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int choice = JOptionPane.showConfirmDialog(
                        AppVenster.this,
                        "Weet je zeker dat je de chatbot wilt sluiten?\n"
                        + "Bij het afsluiten wordt de gespreksgeschiedenis gewist.",
                        "Chatbot Afsluiten",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);

                if (choice == JOptionPane.YES_OPTION) {
                    dispose();
                }
            }
        });
    }

    // Bouwt de layout van het scherm.
    // Plaatst het chatgedeelte in het midden en het inputgedeelte onderaan.
    private void setupUI() {
        setLayout(new BorderLayout());
        getContentPane().setBackground(DARK_NAVY);

        berichtenTonen = new BerichtenTonen();
        inputPanel = new InputPanel();

        add(berichtenTonen.getScrollPane(), BorderLayout.CENTER);
        add(inputPanel.getPanel(), BorderLayout.SOUTH);
    }

    // ===== UI acties =====
    // Toont een bericht van de gebruiker in het chatvenster.
    // Wordt aangeroepen door de controller wanneer de gebruiker een vraag stelt.
    public void addUserBubble(String text) {
        addUserBubble(text, true);
    }

    public void addUserBubble(String text, boolean conversational) {
        berichtenTonen.addBubble(text, true, conversational, rememberedMessageLimit);
    }

    // Toont een antwoord van de chatbot in het chatvenster.
    // Wordt aangeroepen door de controller na het genereren van een antwoord.
    public void addAssistantBubble(String text) {
        addAssistantBubble(text, true);
    }

    public void addAssistantBubble(String text, boolean conversational) {
        berichtenTonen.addBubble(text, false, conversational, rememberedMessageLimit);
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

    public void setRememberedMessageLimit(int rememberedMessageLimit) {
        this.rememberedMessageLimit = Math.max(0, rememberedMessageLimit);
    }
}
