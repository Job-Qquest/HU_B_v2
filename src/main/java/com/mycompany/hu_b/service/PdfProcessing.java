/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkEmbedding;
import com.mycompany.hu_b.model.ChunkDraft;
import com.mycompany.hu_b.util.FunctionProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionRemoteGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;


// Test Demi 14-04-2026

// De class leest de PDF in, haalt gelinkte Word-bronnen uit dezelfde map op, splitst de tekst
// in chunks, maakt embeddings, herkent functiegebonden inhoud en zoekt de meest relevante chunks
// voor een gebruikersvraag met een combinatie van semantische en lexicale matching.

public class PdfProcessing {

// Lijst met alle chunks uit de gids, inclusief embedding, pagina en functiescope
    private final List<ChunkEmbedding> chunks = new ArrayList<>();
    private static final Pattern WORD_FILE_PATTERN = Pattern.compile("(?i)([\\w\\-() ]+\\.docx?|[\\w\\-() ]+\\.doc)");
    private static final double GUIDE_THRESHOLD = 0.65;
    private static final double MIN_SIMILARITY = 0.3;
    private static final int MAX_RESULTS = 6;
    private static final int MIN_GUIDE_RESULTS = 3;
    private static final double GUIDE_WEIGHT = 1.2;
    private static final double EXTERNAL_WEIGHT = 0.9;
    private static final double MAX_DUPLICATE_SIMILARITY = 0.92;
    private final OpenAI openAIService;

    public PdfProcessing(OpenAI openAIService) {
        this.openAIService = openAIService;
    }
// Geeft alle geladen chunks terug
    public List<ChunkEmbedding> getChunks() {
        return chunks;
    }

//  Leest de PDF in en maakt voor elke chunk een embedding.
    public void loadGuide(String guidePath) throws Exception {
        loadGuide(guidePath, List.of());
    }

// Hoofdingang voor het laden van de gids.
// Eerst wordt de hoofd-PDF ingelezen, daarna worden de extra bronnen geladen die
// handmatig zijn opgegeven of vanuit de PDF als link zijn gevonden.
    public void loadGuide(String guidePath, List<String> supplementarySources) throws Exception {
        chunks.clear();
        // Houdt bij welke bronnen al zijn verwerkt zodat we niets dubbel inladen.
        Set<String> loadedSources = new LinkedHashSet<>();

        String normalizedPath = guidePath == null ? "" : guidePath.toLowerCase(Locale.ROOT);
        if (!normalizedPath.endsWith(".pdf")) {
            throw new IllegalArgumentException("Niet-ondersteund bestandstype. Gebruik een .pdf-bestand als hoofddocument.");
        }

        loadPdfGuide(guidePath, loadedSources);

        if (supplementarySources != null) {
            // Laad de handmatig opgegeven extra documenten die bij de gids horen.
            for (String sourcePath : supplementarySources) {
                loadSupplementarySource(sourcePath, loadedSources);
            }
        }

// Als er geen chunks zijn geladen, klopt er iets niet met het document
        if (chunks.isEmpty()) {
            throw new RuntimeException("Geen tekst uit personeelsgids geladen.");
        }
    }

// Leest de hoofd-PDF in, maakt chunks per pagina en zoekt daarna naar gekoppelde
// Word- of PDF-bronnen die in dezelfde map staan.
    private void loadPdfGuide(String pdfPath, Set<String> loadedSources) throws Exception {
        // Lees de hoofd-PDF uit en verzamel de documenten waar die PDF naar verwijst.
        Path pdfFile = Path.of(pdfPath).toAbsolutePath().normalize();
        Set<Path> linkedWordFiles = discoverLinkedWordFiles(pdfFile);

        String sourceLabel = buildSourceLabel(pdfFile);
        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
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
            chunks.add(new ChunkEmbedding(
                    draft.getText(),
                    openAIService.embed(draft.getText()),
                    page,
                    draft.getFunctionScope(),
                    sourceLabel,
                    null,
                    null,
                    true,
                    true));
                }
            }
        }

        for (Path linkedWordFile : linkedWordFiles) {
            // De gevonden Word-bronnen worden daarna ook als kennisbron toegevoegd.
            loadSupplementarySource(linkedWordFile.toString(), loadedSources);
        }
    }

