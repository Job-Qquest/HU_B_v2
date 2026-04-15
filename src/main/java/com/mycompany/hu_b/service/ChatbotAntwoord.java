package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import com.mycompany.hu_b.service.PdfProcessing.SearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Bouwt het uiteindelijke chatbotantwoord op...
// De class haalt relevante chunks op uit PdfProcessing,
// stelt de prompt samen voor het AI-model,
// verwerkt speciale gevallen zoals vragen over verzuimduur,
// en verfijnt het antwoord inclusief bronvermelding en paginareferenties.

public class ChatbotAntwoord {

    private final List<org.json.JSONObject> conversationHistory = new ArrayList<>();
// Bewaart de recente conversatiegeschiedenis als JSON-objecten
// In deze versie wordt de historie opgeslagen, maar niet actief meegestuurd in de prompt naar OpenAI

    private final PdfProcessing knowledgeService;
    private final OpenAI openAIService;
    private final ChatbotPrompt promptBuilder;
    private final ChatbotAntwoordVerfijner antwoordVerfijner;

// Initialiseert alle benodigde services
    public ChatbotAntwoord(PdfProcessing knowledgeService, OpenAI openAIService) {
        this.knowledgeService = knowledgeService;
        this.openAIService = openAIService;
        this.promptBuilder = new ChatbotPrompt(knowledgeService);
        this.antwoordVerfijner = new ChatbotAntwoordVerfijner(knowledgeService, openAIService);
    }

// Hoofdmethode: verwerkt een gebruikersvraag en geeft een antwoord terug   
    public String ask(String question) throws Exception {

// Zoekt de meest relevante chunks en splitst gids- en externe bronnen
        SearchResult searchResult = knowledgeService.search(question);
        List<ChunkEmbedding> guideChunks = searchResult.getGuideChunks();
        List<ChunkEmbedding> externalChunks = selectExternalChunks(guideChunks, searchResult.getExternalChunks(), searchResult.isGuideSufficient());

// Plus een map om chunks later terug te kunnen koppelen aan Bron-ID's
        Map<Integer, ChunkEmbedding> sourceById = new LinkedHashMap<>();

// Bouwt een context string (tekst die naar OpenAI gaat)
        String contextString = promptBuilder.buildContextText(guideChunks, externalChunks, sourceById);

// Als het een vraag is over verzuim -> direct antwoord zonder OpenAI
        Optional<String> verzuimDurationAnswer =
                promptBuilder.buildVerzuimDurationAnswer(question, combineChunks(guideChunks, externalChunks));

        if (verzuimDurationAnswer.isPresent()) {
            return verzuimDurationAnswer.get();
        }

// Bouwt de uiteindelijk system prompt voor OpenAI
        String finalSystemPrompt =
                promptBuilder.buildSystemPrompt(question, contextString);

// Vraagt OpenAI om een antwoord te genereren
        String answer = openAIService.chat(finalSystemPrompt);

// Verfijnt het antwoord (schoonmaken, bronvermelding, pagina's bepalen)
        String normalizedAnswer =
                antwoordVerfijner.normalizeAnswerWithPageReferences(question, answer, sourceById);

// Slaat de conversatie op
        conversationHistory.add(new org.json.JSONObject().put("role", "user").put("content", question));
        conversationHistory.add(new org.json.JSONObject().put("role", "assistant").put("content", normalizedAnswer));

// Houd maximaal 12 berichten
        if (conversationHistory.size() > 12)
            conversationHistory.subList(0, conversationHistory.size() - 12).clear();

// Geeft het uiteindelijke antwoord terug
        return normalizedAnswer;
    }

// Houdt externe bronnen beperkt tot echte aanvullingen wanneer de personeelsgids al voldoende dekking heeft.
    private List<ChunkEmbedding> selectExternalChunks(List<ChunkEmbedding> guideChunks,
                                                     List<ChunkEmbedding> externalChunks,
                                                     boolean guideSufficient) {
        if (externalChunks == null || externalChunks.isEmpty()) {
            return List.of();
        }

        if (guideChunks == null || guideChunks.isEmpty() || !guideSufficient) {
            return externalChunks;
        }

        List<ChunkEmbedding> selected = new ArrayList<>();
        for (ChunkEmbedding externalChunk : externalChunks) {
            if (isSupplementaryToGuide(externalChunk, guideChunks)) {
                selected.add(externalChunk);
            }

            if (selected.size() >= 4) {
                break;
            }
        }

        return selected;
    }

// Combineert gids- en externe chunks voor speciale afhandeling, met de gids eerst.
    private List<ChunkEmbedding> combineChunks(List<ChunkEmbedding> guideChunks, List<ChunkEmbedding> externalChunks) {
        List<ChunkEmbedding> combined = new ArrayList<>();
        if (guideChunks != null) {
            combined.addAll(guideChunks);
        }
        if (externalChunks != null) {
            combined.addAll(externalChunks);
        }
        return combined;
    }

// Een externe chunk is alleen aanvullend als die inhoud toevoegt en niet gewoon hetzelfde zegt als de gids.
    private boolean isSupplementaryToGuide(ChunkEmbedding externalChunk, List<ChunkEmbedding> guideChunks) {
        if (externalChunk == null || externalChunk.getText() == null || externalChunk.getText().isBlank()) {
            return false;
        }

        String externalText = externalChunk.getText();
        double bestSimilarity = 0.0;
        String bestGuideText = null;

        for (ChunkEmbedding guideChunk : guideChunks) {
            if (guideChunk == null || guideChunk.getText() == null || guideChunk.getText().isBlank()) {
                continue;
            }

            double similarity = knowledgeService.lexicalSimilarity(externalText, guideChunk.getText());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestGuideText = guideChunk.getText();
            }
        }

        if (bestGuideText == null) {
            return false;
        }

        if (bestSimilarity >= 0.75) {
            return false;
        }

        if (bestSimilarity < 0.20) {
            return false;
        }

        if (hasConflictingNumbers(externalText, bestGuideText)) {
            return false;
        }

        Set<String> externalTokens = knowledgeService.tokenize(externalText);
        Set<String> guideTokens = knowledgeService.tokenize(bestGuideText);
        if (externalTokens.isEmpty()) {
            return false;
        }

        int overlap = 0;
        for (String token : externalTokens) {
            if (guideTokens.contains(token)) {
                overlap++;
            }
        }

        double newTokenRatio = (double) (externalTokens.size() - overlap) / externalTokens.size();
        return newTokenRatio >= 0.25;
    }

// Detecteert eenvoudige conflicten op basis van verschillende getallen in soortgelijke zinnen.
    private boolean hasConflictingNumbers(String externalText, String guideText) {
        Set<String> externalNumbers = extractNumbers(externalText);
        Set<String> guideNumbers = extractNumbers(guideText);

        if (externalNumbers.isEmpty() || guideNumbers.isEmpty()) {
            return false;
        }

        for (String number : externalNumbers) {
            if (!guideNumbers.contains(number)) {
                return true;
            }
        }

        return false;
    }

    private Set<String> extractNumbers(String text) {
        Set<String> numbers = new java.util.LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return numbers;
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b\\d+[\\d.,]*\\b").matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }

        return numbers;
    }

// Detecteert of een vraag over salaris gaat (met salaris-gerelateerde keywords)
    public boolean isSalaryQuestion(String query) {
        String normalized = query.toLowerCase();
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
}
