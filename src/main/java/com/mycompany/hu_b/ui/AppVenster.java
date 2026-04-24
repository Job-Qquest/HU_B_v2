package com.mycompany.hu_b.ui;

import com.mycompany.hu_b.controller.ChatController;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;

//Dit is de 'regiseur' van de hele User Interface.
public class AppVenster extends JFrame {

    private static final int DEFAULT_REMEMBERED_MESSAGE_LIMIT = 20;
    private static final Color DARK_NAVY = new Color(0x091E38);
    private static final Color HEADER_BACKGROUND = Color.WHITE;
    private static final String LOGO_RESOURCE = "/com/mycompany/hu_b/ui/qquest-logo.png";

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
                Object[] options = {"Ja", "Nee"};
                int choice = JOptionPane.showOptionDialog(
                        AppVenster.this,
                        "Weet je zeker dat je de chatbot wilt sluiten?\n"
                        + "Bij het afsluiten wordt de gespreksgeschiedenis gewist.",
                        "Chatbot Afsluiten",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        options,
                        options[0]);

                if (choice == 0) {
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

        JPanel headerPanel = buildHeaderPanel();
        berichtenTonen = new BerichtenTonen();
        inputPanel = new InputPanel();

        add(headerPanel, BorderLayout.NORTH);
        add(berichtenTonen.getScrollPane(), BorderLayout.CENTER);
        add(inputPanel.getPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildHeaderPanel() {
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 12));
        headerPanel.setBackground(HEADER_BACKGROUND);
        headerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xD9E2F2)));

        JLabel logoLabel = new JLabel();
        ImageIcon logoIcon = loadLogoIcon();
        if (logoIcon != null) {
            logoLabel.setIcon(logoIcon);
            logoLabel.setText("");
        } else {
            logoLabel.setText("Qquest");
            logoLabel.setFont(logoLabel.getFont().deriveFont(Font.BOLD, 24f));
            logoLabel.setForeground(DARK_NAVY);
        }

        headerPanel.add(logoLabel);
        return headerPanel;
    }

    private ImageIcon loadLogoIcon() {
        URL resource = AppVenster.class.getResource(LOGO_RESOURCE);
        if (resource == null) {
            return null;
        }
        return new ImageIcon(resource);
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

    public void addAssistantBubbleAnimated(String text, boolean conversational) {
        berichtenTonen.addAnimatedAssistantBubble(text, conversational, rememberedMessageLimit);
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
