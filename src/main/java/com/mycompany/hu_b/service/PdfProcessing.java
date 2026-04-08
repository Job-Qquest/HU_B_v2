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

    private final List<ChunkEmbedding> chunks = new ArrayList<>();
    private final OpenAI openAIService;

    public PdfProcessing(OpenAI openAIService) {
        this.openAIService = openAIService;
    }

    public List<ChunkEmbedding> getChunks() {
        return chunks;
    }

    public void loadGuide(String pdfPath) throws Exception {
        PDDocument doc = Loader.loadPDF(new File(pdfPath));
        PDFTextStripper stripper = new PDFTextStripper();
        Set<String> activeFunctionScope = new LinkedHashSet<>();

        for (int page = 1; page <= doc.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);

            String pageText = stripper.getText(doc);
            List<ChunkDraft> drafts = chunkTextWithFunctionScope(pageText, 800, activeFunctionScope);

            for (ChunkDraft draft : drafts) {
                chunks.add(new ChunkEmbedding(draft.getText(), openAIService.embed(draft.getText()), page, draft.getFunctionScope()));
            }
        }

        doc.close();

        if (chunks.isEmpty()) {
            throw new RuntimeException("Geen tekst uit personeelsgids geladen.");
        }
    }

    public static List<String> chunkText(String text, int size) {
        List<String> result = new ArrayList<>();
        String[] words = text.split("\\s+");

        for (int i = 0; i < words.length; i += size) {
            result.add(String.join(" ",
                    Arrays.copyOfRange(words, i, Math.min(words.length, i + size))));
        }

        return result;
    }

    public List<ChunkDraft> chunkTextWithFunctionScope(String text, int maxWords, Set<String> activeScope) {
        List<ChunkDraft> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        StringBuilder buffer = new StringBuilder();
        int bufferWords = 0;
        Set<String> bufferScope = new LinkedHashSet<>(activeScope);

        String[] lines = text.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Set<String> headerLabels = detectFunctionHeaderLabels(line);
            if (!headerLabels.isEmpty()) {
                if (buffer.length() > 0) {
                    result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
                    buffer.setLength(0);
                    bufferWords = 0;
                }

                activeScope.clear();
                activeScope.addAll(headerLabels);
                bufferScope = new LinkedHashSet<>(activeScope);
                continue;
            }

            int lineWords = countWords(line);
            if (lineWords == 0) {
                continue;
            }

            if (bufferWords + lineWords > maxWords && buffer.length() > 0) {
                result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
                buffer.setLength(0);
                bufferWords = 0;
                bufferScope = new LinkedHashSet<>(activeScope);
            }

            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(line);
            bufferWords += lineWords;
        }

        if (buffer.length() > 0) {
            result.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
        }

        return result;
    }

    public int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    public Set<String> detectFunctionHeaderLabels(String line) {
        return FunctionProfile.detectFunctionHeaderLabels(line);
    }

    public Set<String> detectFunctionLabels(String text) {
        return FunctionProfile.detectFunctionLabels(text);
    }

    public double cosine(List<Double> a, List<Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public List<ChunkEmbedding> search(String query) throws Exception {
        String retrievalQuery = buildRetrievalQuery(query);
        List<Double> qVec = openAIService.embed(retrievalQuery);

        List<Map.Entry<ChunkEmbedding, Double>> scoredChunks = new ArrayList<>();

        for (ChunkEmbedding c : chunks) {
            double semanticScore = cosine(c.getEmbedding(), qVec);
            double lexicalScore = lexicalSimilarity(retrievalQuery, c.getText());
            double score = (semanticScore * 0.80) + (lexicalScore * 0.20);
            scoredChunks.add(Map.entry(c, score));
        }

        scoredChunks.sort((a, b)
                -> Double.compare(b.getValue(), a.getValue()));

        double MIN_SIMILARITY = 0.3;
        int MAX_RESULTS = 8;

        List<ChunkEmbedding> results = new ArrayList<>();
        Set<ChunkEmbedding> added = new HashSet<>();

        boolean talentclassVraag = isTalentclassQuestion(query);
        boolean referralVraag = isReferralQuestion(query);
        Set<String> functionLabels = detectFunctionLabels(query);

        for (Map.Entry<ChunkEmbedding, Double> entry : scoredChunks) {
            if (entry.getValue() < MIN_SIMILARITY) {
                break;
            }

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
                return results;
            }
        }

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

    public String buildRetrievalQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String normalized = query.toLowerCase(Locale.ROOT);
        StringBuilder enriched = new StringBuilder(query.trim());

        if (normalized.contains("declaratie") || normalized.contains("declareren")) {
            enriched.append(" declareren onkosten onkostendeclaratie declaratie indienen bonnetjes terugbetaling expense claim");
        }

        if (normalized.contains("ziekmeld") || normalized.contains("verzuim")) {
            enriched.append(" ziekmelding langdurig verzuim herstelmelding arbodienst");
        }

        return enriched.toString();
    }

    public boolean matchesFunctionScope(ChunkEmbedding chunk, Set<String> requiredLabels) {
        if (chunk == null || chunk.getText() == null || requiredLabels == null || requiredLabels.isEmpty()) {
            return true;
        }

        if (chunk.getFunctionScope() != null && !chunk.getFunctionScope().isEmpty()) {
            for (String label : requiredLabels) {
                if (chunk.getFunctionScope().contains(label)) {
                    return true;
                }
            }
            return false;
        }

        Set<String> chunkLabels = detectFunctionLabels(chunk.getText());
        if (chunkLabels.isEmpty()) {
            return true;
        }

        for (String label : requiredLabels) {
            if (chunkLabels.contains(label)) {
                return true;
            }
        }

        return false;
    }

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

    public boolean isTalentclassQuestion(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("talentclass")
                || normalized.contains("tc consultant")
                || normalized.contains("tc-consultant");
    }

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