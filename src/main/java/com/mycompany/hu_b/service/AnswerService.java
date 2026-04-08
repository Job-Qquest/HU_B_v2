/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.Chunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AnswerService {

    private final List<org.json.JSONObject> conversationHistory = new ArrayList<>();
    private final KnowledgeService knowledgeService;
    private final OpenAIService openAIService;

    public AnswerService(KnowledgeService knowledgeService, OpenAIService openAIService) {
        this.knowledgeService = knowledgeService;
        this.openAIService = openAIService;
    }

    public String ask(String question) throws Exception {
        List<Chunk> topChunks = knowledgeService.search(question);
        Map<Integer, Chunk> sourceById = new LinkedHashMap<>();

        StringBuilder contextText = new StringBuilder();
        int sourceId = 1;
        for (Chunk c : topChunks) {
            Set<String> chunkFunctions = (c.getFunctionScope() == null || c.getFunctionScope().isEmpty())
                    ? knowledgeService.detectFunctionLabels(c.getText())
                    : c.getFunctionScope();
            String functionMarker = chunkFunctions.isEmpty()
                    ? "ALGEMEEN"
                    : String.join(", ", chunkFunctions);
            sourceById.put(sourceId, c);
            contextText.append("BRON ")
                    .append(sourceId)
                    .append(" | PAGINA ")
                    .append(c.getPage())
                    .append(" | FUNCTIEAFHANKELIJK: ")
                    .append(functionMarker)
                    .append(": ")
                    .append(c.getText())
                    .append("\n\n");
            sourceId++;
        }

        String contextString = contextText.toString();
        Optional<String> verzuimDurationAnswer = buildVerzuimDurationAnswer(question, topChunks);
        if (verzuimDurationAnswer.isPresent()) {
            return verzuimDurationAnswer.get();
        }

        String systemPrompt =

"# ROLE " +
"Je bent HU-B, gedraag je zoals iemand die 20 jaar HR ervaring heeft en die vragen beantwoord op basis van de personeelsgids." +

"# DOEL " +
"Verstrek accurate, feitelijke informatie over het gevraagde HR-onderwerp op basis van de verstrekte PERSONEELSGIDS. " +

"# CONSTRAINTS (STRIKTE REGELS) " +
"1. Source Grounding: Gebruik ALLEEN de informatie tussen de <context> tags. " +
"Als het antwoord daar niet staat geef je aan wat je niet kan vinden." +

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

        String answer = openAIService.chat(finalSystemPrompt);

        String normalizedAnswer = normalizeAnswerWithPageReferences(question, answer, sourceById);
        conversationHistory.add(new org.json.JSONObject().put("role", "user").put("content", question));
        conversationHistory.add(new org.json.JSONObject().put("role", "assistant").put("content", normalizedAnswer));

        if (conversationHistory.size() > 12)
            conversationHistory.subList(0, conversationHistory.size() - 12).clear();

        return normalizedAnswer;
    }

    public Optional<String> buildVerzuimDurationAnswer(String question, List<Chunk> contextChunks) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

        String normalized = question.toLowerCase();
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
            if (chunk == null || chunk.getText() == null) {
                continue;
            }

            String chunkText = chunk.getText().toLowerCase();
            if (chunkText.contains("langdurig verzuim")
                    && (chunkText.contains("meer dan twee weken") || chunkText.contains("langer dan twee weken"))) {
                sourcePage = chunk.getPage();
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

    public String normalizeAnswerWithPageReferences(String question, String rawAnswer, Map<Integer, Chunk> sourceById) throws Exception {
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
        Map<Integer, Double> pageRelevanceScores = new java.util.HashMap<>();
        Map<Integer, Integer> pageChunkCounts = new java.util.HashMap<>();

        List<Double> questionEmbedding = openAIService.embed(question);

        if (bronField != null && !bronField.equalsIgnoreCase("N.v.t.")) {
            Matcher matcher = Pattern.compile("\\d+").matcher(bronField);
            while (matcher.find()) {
                int id = Integer.parseInt(matcher.group());
                Chunk chunk = sourceById.get(id);
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

    public boolean isRelevantCitation(String question, String answerText, String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            return false;
        }

        Set<String> questionTokens = knowledgeService.tokenize(question == null ? "" : question);
        Set<String> chunkTokens = knowledgeService.tokenize(chunkText);

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

        return knowledgeService.lexicalSimilarity(answerText == null ? "" : answerText, chunkText) >= 0.10;
    }

    public double citationRelevanceScore(String question, String answerText, String chunkText,
                                          List<Double> questionEmbedding, List<Double> chunkEmbedding) {
        if (chunkText == null || chunkText.isBlank()) {
            return 0.0;
        }

        double lexicalQuestion = knowledgeService.lexicalSimilarity(question == null ? "" : question, chunkText);
        double lexicalAnswer   = knowledgeService.lexicalSimilarity(answerText == null ? "" : answerText, chunkText);
        double lexicalScore    = (lexicalQuestion * 0.65) + (lexicalAnswer * 0.35);

        if (questionEmbedding != null && chunkEmbedding != null
                && !questionEmbedding.isEmpty() && !chunkEmbedding.isEmpty()) {
            double semanticScore = knowledgeService.cosine(questionEmbedding, chunkEmbedding);
            return (semanticScore * 0.60) + (lexicalScore * 0.40);
        }

        return lexicalScore;
    }

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

    public String stripSourceArtifacts(String answerText) {
        if (answerText == null) {
            return "";
        }

        String cleaned = answerText;
        cleaned = cleaned.replaceAll("(?is)\\bBronID\\s*:\\s*[^\\n]*", "");
        cleaned = cleaned.replaceAll("(?is)\\bBron\\s*:\\s*[^\\n]*", "");

        return cleaned.trim();
    }
}