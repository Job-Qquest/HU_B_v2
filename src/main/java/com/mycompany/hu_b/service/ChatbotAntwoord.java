package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import com.mycompany.hu_b.service.KnowledgeProcessingUtils.SearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

// Zoekt de meest relevante chunks in één globale, gewogen ranglijst
        SearchResult searchResult = knowledgeService.search(question);
        List<ChunkEmbedding> rankedChunks = searchResult.getRankedChunks();

// Plus een map om chunks later terug te kunnen koppelen aan Bron-ID's
        Map<Integer, ChunkEmbedding> sourceById = new LinkedHashMap<>();

// Bouwt een context string (tekst die naar OpenAI gaat)
        String contextString = promptBuilder.buildContextText(rankedChunks, sourceById);

// Als het een vraag is over verzuim -> direct antwoord zonder OpenAI
        Optional<String> verzuimDurationAnswer =
                promptBuilder.buildVerzuimDurationAnswer(question, rankedChunks);

        if (verzuimDurationAnswer.isPresent()) {
            return verzuimDurationAnswer.get();
        }

// Bouwt de uiteindelijk system prompt voor OpenAI
        String finalSystemPrompt =
                promptBuilder.buildSystemPrompt(question, contextString);

// Logt de chunks die daadwerkelijk in de laatste LLM-stap mee worden gestuurd
        logChunksForFinalPrompt(question, sourceById, contextString);

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

// Geeft zichtbaar weer welke chunks in de laatste prompt zitten
    private void logChunksForFinalPrompt(String question,
                                         Map<Integer, ChunkEmbedding> sourceById,
                                         String contextString) {
        System.out.println("=== LLM FINAL CONTEXT DEBUG ===");
        System.out.println("Vraag: " + (question == null ? "" : question));

        if (sourceById == null || sourceById.isEmpty()) {
            System.out.println("Geen chunks gevonden voor de laatste LLM-stap.");
            System.out.println("=== EINDE LLM FINAL CONTEXT DEBUG ===");
            return;
        }

        System.out.println("Aantal chunks: " + sourceById.size());
        for (Map.Entry<Integer, ChunkEmbedding> entry : sourceById.entrySet()) {
            Integer sourceId = entry.getKey();
            ChunkEmbedding chunk = entry.getValue();
            if (chunk == null) {
                System.out.println("BRON " + sourceId + ": <null>");
                continue;
            }

            System.out.println(formatChunkDebugLine(sourceId, chunk));
        }

        System.out.println("--- FINAL CONTEXT STRING ---");
        System.out.println(contextString == null ? "" : contextString);
        System.out.println("=== EINDE LLM FINAL CONTEXT DEBUG ===");
    }

    private String formatChunkDebugLine(Integer sourceId, ChunkEmbedding chunk) {
        String sourceType = chunk.isPrimaryGuide() ? "PERSONEELSGIDS" : "EXTERNE BRONNEN";
        String sourceLabel = chunk.getSourceLabel();
        String sourceName = chunk.getSourceName();
        String sourceUrl = chunk.getSourceUrl();
        String pageInfo = chunk.getPage() > 0 ? "pagina " + chunk.getPage() : "geen paginanummer";
        String functionScope = (chunk.getFunctionScope() == null || chunk.getFunctionScope().isEmpty())
                ? "ALGEMEEN"
                : String.join(", ", chunk.getFunctionScope());

        StringBuilder line = new StringBuilder();
        line.append("BRON ").append(sourceId)
                .append(" | ").append(sourceType)
                .append(" | ").append(pageInfo)
                .append(" | FUNCTIEAFHANKELIJK: ").append(functionScope);

        if (sourceLabel != null && !sourceLabel.isBlank()) {
            line.append(" | LABEL: ").append(sourceLabel);
        }
        if (sourceName != null && !sourceName.isBlank()) {
            line.append(" | BRONNAAM: ").append(sourceName);
        }
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            line.append(" | URL: ").append(sourceUrl);
        }

        line.append("\nTEKST: ").append(chunk.getText() == null ? "" : chunk.getText());
        return line.toString();
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
