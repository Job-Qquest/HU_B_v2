package com.mycompany.hu_b;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.*;
import okhttp3.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static javax.swing.WindowConstants.EXIT_ON_CLOSE;

public class HU_B extends JFrame {

    // ==============================
    // CONFIGURATIE GIJS
    // ==============================

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();  

    private static final String PERSONEELSGIDS_VERSIE =
            "Personeelsgids BU Talentclass versie 2024.1"
            + "Disclaimer: De informatie die HU-B geeft is mogelijk niet volledig of niet actueel. De informatie die gegeven is, is niet juridisch bindend. Raadpleeg bij twijfel altijd HR.";

    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private JButton sendButton;
    private Image backgroundImage;
        private volatile boolean knowledgeReady = false;

    private final List<JSONObject> conversationHistory = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private static final Map<String, List<String>> FUNCTION_PROFILES = createFunctionProfiles();
        
    // ==============================
    // DATASTRUCTUUR
    // ==============================

    static class Chunk {
        String text;
        List<Double> embedding;
        int page;
        Set<String> functionScope;
        
        // Slaat per tekstdeel de broninhoud, embedding-vector en paginaverwijzing op.
        Chunk(String text, List<Double> embedding, int page, Set<String> functionScope) {
            this.text = text;
            this.embedding = embedding;
            this.page = page;
            this.functionScope = functionScope == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(functionScope);            
        }
    }
    
    private static Map<String, List<String>> createFunctionProfiles() {
        Map<String, List<String>> profiles = new LinkedHashMap<>();
        profiles.put("Talentclass Consultant", Arrays.asList(
                "talentclass", "talent class", "tc consultant", "tc-consultant", "tc consultants"
        ));
        profiles.put("Accountmanager", Arrays.asList(
                "accountmanager"
        ));
        profiles.put("Recruiter", Arrays.asList(
                "recruiter", "corporate recruiter", "stage", "werkstudent"
        ));
        profiles.put("Fieldmanager TC", Arrays.asList(
                "fieldmanager", "fieldmanager TC"
        ));
        profiles.put("TC coördinator", Arrays.asList(
                "TC coördinator", "coördinator"
        ));
        profiles.put("Business unit manager", Arrays.asList(
                "business unit manager", "BU manager"
        ));
        return profiles;
    }
    

    // ==============================
    // CONSTRUCTOR
    // ==============================

