/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import com.mycompany.hu_b.model.ChunkDraft;
import com.mycompany.hu_b.util.FunctionProfile;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

// De class leest de PDF in, splitst de tekst in chunks, maakt embeddings,
// herkent functiegebonden inhoud en zoekt de meest relevante chunks
// voor een gebruikersvraag met een combinatie van semantische en lexicale matching.

public class PdfProcessing {

// Lijst met alle chunks uit de PDF, inclusief embedding, pagina en functiescope
    private final List<ChunkEmbedding> chunks = new ArrayList<>();
    private final OpenAI openAIService;

    public PdfProcessing(OpenAI openAIService) {
        this.openAIService = openAIService;
    }
// Geeft alle geladen chunks terug
    public List<ChunkEmbedding> getChunks() {
        return chunks;
    }

//  Leest de PDF in, splitst de tekst per pagina in chunks en maakt voor elke chunk een embedding.
    public void loadGuide(String pdfPath) throws Exception {
        PDDocument doc = Loader.loadPDF(new File(pdfPath));
        PDFTextStripper stripper = new PDFTextStripper();
// Houdt bij welke functie-scope actief is tijdens het lezen
// Bijvoorbeeld: Talentclass, TC consultant, etc.
        Set<String> activeFunctionScope = new LinkedHashSet<>();

// Loopt door alle pagina's van de PDF
        for (int page = 1; page <= doc.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);

// Haalt tekst van 1 pagina op
            String pageText = stripper.getText(doc);
// Deelt pagina op in chunks en houd functiescope bij
            List<ChunkDraft> drafts = chunkTextWithFunctionScope(pageText, 800, activeFunctionScope);

// Maakt van elke draft een definitieve chunk met embedding
            for (ChunkDraft draft : drafts) {
                chunks.add(new ChunkEmbedding(draft.getText(), openAIService.embed(draft.getText()), page, draft.getFunctionScope()));
            }
        }

        doc.close();

// Als er geen chunks zijn geladen, klopt er iets niet met de PDF
        if (chunks.isEmpty()) {
            throw new RuntimeException("Geen tekst uit personeelsgids geladen.");
        }
    }

// Simpele methode om tekst op te splitsen in blokken van een vast aantal woorden. 
// Deze methode kijkt niet naar functie-scope of regels, alleen naar woordenaantal
    public static List<String> chunkText(String text, int size) {
        List<String> result = new ArrayList<>();
        String[] words = text.split("\\s+");

        for (int i = 0; i < words.length; i += size) {
            result.add(String.join(" ",
                    Arrays.copyOfRange(words, i, Math.min(words.length, i + size))));
        }

        return result;
    }

// Splitst tekst op in chunks van maximaal maxWords woorden,
// terwijl functie-specifieke kopjes worden herkend en bijgehouden.
// activeScope wordt aangepast zodra een functiekop wordt gevonden.
    public List<ChunkDraft> chunkTextWithFunctionScope(String text, int maxWords, Set<String> activeScope) {
        List<ChunkDraft> result = new ArrayList<>();
// Lege tekst -> geen chunks
        if (text == null || text.isBlank()) {
            return result;
        }

        StringBuilder buffer = new StringBuilder();
        int bufferWords = 0;
        Set<String> bufferScope = new LinkedHashSet<>(activeScope);

// Split pagina op in losse regels
        String[] lines = text.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

// Kijk of deze regel een functiekop bevat
            Set<String> headerLabels = detectFunctionHeaderLabels(line);
            if (!headerLabels.isEmpty()) {
// Als buffer al tekst bevat: eerst huidige chunk opslaan
                if (buffer.length() > 0) {
                    result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
                    buffer.setLength(0);
                    bufferWords = 0;
                }
// Nieuwe actieve scope instellen op basis van header
                activeScope.clear();
                activeScope.addAll(headerLabels);
                bufferScope = new LinkedHashSet<>(activeScope);
                continue;
            }
// Tel woorden in de huidige regel
            int lineWords = countWords(line);
            if (lineWords == 0) {
                continue;
            }
// Als chunk te groot wordt: eerst huidige chunk afsluiten
            if (bufferWords + lineWords > maxWords && buffer.length() > 0) {
                result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
                buffer.setLength(0);
                bufferWords = 0;
// Nieuwe chunk krijgt de op dat moment actieve scope mee
                bufferScope = new LinkedHashSet<>(activeScope);
            }
// Voeg regel toe aan de buffer
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(line);
            bufferWords += lineWords;
        }
