package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkDraft;
import com.mycompany.hu_b.model.ChunkEmbedding;
import com.mycompany.hu_b.util.FunctionProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public abstract class KnowledgeProcessingUtils {

    final List<ChunkEmbedding> chunks = new ArrayList<>();
    final OpenAI openAIService;

    private static final double GUIDE_THRESHOLD = 0.65;             //bepaald wanneer personeelsgids genoeg is en wanneer extra info benodigd is uit externe bron (guide score > guide threshold = pg is genoeg)
    private static final double MIN_SIMILARITY = 0.15;              //alles wat minder dan x similar is (embedding en woord overlap) wordt weggegooid. lager = meer ruis, hoger = minder resultaten
    private static final int MAX_RESULTS = 6;                       //max aantal chunks dat je teruggeeft. laag = weinig context, hoog = meer kans op mix van bronnen
    private static final int MIN_GUIDE_RESULTS = 1;                 //neemt minimaal x personeelsgids chunks mee
    private static final double GUIDE_WEIGHT = 1.1;                 //boost de personeelsgids chunk (deze moet dus iets hoger staan dan external_weight)
    private static final double EXTERNAL_WEIGHT = 1.0;              //boost de externe bron chunk
    private static final double MAX_DUPLICATE_SIMILARITY = 0.97;    //voorkomt dubbele info. te laag zorgt ervoor dat je altijd maar 1 bron krijgt
    private static final int TARGET_CHUNK_WORDS = 700;
    private static final int MAX_CHUNK_WORDS = 800;
    private static final int CHUNK_OVERLAP_WORDS = 75;

    protected KnowledgeProcessingUtils(OpenAI openAIService) {
        this.openAIService = openAIService;
    }

    public List<ChunkEmbedding> getChunks() {
        return chunks;
    }

    // Vervangt alle geladen chunks in één keer.
    public void replaceChunks(List<ChunkEmbedding> newChunks) {
        chunks.clear();
        if (newChunks != null && !newChunks.isEmpty()) {
            chunks.addAll(newChunks);
        }
    }

    // Leegt alle geladen chunks.
    public void clearChunks() {
        chunks.clear();
    }

    // Zet een bestandspad om naar een nette titel voor bronvermelding.
    // We gebruiken de bestandsnaam zonder extensie zodat Word-bronnen geen paginanummer nodig hebben.
    protected String buildSourceLabel(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }

        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    // Simpele methode om tekst op te splitsen in blokken van een vast aantal woorden.
    // Deze methode kijkt niet naar functie-scope of regels, alleen naar woordenaantal.
    public static List<String> chunkText(String text, int size) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank() || size <= 0) {
            return result;
        }

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
        List<ChunkDraft> rawResult = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return rawResult;
        }

        StringBuilder buffer = new StringBuilder();
        int bufferWords = 0;
        Set<String> bufferScope = new LinkedHashSet<>(activeScope == null ? Set.of() : activeScope);

        String[] lines = text.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Set<String> headerLabels = detectFunctionHeaderLabels(line);
            if (!headerLabels.isEmpty()) {
                if (buffer.length() > 0) {
                    rawResult.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
                    buffer.setLength(0);
                    bufferWords = 0;
                }

                if (activeScope != null) {
                    activeScope.clear();
                    activeScope.addAll(headerLabels);
                    bufferScope = new LinkedHashSet<>(activeScope);
                } else {
                    bufferScope = new LinkedHashSet<>(headerLabels);
                }
                continue;
            }

            int lineWords = countWords(line);
            if (lineWords == 0) {
                continue;
            }

            if (bufferWords + lineWords > maxWords && buffer.length() > 0) {
                rawResult.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
                buffer.setLength(0);
                bufferWords = 0;
                bufferScope = new LinkedHashSet<>(activeScope == null ? Set.of() : activeScope);
            }

            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(line);
            bufferWords += lineWords;
        }

        if (buffer.length() > 0) {
            rawResult.add(new ChunkDraft(buffer.toString().trim(), bufferScope));
        }

        int effectiveTargetWords = Math.min(Math.max(1, TARGET_CHUNK_WORDS), Math.max(1, maxWords));
        int effectiveMaxWords = Math.max(effectiveTargetWords, maxWords);
        return mergeDrafts(rawResult, effectiveTargetWords, effectiveMaxWords, CHUNK_OVERLAP_WORDS);
    }

    // Telt het aantal woorden in een stuk tekst.
    public int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private List<ChunkDraft> mergeDrafts(List<ChunkDraft> drafts, int targetWords, int maxWords, int overlapWords) {
        List<ChunkDraft> merged = new ArrayList<>();
        if (drafts == null || drafts.isEmpty()) {
            return merged;
        }

        String currentText = null;
        Set<String> currentScope = null;
        int currentWords = 0;

        for (ChunkDraft draft : drafts) {
            if (draft == null || draft.getText() == null || draft.getText().isBlank()) {
                continue;
            }

            String draftText = draft.getText().trim();
            Set<String> draftScope = draft.getFunctionScope() == null
                    ? Set.of()
                    : new LinkedHashSet<>(draft.getFunctionScope());
            int draftWords = countWords(draftText);

            if (currentText == null) {
                currentText = draftText;
                currentScope = draftScope;
                currentWords = draftWords;
                continue;
            }

            boolean sameScope = scopesEqual(currentScope, draftScope);
            boolean canMergeNormally = sameScope && currentWords < targetWords && currentWords + draftWords <= maxWords;

            if (canMergeNormally) {
                currentText = currentText + " " + draftText;
                currentWords += draftWords;
                continue;
            }

            merged.add(new ChunkDraft(currentText.trim(), currentScope));

            String textToStart = draftText;
            if (sameScope && overlapWords > 0) {
                String overlap = tailWords(currentText, overlapWords);
                if (!overlap.isBlank()) {
                    textToStart = overlap + " " + draftText;
                    int overlappedWords = countWords(overlap);
                    int candidateWords = overlappedWords + draftWords;
                    if (candidateWords <= maxWords) {
                        currentText = textToStart;
                        currentScope = draftScope;
                        currentWords = candidateWords;
                        continue;
                    }
                }
            }

            currentText = textToStart;
            currentScope = draftScope;
            currentWords = countWords(currentText);
        }

        if (currentText != null && !currentText.isBlank()) {
            merged.add(new ChunkDraft(currentText.trim(), currentScope));
        }

        return merged;
    }

    private boolean scopesEqual(Set<String> left, Set<String> right) {
        if (left == null || left.isEmpty()) {
            return right == null || right.isEmpty();
        }
        if (right == null || right.isEmpty()) {
            return false;
        }
        return new LinkedHashSet<>(left).equals(new LinkedHashSet<>(right));
    }

    private String tailWords(String text, int wordCount) {
        if (text == null || text.isBlank() || wordCount <= 0) {
            return "";
        }

        String[] words = text.trim().split("\\s+");
        if (words.length <= wordCount) {
            return text.trim();
        }

        StringBuilder tail = new StringBuilder();
        for (int i = words.length - wordCount; i < words.length; i++) {
            if (tail.length() > 0) {
                tail.append(' ');
            }
            tail.append(words[i]);
        }
        return tail.toString();
    }

    // Probeert functielabels te herkennen in een kopregel.
    // Bijvoorbeeld een header die alleen geldt voor een bepaalde functie.
    public Set<String> detectFunctionHeaderLabels(String line) {
        return FunctionProfile.detectFunctionHeaderLabels(line);
    }

    // Probeert functielabels te herkennen in gewone tekst.
    public Set<String> detectFunctionLabels(String text) {
        return FunctionProfile.detectFunctionLabels(text);
    }

    // Berekent cosine similarity tussen twee embedding-vectoren.
    // Hoe dichter bij 1, hoe meer de vectoren op elkaar lijken.
    public double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }

        double dot = 0;
        double na = 0;
        double nb = 0;
        int limit = Math.min(a.size(), b.size());
        for (int i = 0; i < limit; i++) {
            double av = a.get(i);
            double bv = b.get(i);
            dot += av * bv;
            na += av * av;
            nb += bv * bv;
        }

        if (na == 0 || nb == 0) {
            return 0.0;
        }

        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // Zoekt de meest relevante chunks voor een query en splitst ze op in gids- en externe bronnen.
    public SearchResult search(String query) throws Exception {
        String retrievalQuery = buildRetrievalQuery(query);
        List<Double> qVec = openAIService.embed(retrievalQuery);
        boolean talentclassVraag = isTalentclassQuestion(query);
        boolean referralVraag = isReferralQuestion(query);
        Set<String> functionLabels = detectFunctionLabels(query);

        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (ChunkEmbedding c : chunks) {
            if (!isValidCandidate(c, functionLabels, talentclassVraag, referralVraag)) {
                continue;
            }

            double semanticScore = cosine(c.getEmbedding(), qVec);
            double lexicalScore = lexicalSimilarity(retrievalQuery, c.getText());
            double baseScore = (semanticScore * 0.80) + (lexicalScore * 0.20);
            double scopeWeight = functionLabels.isEmpty() || matchesFunctionScope(c, functionLabels) ? 1.05 : 1.0;
            if (talentclassVraag && isTalentclassChunk(c)) {
                scopeWeight *= 1.05;
            }
            double weightedScore = baseScore * (c.isPrimaryGuide() ? GUIDE_WEIGHT : EXTERNAL_WEIGHT) * scopeWeight;
            scoredChunks.add(new ScoredChunk(c, baseScore, weightedScore));
        }

        scoredChunks.sort((a, b) -> Double.compare(b.getWeightedScore(), a.getWeightedScore()));

        List<ChunkEmbedding> rankedChunks = collectBalancedChunks(scoredChunks);
        double guideScore = scoredChunks.stream()
                .filter(candidate -> candidate.getChunk().isPrimaryGuide())
                .mapToDouble(ScoredChunk::getWeightedScore)
                .max()
                .orElse(0.0);

        return new SearchResult(rankedChunks, guideScore);
    }

    // Selecteert een globale, gebalanceerde top-lijst met guide-prioriteit en deduplicatie.
    public List<ChunkEmbedding> collectBalancedChunks(List<ScoredChunk> scoredCandidates) {
        List<ChunkEmbedding> selected = new ArrayList<>();
        List<ChunkEmbedding> selectedEmbeddings = new ArrayList<>();
        if (scoredCandidates == null || scoredCandidates.isEmpty()) {
            return selected;
        }

        int guideAvailable = 0;
        for (ScoredChunk scoredCandidate : scoredCandidates) {
            if (scoredCandidate != null
                    && scoredCandidate.getChunk() != null
                    && scoredCandidate.getChunk().isPrimaryGuide()
                    && scoredCandidate.getWeightedScore() >= MIN_SIMILARITY) {
                guideAvailable++;
            }
        }

        int guideTarget = Math.min(MIN_GUIDE_RESULTS, guideAvailable);

        for (ScoredChunk scoredCandidate : scoredCandidates) {
            if (selected.size() >= MAX_RESULTS || guideTarget <= 0) {
                break;
            }

            if (scoredCandidate == null || scoredCandidate.getChunk() == null) {
                continue;
            }

            if (!scoredCandidate.getChunk().isPrimaryGuide()) {
                continue;
            }

            if (scoredCandidate.getBaseScore() < MIN_SIMILARITY) {
                continue;
            }

            if (addIfNotDuplicate(selected, selectedEmbeddings, scoredCandidate.getChunk())) {
                guideTarget--;
            }
        }

        for (ScoredChunk scoredCandidate : scoredCandidates) {
            if (selected.size() >= MAX_RESULTS) {
                break;
            }

            if (scoredCandidate == null || scoredCandidate.getChunk() == null) {
                continue;
            }

            if (scoredCandidate.getBaseScore() < MIN_SIMILARITY) {
                continue;
            }

            addIfNotDuplicate(selected, selectedEmbeddings, scoredCandidate.getChunk());
        }

        return selected;
    }

    // Verrijkt de zoekquery met extra synoniemen/verwante termen.
    // Dit helpt om relevantere chunks terug te vinden.
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

    // Controleert of een chunk past binnen de gevraagde functie-scope.
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

    // Berekent een simpele lexicale overeenkomst op basis van token overlap.
    // Score = aantal overlappende tokens / aantal query tokens.
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

    // Zet tekst om in een set tokens: lowercase, verwijdert leestekens, houdt alleen tokens van lengte >= 3 over.
    public Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

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

    // Detecteert of de vraag specifiek over Talentclass gaat.
    public boolean isTalentclassQuestion(String query) {
        if (query == null) {
            return false;
        }

        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("talentclass")
                || normalized.contains("tc consultant")
                || normalized.contains("tc-consultant");
    }

    // Detecteert of een chunk tekst over Talentclass gaat.
    public boolean isTalentclassChunk(ChunkEmbedding chunk) {
        if (chunk == null) {
            return false;
        }

        Set<String> scope = chunk.getFunctionScope();
        if (scope != null && !scope.isEmpty()) {
            for (String label : scope) {
                if (label == null) {
                    continue;
                }
                String normalizedLabel = label.toLowerCase(Locale.ROOT);
                if (normalizedLabel.contains("talentclass")
                        || normalizedLabel.contains("talent class")
                        || normalizedLabel.contains("tc consultant")
                        || normalizedLabel.contains("tc-consultant")
                        || normalizedLabel.contains("tc consultants")
                        || normalizedLabel.contains("tc-consultants")) {
                    return true;
                }
            }
        }

        if (chunk.getText() == null) {
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

    // Detecteert of de vraag over referral / voordragen / aandragen gaat.
    public boolean isReferralQuestion(String query) {
        if (query == null) {
            return false;
        }

        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("referral")
                || normalized.contains("voordraag")
                || normalized.contains("voordragen")
                || normalized.contains("aandraag")
                || normalized.contains("aandragen")
                || normalized.contains("iemand aanbreng")
                || normalized.contains("iemand voordraag");
    }

    public double getGuideThreshold() {
        return GUIDE_THRESHOLD;
    }

    // Filtert chunks op basis van de bestaande zoekregels.
    private boolean isValidCandidate(ChunkEmbedding candidate,
                                     Set<String> functionLabels,
                                     boolean talentclassVraag,
                                     boolean referralVraag) {
        if (candidate == null || candidate.getText() == null || candidate.getText().isBlank()) {
            return false;
        }
        return true;
    }

    // Voorkomt dat bijna-identieke chunks dubbel in de selectie terechtkomen.
    private boolean addIfNotDuplicate(List<ChunkEmbedding> selected,
                                      List<ChunkEmbedding> selectedEmbeddings,
                                      ChunkEmbedding candidate) {
        if (candidate == null || candidate.getText() == null || candidate.getText().isBlank()) {
            return false;
        }

        for (ChunkEmbedding existing : selectedEmbeddings) {
            if (existing == null || existing.getEmbedding() == null || candidate.getEmbedding() == null) {
                continue;
            }

            double similarity = cosine(existing.getEmbedding(), candidate.getEmbedding());
            if (similarity > MAX_DUPLICATE_SIMILARITY
                    && existing.isPrimaryGuide() == candidate.isPrimaryGuide()) {
                return false;
            }
        }

        selected.add(candidate);
        selectedEmbeddings.add(candidate);
        return true;
    }

    // Resultaat van de zoekactie met gerangschikte chunks en guide-score.
    public static final class SearchResult {
        private final List<ChunkEmbedding> rankedChunks;
        private final double guideScore;

        private SearchResult(List<ChunkEmbedding> rankedChunks, double guideScore) {
            this.rankedChunks = rankedChunks;
            this.guideScore = guideScore;
        }

        public List<ChunkEmbedding> getRankedChunks() {
            return rankedChunks;
        }

        public double getGuideScore() {
            return guideScore;
        }

        public boolean isGuideSufficient() {
            return guideScore >= GUIDE_THRESHOLD;
        }
    }

    // Tussenresultaat voor scoring van chunks.
    public static final class ScoredChunk {
        private final ChunkEmbedding chunk;
        private final double baseScore;
        private final double weightedScore;

        private ScoredChunk(ChunkEmbedding chunk, double baseScore, double weightedScore) {
            this.chunk = chunk;
            this.baseScore = baseScore;
            this.weightedScore = weightedScore;
        }

        public ChunkEmbedding getChunk() {
            return chunk;
        }

        public double getBaseScore() {
            return baseScore;
        }

        public double getWeightedScore() {
            return weightedScore;
        }
    }
}