// Laadt een extra bronbestand als kennisbron.
// Het bestandstype bepaalt of het als PDF, .docx, .doc of JSON-webarchief wordt ingelezen.
    private void loadSupplementarySource(String sourcePath, Set<String> loadedSources) throws Exception {
        if (sourcePath == null || sourcePath.isBlank()) {
            return;
        }

        Path path = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!path.toFile().exists()) {
            return;
        }

        String sourceKey = path.toString().toLowerCase(Locale.ROOT);
        if (loadedSources != null && !loadedSources.add(sourceKey)) {
            // Zelfde bestand zat al in de kennisbron, dus overslaan.
            return;
        }

        // Kies de juiste reader op basis van bestandstype.
        String lower = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            loadSupplementaryPdf(path);
        } else if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            loadSupplementaryWordDocument(path);
        } else if (lower.endsWith(".json")) {
            loadSupplementaryJson(path);
        }
    }

// Leest een JSON-webarchief en zet de opgeslagen tekst om naar chunks.
    private void loadSupplementaryJson(Path jsonPath) throws Exception {
        try (Reader reader = java.nio.file.Files.newBufferedReader(jsonPath)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                return;
            }

            if (root.isJsonArray()) {
                JsonArray array = root.getAsJsonArray();
                for (JsonElement element : array) {
                    if (element != null && element.isJsonObject()) {
                        loadArchivedWebPage(element.getAsJsonObject(), jsonPath, false);
                    }
                }
                return;
            }

            if (root.isJsonObject()) {
                loadArchivedWebPage(root.getAsJsonObject(), jsonPath, true);
            }
        }
    }

// Zet een opgeslagen webpagina om naar chunks en embeddings.
    private void loadArchivedWebPage(JsonObject object, Path jsonPath, boolean treatAsSinglePage) throws Exception {
        if (object == null) {
            return;
        }

        String url = getStringOrNull(object, "url");
        String title = getStringOrNull(object, "title");
        String source = getStringOrNull(object, "source");
        List<String> contentLines = extractContentLinesFromJson(object);

        if (contentLines.isEmpty()) {
            if (title != null) {
                contentLines.add(title);
            }
            if (url != null) {
                contentLines.add(url);
            }
        }

        String sourceLabel = title != null ? title : buildSourceLabel(jsonPath);
        String sourceUrl = url;
        String sourceName = source;
        Set<String> activeFunctionScope = new LinkedHashSet<>();
        int pageNumber = treatAsSinglePage ? 1 : 0;

        String pageText = String.join("\n", contentLines);
        List<ChunkDraft> drafts = chunkTextWithFunctionScope(pageText, 800, activeFunctionScope);
        for (ChunkDraft draft : drafts) {
            chunks.add(new ChunkEmbedding(
                    draft.getText(),
                    openAIService.embed(draft.getText()),
                    pageNumber,
                    draft.getFunctionScope(),
                    sourceLabel,
                    sourceUrl,
                    sourceName,
                    false,
                    false));
        }
    }

