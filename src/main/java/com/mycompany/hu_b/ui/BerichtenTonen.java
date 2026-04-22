package com.mycompany.hu_b.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

//Deze klasse is verantwoordelijk voor het bouwen van het bovenste gedeelte
//van de user interface. Alle chatbubbels.
public class BerichtenTonen {

    private static final Color USER_BUBBLE_COLOR = new Color(0x37C1F1);
    private static final Color ASSISTANT_BUBBLE_COLOR = new Color(0xFF3200);
    private static final Color OUT_OF_MEMORY_BUBBLE_COLOR = new Color(0x091E38);
    private static final Color DARK_NAVY = new Color(0x091E38);
    private static final Color WHITE = Color.WHITE;
    private static final Color BLACK = Color.BLACK;

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
        chatPanel.setOpaque(true);
        chatPanel.setBackground(DARK_NAVY);
        chatPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        scrollPane = new JScrollPane(chatPanel);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(DARK_NAVY);
        scrollPane.setOpaque(true);
        scrollPane.setBackground(DARK_NAVY);
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
                "<div style='font-family:Arial,sans-serif; font-size:13px; font-weight:bold; width:650px'>" +
                antwoord.replace("\n", "<br>") +
                "</div>" +
                "<div style='margin-top:20px; font-family:Arial,sans-serif; font-size:10px; color:#D9E2F2; text-align:left;'>" +
                disclaimer.replace("\n", "<br>") +
                "</div>" +
                "</html>";
        } else {
            htmlText =
                "<html>" +
                "<div style='font-family:Arial,sans-serif; font-size:13px; font-weight:bold; width:650px'>" +
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
            bubble.setForeground(DARK_NAVY);
        } else {
            bubble.setBackground(ASSISTANT_BUBBLE_COLOR);
            bubble.setForeground(WHITE);
        }

        bubble.setOpaque(true);
        bubble.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        bubble.addHyperlinkListener(event -> {
            if (event.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    openLink(event.getURL() != null ? event.getURL().toURI() : null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            } else if (event.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ENTERED) {
                bubble.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else if (event.getEventType() == javax.swing.event.HyperlinkEvent.EventType.EXITED) {
                bubble.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            }
        });

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
                applyBubbleColors(bubble, true);
                continue;
            }

            boolean remembered = conversationalIndex >= rememberedStartIndex;
            applyBubbleColors(bubble, remembered);
            conversationalIndex++;
        }
    }

    private Color baseColorFor(boolean user) {
        return user ? USER_BUBBLE_COLOR : ASSISTANT_BUBBLE_COLOR;
    }

    private void applyBubbleColors(MessageBubble bubble, boolean remembered) {
        JTextPane component = bubble.component();
        if (remembered) {
            component.setBackground(baseColorFor(bubble.user()));
            component.setForeground(bubble.user() ? DARK_NAVY : WHITE);
            return;
        }

        component.setBackground(OUT_OF_MEMORY_BUBBLE_COLOR);
        component.setForeground(WHITE);
    private void openLink(URI uri) {
        if (uri == null) {
            return;
        }

        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }

            Desktop desktop = Desktop.getDesktop();
            String scheme = uri.getScheme();
            if (scheme != null && scheme.equalsIgnoreCase("file")) {
                desktop.open(new File(uri));
            } else {
                desktop.browse(uri);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // Geeft de scrollbare container van het chatgedeelte terug.
    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    private record MessageBubble(JTextPane component, boolean user, boolean conversational) {
    }
}