    // Initialiseert het hoofdvenster, laadt de gidsdata en zet de chatinterface op.
    public HU_B() throws Exception {

        setTitle("HU-B – HR Chatbot");
        setSize(1100, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        backgroundImage = new ImageIcon("qquestlogoHoe gaa.png").getImage();

        
        
        setupChatPanel();
        setupInputPanel();

        setVisible(true);

        addBubble("Welkom! Ik ben HU-B, jouw HR-assistent.", false);
        addBubble("Gebruikte bron: " + PERSONEELSGIDS_VERSIE, false);
        addBubble("Ik laad nu de personeelsgids. Een moment geduld...", false);

        startKnowledgeLoading();
    }

    // ==============================
    // UI
    // ==============================

    // Bouwt het scrollbare chatgedeelte op en tekent de achtergrondafbeelding.
    private void setupChatPanel() {

        chatPanel = new JPanel() {
            // Tekent de achtergrond telkens wanneer het paneel opnieuw wordt gerenderd.
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

    // Bouwt het invoerveld met verzendknop en koppelt beide aan dezelfde verzendactie.
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

        sendButton.addActionListener(e -> send());
        inputField.addActionListener(e -> send());
    }

    // ==============================
    // CHAT
    // ==============================

    // Leest de gebruikersvraag, toont die in de UI en haalt een antwoord op.
    private void send() {

        String question = inputField.getText().trim();
        if (question.isEmpty()) return;

        if (!knowledgeReady) {
            addBubble("De gids is nog niet klaar met laden. Probeer het zo opnieuw.", false);
            return;
        }

        addBubble(question, true);
        inputField.setText("");

        new Thread(() -> {
            try {

                String answer = ask(question);

                if (answer == null || answer.trim().isEmpty()) {
                    answer = "Sorry, ik kon geen antwoord genereren.";
                }

                String finalAnswer = answer;

                SwingUtilities.invokeLater(() ->
                        addBubble(finalAnswer, false));

            } catch (Exception ex) {

                ex.printStackTrace();

                String msg = ex.getMessage() == null
                        ? "Onbekende fout (check console)"
                        : ex.getMessage();

                if (isTimeoutException(ex)) {
                    msg = "timeout bij het ophalen van een antwoord van de AI-service. Probeer het opnieuw.";
                }
                
                String finalMsg = msg;
                SwingUtilities.invokeLater(() ->
                        {
                            addBubble("Er ging iets mis: " + finalMsg, false);
                });
            }
        }).start();
    }

    private boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
   private void startKnowledgeLoading() {
        new Thread(() -> {
            try {
                if (API_KEY == null || API_KEY.isBlank()) {
                    throw new IllegalStateException("OPENAI_API_KEY ontbreekt. Voeg deze omgevingsvariabele toe.");
                }

                loadGuide();
                knowledgeReady = true;

                SwingUtilities.invokeLater(() -> {
                    sendButton.setEnabled(true);
                    addBubble("De personeelsgids is geladen. Je kunt nu vragen stellen.", false);
                });

            } catch (Exception ex) {
                ex.printStackTrace();

                SwingUtilities.invokeLater(() -> {
                    addBubble("Opstartfout: " + ex.getMessage(), false);
                    addBubble("Tip: controleer OPENAI_API_KEY en je internetverbinding.", false);
                });
            }
        }).start();
    }

    // Voegt een chatballon toe voor gebruiker of assistent en scrolt naar beneden.
    private void addBubble(String text, boolean user) {

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        // ===== Tekst splitsen in antwoord en disclaimer =====
String antwoord = text;
String disclaimer = "";

if (!user && text.contains("Disclaimer:")) {
    int index = text.indexOf("Disclaimer:");
    antwoord = text.substring(0, index).trim();
    disclaimer = text.substring(index).trim();
}

// ===== HTML layout maken =====
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

// ⭐ BELANGRIJK — zorgt dat tekst wrapt binnen venster
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

    // ==============================
    // PDF + EMBEDDINGS
    // ==============================

    // Leest de PDF per pagina uit, splitst tekst in chunks en maakt embeddings voor zoekwerk.
    private void loadGuide() throws Exception {

        PDDocument doc = Loader.loadPDF(new File("personeelsgids.pdf"));
        PDFTextStripper stripper = new PDFTextStripper();
        Set<String> activeFunctionScope = new LinkedHashSet<>();
        
        for (int page = 1; page <= doc.getNumberOfPages(); page++) {

            stripper.setStartPage(page);
            stripper.setEndPage(page);

            String pageText = stripper.getText(doc);          
            List<ChunkDraft> drafts = chunkTextWithFunctionScope(pageText, 800, activeFunctionScope);

            for (ChunkDraft draft : drafts) {
                chunks.add(new Chunk(draft.text, embed(draft.text), page, draft.functionScope));
            }            
        }

        doc.close();

        if (chunks.isEmpty())
            throw new RuntimeException("Geen tekst uit personeelsgids geladen.");
    }

    static class ChunkDraft {
        String text;
        Set<String> functionScope;

        ChunkDraft(String text, Set<String> functionScope) {
            this.text = text;
            this.functionScope = functionScope == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(functionScope);
        }
    }    

// Splitst lange tekst op in woordblokken van vaste grootte voor verwerking.
    private static List<String> chunkText(String text, int size) {

        List<String> result = new ArrayList<>();
        String[] words = text.split("\\s+");

        for (int i = 0; i < words.length; i += size) {
            result.add(String.join(" ",
                    Arrays.copyOfRange(words, i, Math.min(words.length, i + size))));
        }

        return result;
    }

    private List<ChunkDraft> chunkTextWithFunctionScope(String text, int maxWords, Set<String> activeScope) {
        List<ChunkDraft> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        StringBuilder buffer = new StringBuilder();
        int bufferWords = 0;
        Set<String> bufferScope = new LinkedHashSet<>(activeScope);

        String[] lines = text.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Set<String> headerLabels = detectFunctionHeaderLabels(line);
            if (!headerLabels.isEmpty()) {
                if (buffer.length() > 0) {
                    result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
                    buffer.setLength(0);
                    bufferWords = 0;
                }

                activeScope.clear();
                activeScope.addAll(headerLabels);
                bufferScope = new LinkedHashSet<>(activeScope);
                continue;
            }

            int lineWords = countWords(line);
            if (lineWords == 0) {
                continue;
            }

            if (bufferWords + lineWords > maxWords && buffer.length() > 0) {
                result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
                buffer.setLength(0);
                bufferWords = 0;
                bufferScope = new LinkedHashSet<>(activeScope);
            }

            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(line);
            bufferWords += lineWords;
        }

        if (buffer.length() > 0) {
            result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
        }

        return result;
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private Set<String> detectFunctionHeaderLabels(String line) {
        Set<String> labels = new LinkedHashSet<>();
        if (line == null || line.isBlank()) {
            return labels;
        }

        String normalizedLine = line.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();

        if (normalizedLine.length() > 90) {
            return labels;
        }

        for (Map.Entry<String, List<String>> profile : FUNCTION_PROFILES.entrySet()) {
            for (String keyword : profile.getValue()) {
                String normalizedKeyword = keyword.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                        .trim();
                if (normalizedKeyword.isEmpty()) {
                    continue;
                }

                if (normalizedLine.equals(normalizedKeyword)
                        || normalizedLine.startsWith(normalizedKeyword + " ")
                        || normalizedLine.endsWith(" " + normalizedKeyword)
                        || normalizedLine.contains(" " + normalizedKeyword + " ")) {
                    labels.add(profile.getKey());
                    break;
                }
            }
        }

        return labels;
    }

    private static Response executeWithRetries(Request request, String operationName) throws Exception {
        final int maxAttempts = 3;
        long waitMs = 1200;

        IOException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Response response = CLIENT.newCall(request).execute();

                if (response.isSuccessful()) {
                    return response;
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                int statusCode = response.code();
                response.close();

                boolean retryableStatus = statusCode == 408 || statusCode == 429 || statusCode >= 500;
                if (!retryableStatus || attempt == maxAttempts) {
                    String suffix = responseBody == null || responseBody.isBlank()
                            ? ""
                            : " - " + responseBody;
                    throw new RuntimeException(operationName + " API error: " + statusCode + suffix);
                }
            } catch (SocketTimeoutException e) {
                lastException = e;
                if (attempt == maxAttempts) {
                    throw new RuntimeException(operationName + " timeout na meerdere pogingen.", e);
                }
            } catch (IOException e) {
                lastException = e;
                if (attempt == maxAttempts) {
                    throw new RuntimeException(operationName + " netwerkfout na meerdere pogingen.", e);
                }
            }

            Thread.sleep(waitMs);
            waitMs *= 2;
        }

        if (lastException != null) {
            throw new RuntimeException(operationName + " fout bij uitvoeren van request.", lastException);
        }

        throw new RuntimeException(operationName + " kon niet worden uitgevoerd.");
    }
    
    // Roept de embedding-API aan en retourneert de numerieke vector van de invoertekst.
    private static List<Double> embed(String input) throws Exception {

        JSONObject body = new JSONObject()
                .put("model", "text-embedding-3-small")
                .put("input", input);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/embeddings")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = executeWithRetries(request, "Embedding")) {        

            JSONObject json = new JSONObject(response.body().string());

            JSONArray arr = json.getJSONArray("data")
                    .getJSONObject(0)
                    .getJSONArray("embedding");

            List<Double> vector = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++)
                vector.add(arr.getDouble(i));

            return vector;
        }
    }

