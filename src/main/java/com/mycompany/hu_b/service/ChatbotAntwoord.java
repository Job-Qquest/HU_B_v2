package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import com.mycompany.hu_b.service.KnowledgeProcessingUtils.SearchResult;
import com.mycompany.hu_b.util.FunctionProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

// Bouwt het uiteindelijke chatbotantwoord op.
// Deze variant gebruikt een kleine conversatiestatus zodat verduidelijkingsvragen
// zoals functie-informatie niet verloren gaan tussen twee user messages.
public class ChatbotAntwoord {

    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_HISTORY_FOR_PROMPT = 20;
    private static final int MAX_PREVIOUS_USER_QUESTIONS = 3;
    private static final int SHORT_FOLLOW_UP_WORD_LIMIT = 8;
    private static final Pattern CONTEXT_DEPENDENT_PATTERN = Pattern.compile(
            "\\b(dat|dit|deze|die|daar|daarover|daarvan|ervoor|daarvoor|het|ze|zelfde|vorige|eerder)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOPICLESS_QUESTION_PATTERN = Pattern.compile(
            "^(hoe zit het|hoe werkt dat|kan dat|mag dat|wat bedoel je|hoe dan)\\b",
            Pattern.CASE_INSENSITIVE);

    private final List<org.json.JSONObject> conversationHistory = new ArrayList<>();
    private final Set<String> knownUserFunctionLabels = new LinkedHashSet<>();

    private final PdfProcessing knowledgeService;
    private final OpenAI openAIService;
    private final ChatbotPrompt promptBuilder;
    private final ChatbotAntwoordVerfijner antwoordVerfijner;

    private PendingClarification pendingClarification;

    public ChatbotAntwoord(PdfProcessing knowledgeService, OpenAI openAIService) {
        this.knowledgeService = knowledgeService;
        this.openAIService = openAIService;
        this.promptBuilder = new ChatbotPrompt(knowledgeService);
        this.antwoordVerfijner = new ChatbotAntwoordVerfijner(knowledgeService, openAIService);
    }

    public String ask(String question) throws Exception {
        if (question == null || question.isBlank()) {
            return "Ik help je graag. Kun je je vraag iets concreter formuleren?";
        }

        rememberFunctionFromText(question);

        PendingResolution pendingResolution = resolvePendingClarification(question);
        if (pendingResolution.immediateResponse() != null) {
            appendConversationTurn(question, pendingResolution.immediateResponse());
            return pendingResolution.immediateResponse();
        }

        String effectiveQuestion = pendingResolution.effectiveQuestion();
        String historyQuestion = pendingResolution.historyQuestion();

        String contextualQuestion = buildQuestionWithMemory(effectiveQuestion, knownUserFunctionLabels);
        SearchResult searchResult = knowledgeService.search(contextualQuestion);
        List<ChunkEmbedding> rankedChunks = searchResult.getRankedChunks();

        Map<Integer, ChunkEmbedding> sourceById = new LinkedHashMap<>();
        String contextString = promptBuilder.buildContextText(rankedChunks, sourceById);
        String conversationHistoryText =
                promptBuilder.buildConversationHistoryText(getRecentConversationHistory(MAX_HISTORY_FOR_PROMPT));

        Optional<String> verzuimDurationAnswer =
                promptBuilder.buildVerzuimDurationAnswer(effectiveQuestion, rankedChunks);
        if (verzuimDurationAnswer.isPresent()) {
            String answer = verzuimDurationAnswer.get();
            clearPendingClarification();
            appendConversationTurn(historyQuestion, answer);
            return answer;
        }

        ClarificationRequest clarificationRequest =
                determineClarificationRequest(effectiveQuestion, rankedChunks);
        if (clarificationRequest != null) {
            pendingClarification = new PendingClarification(
                    clarificationRequest.type(),
                    effectiveQuestion,
                    clarificationRequest.message());
            appendConversationTurn(historyQuestion, clarificationRequest.message());
            return clarificationRequest.message();
        }

        String finalSystemPrompt =
                promptBuilder.buildSystemPrompt(effectiveQuestion, contextString, conversationHistoryText);

        logChunksForFinalPrompt(contextualQuestion, sourceById, contextString);

        String answer = openAIService.chat(finalSystemPrompt);
        String normalizedAnswer =
                antwoordVerfijner.normalizeAnswerWithPageReferences(effectiveQuestion, answer, sourceById);

        clearPendingClarification();
        appendConversationTurn(historyQuestion, normalizedAnswer);
        return normalizedAnswer;
    }

