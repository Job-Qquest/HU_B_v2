package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;

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
            return "Functie: Algemeen\n"
                    + "Antwoord: Ik kan geen antwoord genereren op basis van de aangeleverde context.\n"
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
        String functieField = extractField(rawAnswer, "Functie:");
        String assumedFunction = resolveAssumedFunction(question, functieField, sourceById);
        Set<String> citedReferences = new LinkedHashSet<>();
        Set<String> allCitedReferences = new LinkedHashSet<>();
// Scores per bronverwijzing
        Map<String, Double> referenceRelevanceScores = new java.util.HashMap<>();
        Map<String, Integer> referenceChunkCounts = new java.util.HashMap<>();

// Maak embedding van de vraag
        List<Double> questionEmbedding = openAIService.embed(question);

// Als er bron-ID's zijn -> verwerken
// Bijbehorende chunk, relevantie score berekenen, score optellen per bron en
// onthouden welke bronverwijzingen uiteindelijk in de output moeten komen.
        if (bronField != null && !bronField.equalsIgnoreCase("N.v.t.")) {
            Matcher matcher = Pattern.compile("\\d+").matcher(bronField);
            while (matcher.find()) {
                int id = Integer.parseInt(matcher.group());
                ChunkEmbedding chunk = sourceById.get(id);
                if (chunk != null) {
                    String reference = formatSourceReference(chunk);
                    allCitedReferences.add(reference);
                    double relevance = citationRelevanceScore(question, answerText, chunk.getText(), questionEmbedding, chunk.getEmbedding());
                    referenceRelevanceScores.merge(reference, relevance, Double::sum);
                    referenceChunkCounts.merge(reference, 1, Integer::sum);
                    if (isRelevantCitation(question, answerText, chunk.getText())) {
                        citedReferences.add(reference);
                    }
                }
            }
        }
// Gemiddelde score per bronverwijzing berekenen
        referenceRelevanceScores.replaceAll((reference, sum) ->
                sum / referenceChunkCounts.getOrDefault(reference, 1));
        if (citedReferences.isEmpty()) {
            citedReferences.addAll(allCitedReferences);
        }

        String bronText;

// Als er geen bronnen zijn -> N.v.t.
        if (citedReferences.isEmpty()) {
            bronText = "N.v.t.";
        } else {
// Format: PAGINA 1 of documentnaam
            bronText = String.join(", ", citedReferences);
        }
// Eindresultaat
        return "Functie: " + assumedFunction + "\n"
                + "Antwoord: " + answerText.trim() + "\n"
                + "Bron: " + bronText;
    }

    private String resolveAssumedFunction(String question,
                                          String functionField,
                                          Map<Integer, ChunkEmbedding> sourceById) {
        Set<String> questionFunctions = knowledgeService.detectFunctionLabels(question);
        String questionFunction = inferSingleFunction(questionFunctions);
        if (questionFunction != null) {
            return questionFunction;
        }

        if (functionField != null && !functionField.isBlank()) {
            String normalizedField = normalizeFunctionLabel(functionField);
            if (normalizedField != null && "Meerdere functies".equals(normalizedField)) {
                return normalizedField;
            }
        }

        return "Algemeen";
    }

    private String inferSingleFunction(Set<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }

        if (labels.size() == 1) {
            return labels.iterator().next();
        }

        return "Meerdere functies";
    }

    private String inferDominantFunction(Map<Integer, ChunkEmbedding> sourceById) {
        if (sourceById == null || sourceById.isEmpty()) {
            return null;
        }

        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ChunkEmbedding chunk : sourceById.values()) {
            if (chunk == null || chunk.getFunctionScope() == null || chunk.getFunctionScope().isEmpty()) {
                continue;
            }

            for (String label : chunk.getFunctionScope()) {
                if (label == null || label.isBlank()) {
                    continue;
                }
                counts.merge(label, 1, Integer::sum);
            }
        }

        if (counts.isEmpty()) {
            return null;
        }

        int bestCount = 0;
        List<String> bestLabels = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count > bestCount) {
                bestCount = count;
                bestLabels.clear();
                bestLabels.add(entry.getKey());
            } else if (count == bestCount) {
                bestLabels.add(entry.getKey());
            }
        }

        if (bestLabels.size() == 1) {
            return bestLabels.get(0);
        }

        return "Meerdere functies";
    }

    private String normalizeFunctionLabel(String functionField) {
        if (functionField == null) {
            return null;
        }

        String normalized = functionField.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("N.v.t.")) {
            return null;
        }

        return normalized;
    }

// Zet een chunk om naar een bronverwijzing die klopt voor PDF en Word.
// PDF-chunks krijgen een paginanummer; Word-bronnen krijgen de bestandsnaam.
    private String formatSourceReference(ChunkEmbedding chunk) {
        if (chunk == null) {
            return "N.v.t.";
        }

        String label = chunk.getSourceLabel();
        String sourceName = chunk.getSourceName();
        String sourceUrl = chunk.getSourceUrl();
        String sourcePath = chunk.getSourcePath();
        int page = chunk.getPage();
        String pageText = page > 0 ? "pagina " + page : null;

        if ((sourceName != null && !sourceName.isBlank()) || (sourceUrl != null && !sourceUrl.isBlank())) {
            String displayLabel = label != null && !label.isBlank()
                    ? label
                    : (sourceName != null && !sourceName.isBlank() ? sourceName : "webpagina");
            String linkTarget = sourceUrl != null && !sourceUrl.isBlank()
                    ? sourceUrl
                    : toFileUri(sourcePath);
            if (linkTarget != null && !linkTarget.isBlank()) {
                return buildHtmlLink(displayLabel, linkTarget);
            }
            return displayLabel;
        }

        if (label != null && !label.isBlank()) {
            if (chunk.isSourcePdf() && pageText != null) {
                String linkTarget = toFileUri(sourcePath);
                return linkTarget != null ? buildHtmlLink(label + " (" + pageText + ")", linkTarget) : label + " (" + pageText + ")";
            }
            if (sourcePath != null && !sourcePath.isBlank()) {
                String linkTarget = toFileUri(sourcePath);
                return linkTarget != null ? buildHtmlLink(label, linkTarget) : label;
            }
            return label;
        }

        if (chunk.isSourcePdf() && pageText != null) {
            String linkTarget = toFileUri(sourcePath);
            return linkTarget != null ? buildHtmlLink(pageText, linkTarget) : pageText;
        }

        if (sourcePath != null && !sourcePath.isBlank()) {
            String linkTarget = toFileUri(sourcePath);
            return linkTarget != null ? buildHtmlLink(page > 0 ? "PAGINA " + page : "bron", linkTarget) : (page > 0 ? "PAGINA " + page : "N.v.t.");
        }

        return page > 0 ? "PAGINA " + page : "N.v.t.";
    }

    private String buildHtmlLink(String label, String href) {
        return "<a href=\"" + escapeHtml(href) + "\">" + escapeHtml(label) + "</a>";
    }

    private String toFileUri(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return null;
        }

        try {
            return Path.of(sourcePath).toAbsolutePath().normalize().toUri().toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
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
