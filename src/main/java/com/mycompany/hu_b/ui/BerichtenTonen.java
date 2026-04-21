package com.mycompany.hu_b.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

//Deze klasse is verantwoordelijk voor het bouwen van het bovenste gedeelte
//van de user interface. Alle chatbubbels.
public class BerichtenTonen {

    private static final Color USER_BUBBLE_COLOR = new Color(55, 192, 241);
    private static final Color ASSISTANT_BUBBLE_COLOR = new Color(255, 255, 255, 235);
    private static final Color REMEMBERED_BUBBLE_COLOR = new Color(0xFF3200);

    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private final List<MessageBubble> bubbles = new ArrayList<>();

    // Initialiseert het onderdeel dat alle chatberichten toont.
    // Roept direct de setup van het chatgedeelte aan.
    public BerichtenTonen() {
        setup();
    }

    // Bouwt het chatgedeelte van de interface.
    // Maakt het panel voor de berichten en de scrollbare container daaromheen.
    // Wordt alleen gebruikt bij het initialiseren van deze class.
    private void setup() {
        chatPanel = new JPanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        chatPanel.setOpaque(false);
        chatPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        scrollPane = new JScrollPane(chatPanel);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    }

    // Voegt één chatbericht toe aan het scherm als bubble van gebruiker of assistent.
    // Verwerkt ook een eventuele disclaimer apart in de opmaak.
    // Wordt aangeroepen vanuit AppVenster om berichten zichtbaar te maken in de UI.
    public void addBubble(String text, boolean user, boolean conversational, int rememberedMessageLimit) {
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
                "<div style='font-family:Segoe UI; font-size:13px; width:650px'>" +
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
            bubble.setBackground(USER_BUBBLE_COLOR);
            bubble.setForeground(Color.WHITE);
        } else {
            bubble.setBackground(ASSISTANT_BUBBLE_COLOR);
            bubble.setForeground(Color.BLACK);
        }

        bubble.setOpaque(true);

        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);
        container.setBorder(new EmptyBorder(8, 40, 8, 40));
        container.add(bubble, BorderLayout.CENTER);

        row.add(container, BorderLayout.CENTER);
        chatPanel.add(row);
        bubbles.add(new MessageBubble(bubble, user, conversational));
        updateRememberedHighlights(rememberedMessageLimit);
        chatPanel.revalidate();
        chatPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void updateRememberedHighlights(int rememberedMessageLimit) {
        int conversationalCount = 0;
        for (MessageBubble bubble : bubbles) {
            if (bubble.conversational()) {
                conversationalCount++;
            }
        }

        int rememberedStartIndex = Math.max(0, conversationalCount - Math.max(0, rememberedMessageLimit));
        int conversationalIndex = 0;

        for (MessageBubble bubble : bubbles) {
            if (!bubble.conversational()) {
                bubble.component().setBackground(baseColorFor(bubble.user()));
                continue;
            }

            boolean remembered = conversationalIndex >= rememberedStartIndex;
            bubble.component().setBackground(remembered
                    ? REMEMBERED_BUBBLE_COLOR
                    : baseColorFor(bubble.user()));
            conversationalIndex++;
        }
    }

    private Color baseColorFor(boolean user) {
        return user ? USER_BUBBLE_COLOR : ASSISTANT_BUBBLE_COLOR;
    }

    // Geeft de scrollbare container van het chatgedeelte terug.
    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    private record MessageBubble(JTextPane component, boolean user, boolean conversational) {
    }
}
