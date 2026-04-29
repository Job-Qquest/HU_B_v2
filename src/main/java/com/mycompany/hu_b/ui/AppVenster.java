package com.mycompany.hu_b.ui;

import com.mycompany.hu_b.controller.ChatController;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

//Dit is de 'regiseur' van de hele User Interface.
public class AppVenster extends JFrame {

    private static final int DEFAULT_REMEMBERED_MESSAGE_LIMIT = 20;
    private static final Color DARK_NAVY = new Color(0x091E38);
    private static final Color HEADER_BACKGROUND = Color.WHITE;
    private static final String LOGO_RESOURCE = "/com/mycompany/hu_b/ui/qquest-logo.png";

    private BerichtenTonen berichtenTonen;
    private InputPanel inputPanel;
    private ChatController controller;
    private JButton refreshButton;
    private ConfettiGlassPane confettiGlassPane;
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
        setRefreshEnabled(false);

        setVisible(true);

        // Toont eerste berichten bij opstarten
        addAssistantBubble("Welkom! Ik ben HU-B, jouw HR-assistent.", false);
        addAssistantBubble("Gebruikte bron: " + PERSONEELSGIDS_VERSIE, false);
        
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

        confettiGlassPane = new ConfettiGlassPane();
        setGlassPane(confettiGlassPane);

        JPanel headerPanel = buildHeaderPanel();
        berichtenTonen = new BerichtenTonen();
        inputPanel = new InputPanel();

        add(headerPanel, BorderLayout.NORTH);
        add(berichtenTonen.getScrollPane(), BorderLayout.CENTER);
        add(inputPanel.getPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(HEADER_BACKGROUND);
        headerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xD9E2F2)));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 12));
        leftPanel.setOpaque(false);

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

        leftPanel.add(logoLabel);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 12));
        rightPanel.setOpaque(false);

        refreshButton = new JButton("Update bronnen");
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> {
            if (controller != null) {
                controller.refreshKnowledge();
            }
        });
        rightPanel.add(refreshButton);

        headerPanel.add(leftPanel, BorderLayout.WEST);
        headerPanel.add(rightPanel, BorderLayout.EAST);
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

    public void replaceLastAssistantBubble(String text, boolean conversational) {
        berichtenTonen.replaceLastAssistantBubble(text, conversational, rememberedMessageLimit);
    }

    public void addAssistantBubbleAnimated(String text, boolean conversational) {
        berichtenTonen.addAnimatedAssistantBubble(text, conversational, rememberedMessageLimit);
    }

    // Laat een korte confetti-animatie zien voor een speelse "hoera"-trigger.
    public void playCelebration() {
        if (confettiGlassPane != null) {
            confettiGlassPane.play();
        }
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

    public void setRefreshEnabled(boolean enabled) {
        if (refreshButton != null) {
            refreshButton.setEnabled(enabled);
        }
    }

    private final class ConfettiGlassPane extends JComponent {

        private static final int PARTICLE_COUNT = 120;
        private static final int ANIMATION_DELAY_MS = 16;
        private static final int DURATION_MS = 1800;
        private static final int START_BAND_HEIGHT = 80;

        private final Random random = new Random();
        private final List<ConfettiParticle> particles = new ArrayList<>();
        private Timer timer;
        private long startedAt;

        private ConfettiGlassPane() {
            setOpaque(false);
            setVisible(false);
        }

        private void play() {
            if (getWidth() <= 0 || getHeight() <= 0) {
                setBounds(0, 0, AppVenster.this.getWidth(), AppVenster.this.getHeight());
            }

            particles.clear();
            startedAt = System.currentTimeMillis();

            int width = Math.max(1, getWidth());
            int height = Math.max(1, getHeight());
            int originY = Math.max(0, height / 6);
            int bandHeight = Math.min(START_BAND_HEIGHT, Math.max(20, height / 4));

            for (int i = 0; i < PARTICLE_COUNT; i++) {
                particles.add(createParticle(width, originY, bandHeight));
            }

            if (timer != null && timer.isRunning()) {
                timer.stop();
            }

            setVisible(true);
            timer = new Timer(ANIMATION_DELAY_MS, event -> tick());
            timer.start();
        }

        private ConfettiParticle createParticle(int width, int originY, int bandHeight) {
            Color[] palette = {
                    new Color(0xFF4D4D),
                    new Color(0xFFD93D),
                    new Color(0x3DDC97),
                    new Color(0x37C1F1),
                    new Color(0xFF9F1C),
                    new Color(0xB56BFF)
            };

            double size = 6 + random.nextDouble() * 10;
            double x = random.nextDouble() * width;
            double y = originY + random.nextDouble() * bandHeight;
            double vx = -3.5 + random.nextDouble() * 7.0;
            double vy = 1.5 + random.nextDouble() * 4.0;
            double spin = -0.2 + random.nextDouble() * 0.4;
            Color color = palette[random.nextInt(palette.length)];
            boolean round = random.nextBoolean();
            return new ConfettiParticle(x, y, vx, vy, spin, size, color, round);
        }

        private void tick() {
            long elapsed = System.currentTimeMillis() - startedAt;
            int width = Math.max(1, getWidth());
            int height = Math.max(1, getHeight());

            for (int i = 0; i < particles.size(); i++) {
                ConfettiParticle particle = particles.get(i);
                particle.x += particle.vx;
                particle.y += particle.vy;
                particle.vy += 0.12;
                particle.rotation += particle.spin;
                particle.life = Math.min(1.0, (double) elapsed / DURATION_MS);
            }

            particles.removeIf(particle ->
                    particle.life >= 1.0
                            || particle.y > height + 30
                            || particle.x < -40
                            || particle.x > width + 40);

            repaint();

            if (particles.isEmpty() || elapsed >= DURATION_MS) {
                stopAnimation();
            }
        }

        private void stopAnimation() {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            particles.clear();
            setVisible(false);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (particles.isEmpty()) {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                for (ConfettiParticle particle : particles) {
                    float alpha = (float) Math.max(0.0, 1.0 - particle.life);
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    g2.setColor(particle.color);

                    AffineTransform oldTransform = g2.getTransform();
                    g2.translate(particle.x, particle.y);
                    g2.rotate(particle.rotation);

                    double half = particle.size / 2.0;
                    Shape shape = particle.round
                            ? new RoundRectangle2D.Double(-half, -half, particle.size, particle.size * 0.7, 4, 4)
                            : new Rectangle((int) Math.round(-half), (int) Math.round(-half),
                                    (int) Math.max(3, Math.round(particle.size)),
                                    (int) Math.max(3, Math.round(particle.size * 0.7)));
                    g2.fill(shape);
                    g2.setTransform(oldTransform);
                }
            } finally {
                g2.dispose();
            }
        }

        private final class ConfettiParticle {
            private double x;
            private double y;
            private double vx;
            private double vy;
            private double rotation;
            private final double spin;
            private final double size;
            private final Color color;
            private final boolean round;
            private double life;

            private ConfettiParticle(double x, double y, double vx, double vy, double spin,
                                     double size, Color color, boolean round) {
                this.x = x;
                this.y = y;
                this.vx = vx;
                this.vy = vy;
                this.spin = spin;
                this.size = size;
                this.color = color;
                this.round = round;
            }
        }
    }
}
