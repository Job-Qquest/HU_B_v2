package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChatbotAntwoordVerfijner {

    private final PdfProcessing knowledgeService;
    private final OpenAI openAIService;

    public ChatbotAntwoordVerfijner(PdfProcessing knowledgeService, OpenAI openAIService) {
        this.knowledgeService = knowledgeService;
        this.openAIService = openAIService;
    }

// Hoofmethode die: Antwoord opschoont, bronnen analyseert, relevante pagina's bepaalt, nette output maakt met paginareferenties 
    public String normalizeAnswerWithPageReferences(String question,
                                                    String rawAnswer,
                                                    Map<Integer, ChunkEmbedding> sourceById) throws Exception {

// Als er geen antwoord is
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "Antwoord: Ik kan geen antwoord genereren op basis van de aangeleverde context.\n"
                    + "Bron: N.v.t.";
        }

// Haal alleen het stuk na "Antwoord: " eruit
        String answerText = extractField(rawAnswer, "Antwoord:");
        if (answerText == null || answerText.isBlank()) {
            answerText = rawAnswer.trim();
        }
        answerText = stripSourceArtifacts(answerText);

// Haal bron-ID's op
        String bronField = extractField(rawAnswer, "BronID:");
        Set<Integer> citedPages = new LinkedHashSet<>();
        Set<Integer> allCitedPages = new LinkedHashSet<>();
// Scores per pagina
        Map<Integer, Double> pageRelevanceScores = new java.util.HashMap<>();
        Map<Integer, Integer> pageChunkCounts = new java.util.HashMap<>();

// Maak embedding van de vraag
        List<Double> questionEmbedding = openAIService.embed(question);

// Als er bron-ID's zijn -> verwerken
// Bijbehorende chunk, relevantie score berekenen, score optellen per pagina, hoeveel chunks per pagina
        if (bronField != null && !bronField.equalsIgnoreCase("N.v.t.")) {
            Matcher matcher = Pattern.compile("\\d+").matcher(bronField);
            while (matcher.find()) {
                int id = Integer.parseInt(matcher.group());
                ChunkEmbedding chunk = sourceById.get(id);
                if (chunk != null) {
                    allCitedPages.add(chunk.getPage());
                    double relevance = citationRelevanceScore(question, answerText, chunk.getText(), questionEmbedding, chunk.getEmbedding());
                    pageRelevanceScores.merge(chunk.getPage(), relevance, Double::sum);
                    pageChunkCounts.merge(chunk.getPage(), 1, Integer::sum);
                    if (isRelevantCitation(question, answerText, chunk.getText())) {
                        citedPages.add(chunk.getPage());
                    }
                }
            }
        }
// Gemiddelde score per pagina berekenen
        pageRelevanceScores.replaceAll((page, sum) ->
                sum / pageChunkCounts.getOrDefault(page, 1));
        if (citedPages.isEmpty()) {
            citedPages.addAll(allCitedPages);
        }

        String bronText;

// Als er geen pagina's zijn -> N.v.t.
        if (citedPages.isEmpty()) {
            bronText = "N.v.t.";
        } else {
// Format: PAGINA 1, PAGINA 2, etc.
            bronText = "PAGINA " + citedPages.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", PAGINA "));
        }
// Eindresultaat
        return "Antwoord: " + answerText.trim() + "\n"
                + "Bron: " + bronText;
    }

// Bepaalt of een chunk relevant is voor het antwoord
// Geen tekst -> niet relevant
    public boolean isRelevantCitation(String question, String answerText, String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            return false;
        }

// Tokenize vraag en chunk
        Set<String> questionTokens = knowledgeService.tokenize(question == null ? "" : question);
        Set<String> chunkTokens = knowledgeService.tokenize(chunkText);

// Telt overlap tussen vraag en chunk
        int overlap = 0;
        for (String token : questionTokens) {
            if (chunkTokens.contains(token)) {
                overlap++;
            }
        }

// Berekent overlap ratio
// >= 30% overlap of bij korte vraag (<= 3 woorden) met minimaal 1 match
        if (!questionTokens.isEmpty()) {
            double overlapRatio = (double) overlap / questionTokens.size();
            if (overlapRatio >= 0.30 || (questionTokens.size() <= 3 && overlap >= 1)) {
                return true;
            }
        }

        return knowledgeService.lexicalSimilarity(answerText == null ? "" : answerText, chunkText) >= 0.10;
    }

// Combineert lexical en semantic similarity tot één score
    public double citationRelevanceScore(String question, String answerText, String chunkText,
                                          List<Double> questionEmbedding, List<Double> chunkEmbedding) {

// Geen tekst -> Score van 0
        if (chunkText == null || chunkText.isBlank()) {
            return 0.0;
        }

// Lexical similarity
// Combineert vraag en antwoord; Vraag telt zwaarder
        double lexicalQuestion = knowledgeService.lexicalSimilarity(question == null ? "" : question, chunkText);
        double lexicalAnswer   = knowledgeService.lexicalSimilarity(answerText == null ? "" : answerText, chunkText);
        double lexicalScore    = (lexicalQuestion * 0.65) + (lexicalAnswer * 0.35);

        if (questionEmbedding != null && chunkEmbedding != null
                && !questionEmbedding.isEmpty() && !chunkEmbedding.isEmpty()) {
            double semanticScore = knowledgeService.cosine(questionEmbedding, chunkEmbedding);
// Combineert semantic en lexical
            return (semanticScore * 0.60) + (lexicalScore * 0.40);
        }
// Anders alleen lexical
        return lexicalScore;
    }

// Haalt een veld op uit tekst (bv. "Antwoord:" of "BronID")
    public String extractField(String text, String label) {
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

// Verwijdert bron-gerelateerde tekst uit het antwoord
    public String stripSourceArtifacts(String answerText) {
        if (answerText == null) {
            return "";
        }

// Verwijdert "BronID: ..."
// Verwijdert "Bron: ..."
        String cleaned = answerText;
        cleaned = cleaned.replaceAll("(?is)\\bBronID\\s*:\\s*[^\\n]*", "");
        cleaned = cleaned.replaceAll("(?is)\\bBron\\s*:\\s*[^\\n]*", "");

        return cleaned.trim();
    }
}