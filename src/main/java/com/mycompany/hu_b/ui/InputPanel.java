package com.mycompany.hu_b.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

//Deze klasse is verantwoordelijjk voor het bouwen van de input panel/chatbox
//Waar de gebruiker zijn vraag kan stellen.
public class InputPanel {

    private JPanel panel;
    private JTextField inputField;
    private JButton sendButton;

    private Consumer<String> onSend;
    
    // Initialiseert het inputgedeelte van de UI.
    // Roept de setup aan om het invoerveld en de knop te bouwen.
    public InputPanel() {
        setup();
    }

    // Bouwt het inputpaneel met een tekstveld en verzendknop.
    // Koppelt acties zodat zowel de knop als de Enter-toets een bericht versturen.
    // Wordt alleen gebruikt tijdens initialisatie van deze class.
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

    // Verwerkt het versturen van een bericht.
    // Haalt de tekst op uit het invoerveld en stuurt deze door via de onSend callback.
    // Wordt aangeroepen wanneer de gebruiker op Enter drukt of op de verzendknop klikt.
    private void send() {
        if (onSend != null) {
            String text = inputField.getText().trim();
            if (!text.isEmpty()) {
                onSend.accept(text);
            }
        }
    }

    // Stelt in wat er moet gebeuren wanneer een bericht wordt verzonden.
    // Wordt gebruikt door AppVenster om de controller te koppelen aan dit inputpaneel.
    public void setOnSend(Consumer<String> onSend) {
        this.onSend = onSend;
    }

    // Zet de verzendknop aan of uit.
    // Wordt gebruikt om invoer te blokkeren (bijv. tijdens het laden van data).
    public void setSendEnabled(boolean enabled) {
        sendButton.setEnabled(enabled);
    }
    // Leegt het invoerveld.
    // Wordt aangeroepen nadat een bericht succesvol is verzonden.
    public void clearInput() {
        inputField.setText("");
    }
    
    // Geeft het volledige inputpaneel terug.
    // Wordt gebruikt door AppVenster om dit onderdeel in het hoofdvenster te plaatsen.
    public JPanel getPanel() {
        return panel;
    }
}