package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import com.mycompany.hu_b.service.KnowledgeProcessingUtils.SearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Bouwt het uiteindelijke chatbotantwoord op.
// Deze variant gebruikt een kleine conversatiestatus zodat verduidelijkingsvragen
// niet verloren gaan tussen twee user messages.
public class ChatbotAntwoord {

    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_HISTORY_FOR_PROMPT = 20;
    private static final int MAX_PREVIOUS_USER_QUESTIONS = 3;
    private static final int SHORT_FOLLOW_UP_WORD_LIMIT = 8;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b");
    private static final Pattern CONTEXT_DEPENDENT_PATTERN = Pattern.compile(
            "\\b(dat|dit|deze|die|daar|daarover|daarvan|ervoor|daarvoor|hierover|hiervan|hiermee|daarmee|zelfde|vorige|eerder|voorgaande|bovenstaande|hierboven|hieronder|hierna)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_PATTERN = Pattern.compile(
            "\\b(?:in|vanaf|per|begin(?:nen)?\\s+(?:in|op)?|start(?:en)?\\s+(?:in|op)?)\\s+"
                    + "(januari|februari|maart|april|mei|juni|juli|augustus|september|oktober|november|december|"
                    + "jan|feb|mrt|apr|mei|jun|jul|aug|sep|sept|okt|nov|dec)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRORATA_ANNUAL_PATTERN = Pattern.compile(
            "(?i)\\b(\\d+(?:[.,]\\d+)?)\\s+(?:vakantieverlofdagen|vakantiedagen|verlofdagen|dagen|uren)\\s+per\\s+jaar\\b");

    private final List<org.json.JSONObject> conversationHistory = new ArrayList<>();

    private final PdfProcessing knowledgeService;
    private final OpenAI openAIService;
    private final ChatbotPrompt promptBuilder;
    private final ChatbotAntwoordVerfijner antwoordVerfijner;

    private PendingClarification pendingClarification;
    private PendingEmailDraft pendingEmailDraft;

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

        PendingResolution pendingResolution = resolvePendingClarification(question);
        if (pendingResolution.immediateResponse() != null) {
            String immediate = linkifyPlainEmailAddresses(pendingResolution.immediateResponse());
            appendConversationTurn(question, immediate);
            return immediate;
        }

        if (pendingEmailDraft != null) {
            if (isEmailDraftConfirmation(question)) {
                String draft = buildEmailDraft(question, pendingEmailDraft);
                clearPendingEmailDraft();
                appendConversationTurn(question, draft);
                return draft;
            }

            if (isEmailDraftDecline(question)) {
                String declineResponse = "Prima, dan laat ik het daarbij. Laat het gerust weten als je later toch een conceptmail wilt.";
                clearPendingEmailDraft();
                appendConversationTurn(question, declineResponse);
                return declineResponse;
            }
        }

        String effectiveQuestion = pendingResolution.effectiveQuestion();
        String historyQuestion = pendingResolution.historyQuestion();

        String searchQuery = effectiveQuestion;
        String llmQuestion = buildQuestionWithMemory(effectiveQuestion);

        SearchResult searchResult = knowledgeService.search(searchQuery);
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

        ClarificationRequest clarificationRequest = determineClarificationRequest(effectiveQuestion, rankedChunks);
        if (clarificationRequest != null) {
            pendingClarification = new PendingClarification(effectiveQuestion, clarificationRequest.message());
            appendConversationTurn(historyQuestion, clarificationRequest.message());
            return clarificationRequest.message();
        }

        String finalSystemPrompt =
                promptBuilder.buildSystemPrompt(llmQuestion, contextString, conversationHistoryText);

        logChunksForFinalPrompt(searchQuery, sourceById, contextString);

        String answer = openAIService.chat(finalSystemPrompt);
        String normalizedAnswer =
                antwoordVerfijner.normalizeAnswerWithPageReferences(effectiveQuestion, answer, sourceById);
        normalizedAnswer = addEmailDraftOfferIfNeeded(normalizedAnswer, historyQuestion, effectiveQuestion, rankedChunks, sourceById);

