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
    private static final int TALENTCLASS_CONSULTANT_PAYROLL_PAGE = 8;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b");
    private static final Pattern CONTEXT_DEPENDENT_PATTERN = Pattern.compile(
            "\\b(dat|dit|deze|die|daar|daarover|daarvan|ervoor|daarvoor|hierover|hiervan|hiermee|daarmee|zelfde|vorige|eerder|voorgaande|bovenstaande|hierboven|hieronder|hierna)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BONUS_TOPIC_PATTERN = Pattern.compile(
            "\\b(bonus|bonussen|quadrimester|quadrimesters|uitkering)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VACATION_TOPIC_PATTERN = Pattern.compile(
            "\\b(vakantie|vakantiedag|vakantieverlof|verlofdagen|vrije dag)\\b",
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
    private Topic lastExplicitTopic = Topic.UNKNOWN;

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

        registerExplicitTopic(question);

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

        String searchQuery = buildSearchQueryWithMemory(effectiveQuestion);
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

        Optional<String> salaryRedirectAnswer = buildSalaryRedirectAnswer(effectiveQuestion, rankedChunks);
        if (salaryRedirectAnswer.isPresent()) {
            String answer = salaryRedirectAnswer.get();
            clearPendingClarification();
            appendConversationTurn(historyQuestion, answer);
            return answer;
        }

        Optional<String> vacationProrataAnswer = buildVacationProrataAnswer(effectiveQuestion, rankedChunks);
        if (vacationProrataAnswer.isPresent()) {
            String answer = vacationProrataAnswer.get();
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
        Topic followUpTopic = resolveFollowUpTopic(question);
        if (isContextDependentQuestion(question)) {
            List<String> recentQuestions = getRecentUserQuestionsForTopic(MAX_PREVIOUS_USER_QUESTIONS, followUpTopic);
            if (recentQuestions.isEmpty()) {
                recentQuestions = getRecentUserQuestions(MAX_PREVIOUS_USER_QUESTIONS);
            }
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

    // Verrijkt de zoekquery alleen met het meest waarschijnlijke onderwerp uit de context.
    // Hierdoor blijven korte vervolgvragen zoals "en als ik in september start?" op het juiste thema.
    private String buildSearchQueryWithMemory(String question) {
        if (question == null || question.isBlank()) {
            return question;
        }

        String enrichedQuery = question.trim();
        Topic questionTopic = extractExplicitTopic(enrichedQuery);
        if (questionTopic != Topic.UNKNOWN) {
            return enrichedQuery;
        }

        if (!isContextDependentQuestion(question)) {
            return enrichedQuery;
        }

        switch (resolveFollowUpTopic(question)) {
            case BONUS -> enrichedQuery += " bonus bonusberekening quadrimester";
            case VACATION -> enrichedQuery += " vakantieverlof vakantieverlofdagen naar rato";
            default -> {
            }
        }

        return enrichedQuery;
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

    private List<String> getRecentUserQuestionsForTopic(int maxQuestions, Topic topic) {
        List<String> questions = new ArrayList<>();
        if (maxQuestions <= 0 || topic == null || topic == Topic.UNKNOWN) {
            return questions;
        }

        for (int i = conversationHistory.size() - 1; i >= 0 && questions.size() < maxQuestions; i--) {
            org.json.JSONObject message = conversationHistory.get(i);
            if (message == null || !"user".equalsIgnoreCase(message.optString("role"))) {
                continue;
            }

            String content = message.optString("content", "").trim();
            if (content.isEmpty()) {
                continue;
            }

            if (extractExplicitTopic(content) == topic) {
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

    private Optional<String> buildSalaryRedirectAnswer(String question, List<ChunkEmbedding> rankedChunks) {
        if (!isSalaryQuestion(question)) {
            return Optional.empty();
        }

        Set<String> questionFunctions = knowledgeService.detectFunctionLabels(question);
        if (questionFunctions == null || questionFunctions.isEmpty()) {
            return Optional.empty();
        }

        ChunkEmbedding payrollChunk = findRelevantPayrollChunk(rankedChunks, questionFunctions);

        if (payrollChunk == null) {
            return Optional.of("Ik kan de loontabelbron niet direct vinden. Controleer de bron of geef de functie iets specifieker op.");
        }

        String sourceReference = questionFunctions.contains("Talentclass Consultant")
                ? antwoordVerfijner.formatSourceReferenceForPage(payrollChunk, TALENTCLASS_CONSULTANT_PAYROLL_PAGE)
                : antwoordVerfijner.formatSourceReferenceForDisplay(payrollChunk);

        return Optional.of(
                "Klik op de bronlink hieronder om de loontabel te openen.\n"
                + "Bron: " + sourceReference
        );
    }

    private ChunkEmbedding findRelevantPayrollChunk(List<ChunkEmbedding> rankedChunks, Set<String> questionFunctions) {
        if (rankedChunks == null || rankedChunks.isEmpty()) {
            return null;
        }

        List<ChunkEmbedding> payrollChunks = new ArrayList<>();
        for (ChunkEmbedding chunk : rankedChunks) {
            if (isPayrollTableChunk(chunk)) {
                payrollChunks.add(chunk);
            }
        }

        if (payrollChunks.isEmpty()) {
            return null;
        }

        if (questionFunctions != null && !questionFunctions.isEmpty()) {
            for (ChunkEmbedding chunk : payrollChunks) {
                Set<String> scope = chunk.getFunctionScope();
                if (scope == null || scope.isEmpty()) {
                    continue;
                }

                for (String functionLabel : questionFunctions) {
                    if (scope.contains(functionLabel)) {
                        return chunk;
                    }
                }
            }

            return null;
        }

        return payrollChunks.size() == 1 ? payrollChunks.get(0) : null;
    }

    private boolean isPayrollTableChunk(ChunkEmbedding chunk) {
        if (chunk == null || chunk.getText() == null) {
            return false;
        }

        return chunk.getText().trim().toUpperCase(Locale.ROOT).startsWith("LOONTABEL");
    }

    private Optional<String> buildVacationProrataAnswer(String question, List<ChunkEmbedding> rankedChunks) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        if (resolveFollowUpTopic(question) == Topic.BONUS) {
            return Optional.empty();
        }

        boolean vacationQuestion = normalized.contains("vakantie")
                || normalized.contains("vakantiedag")
                || normalized.contains("vakantieverlof")
                || normalized.contains("vrije dag")
                || normalized.contains("verlofdagen");
        if (!vacationQuestion) {
            return Optional.empty();
        }

        Matcher monthMatcher = MONTH_PATTERN.matcher(normalized);
        String monthName = null;
        while (monthMatcher.find()) {
            monthName = monthMatcher.group(1);
        }

        if (monthName == null) {
            return Optional.empty();
        }

        Integer monthNumber = monthNameToNumber(monthName);
        if (monthNumber == null) {
            return Optional.empty();
        }

        int monthsInServiceThisYear = 13 - monthNumber;
        int totalDays = (int) Math.round((26.0 * monthsInServiceThisYear) / 12.0);
        int statutoryDays = (int) Math.round((20.0 * monthsInServiceThisYear) / 12.0);
        int extraDays = Math.max(0, totalDays - statutoryDays);

        ChunkEmbedding sourceChunk = findVacationDaysChunk(rankedChunks);
        String bron = sourceChunk == null
                ? "N.v.t."
                : antwoordVerfijner.formatSourceReferenceForDisplay(sourceChunk);

        String monthLabel = capitalizeMonth(monthName);
        return Optional.of(
                "Functie: Algemeen\n"
                + "Antwoord: Als je in " + monthLabel + " begint, krijg je naar rato recht op "
                + totalDays + " van de 26 vakantieverlofdagen. "
                + "Dat is berekend op basis van " + monthsInServiceThisYear + "/12 van het jaar, afgerond op hele dagen. "
                + "Daarvan zijn " + statutoryDays + " wettelijke en " + extraDays + " bovenwettelijke dagen.\n"
                + "Bron: " + bron
        );
    }

    private Integer monthNameToNumber(String monthName) {
        if (monthName == null || monthName.isBlank()) {
            return null;
        }

        return switch (monthName.toLowerCase(Locale.ROOT)) {
            case "januari", "jan" -> 1;
            case "februari", "feb" -> 2;
            case "maart", "mrt" -> 3;
            case "april", "apr" -> 4;
            case "mei" -> 5;
            case "juni", "jun" -> 6;
            case "juli", "jul" -> 7;
            case "augustus", "aug" -> 8;
            case "september", "sep", "sept" -> 9;
            case "oktober", "okt" -> 10;
            case "november", "nov" -> 11;
            case "december", "dec" -> 12;
            default -> null;
        };
    }

    private String capitalizeMonth(String monthName) {
        if (monthName == null || monthName.isBlank()) {
            return "";
        }

        String normalized = monthName.trim().toLowerCase(Locale.ROOT);
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private ChunkEmbedding findVacationDaysChunk(List<ChunkEmbedding> rankedChunks) {
        ChunkEmbedding exactMatch = findVacationDaysChunkInList(rankedChunks, true);
        if (exactMatch != null) {
            return exactMatch;
        }

        ChunkEmbedding looseMatch = findVacationDaysChunkInList(rankedChunks, false);
        if (looseMatch != null) {
            return looseMatch;
        }

        List<ChunkEmbedding> allChunks = knowledgeService == null ? List.of() : knowledgeService.getChunks();
        exactMatch = findVacationDaysChunkInList(allChunks, true);
        if (exactMatch != null) {
            return exactMatch;
        }

        return findVacationDaysChunkInList(allChunks, false);
    }

    private ChunkEmbedding findVacationDaysChunkInList(List<ChunkEmbedding> chunks, boolean exactPhrase) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }

        for (ChunkEmbedding chunk : chunks) {
            if (chunk == null || chunk.getText() == null) {
                continue;
            }

            String text = chunk.getText().toLowerCase(Locale.ROOT);
            boolean exactMatch = text.contains("26 vakantieverlofdagen")
                    && text.contains("naar rato")
                    && text.contains("40-urige werkweek");
            boolean looseMatch = text.contains("vakantieverlofdagen") && text.contains("naar rato");
            if ((exactPhrase && exactMatch) || (!exactPhrase && looseMatch)) {
                return chunk;
            }
        }

        return null;
    }

    private Topic resolveFollowUpTopic(String question) {
        Topic explicitTopic = extractExplicitTopic(question);
        if (explicitTopic != Topic.UNKNOWN) {
            return explicitTopic;
        }

        if (lastExplicitTopic != Topic.UNKNOWN) {
            return lastExplicitTopic;
        }

        return inferTopicFromRecentUserQuestions();
    }

    private Topic inferTopicFromRecentUserQuestions() {
        for (int i = conversationHistory.size() - 1; i >= 0 && i >= conversationHistory.size() - 8; i--) {
            org.json.JSONObject message = conversationHistory.get(i);
            if (message == null || !"user".equalsIgnoreCase(message.optString("role"))) {
                continue;
            }

            Topic topic = extractExplicitTopic(message.optString("content", ""));
            if (topic != Topic.UNKNOWN) {
                return topic;
            }
        }

        return Topic.UNKNOWN;
    }

    private Topic extractExplicitTopic(String question) {
        if (question == null || question.isBlank()) {
            return Topic.UNKNOWN;
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        if (BONUS_TOPIC_PATTERN.matcher(normalized).find()) {
            return Topic.BONUS;
        }
        if (VACATION_TOPIC_PATTERN.matcher(normalized).find()) {
            return Topic.VACATION;
        }

        return Topic.UNKNOWN;
    }

    private void registerExplicitTopic(String question) {
        Topic explicitTopic = extractExplicitTopic(question);
        if (explicitTopic != Topic.UNKNOWN) {
            lastExplicitTopic = explicitTopic;
        }
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
        if (query == null) {
            return false;
        }

        String normalized = query.toLowerCase(Locale.ROOT);
        if (normalized.contains("bonus")) {
            return false;
        }

        Set<String> questionFunctions = knowledgeService.detectFunctionLabels(query);
        boolean earningQuestion = normalized.matches(".*\\b(verdien|verdient|verdienen|betaal|betaalt|betalen)\\b.*")
                || normalized.matches(".*\\b(wat|hoeveel)\\s+(verdien|verdient|verdienen)\\b.*")
                || normalized.contains("wat verdient")
                || normalized.contains("hoeveel verdient")
                || normalized.contains("salaris van")
                || normalized.contains("loon van")
                || normalized.contains("inkomen van");

        return earningQuestion && questionFunctions != null && !questionFunctions.isEmpty();
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

    private enum Topic {
        BONUS,
        VACATION,
        UNKNOWN
    }

    private record PendingEmailDraft(String originalQuestion,
                                     String effectiveQuestion,
                                     String originalAnswer,
                                     List<String> emailAddresses,
                                     List<ChunkEmbedding> contextChunks,
                                     Map<Integer, ChunkEmbedding> sourceById) {
    }
}