    // Berekent de cosine similarity tussen twee vectors om inhoudelijke overeenkomst te meten.
    private double cosine(List<Double> a, List<Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // Zoekt de meest relevante chunks voor de vraag op basis van embedding-similarity.
    private List<Chunk> search(String query) throws Exception {

        // Hybride retrieval: combineer semantische embedding-score met lexicale overlap-score
         String retrievalQuery = buildRetrievalQuery(query);
        List<Double> qVec = embed(retrievalQuery);

        // Maak lijst van (chunk + similarity score)
        List<Map.Entry<Chunk, Double>> scoredChunks = new ArrayList<>();

        for (Chunk c : chunks) {
            double semanticScore = cosine(c.embedding, qVec);
            double lexicalScore = lexicalSimilarity(retrievalQuery, c.text);
            double score = (semanticScore * 0.80) + (lexicalScore * 0.20);
            scoredChunks.add(Map.entry(c, score));
        }

        // Sorteer op hoogste similarity eerst
        scoredChunks.sort((a, b)
                -> Double.compare(b.getValue(), a.getValue()));

         // Houd relevante context als primaire bron, maar vul aan als er te weinig matches zijn.
        double MIN_SIMILARITY = 0.3;
        int MAX_RESULTS = 8;

        List<Chunk> results = new ArrayList<>();
        Set<Chunk> added = new HashSet<>();
        
        boolean talentclassVraag = isTalentclassQuestion(query);
        boolean referralVraag = isReferralQuestion(query);
        Set<String> functionLabels = detectFunctionLabels(query);
        
        // 1) Eerst alleen voldoende relevante chunks.
        for (Map.Entry<Chunk, Double> entry : scoredChunks) {
            if (entry.getValue() < MIN_SIMILARITY) {
                break;
            }

            Chunk candidate = entry.getKey();
          
            if (!functionLabels.isEmpty() && !matchesFunctionScope(candidate, functionLabels)) {
                continue;
            }
            
          if (talentclassVraag && !referralVraag && !isTalentclassChunk(candidate)) {
                continue;
            }


            if (candidate.text != null && !candidate.text.isBlank() && added.add(candidate)) {
                results.add(candidate);
            }

            if (results.size() >= MAX_RESULTS) {
                return results;
            }
        }

        // 2) Fallback: als er te weinig relevante chunks zijn, vul aan met best scorende overigen.
        // Bij Talentclass-vragen vullen we alleen aan met chunks die Talentclass expliciet noemen.
        for (Map.Entry<Chunk, Double> entry : scoredChunks) {
            Chunk candidate = entry.getKey();
            
            if (!functionLabels.isEmpty() && !matchesFunctionScope(candidate, functionLabels)) {
                continue;
            }

            if (talentclassVraag && !referralVraag && !isTalentclassChunk(candidate)) {
                continue;
            }

            if (candidate.text != null && !candidate.text.isBlank() && added.add(candidate)) {
                results.add(candidate);
            }

            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }

        return results;
    }
    private String buildRetrievalQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String normalized = query.toLowerCase(Locale.ROOT);
        StringBuilder enriched = new StringBuilder(query.trim());

        if (normalized.contains("declaratie") || normalized.contains("declareren")) {
            enriched.append(" declareren onkosten onkostendeclaratie declaratie indienen bonnetjes terugbetaling expense claim");
        }

        if (normalized.contains("ziekmeld") || normalized.contains("verzuim")) {
            enriched.append(" ziekmelding langdurig verzuim herstelmelding arbodienst");
        }

        return enriched.toString();
    }
    private Set<String> detectFunctionLabels(String text) {
        Set<String> labels = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return labels;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> profile : FUNCTION_PROFILES.entrySet()) {
            for (String keyword : profile.getValue()) {
                if (normalized.contains(keyword)) {
                    labels.add(profile.getKey());
                    break;
                }
            }
        }

