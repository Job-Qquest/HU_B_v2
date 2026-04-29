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

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b");

    private final PdfProcessing knowledgeService;
    private final OpenAI openAIService;

    public ChatbotAntwoordVerfijner(PdfProcessing knowledgeService, OpenAI openAIService) {
        this.knowledgeService = knowledgeService;
        this.openAIService = openAIService;
    }

    // Laat andere onderdelen dezelfde bronopmaak gebruiken zonder de linklogica te dupliceren.
    public String formatSourceReferenceForDisplay(ChunkEmbedding chunk) {
        return formatSourceReference(chunk);
    }

    // Gebruikt een expliciet paginanummer voor gevallen waarin de bron vast moet staan.
    public String formatSourceReferenceForPage(ChunkEmbedding chunk, int pageNumber) {
        return formatSourceReference(chunk, pageNumber);
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
        answerText = linkifyEmailAddresses(answerText);

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

    private String linkifyEmailAddresses(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        Matcher matcher = EMAIL_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String email = matcher.group();
            String replacement = "<a href=\"mailto:" + email + "\">" + email + "</a>";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
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
    String formatSourceReference(ChunkEmbedding chunk) {
        return formatSourceReference(chunk, null);
    }

    private String formatSourceReference(ChunkEmbedding chunk, Integer forcedPage) {
        if (chunk == null) {
            return "N.v.t.";
        }

        String label = chunk.getSourceLabel();
        String sourceName = chunk.getSourceName();
        String sourceUrl = chunk.getSourceUrl();
        String sourcePath = chunk.getSourcePath();
        int page = chunk.getPage();
        int resolvedPage = forcedPage != null && forcedPage > 0 ? forcedPage : page;
        String pageText = resolvedPage > 0 ? "pagina " + resolvedPage : null;
        String linkTarget = buildSourceTarget(chunk, forcedPage);

        if ((sourceName != null && !sourceName.isBlank()) || (sourceUrl != null && !sourceUrl.isBlank())) {
            String displayLabel = label != null && !label.isBlank()
                    ? label
                    : (sourceName != null && !sourceName.isBlank() ? sourceName : "webpagina");
            if (linkTarget != null && !linkTarget.isBlank()) {
                return buildHtmlLink(displayLabel, linkTarget);
            }
            return displayLabel;
        }

        if (label != null && !label.isBlank()) {
            if (linkTarget != null && !linkTarget.isBlank()) {
                String displayLabel = chunk.isSourcePdf() && pageText != null ? label + " (" + pageText + ")" : label;
                return buildHtmlLink(displayLabel, linkTarget);
            }
            return label + (chunk.isSourcePdf() && pageText != null ? " (" + pageText + ")" : "");
        }

        if (linkTarget != null && !linkTarget.isBlank()) {
            if (chunk.isSourcePdf() && pageText != null) {
                return buildHtmlLink(pageText, linkTarget);
            }
            return buildHtmlLink(resolvedPage > 0 ? "PAGINA " + resolvedPage : "bron", linkTarget);
        }

        return resolvedPage > 0 ? "PAGINA " + resolvedPage : "N.v.t.";
    }

    private String buildSourceTarget(ChunkEmbedding chunk) {
        return buildSourceTarget(chunk, null);
    }

    private String buildSourceTarget(ChunkEmbedding chunk, Integer forcedPage) {
        if (chunk == null) {
            return null;
        }

        String sourceTarget = chunk.getSourceTarget();
        if (sourceTarget != null && !sourceTarget.isBlank()) {
            if (forcedPage != null && forcedPage > 0) {
                return forcePageOnTarget(sourceTarget, forcedPage);
            }
            return sourceTarget;
        }

        String sourceUrl = chunk.getSourceUrl();
        String sourcePath = chunk.getSourcePath();
        int page = forcedPage != null && forcedPage > 0 ? forcedPage : chunk.getPage();

        if (sourceUrl != null && !sourceUrl.isBlank()) {
            String trimmedUrl = sourceUrl.trim();
            if (chunk.isSourcePdf() && page > 0) {
                if (looksLikePdfLink(trimmedUrl)) {
                    return appendPdfPageFragment(trimmedUrl, page);
                }
                if (sourcePath != null && !sourcePath.isBlank()) {
                    return toFileUriWithPage(sourcePath, page);
                }
            }
            return trimmedUrl;
        }

        if (sourcePath != null && !sourcePath.isBlank()) {
            return chunk.isSourcePdf() && page > 0
                    ? toFileUriWithPage(sourcePath, page)
                    : toFileUri(sourcePath);
        }

        return null;
    }

    private String forcePageOnTarget(String target, int page) {
        if (target == null || target.isBlank() || page <= 0) {
            return target;
        }

        String trimmedTarget = target.trim();
        if (trimmedTarget.contains("#page=")) {
            return trimmedTarget.replaceAll("#page=\\d+", "#page=" + page);
        }

        if (trimmedTarget.contains("#")) {
            return trimmedTarget.substring(0, trimmedTarget.indexOf('#')) + "#page=" + page;
        }

        if (looksLikePdfLink(trimmedTarget) || trimmedTarget.startsWith("file:")) {
            return trimmedTarget + "#page=" + page;
        }

        return trimmedTarget;
    }

    private boolean looksLikePdfLink(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return false;
        }
        String normalized = sourceUrl.trim().toLowerCase();
        return normalized.endsWith(".pdf") || normalized.contains(".pdf?") || normalized.contains(".pdf#");
    }

    private String appendPdfPageFragment(String url, int page) {
        if (url == null || url.isBlank() || page <= 0) {
            return url;
        }
        String trimmedUrl = url.trim();
        if (trimmedUrl.contains("#page=")) {
            return trimmedUrl.replaceAll("#page=\\d+", "#page=" + page);
        }
        if (trimmedUrl.contains("#")) {
            return trimmedUrl.substring(0, trimmedUrl.indexOf('#')) + "#page=" + page;
        }
        return trimmedUrl + "#page=" + page;
    }

    private String toFileUriWithPage(String sourcePath, int page) {
        if (sourcePath == null || sourcePath.isBlank() || page <= 0) {
            return toFileUri(sourcePath);
        }

        try {
            return Path.of(sourcePath).toAbsolutePath().normalize().toUri().toString() + "#page=" + page;
        } catch (Exception ex) {
            return toFileUri(sourcePath);
        }
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