// Leest de content uit de JSON, ongeacht of die als array of als losse string is opgeslagen.
    private List<String> extractContentLinesFromJson(JsonObject object) {
        List<String> content = new ArrayList<>();
        if (object == null) {
            return content;
        }

        JsonElement contentElement = object.get("content");
        if (contentElement != null && contentElement.isJsonArray()) {
            for (JsonElement element : contentElement.getAsJsonArray()) {
                if (element != null && element.isJsonPrimitive()) {
                    String line = element.getAsString();
                    if (line != null && !line.isBlank()) {
                        content.add(line.trim());
                    }
                }
            }
        } else if (contentElement != null && contentElement.isJsonPrimitive()) {
            String text = contentElement.getAsString();
            if (text != null && !text.isBlank()) {
                for (String line : text.split("\\R")) {
                    if (line != null && !line.isBlank()) {
                        content.add(line.trim());
                    }
                }
            }
        }

        JsonElement linesElement = object.get("lines");
        if (content.isEmpty() && linesElement != null && linesElement.isJsonArray()) {
            for (JsonElement element : linesElement.getAsJsonArray()) {
                if (element != null && element.isJsonPrimitive()) {
                    String line = element.getAsString();
                    if (line != null && !line.isBlank()) {
                        content.add(line.trim());
                    }
                }
            }
        }

        return content;
    }

// Haalt een string op uit een JSON-object, of null als het veld ontbreekt.
    private String getStringOrNull(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }

        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()) {
            return null;
        }

        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

// Leest een extra PDF-bron op dezelfde manier als de hoofdgids.
    private void loadSupplementaryPdf(Path pdfPath) throws Exception {
        String sourceLabel = buildSourceLabel(pdfPath);
        // Extra PDF-bronnen worden op dezelfde manier verwerkt als de hoofdgids.
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            Set<String> activeFunctionScope = new LinkedHashSet<>();

            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String pageText = stripper.getText(doc);
                List<ChunkDraft> drafts = chunkTextWithFunctionScope(pageText, 800, activeFunctionScope);

                for (ChunkDraft draft : drafts) {
                    chunks.add(new ChunkEmbedding(
                            draft.getText(),
                            openAIService.embed(draft.getText()),
                            page,
                            draft.getFunctionScope(),
                            sourceLabel,
                            null,
                            null,
                            true,
                            false));
                }
            }
        }
    }

// Leest een extra Word-document en splitst het per pagina in chunks.
    private void loadSupplementaryWordDocument(Path guidePath) throws Exception {
        // Word-bronnen worden op basis van page breaks omgezet naar chunks en embeddings.
        Set<String> activeFunctionScope = new LinkedHashSet<>();
        List<String> pages = extractWordPages(guidePath);
        String sourceLabel = buildSourceLabel(guidePath);

        int pageNumber = 1;
        for (String pageText : pages) {
            List<ChunkDraft> drafts = chunkTextWithFunctionScope(pageText, 800, activeFunctionScope);
            for (ChunkDraft draft : drafts) {
                chunks.add(new ChunkEmbedding(
                        draft.getText(),
                        openAIService.embed(draft.getText()),
                        pageNumber,
                        draft.getFunctionScope(),
                        sourceLabel,
                        null,
                        null,
                        false,
                        false));
            }
            pageNumber++;
        }
    }

// Zoekt in de PDF naar bestandsnamen en linkverwijzingen naar documenten.
// Alleen bestaande .doc/.docx-bestanden in de map van de PDF worden meegenomen.
    private Set<Path> discoverLinkedWordFiles(Path pdfFile) throws Exception {
        // Doorzoek de PDF op bestandsnamen en linkannotaties die naar documenten verwijzen.
        Set<Path> linkedFiles = new LinkedHashSet<>();
        Path baseDir = pdfFile.getParent() == null ? Path.of(".") : pdfFile.getParent();

        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                PDPage page = doc.getPage(pageIndex);
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);

                String pageText = stripper.getText(doc);
                collectWordFilesFromText(pageText, baseDir, linkedFiles);
                collectWordFilesFromAnnotations(page, baseDir, linkedFiles);
            }
        }

        return linkedFiles;
    }

// Haalt losse bestandsnamen uit de tekst van de PDF.
// Dit vangt situaties af waarin een documentnaam gewoon als tekst in de gids staat.
    private void collectWordFilesFromText(String text, Path baseDir, Set<Path> linkedFiles) {
        if (text == null || text.isBlank()) {
            return;
        }

        Matcher matcher = WORD_FILE_PATTERN.matcher(text);
        while (matcher.find()) {
            addLinkedWordFile(baseDir, matcher.group(1), linkedFiles);
        }
    }