        return labels;
    }

    private boolean matchesFunctionScope(Chunk chunk, Set<String> requiredLabels) {
        if (chunk == null || chunk.text == null || requiredLabels == null || requiredLabels.isEmpty()) {
            return true;
        }

        if (chunk.functionScope != null && !chunk.functionScope.isEmpty()) {
            for (String label : requiredLabels) {
                if (chunk.functionScope.contains(label)) {
                    return true;
                }
            }
            return false;
        }
        
        Set<String> chunkLabels = detectFunctionLabels(chunk.text);
        if (chunkLabels.isEmpty()) {
            return true;
        }

        for (String label : requiredLabels) {
            if (chunkLabels.contains(label)) {
                return true;
            }
        }

        return false;
    }
    
    private double lexicalSimilarity(String query, String chunkText) {
        if (query == null || chunkText == null || query.isBlank() || chunkText.isBlank()) {
            return 0.0;
        }

        Set<String> queryTokens = tokenize(query);
        Set<String> chunkTokens = tokenize(chunkText);

        if (queryTokens.isEmpty() || chunkTokens.isEmpty()) {
            return 0.0;
        }

        int overlap = 0;
        for (String token : queryTokens) {
            if (chunkTokens.contains(token)) {
                overlap++;
            }
        }

        return (double) overlap / (double) queryTokens.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();

        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();

        if (normalized.isEmpty()) {
            return tokens;
        }

        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }

        return tokens;
    }
 private boolean isTalentclassQuestion(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("talentclass")
                || normalized.contains("tc consultant")
                || normalized.contains("tc-consultant");
    }

    private boolean isTalentclassChunk(Chunk chunk) {
        if (chunk == null || chunk.text == null) {
            return false;
        }

        String normalized = chunk.text.toLowerCase(Locale.ROOT);
        return normalized.contains("talentclass")
                || normalized.contains("talent class")
                || normalized.contains("tc consultant")
                || normalized.contains("tc-consultant")
                || normalized.contains("tc consultants")
                || normalized.contains("tc-consultants");
    }


    private boolean isReferralQuestion(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("referral")
                || normalized.contains("voordraag")
                || normalized.contains("voordragen")
                || normalized.contains("aandraag")
                || normalized.contains("aandragen")
                || normalized.contains("iemand aanbreng")
                || normalized.contains("iemand voordraag");
    }

    private Optional<String> buildVerzuimDurationAnswer(String question, List<Chunk> contextChunks) {    
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        boolean isVerzuimQuestion = normalized.contains("verzuim")
                || normalized.contains("ziek")
                || normalized.contains("ziekmelding");

        if (!isVerzuimQuestion || (!normalized.contains("dagen") && !normalized.contains("weken"))) {
            return Optional.empty();
        }

        Matcher daysMatcher = Pattern.compile("(\\d+)\\s*dagen?").matcher(normalized);
        Matcher weeksMatcher = Pattern.compile("(\\d+)\\s*weken?").matcher(normalized);

        Integer totalDays = null;
        if (daysMatcher.find()) {
            totalDays = Integer.parseInt(daysMatcher.group(1));
        } else if (weeksMatcher.find()) {
            totalDays = Integer.parseInt(weeksMatcher.group(1)) * 7;
        }

        if (totalDays == null) {
            return Optional.empty();
        }

        Integer sourcePage = null;
        for (Chunk chunk : contextChunks) {
            if (chunk == null || chunk.text == null) {
                continue;
            }

            String chunkText = chunk.text.toLowerCase(Locale.ROOT);
            if (chunkText.contains("langdurig verzuim")
                    && (chunkText.contains("meer dan twee weken") || chunkText.contains("langer dan twee weken"))) {
                sourcePage = chunk.page;
                break;
            }
        }

        boolean langdurigVerzuim = totalDays > 14;
        String bron = sourcePage == null ? "N.v.t." : "PAGINA " + sourcePage;

        if (langdurigVerzuim) {
            return Optional.of(
                    "Antwoord: Ja, als je langer dan twee weken ziek bent, val je onder langdurig verzuim.\n"
                            + "Bron: " + bron
            );
        }

        return Optional.of(
                "Antwoord: Nee, bij " + totalDays + " dagen ziekte val je nog niet onder langdurig verzuim, omdat dat pas geldt bij meer dan twee weken. Je moet je wel ziek melden volgens de procedures.\n"
                        + "Bron: " + bron
        );
    }   
    private boolean isSalaryQuestion(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("salaris")
                || normalized.contains("loon")
                || normalized.contains("loonstrook")
                || normalized.contains("uitbetaling")
                || normalized.contains("toeslag")
                || normalized.contains("vakantietoeslag")
                || normalized.contains("vakantiegeld")
                || normalized.contains("eindejaarsuitkering")
                || normalized.contains("bonus")
                || normalized.contains("declaratie")
                || normalized.contains("inhouding");
    }

    // ==============================
    // OPENAI CHAT
    // ==============================

    // Stelt context en prompt samen, vraagt de chat-API om antwoord en bewaart conversatiehistorie.
    private String ask(String question) throws Exception {

        List<Chunk> topChunks = search(question);
        Map<Integer, Chunk> sourceById = new LinkedHashMap<>(); 

        StringBuilder contextText = new StringBuilder();
        int sourceId = 1;
        for (Chunk c : topChunks) {
                Set<String> chunkFunctions = (c.functionScope == null || c.functionScope.isEmpty())
                    ? detectFunctionLabels(c.text)
                    : c.functionScope;       
            String functionMarker = chunkFunctions.isEmpty()
                    ? "ALGEMEEN"
                    : String.join(", ", chunkFunctions);            
            sourceById.put(sourceId, c);
            contextText.append("BRON ")
                    .append(sourceId)
                    .append(" | PAGINA ")
                    .append(c.page)
                    .append(" | FUNCTIEAFHANKELIJK: ")
                    .append(functionMarker)
                    .append(": ")
                    .append(c.text)
                    .append("\n\n");
            sourceId++;
        }

        String contextString = contextText.toString();
        Optional<String> verzuimDurationAnswer = buildVerzuimDurationAnswer(question, topChunks);
        if (verzuimDurationAnswer.isPresent()) {
            return verzuimDurationAnswer.get();
        }

        // 👉 JOUW PROMPT EXACT
        String systemPrompt =

"# ROLE " +
"Je bent HU-B, gedraag je zoals iemand die 20 jaar HR ervaring heeft en die vragen beantwoord op basis van de personeelsgids." +
                
"# DOEL " +
"Verstrek accurate, feitelijke informatie over het gevraagde HR-onderwerp op basis van de verstrekte PERSONEELSGIDS. " +

"# CONSTRAINTS (STRIKTE REGELS) " +
"1. Source Grounding: Gebruik ALLEEN de informatie tussen de <context> tags. " +
"Als het antwoord daar niet staat geef je aan wat je niet kan vinden." +
            //    + "\"Ik kan deze informatie niet terugvinden in de personeelsgids. Neem contact op met HR voor verdere ondersteuning.\" " +

"2. Scope: Behandel de vraag alleen binnen de HR-context van de personeelsgids."+
"Als de vraag een specifieke doelgroep/functie noemt (zoals Talentclass of TC consultant), gebruik dan alleen context waarin die doelgroep/functie expliciet voorkomt, behalve bij referral/voordracht-vragen waar een algemene referralregeling van toepassing kan zijn. " +
                
"3. Geen Hallucinaties: Verzin nooit paginanummers, citaten, data of percentages die niet letterlijk in de tekst staan. " +

"4. Bronvermelding (verplicht): " +
"Als informatie uit de PERSONEELSGIDS wordt gebruikt, moet je: " +
"- uitsluitend bron-ID's noemen die in de context voorkomen (BRON X), " +
"- geen paginanummers zelf uitschrijven. " +
"- splits de bronvermelding met een enter van de rest van het antwoord. " +

"5. Toon: Professioneel en behulpzaam, maar kortaf waar nodig om feitelijkheid te bewaren. " +

"# STAPSGEWIJZE VERWERKING (Chain of Thought) " +
"Voordat je antwoordt, doorloop je intern deze stappen: " +
    "- Stap 1: Classificeer de vraag: in-scope of out-of-scope. " + 
    "- Stap 2: Zoek expliciet bewijs in <context>. " +  
    "- Stap 3: Controleer consistentie en of paginanummer aanwezig is. " +
    "- Stap 4: Formuleer compact eindantwoord op basis van bewijs, splits antwoorden met enters. " +
    "- Stap 5: Als bewijs ontbreekt: zeg dat je het niet kunt terugvinden en verwijs naar HR." +
                
//"- Stap 1: Analyseer of de vraag (geheel of gedeeltelijk) bij het geselecteerde onderwerp hoort. " +
//"- Stap 2: Zoek in de <context> naar de specifieke secties die over dit onderwerp gaan. " +
//"- Stap 3: Controleer of er tegenstrijdigheden zijn in de tekst. " +        
//"- Stap 4: Formuleer het antwoord met enters tussen verschillende antwoorden, en identificeer de bron inclusief paginanummer en relevante passage. " +

"# OUTPUT FORMAT " +
"Hanteer strikt de volgende structuur: " +

"Antwoord: [Geef hier het feitelijke antwoord, zonder labels zoals BronID of Bron in deze regel.] " +
                
"BronID: [Noem alleen BRON-nummers, bijv. 2 of 2,5. Indien niet gevonden: N.v.t.] " +
               
"<context> " +
"{{hier de tekst uit de personeelsgids}} " +
"</context> " +

"<vraag_gebruiker> " +
"{{vraag}} " +
"</vraag_gebruiker>";

        String finalSystemPrompt = systemPrompt          
                .replace("{{hier de tekst uit de personeelsgids}}", contextString)
                .replace("{{vraag}}", question);

        JSONArray messages = new JSONArray()
                .put(new JSONObject()
                        .put("role", "system")
                        .put("content", finalSystemPrompt));

        JSONObject body = new JSONObject()
                .put("model", "gpt-4o-mini")
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("top_p", 0);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                .build();
        
        try (Response response = executeWithRetries(request, "Chat")) {
            
            JSONObject json = new JSONObject(response.body().string());

            String answer = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

            String normalizedAnswer = normalizeAnswerWithPageReferences(question, answer, sourceById);
            conversationHistory.add(new JSONObject().put("role", "user").put("content", question));
            conversationHistory.add(new JSONObject().put("role", "assistant").put("content", normalizedAnswer));

            if (conversationHistory.size() > 12)
                conversationHistory.subList(0, conversationHistory.size() - 12).clear();

            return normalizedAnswer;
        }
    }

    private String normalizeAnswerWithPageReferences(String question, String rawAnswer, Map<Integer, Chunk> sourceById) throws Exception {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "Antwoord: Ik kan geen antwoord genereren op basis van de aangeleverde context.\n"
                    + "Bron: N.v.t.";
        }

        String answerText = extractField(rawAnswer, "Antwoord:");
        if (answerText == null || answerText.isBlank()) {
            answerText = rawAnswer.trim();
        }
        answerText = stripSourceArtifacts(answerText);   
               
        String bronField = extractField(rawAnswer, "BronID:");
        Set<Integer> citedPages = new LinkedHashSet<>();
        Set<Integer> allCitedPages = new LinkedHashSet<>();
        // Page-level aggregated scores: sum chunk scores per page (#4)
        Map<Integer, Double> pageRelevanceScores = new HashMap<>();
        Map<Integer, Integer> pageChunkCounts = new HashMap<>();

        // Embed the question once for semantic scoring (#1)
        List<Double> questionEmbedding = embed(question);

        if (bronField != null && !bronField.equalsIgnoreCase("N.v.t.")) {
            Matcher matcher = Pattern.compile("\\d+").matcher(bronField);
            while (matcher.find()) {
                int id = Integer.parseInt(matcher.group());
                Chunk chunk = sourceById.get(id);
                if (chunk != null) {
                    allCitedPages.add(chunk.page);
                    double relevance = citationRelevanceScore(question, answerText, chunk.text, questionEmbedding, chunk.embedding);
                    // Aggregate scores per page (#4): accumulate sum and count
                    pageRelevanceScores.merge(chunk.page, relevance, Double::sum);
                    pageChunkCounts.merge(chunk.page, 1, Integer::sum);
                    if (isRelevantCitation(question, answerText, chunk.text)) {
                        citedPages.add(chunk.page);
                    }
                }
            }
        }

        // Convert summed page scores to averages (#4)
        pageRelevanceScores.replaceAll((page, sum) ->
                sum / pageChunkCounts.getOrDefault(page, 1));
        if (citedPages.isEmpty()) {
            citedPages.addAll(allCitedPages);
        }


     
        String bronText;


        if (citedPages.isEmpty()) {
            bronText = "N.v.t.";            
        } else {
            bronText = "PAGINA " + citedPages.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", PAGINA "));

        }

        return "Antwoord: " + answerText.trim() + "\n"
                + "Bron: " + bronText;
    }

        