// Laatste chunk ook opslaan
        if (buffer.length() > 0) {
            result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
        }

        return result;
    }
// Telt het aantal woorden in een stuk tekst
    public int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
// Probeert functielabels te herkennen in een kopregel
// Bijvoorbeeld een header die alleen geldt voor een bepaalde functie
    public Set<String> detectFunctionHeaderLabels(String line) {
        return FunctionProfile.detectFunctionHeaderLabels(line);
    }
// Probeert functielabels te herkennen in gewone tekst
    public Set<String> detectFunctionLabels(String text) {
        return FunctionProfile.detectFunctionLabels(text);
    }
// Berekent cosine similarity tussen twee embedding-vectoren
// Hoe dichter bij 1, hoe meer de vectoren op elkaar lijken
    public double cosine(List<Double> a, List<Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
// Zoekt de meest relevante chunks voor een query
    public List<ChunkEmbedding> search(String query) throws Exception {
        String retrievalQuery = buildRetrievalQuery(query);
        List<Double> qVec = openAIService.embed(retrievalQuery);

        List<Map.Entry<ChunkEmbedding, Double>> scoredChunks = new ArrayList<>();

        for (ChunkEmbedding c : chunks) {
            double semanticScore = cosine(c.getEmbedding(), qVec);
            double lexicalScore = lexicalSimilarity(retrievalQuery, c.getText());
// Combineert semantic en lexical score
            double score = (semanticScore * 0.80) + (lexicalScore * 0.20);
            scoredChunks.add(Map.entry(c, score));
        }
// Sorteer van hoogste naar laagste score
        scoredChunks.sort((a, b)
                -> Double.compare(b.getValue(), a.getValue()));

        double MIN_SIMILARITY = 0.3;
        int MAX_RESULTS = 8;

        List<ChunkEmbedding> results = new ArrayList<>();
        Set<ChunkEmbedding> added = new HashSet<>();

// Bepaal speciale kenmerken van de vraag
        boolean talentclassVraag = isTalentclassQuestion(query);
        boolean referralVraag = isReferralQuestion(query);
        Set<String> functionLabels = detectFunctionLabels(query);
// Eerste ronde: alleen chunks boven minimum similarity
        for (Map.Entry<ChunkEmbedding, Double> entry : scoredChunks) {
            if (entry.getValue() < MIN_SIMILARITY) {
                break;
            }

            ChunkEmbedding candidate = entry.getKey();
// Als er functie-labels in de vraag zitten, dan moet chunk matchen met die scope
            if (!functionLabels.isEmpty() && !matchesFunctionScope(candidate, functionLabels)) {
                continue;
            }
// Alleen chunks accepteren die echt Talentclass-gerelateerd zijn
            if (talentclassVraag && !referralVraag && !isTalentclassChunk(candidate)) {
                continue;
            }
// Voegt geldige chunk toe
            if (candidate.getText() != null && !candidate.getText().isBlank() && added.add(candidate)) {
                results.add(candidate);
            }

            if (results.size() >= MAX_RESULTS) {
                return results;
            }
        }
// Tweede ronde: vul aan met lagere scores als er nog te weinig resultaten zijn
        for (Map.Entry<ChunkEmbedding, Double> entry : scoredChunks) {
            ChunkEmbedding candidate = entry.getKey();

            if (!functionLabels.isEmpty() && !matchesFunctionScope(candidate, functionLabels)) {
                continue;
            }

            if (talentclassVraag && !referralVraag && !isTalentclassChunk(candidate)) {
                continue;
            }

            if (candidate.getText() != null && !candidate.getText().isBlank() && added.add(candidate)) {
                results.add(candidate);
            }

            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }

        return results;
    }
// Verrijkt de zoekquery met extra synoniemen/verwante termen
// Dit helpt om relevantere chunks terug te vinden
    public String buildRetrievalQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String normalized = query.toLowerCase(Locale.ROOT);
        StringBuilder enriched = new StringBuilder(query.trim());
// Verrijkt declaratie-vragen met extra woorden
        if (normalized.contains("declaratie") || normalized.contains("declareren")) {
            enriched.append(" declareren onkosten onkostendeclaratie declaratie indienen bonnetjes terugbetaling expense claim");
        }
// Verrijkt verzuim-vragen met extra woorden
        if (normalized.contains("ziekmeld") || normalized.contains("verzuim")) {
            enriched.append(" ziekmelding langdurig verzuim herstelmelding arbodienst");
        }

        return enriched.toString();
    }