// Haalt echte PDF-linkannotaties uit een pagina.
// Hiermee worden klikbare links zoals file:-links of documentverwijzingen meegenomen.
    private void collectWordFilesFromAnnotations(PDPage page, Path baseDir, Set<Path> linkedFiles) throws Exception {
        for (PDAnnotation annotation : page.getAnnotations()) {
            if (!(annotation instanceof PDAnnotationLink link)) {
                continue;
            }

            PDAction action = link.getAction();
            if (action == null) {
                continue;
            }

            if (action instanceof PDActionURI uriAction) {
                String uriText = uriAction.getURI();
                if (uriText != null && !uriText.isBlank()) {
                    // Externe links zoals mailto: of file: worden hier gefilterd.
                    addLinkedWordFileFromUri(baseDir, uriText, linkedFiles);
                }
                continue;
            }

            if (action instanceof PDActionLaunch launchAction) {
                addLinkedWordFile(baseDir, extractFileReference(launchAction.getFile()), linkedFiles);
                continue;
            }

            if (action instanceof PDActionRemoteGoTo goToRemoteAction) {
                addLinkedWordFile(baseDir, extractFileReference(goToRemoteAction.getFile()), linkedFiles);
            }
        }
    }

// Leest de inhoud van een Word-document uit als losse tekstpagina's.
// Voor .docx gebruiken we XWPF, voor .doc gebruiken we HWPF.
    private List<String> extractWordPages(Path guidePath) throws Exception {
        // Lees de inhoud uit een Word-document als losse tekstpagina's.
        // De pagina-indeling komt uit page breaks (\f) als die in het document aanwezig zijn.
        List<String> pages = new ArrayList<>();
        String normalizedPath = guidePath.toString().toLowerCase(Locale.ROOT);

        if (normalizedPath.endsWith(".docx")) {
            try (java.io.FileInputStream input = new java.io.FileInputStream(guidePath.toFile());
                    org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(input);
                    org.apache.poi.xwpf.extractor.XWPFWordExtractor extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
                String fullText = extractor.getText();
                for (String pageText : splitWordPages(fullText)) {
                    if (!pageText.isBlank()) {
                        pages.add(pageText.trim());
                    }
                }
            }
            return pages;
        }

        try (java.io.FileInputStream input = new java.io.FileInputStream(guidePath.toFile());
                org.apache.poi.hwpf.HWPFDocument document = new org.apache.poi.hwpf.HWPFDocument(input);
                org.apache.poi.hwpf.extractor.WordExtractor extractor = new org.apache.poi.hwpf.extractor.WordExtractor(document)) {
            String fullText = extractor.getText();
            for (String pageText : splitWordPages(fullText)) {
                if (!pageText.isBlank()) {
                    pages.add(pageText.trim());
                }
            }
        }

        return pages;
    }

// Splitst Word-tekst op basis van page breaks.
// Dit geeft echte paginablokken als het document page breaks bevat; anders blijft alles op pagina 1.
    private List<String> splitWordPages(String text) {
        List<String> pages = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return pages;
        }

        String[] parts = text.split("\\f");
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                pages.add(part.trim());
            }
        }

        if (pages.isEmpty()) {
            pages.add(text.trim());
        }

        return pages;
    }