private boolean isRelevantCitation(String question, String answerText, String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            return false;
        }

        Set<String> questionTokens = tokenize(question == null ? "" : question);
        Set<String> chunkTokens = tokenize(chunkText);

        int overlap = 0;
        for (String token : questionTokens) {
            if (chunkTokens.contains(token)) {
                overlap++;
            }
        }

        if (!questionTokens.isEmpty()) {
            double overlapRatio = (double) overlap / questionTokens.size();
            if (overlapRatio >= 0.30 || (questionTokens.size() <= 3 && overlap >= 1)) {
                return true;
            }
        }

        return lexicalSimilarity(answerText == null ? "" : answerText, chunkText) >= 0.10;
    }

private double citationRelevanceScore(String question, String answerText, String chunkText,
                                          List<Double> questionEmbedding, List<Double> chunkEmbedding) {
        if (chunkText == null || chunkText.isBlank()) {
            return 0.0;
        }

        double lexicalQuestion = lexicalSimilarity(question == null ? "" : question, chunkText);
        double lexicalAnswer   = lexicalSimilarity(answerText == null ? "" : answerText, chunkText);
        double lexicalScore    = (lexicalQuestion * 0.65) + (lexicalAnswer * 0.35);

        // Blend with semantic similarity when embeddings are available (#1)
        if (questionEmbedding != null && chunkEmbedding != null
                && !questionEmbedding.isEmpty() && !chunkEmbedding.isEmpty()) {
            double semanticScore = cosine(questionEmbedding, chunkEmbedding);
            return (semanticScore * 0.60) + (lexicalScore * 0.40);
        }

        return lexicalScore;
    }

    
    

    private String extractField(String text, String label) {
        if (text == null || label == null) {
            return null;
        }

        Pattern pattern = Pattern.compile("(?is)" + Pattern.quote(label) + "\\s*(.*?)(?:\\n\\s*[A-Za-zÀ-ÿ]+\\s*:|$)");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        return matcher.group(1).trim();   
    }

        private String stripSourceArtifacts(String answerText) {
        if (answerText == null) {
            return "";
        }

        String cleaned = answerText;
        cleaned = cleaned.replaceAll("(?is)\\bBronID\\s*:\\s*[^\\n]*", "");
        cleaned = cleaned.replaceAll("(?is)\\bBron\\s*:\\s*[^\\n]*", "");

        return cleaned.trim();
    }

    // ==============================

    // Start de Swing-applicatie en opent het chatbotvenster op de event-dispatch-thread.
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            try {
                new HU_B();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}

// MORE AGENTS!