package com.mycompany.hu_b.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

// Deze klasse is verantwoordelijk voor het bouwen van het bovenste gedeelte
// van de user interface. Alle chatbubbels.
public class BerichtenTonen {

    private static final Color USER_BUBBLE_COLOR = new Color(0x37C1F1);
    private static final Color ASSISTANT_BUBBLE_COLOR = new Color(0xFF3200);
    private static final Color OUT_OF_MEMORY_BUBBLE_COLOR = new Color(0x091E38);
    private static final Color DARK_NAVY = new Color(0x091E38);
    private static final Color WHITE = Color.WHITE;
    private static final int TYPEWRITER_DELAY_MS = 10;
    private static final int TYPEWRITER_PUNCTUATION_PAUSE_MS = 45;
    private static final int TYPEWRITER_WORD_PAUSE_MS = 18;

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
        addBubble(text, user, conversational, rememberedMessageLimit, false);
    }

    // Toont een assistentbericht met een typewriter-effect.
    public void addAnimatedAssistantBubble(String text, boolean conversational, int rememberedMessageLimit) {
        addBubble(text, false, conversational, rememberedMessageLimit, true);
    }

    private void addBubble(String text, boolean user, boolean conversational, int rememberedMessageLimit, boolean animated) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        String antwoord = text == null ? "" : text;
        String disclaimer = "";

        if (!user && antwoord.contains("Disclaimer:")) {
            int index = antwoord.indexOf("Disclaimer:");
            disclaimer = antwoord.substring(index).trim();
            antwoord = antwoord.substring(0, index).trim();
        }

        boolean hasHtmlLinks = !user && containsHtmlMarkup(antwoord);
        String animationText = hasHtmlLinks ? stripHtmlTags(antwoord) : antwoord;

        JTextPane bubble = new JTextPane();
        bubble.setContentType("text/html");
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

        if (user || !animated) {
            bubble.setText(buildHtmlText(user, antwoord, disclaimer, true));
        } else {
            startTypewriterAnimation(bubble, animationText, antwoord, disclaimer, hasHtmlLinks);
        }

        updateRememberedHighlights(rememberedMessageLimit);
        chatPanel.revalidate();
        chatPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void startTypewriterAnimation(JTextPane bubble, String animationText, String rawAnswer, String disclaimer, boolean renderRawHtmlAtEnd) {
        String safeAnswer = animationText == null ? "" : animationText;
        String safeRawAnswer = rawAnswer == null ? "" : rawAnswer;
        String safeDisclaimer = disclaimer == null ? "" : disclaimer;
        char[] characters = safeAnswer.toCharArray();

        bubble.setText(buildHtmlText(false, "", safeDisclaimer, false));

        if (characters.length == 0) {
            bubble.setText(buildHtmlText(false, safeRawAnswer, safeDisclaimer, renderRawHtmlAtEnd));
            return;
        }

        final int[] index = {0};
        Timer timer = new Timer(TYPEWRITER_DELAY_MS, null);
        timer.addActionListener(event -> {
            if (!bubble.isDisplayable()) {
                timer.stop();
                return;
            }

            index[0] = Math.min(index[0] + 1, characters.length);
            String partialAnswer = safeAnswer.substring(0, index[0]);
            bubble.setText(buildHtmlText(false, partialAnswer, safeDisclaimer, false));
            bubble.setCaretPosition(0);
            scrollToBottom();

            if (index[0] >= characters.length) {
                bubble.setText(buildHtmlText(false, safeRawAnswer, safeDisclaimer, renderRawHtmlAtEnd));
                timer.stop();
                return;
            }

            char current = characters[index[0] - 1];
            if (Character.isWhitespace(current)) {
                timer.setDelay(TYPEWRITER_WORD_PAUSE_MS);
            } else if (isPunctuation(current)) {
                timer.setDelay(TYPEWRITER_PUNCTUATION_PAUSE_MS);
            } else {
                timer.setDelay(TYPEWRITER_DELAY_MS);
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    private boolean isPunctuation(char c) {
        return ".,;:!?".indexOf(c) >= 0;
    }

    private String buildHtmlText(boolean user, String answer, String disclaimer, boolean preserveHtmlInAnswer) {
        String safeAnswer = renderAnswer(answer, preserveHtmlInAnswer);
        String safeDisclaimer = escapeHtml(disclaimer == null ? "" : disclaimer).replace("\n", "<br>");

        if (!user && !safeDisclaimer.isEmpty()) {
            return "<html>"
                    + "<div style='font-family:Arial,sans-serif; font-size:13px; font-weight:bold; width:650px'>"
                    + safeAnswer
                    + "</div>"
                    + "<div style='margin-top:20px; font-family:Arial,sans-serif; font-size:10px; color:#D9E2F2; text-align:left;'>"
                    + safeDisclaimer
                    + "</div>"
                    + "</html>";
        }

        return "<html>"
                + "<div style='font-family:Arial,sans-serif; font-size:13px; font-weight:bold; width:650px'>"
                + safeAnswer
                + "</div>"
                + "</html>";
    }

    private String renderAnswer(String answer, boolean preserveHtmlInAnswer) {
        String safeAnswer = answer == null ? "" : answer;
        if (preserveHtmlInAnswer) {
            return safeAnswer.replace("\n", "<br>");
        }

        return escapeHtml(safeAnswer).replace("\n", "<br>");
    }

    private boolean containsHtmlMarkup(String text) {
        if (text == null) {
            return false;
        }

        return text.contains("<a ")
                || text.contains("<a href=")
                || text.contains("</a>")
                || text.contains("<br")
                || text.contains("<html")
                || text.contains("<div")
                || text.contains("<span")
                || text.contains("<p");
    }

    private String stripHtmlTags(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text.replaceAll("<[^>]+>", "");
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void scrollToBottom() {
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
    }

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