    // Verrijkt een korte of verwijzende vervolgvraag met recente gebruikersvragen,
    // zodat retrieval ook zonder expliciete herhaling genoeg context heeft.
    private String buildQuestionWithMemory(String question, Set<String> activeFunctionLabels) {
        if (question == null || question.isBlank()) {
            return question;
        }

        String enrichedQuestion = question;
        if (isContextDependentQuestion(question)) {
            List<String> recentQuestions = getRecentUserQuestions(MAX_PREVIOUS_USER_QUESTIONS);
            if (!recentQuestions.isEmpty()) {
                StringBuilder contextualQuestion = new StringBuilder();
                contextualQuestion.append("Eerdere relevante vragen:\n");
                for (String previousQuestion : recentQuestions) {
                    contextualQuestion.append("- ").append(previousQuestion).append("\n");
                }
                contextualQuestion.append("Huidige vervolgvraag:\n").append(question.trim());
                enrichedQuestion = contextualQuestion.toString();
            }
        }

        return appendKnownFunctionContext(enrichedQuestion, activeFunctionLabels);
    }

    private ClarificationRequest determineClarificationRequest(String effectiveQuestion,
                                                               List<ChunkEmbedding> rankedChunks) {
        if (needsFunctionClarification(effectiveQuestion, rankedChunks)) {
            return new ClarificationRequest(
                    ClarificationType.FUNCTION,
                    buildFunctionClarificationPrompt());
        }

        if (needsGeneralClarification(effectiveQuestion, rankedChunks)) {
            return new ClarificationRequest(
                    ClarificationType.GENERAL,
                    "Ik help je graag, maar ik mis nog wat context om je vraag goed te beantwoorden. "
                    + "Kun je iets specifieker aangeven waar je vraag over gaat?");
        }

        return null;
    }

    private boolean needsFunctionClarification(String effectiveQuestion,
                                               List<ChunkEmbedding> rankedChunks) {
        if (effectiveQuestion == null || effectiveQuestion.isBlank()) {
            return false;
        }

        if (!knowledgeService.detectFunctionLabels(effectiveQuestion).isEmpty()
                || !knownUserFunctionLabels.isEmpty()) {
            return false;
        }

        int functionSpecificChunks = 0;
        int generalChunks = 0;
        int limit = Math.min(3, rankedChunks == null ? 0 : rankedChunks.size());
        for (int i = 0; i < limit; i++) {
            ChunkEmbedding chunk = rankedChunks.get(i);
            Set<String> chunkLabels = chunk.getFunctionScope() == null || chunk.getFunctionScope().isEmpty()
                    ? knowledgeService.detectFunctionLabels(chunk.getText())
                    : chunk.getFunctionScope();

            if (chunkLabels.isEmpty()) {
                generalChunks++;
            } else {
                functionSpecificChunks++;
            }
        }

        return functionSpecificChunks > 0 && generalChunks == 0;
    }

    private boolean needsGeneralClarification(String effectiveQuestion,
                                              List<ChunkEmbedding> rankedChunks) {
        if (effectiveQuestion == null || effectiveQuestion.isBlank()) {
            return true;
        }

        int tokenCount = knowledgeService.tokenize(effectiveQuestion).size();
        if (tokenCount <= 1) {
            return true;
        }

        String normalized = effectiveQuestion.trim().toLowerCase(Locale.ROOT);
        if (TOPICLESS_QUESTION_PATTERN.matcher(normalized).find()) {
            return true;
        }

        return rankedChunks == null || rankedChunks.isEmpty();
    }

    private String buildFunctionClarificationPrompt() {
        String knownFunctions = String.join(", ", FunctionProfile.getFunctionProfiles().keySet());
        return "Om je vraag goed te beantwoorden heb ik nog je functie nodig. "
                + "Welke functie heb je binnen Talentclass? Bijvoorbeeld: " + knownFunctions + ".";
    }

    private boolean isContextDependentQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String normalized = question.trim().toLowerCase(Locale.ROOT);
        if (CONTEXT_DEPENDENT_PATTERN.matcher(normalized).find()) {
            return true;
        }

        String[] words = normalized.split("\\s+");
        if (words.length > SHORT_FOLLOW_UP_WORD_LIMIT) {
            return false;
        }