// Controleert of een chunk past binnen de gevraagde functie-scope
    public boolean matchesFunctionScope(ChunkEmbedding chunk, Set<String> requiredLabels) {
        if (chunk == null || chunk.getText() == null || requiredLabels == null || requiredLabels.isEmpty()) {
            return true;
        }
// Als de chunk al een bekende functionScope heeft: controleer of minstens één label overeenkomt
        if (chunk.getFunctionScope() != null && !chunk.getFunctionScope().isEmpty()) {
            for (String label : requiredLabels) {
                if (chunk.getFunctionScope().contains(label)) {
                    return true;
                }
            }
            return false;
        }
// Anders: probeer labels uit de tekst af te leiden
        Set<String> chunkLabels = detectFunctionLabels(chunk.getText());
        if (chunkLabels.isEmpty()) {
            return true;
        }
// Check overlap tussen gevraagde labels en chunk labels
        for (String label : requiredLabels) {
            if (chunkLabels.contains(label)) {
                return true;
            }
        }

        return false;
    }
// Berekent een simpele lexicale overeenkomst op basis van token overlap
// Score = aantal overlappende tokens / aantal query tokens
    public double lexicalSimilarity(String query, String chunkText) {
        if (query == null || chunkText == null || query.isBlank() || chunkText.isBlank()) {
            return 0.0;
        }

        Set<String> queryTokens = tokenize(query);
        Set<String> chunkTokens = tokenize(chunkText);

        if (queryTokens.isEmpty() || chunkTokens.isEmpty()) {
            return 0.0;
        }

        int overlap = 0;
        for (String token : queryTokens) {
            if (chunkTokens.contains(token)) {
                overlap++;
            }
        }

        return (double) overlap / (double) queryTokens.size();
    }
// Zet tekst om in een set tokens: lowercase, verwijdert leestekens, houdt alleen tokens van langte >= 3 over
    public Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();

        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();

        if (normalized.isEmpty()) {
            return tokens;
        }

        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }

        return tokens;
    }
// Detecteert of de vraag specifiek over Talentclass gaat
    public boolean isTalentclassQuestion(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("talentclass")
                || normalized.contains("tc consultant")
                || normalized.contains("tc-consultant");
    }
// Detecteert of een chunk tekst over Talentclass gaat
    public boolean isTalentclassChunk(ChunkEmbedding chunk) {
        if (chunk == null || chunk.getText() == null) {
            return false;
        }

        String normalized = chunk.getText().toLowerCase(Locale.ROOT);
        return normalized.contains("talentclass")
                || normalized.contains("talent class")
                || normalized.contains("tc consultant")
                || normalized.contains("tc-consultant")
                || normalized.contains("tc consultants")
                || normalized.contains("tc-consultants");
    }
// Detecteert of de vraag over referral / voordragen / aandragen gaat
    public boolean isReferralQuestion(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("referral")
                || normalized.contains("voordraag")
                || normalized.contains("voordragen")
                || normalized.contains("aandraag")
                || normalized.contains("aandragen")
                || normalized.contains("iemand aanbreng")
                || normalized.contains("iemand voordraag");
    }
}