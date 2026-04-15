package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
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

// Zoekt de meest relevante chunks uit de PDF
// Plus een map om chunks later terug te kunnen koppelen aan Bron-ID's
        List<ChunkEmbedding> topChunks = knowledgeService.search(question);
        Map<Integer, ChunkEmbedding> sourceById = new LinkedHashMap<>();

// Bouwt een context string (tekst die naar OpenAI gaat)
        String contextString = promptBuilder.buildContextText(topChunks, sourceById);

// Als het een vraag is over verzuim -> direct antwoord zonder OpenAI
        Optional<String> verzuimDurationAnswer =
                promptBuilder.buildVerzuimDurationAnswer(question, topChunks);

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