        clearPendingClarification();
        appendConversationTurn(historyQuestion, normalizedAnswer);
        return normalizedAnswer;
    }

    // Verrijkt alleen de prompt en nooit de zoekquery.
    // We doen dit conservatief om context-drift te voorkomen.
    private String buildQuestionWithMemory(String question) {
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

        return enrichedQuestion;
    }

    private ClarificationRequest determineClarificationRequest(String effectiveQuestion,
                                                               List<ChunkEmbedding> rankedChunks) {
        ClarificationRequest functionClarification = determineFunctionClarificationRequest(effectiveQuestion, rankedChunks);
        if (functionClarification != null) {
            return functionClarification;
        }

        if (needsGeneralClarification(effectiveQuestion, rankedChunks)) {
            return new ClarificationRequest(
                    "Ik help je graag, maar ik mis nog wat context om je vraag goed te beantwoorden. "
                    + "Kun je iets specifieker aangeven waar je vraag over gaat?");
        }

        return null;
    }

    private boolean needsGeneralClarification(String effectiveQuestion,
                                              List<ChunkEmbedding> rankedChunks) {
        if (effectiveQuestion == null || effectiveQuestion.isBlank()) {
            return true;
        }

        if (rankedChunks != null && !rankedChunks.isEmpty()) {
            return false;
        }

        String normalized = effectiveQuestion.trim().toLowerCase(Locale.ROOT);
        return isContextDependentQuestion(normalized)
                || normalized.length() < 10;
    }

    private ClarificationRequest determineFunctionClarificationRequest(String effectiveQuestion,
                                                                       List<ChunkEmbedding> rankedChunks) {
        if (effectiveQuestion == null || effectiveQuestion.isBlank() || rankedChunks == null || rankedChunks.isEmpty()) {
            return null;
        }

        if (!isContextDependentQuestion(effectiveQuestion)) {
            return null;
        }

        Set<String> questionFunctions = knowledgeService.detectFunctionLabels(effectiveQuestion);
        if (!questionFunctions.isEmpty()) {
            return null;
        }

        int inspectedChunks = Math.min(3, rankedChunks.size());
        Set<String> explicitScopes = new LinkedHashSet<>();
        boolean hasGeneralChunk = false;

        for (int i = 0; i < inspectedChunks; i++) {
            ChunkEmbedding chunk = rankedChunks.get(i);
            if (chunk == null) {
                continue;
            }

            Set<String> scope = chunk.getFunctionScope();
            if (scope == null || scope.isEmpty()) {
                hasGeneralChunk = true;
                continue;
            }

            explicitScopes.addAll(scope);
        }

        if (hasGeneralChunk || explicitScopes.size() != 1) {
            return null;
        }

        String functionLabel = explicitScopes.iterator().next();
        return new ClarificationRequest(
                "Voor welke functie geldt je vraag? Ik zie dat dit onderdeel functie-afhankelijk is. "
                + "Noem bijvoorbeeld " + functionLabel + " als dat de juiste context is.");
    }

    private boolean isContextDependentQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String normalized = question.trim().toLowerCase(Locale.ROOT);
        if (CONTEXT_DEPENDENT_PATTERN.matcher(normalized).find()) {
            return true;
        }

        String compact = normalized.replaceAll("[!?.,;:]+$", "").trim();
        int wordCount = compact.isEmpty() ? 0 : compact.split("\\s+").length;

        if (wordCount <= SHORT_FOLLOW_UP_WORD_LIMIT) {
            return compact.startsWith("en ")
                    || compact.startsWith("maar ")
                    || compact.startsWith("dus ")
                    || compact.startsWith("ja maar ")
                    || compact.startsWith("hoe zit ")
                    || compact.startsWith("wat bedoel ")
                    || compact.startsWith("waarmee ")
                    || compact.startsWith("daarmee ")
                    || compact.startsWith("daarover ")
                    || compact.startsWith("dat dan")
                    || compact.startsWith("dit dan")
                    || compact.startsWith("die dan")
                    || compact.startsWith("zelfde ");
        }

        return false;
    }

    private PendingResolution resolvePendingClarification(String question) {
        if (pendingClarification == null || question == null || question.isBlank()) {
            return new PendingResolution(question, question, null);
        }

        String effectiveQuestion = pendingClarification.originalQuestion()
                + "\nAanvullende informatie van gebruiker: " + question.trim();
        clearPendingClarification();
        return new PendingResolution(effectiveQuestion, effectiveQuestion, null);
    }

    private void clearPendingClarification() {
        pendingClarification = null;
    }

    private void clearPendingEmailDraft() {
        pendingEmailDraft = null;
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

    private String addEmailDraftOfferIfNeeded(String answer,
                                              String userQuestion,
                                              String effectiveQuestion,
                                              List<ChunkEmbedding> rankedChunks,
                                              Map<Integer, ChunkEmbedding> sourceById) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }

        List<String> emailAddresses = extractEmailAddresses(answer);
        if (emailAddresses.isEmpty()) {
            return answer;
        }

        Map<Integer, ChunkEmbedding> draftSourceMap = sourceById == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(sourceById);
        List<ChunkEmbedding> draftChunks = rankedChunks == null ? List.of() : new ArrayList<>(rankedChunks);
        pendingEmailDraft = new PendingEmailDraft(
                userQuestion,
                effectiveQuestion,
                answer,
                emailAddresses,
                draftChunks,
                draftSourceMap
        );

        String offerText = emailAddresses.size() == 1
                ? "Wil je dat ik een e-mail opstel voor dit adres?"
                : "Wil je dat ik een e-mail opstel voor deze adressen?";

        return answer + "\n\n" + offerText;
    }

    private List<String> extractEmailAddresses(String text) {
        List<String> addresses = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return addresses;
        }

        Matcher matcher = EMAIL_PATTERN.matcher(text);
        while (matcher.find()) {
            String email = matcher.group().trim();
            if (!addresses.contains(email)) {
                addresses.add(email);
            }
        }

        return addresses;
    }

    private boolean isEmailDraftConfirmation(String question) {
        if (question == null) {
            return false;
        }

        String normalized = question.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 120 || normalized.contains("@")) {
            return false;
        }

        return normalized.matches(".*\\b(ja|jazeker|zeker|graag|prima|ok|oke|oké|doe maar|ja graag).*");
    }

    private boolean isEmailDraftDecline(String question) {
        if (question == null) {
            return false;
        }

        String normalized = question.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 120) {
            return false;
        }

        return normalized.matches(".*\\b(nee|nee dank je|niet nodig|liever niet|hoeft niet|geen mail|geen e-mail).*");
    }

    private String buildEmailDraft(String userConfirmation, PendingEmailDraft emailDraft) throws Exception {
        Map<Integer, ChunkEmbedding> sourceMap = emailDraft.sourceById() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(emailDraft.sourceById());
        String contextString = promptBuilder.buildContextText(
                emailDraft.contextChunks() == null ? List.of() : emailDraft.contextChunks(),
                sourceMap
        );
        String conversationHistoryText =
                promptBuilder.buildConversationHistoryText(getRecentConversationHistory(MAX_HISTORY_FOR_PROMPT));
        String prompt = promptBuilder.buildEmailDraftPrompt(
                emailDraft.originalQuestion(),
                emailDraft.effectiveQuestion(),
                emailDraft.originalAnswer(),
                userConfirmation,
                emailDraft.emailAddresses(),
                contextString,
                conversationHistoryText
        );

        String draft = openAIService.chat(prompt);
        if (draft == null || draft.isBlank()) {
            return "Ik kon geen e-mailconcept genereren op basis van de beschikbare informatie.";
        }

        return linkifyPlainEmailAddresses(draft.trim());
    }

    private String linkifyPlainEmailAddresses(String text) {
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

    private record PendingClarification(String originalQuestion, String prompt) {
    }

    private record PendingResolution(String effectiveQuestion, String historyQuestion, String immediateResponse) {
    }

    private record ClarificationRequest(String message) {
    }

    private record PendingEmailDraft(String originalQuestion,
                                     String effectiveQuestion,
                                     String originalAnswer,
                                     List<String> emailAddresses,
                                     List<ChunkEmbedding> contextChunks,
                                     Map<Integer, ChunkEmbedding> sourceById) {
    }
}
