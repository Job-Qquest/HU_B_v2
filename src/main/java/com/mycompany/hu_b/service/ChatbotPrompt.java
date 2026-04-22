package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

// Deze class is verantwoordelijk voor:
//  Het bouwen van de context (tekst uit PDF chunks)
//  Het maken van de system prompt voor OpenAI
//  Het afhandelen van speciale gevallen (zoals verzuimduur)

public class ChatbotPrompt {

    private final PdfProcessing knowledgeService;

    public ChatbotPrompt(PdfProcessing knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

// Bouwt de contexttekst die naar OpenAI wordt gestuurd
    public String buildContextText(List<ChunkEmbedding> topChunks,
                                   Map<Integer, ChunkEmbedding> sourceById) {
        StringBuilder contextText = new StringBuilder();
        contextText.append("[GEWOGEN CONTEXT]\n");

        int sourceId = 1;
        if (topChunks != null) {
            for (ChunkEmbedding c : topChunks) {
                if (sourceById != null) {
                    sourceById.put(sourceId, c);
                }
                appendContextChunk(contextText, sourceId, c);
                sourceId++;
            }
        }

        return contextText.toString();
    }

// Bouwt de contexttekst voor gids- en externe chunks, met gids eerst.
    public String buildContextText(List<ChunkEmbedding> guideChunks,
                                   List<ChunkEmbedding> externalChunks,
                                   Map<Integer, ChunkEmbedding> sourceById) {

        StringBuilder contextText = new StringBuilder();
        int sourceId = 1;

        contextText.append("[PERSONEELSGIDS]\n");
// Loopt door de gevonden gidschunks
        for (ChunkEmbedding c : guideChunks) {
            sourceById.put(sourceId, c);
            appendContextChunk(contextText, sourceId, c);
            sourceId++;
        }

        contextText.append("\n[EXTERNE BRONNEN]\n");
// Loopt door de externe chunks
        for (ChunkEmbedding c : externalChunks) {
            sourceById.put(sourceId, c);
            appendContextChunk(contextText, sourceId, c);
            sourceId++;
        }

        return contextText.toString();
    }

    private void appendContextChunk(StringBuilder contextText, int sourceId, ChunkEmbedding c) {
        if (c == null) {
            return;
        }

// Bepaalt voor welke functie deze chunk geldt
        Set<String> chunkFunctions = (c.getFunctionScope() == null || c.getFunctionScope().isEmpty())
                ? knowledgeService.detectFunctionLabels(c.getText())
                : c.getFunctionScope();

        String sourceType = c.isPrimaryGuide()
                ? "PERSONEELSGIDS"
                : "EXTERNE BRONNEN";

// Als er geen functielabel is -> markeer als ALGEMEEN
        String functionMarker = chunkFunctions.isEmpty()
                ? "ALGEMEEN"
                : String.join(", ", chunkFunctions);

// Koppel deze chunk aan een Bron-ID
        contextText.append("BRON ")
                .append(sourceId)
                .append(" | ")
                .append(sourceType)
                .append(" | ")
                .append(formatSourceReference(c))
                .append(" | FUNCTIEAFHANKELIJK: ")
                .append(functionMarker)
                .append(": ")
                .append(c.getText())
                .append("\n\n");
    }

// Maakt een leesbare bronverwijzing voor de context.
// PDF-chunks krijgen een paginanummer; Word-bronnen krijgen alleen de bestandsnaam.
    private String formatSourceReference(ChunkEmbedding chunk) {
        if (chunk == null) {
            return "N.v.t.";
        }

        String label = chunk.getSourceLabel();
        String sourceName = chunk.getSourceName();
        String sourceUrl = chunk.getSourceUrl();

        int page = chunk.getPage();
        String pageText = page > 0 ? "pagina " + page : null;

        if ((sourceName != null && !sourceName.isBlank()) || (sourceUrl != null && !sourceUrl.isBlank())) {
            String displayLabel = label != null && !label.isBlank()
                    ? label
                    : (sourceName != null && !sourceName.isBlank() ? sourceName : "webpagina");
            List<String> parts = new ArrayList<>();
            if (sourceName != null && !sourceName.isBlank()) {
                parts.add("bron: " + sourceName);
            }
            if (sourceUrl != null && !sourceUrl.isBlank()) {
                parts.add(sourceUrl);
            }
            return displayLabel + " (" + String.join(" | ", parts) + ")";
        }

        if (label != null && !label.isBlank()) {
            return pageText != null ? label + " (" + pageText + ")" : label;
        }

        if (pageText != null) {
            return pageText;
        }

        return "PAGINA " + page;
    }

// Bouwt de volledige system prompt voor OpenAI
// Dit bepaalt hoe het model moet denken en antwoorden
    public String buildSystemPrompt(String question, String contextString, String conversationHistoryText) {

        String systemPrompt =

"# ROLE " +
"Je bent HU-B, gedraag je zoals iemand die 20 jaar HR ervaring heeft en die vragen beantwoord als personificatie van de personeelsgids en/of meegegeven bronnen." +

"# DOEL " +
"Verstrek accurate, feitelijke, volledige informatie over het gevraagde HR-onderwerp op basis van de verstrekte PERSONEELSGIDS en/of meegegeven bronnen. " +
                

"# CONSTRAINTS (STRIKTE REGELS) " +
"1. Source Grounding: Gebruik ALLEEN de informatie tussen de <context> tags. " +
"Als het antwoord daar niet staat geef je aan wat je niet weet." +
"Als je geen antwoord kan vinden, geef je vriendelijk aan dat je dit niet weet." +
"Als het niet binnen de HR-context van de personeelsgids valt, geef je vriendelijk aan dat je daar niet bij kan helpen." + 
                
"2. Scope: Behandel de vraag alleen binnen de HR-context van de personeelsgids en/of meegegeven bronnen."+
"Als de vraag een specifieke doelgroep/functie noemt (zoals Talentclass of TC consultant), gebruik dan alleen context waarin die doelgroep/functie expliciet voorkomt, behalve bij referral/voordracht-vragen waar een algemene referralregeling van toepassing kan zijn. " +

"2b. Doorvragen bij onduidelijkheid: Als cruciale gebruikersinformatie ontbreekt om de vraag goed te beantwoorden, stel dan eerst 1 gerichte vervolgvraag in plaats van te gokken. " +

"3. Geen Hallucinaties: Verzin nooit paginanummers, citaten, data of percentages die niet letterlijk in de tekst staan. " +

"4. Bronvermelding (verplicht): " +
"Als informatie uit de PERSONEELSGIDS wordt gebruikt, moet je: " +
"- uitsluitend bron-ID's noemen die in de context voorkomen (BRON X), " +
"- geen paginanummers zelf uitschrijven. " +
"- splits de bronvermelding met een enter van de rest van het antwoord. " +
"- geef voorrang aan de PERSONEELSGIDS boven EXTERNE BRONNEN. " +
"- gebruik externe bronnen alleen als aanvulling of wanneer de personeelsgids het antwoord niet bevat. " +
"- als personeelsgids en externe bron botsen, volg de personeelsgids. " +

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

"BronID: [Noem altijd eerst alleen BRON-nummers, bijv. 2 of 2,5. Indien niet gevonden: N.v.t.] " +

"<gesprekshistorie> " +
"{{gesprekshistorie}} " +
"</gesprekshistorie> " +

"<context> " +
"{{hier de tekst uit de personeelsgids en/of meegegeven bronnen}} " +
"</context> " +

"<vraag_gebruiker> " +
"{{vraag}} " +
"</vraag_gebruiker>";

        return systemPrompt
                .replace("{{gesprekshistorie}}", conversationHistoryText == null ? "Geen relevante gesprekshistorie." : conversationHistoryText)
                .replace("{{hier de tekst uit de personeelsgids en/of meegegeven bronnen}}", contextString)
                .replace("{{vraag}}", question);
    }

    // Bouwt een compacte tekstweergave van recente vraag-antwoordparen.
    // Deze historie helpt het model bij vervolgvragen, maar geldt niet als feitelijke bron.
    public String buildConversationHistoryText(List<JSONObject> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return "Geen relevante gesprekshistorie.";
        }

        StringBuilder historyText = new StringBuilder();
        int turnNumber = 1;

        for (JSONObject message : conversationHistory) {
            if (message == null) {
                continue;
            }

            String role = message.optString("role", "").trim();
            String content = message.optString("content", "").trim();
            if (role.isEmpty() || content.isEmpty()) {
                continue;
            }

            String roleLabel = "user".equalsIgnoreCase(role) ? "Gebruiker" : "Assistent";
            historyText.append("Turn ").append(turnNumber)
                    .append(" - ")
                    .append(roleLabel)
                    .append(": ")
                    .append(content.replace("\n", " ").trim())
                    .append("\n");
            turnNumber++;
        }

        if (historyText.length() == 0) {
            return "Geen relevante gesprekshistorie.";
        }

        return historyText.toString().trim();
    }

// Speciale logica voor verzuimvragen (zonder OpenAI)
    public Optional<String> buildVerzuimDurationAnswer(String question, List<ChunkEmbedding> contextChunks) {

// Geen vraag -> stop
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

// Check of het een verzuimvraag is
        String normalized = question.toLowerCase();
        boolean isVerzuimQuestion = normalized.contains("verzuim")
                || normalized.contains("ziek")
                || normalized.contains("ziekmelding");

// Moet ook over tijd gaan (dagen/weken)
        if (!isVerzuimQuestion || (!normalized.contains("dagen") && !normalized.contains("weken"))) {
            return Optional.empty();
        }

// Zoekt aantal dagen of weken in de vraag
        Matcher daysMatcher = Pattern.compile("(\\d+)\\s*dagen?").matcher(normalized);
        Matcher weeksMatcher = Pattern.compile("(\\d+)\\s*weken?").matcher(normalized);

        Integer totalDays = null;
        if (daysMatcher.find()) {
            totalDays = Integer.parseInt(daysMatcher.group(1));
        } else if (weeksMatcher.find()) {
            totalDays = Integer.parseInt(weeksMatcher.group(1)) * 7;
        }

// Geen getal gevonden -> stop
        if (totalDays == null) {
            return Optional.empty();
        }

// Zoekt bronpagina in context
        ChunkEmbedding sourceChunk = null;
        for (ChunkEmbedding chunk : contextChunks) {
            if (chunk == null || chunk.getText() == null) {
                continue;
            }

// Zoekt specifieke regel over langdurig verzuim
            String chunkText = chunk.getText().toLowerCase();
            if (chunkText.contains("langdurig verzuim")
                    && (chunkText.contains("meer dan twee weken") || chunkText.contains("langer dan twee weken"))) {
                sourceChunk = chunk;
                break;
            }
        }

// Langdurig verzuim is meer dan 14 dagen verzuim
        boolean langdurigVerzuim = totalDays > 14;
        String bron = sourceChunk == null ? "N.v.t." : formatSourceReference(sourceChunk);

// Genereert antwoord zonder OpenAI
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
}