        return normalized.startsWith("en ")
                || normalized.startsWith("maar ")
                || normalized.startsWith("hoe ")
                || normalized.startsWith("wat ")
                || normalized.startsWith("geldt ")
                || normalized.startsWith("kan ")
                || normalized.startsWith("kun ")
                || normalized.startsWith("is ")
                || normalized.startsWith("zijn ")
                || normalized.startsWith("wanneer ")
                || normalized.startsWith("welke ");
    }

    private PendingResolution resolvePendingClarification(String question) {
        if (pendingClarification == null || question == null || question.isBlank()) {
            return new PendingResolution(question, question, null);
        }

        if (pendingClarification.type() == ClarificationType.FUNCTION) {
            Set<String> detectedLabels = knowledgeService.detectFunctionLabels(question);
            if (detectedLabels.isEmpty()) {
                return new PendingResolution(
                        null,
                        question,
                        "Ik heb nog steeds je functie nodig om je vraag goed te beantwoorden. "
                        + "Welke functie heb je precies binnen Talentclass?");
            }

            knownUserFunctionLabels.clear();
            knownUserFunctionLabels.addAll(detectedLabels);

            String effectiveQuestion = appendKnownFunctionContext(
                    pendingClarification.originalQuestion(),
                    knownUserFunctionLabels);
            clearPendingClarification();
            return new PendingResolution(effectiveQuestion, effectiveQuestion, null);
        }

        String effectiveQuestion = pendingClarification.originalQuestion()
                + "\nAanvullende informatie van gebruiker: " + question.trim();
        clearPendingClarification();
        return new PendingResolution(effectiveQuestion, effectiveQuestion, null);
    }

    private void clearPendingClarification() {
        pendingClarification = null;
    }

    private void rememberFunctionFromText(String text) {
        Set<String> labels = knowledgeService.detectFunctionLabels(text);
        if (!labels.isEmpty()) {
            knownUserFunctionLabels.clear();
            knownUserFunctionLabels.addAll(labels);
        }
    }

    private String appendKnownFunctionContext(String question, Set<String> activeFunctionLabels) {
        if (question == null || question.isBlank() || activeFunctionLabels == null || activeFunctionLabels.isEmpty()) {
            return question;
        }

        if (!knowledgeService.detectFunctionLabels(question).isEmpty()) {
            return question;
        }

        return question.trim() + "\nBekende functie van gebruiker: " + String.join(", ", activeFunctionLabels);
    }

    private List<org.json.JSONObject> getRecentConversationHistory(int maxMessages) {
        if (conversationHistory.isEmpty() || maxMessages <= 0) {
            return List.of();
        }

        int start = Math.max(0, conversationHistory.size() - maxMessages);
        return new ArrayList<>(conversationHistory.subList(start, conversationHistory.size()));
    }

    private List<String> getRecentUserQuestions(int maxQuestions) {
        List<String> questions = new ArrayList<>();
        if (maxQuestions <= 0) {
            return questions;
        }

        for (int i = conversationHistory.size() - 1; i >= 0 && questions.size() < maxQuestions; i--) {
            org.json.JSONObject message = conversationHistory.get(i);
            if (message == null || !"user".equalsIgnoreCase(message.optString("role"))) {
                continue;
            }

            String content = message.optString("content", "").trim();
            if (!content.isEmpty()) {
                questions.add(0, content);
            }
        }

        return questions;
    }

    private void appendConversationTurn(String userQuestion, String assistantAnswer) {
        conversationHistory.add(new org.json.JSONObject().put("role", "user").put("content", userQuestion));
        conversationHistory.add(new org.json.JSONObject().put("role", "assistant").put("content", assistantAnswer));

        if (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.subList(0, conversationHistory.size() - MAX_HISTORY_MESSAGES).clear();
        }
    }

    // Geeft zichtbaar weer welke chunks in de laatste prompt zitten.
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

    public int getMaxHistoryMessages() {
        return MAX_HISTORY_MESSAGES;
    }

    private record PendingClarification(ClarificationType type, String originalQuestion, String prompt) {
    }

    private record PendingResolution(String effectiveQuestion, String historyQuestion, String immediateResponse) {
    }

    private record ClarificationRequest(ClarificationType type, String message) {
    }

    private enum ClarificationType {
        FUNCTION,
        GENERAL
    }
}