// Probeert een URI uit de PDF om te zetten naar een lokaal bestandspad.
// Niet-bestandslinks zoals mailto: worden genegeerd.
    private void addLinkedWordFileFromUri(Path baseDir, String uriText, Set<Path> linkedFiles) {
        try {
            URI uri = URI.create(uriText.trim());
            String scheme = uri.getScheme();
            if (scheme != null && !scheme.equalsIgnoreCase("file")) {
                // Niet-bestand links zoals mailto: zijn niet relevant als bronbestand.
                return;
            }

            if (uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("file")) {
                addLinkedWordFile(baseDir, Path.of(uri).toString(), linkedFiles);
                return;
            }

            String pathPart = uri.getPath();
            if (pathPart != null && !pathPart.isBlank()) {
                addLinkedWordFile(baseDir, pathPart, linkedFiles);
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Geen geldige URI, probeer het als bestandsnaam.
        }

        addLinkedWordFile(baseDir, uriText, linkedFiles);
    }

// Haalt de bestandsnaam uit een PDF-bestandsverwijzing.
// PDFBox kan de naam op meerdere manieren opslaan, dus we proberen de
// standaardvariant en vallen daarna terug op de ruwe COS-representatie.
    private String extractFileReference(org.apache.pdfbox.pdmodel.common.filespecification.PDFileSpecification fileSpecification) throws Exception {
        if (fileSpecification == null) {
            return null;
        }

        String file = fileSpecification.getFile();
        if (file != null && !file.isBlank()) {
            return file;
        }

        return fileSpecification.getCOSObject() == null ? null : fileSpecification.getCOSObject().toString();
    }

// Zet een gevonden bestandsverwijzing om naar een echt pad en controleert
// of het bestand bestaat en een ondersteund Word-formaat heeft.
    private void addLinkedWordFile(Path baseDir, String rawReference, Set<Path> linkedFiles) {
        if (rawReference == null || rawReference.isBlank()) {
            return;
        }

        String cleanedReference = rawReference.trim()
                .replace("\"", "")
                .replace("'", "")
                .replaceAll("[,;)]+$", "");

        Path candidate = Path.of(cleanedReference);
        if (!candidate.isAbsolute()) {
            // Verwijsbestand staat meestal in dezelfde map als de PDF.
            candidate = baseDir.resolve(candidate);
        }

        candidate = candidate.normalize();
        Path fileName = candidate.getFileName();
        if (fileName == null) {
            return;
        }

        String lower = fileName.toString().toLowerCase(Locale.ROOT);
        if ((lower.endsWith(".docx") || lower.endsWith(".doc")) && candidate.toFile().exists()) {
            linkedFiles.add(candidate);
        }
    }

// Zet een bestandspad om naar een nette titel voor bronvermelding.
// We gebruiken de bestandsnaam zonder extensie zodat Word-bronnen geen paginanummer nodig hebben.
    private String buildSourceLabel(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }

        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
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
            double weightedScore = baseScore * (c.isPrimaryGuide() ? GUIDE_WEIGHT : EXTERNAL_WEIGHT);
            scoredChunks.add(new ScoredChunk(c, baseScore, weightedScore));
        }
// Sorteer van hoogste naar laagste score
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

            if (scoredCandidate.getWeightedScore() < MIN_SIMILARITY) {
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

            if (scoredCandidate.getWeightedScore() < MIN_SIMILARITY) {
                continue;
            }

            addIfNotDuplicate(selected, selectedEmbeddings, scoredCandidate.getChunk());
        }

        return selected;
    }

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

            if (cosine(existing.getEmbedding(), candidate.getEmbedding()) > MAX_DUPLICATE_SIMILARITY) {
                return false;
            }
        }

        selected.add(candidate);
        selectedEmbeddings.add(candidate);
        return true;
    }

// Filtert chunks op basis van de bestaande zoekregels.
    private boolean isValidCandidate(ChunkEmbedding candidate,
                                     Set<String> functionLabels,
                                     boolean talentclassVraag,
                                     boolean referralVraag) {
        if (candidate == null || candidate.getText() == null || candidate.getText().isBlank()) {
            return false;
        }

        if (!functionLabels.isEmpty() && !matchesFunctionScope(candidate, functionLabels)) {
            return false;
        }

        if (talentclassVraag && !referralVraag && !isTalentclassChunk(candidate)) {
            return false;
        }

        return true;
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

    public double getGuideThreshold() {
        return GUIDE_THRESHOLD;
    }

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
