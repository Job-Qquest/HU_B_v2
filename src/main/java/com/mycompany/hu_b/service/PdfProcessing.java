package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkDraft;
import com.mycompany.hu_b.model.ChunkEmbedding;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

public class PdfProcessing extends KnowledgeProcessingUtils {

    private static final Pattern WORD_FILE_PATTERN = Pattern.compile("(?i)([\\w\\-() ]+\\.docx?|[\\w\\-() ]+\\.doc)");
    private static final Pattern CHAPTER_HEADER_PATTERN = Pattern.compile("^\\s*\\d{1,2}\\.\\s+[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\- ]{0,60}\\s*$");
    private static final int PDF_FRAGMENT_WORD_LIMIT = 120;
    private static final int PDF_FRAGMENT_MAX_SPILL = 150;

    public PdfProcessing(OpenAI openAIService) {
        super(openAIService);
    }

    // Leest de PDF in en maakt voor elke chunk een embedding.
    public void loadGuide(String guidePath) throws Exception {
        loadGuide(guidePath, List.of());
    }

    // Hoofdingang voor het laden van de gids.
    // Eerst wordt de hoofd-PDF ingelezen, daarna worden de extra bronnen geladen.
    public void loadGuide(String guidePath, List<String> supplementarySources) throws Exception {
        chunks.clear();
        Set<String> loadedSources = new LinkedHashSet<>();

        String normalizedPath = guidePath == null ? "" : guidePath.toLowerCase(Locale.ROOT);
        if (!normalizedPath.endsWith(".pdf")) {
            throw new IllegalArgumentException("Niet-ondersteund bestandstype. Gebruik een .pdf-bestand als hoofddocument.");
        }

        loadPdfGuide(guidePath, loadedSources);

        if (supplementarySources != null) {
            for (String sourcePath : supplementarySources) {
                loadSupplementarySource(sourcePath, loadedSources);
            }
        }

        if (chunks.isEmpty()) {
            throw new RuntimeException("Geen tekst uit personeelsgids geladen.");
        }
    }

    // Leest de hoofd-PDF in, maakt chunks per pagina en zoekt daarna naar gekoppelde
    // Word-bronnen die in dezelfde map staan.
    private void loadPdfGuide(String pdfPath, Set<String> loadedSources) throws Exception {
        Path pdfFile = Path.of(pdfPath).toAbsolutePath().normalize();
        Set<Path> linkedWordFiles = discoverLinkedWordFiles(pdfFile);

        String sourceLabel = buildSourceLabel(pdfFile);
        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<PdfChapterBuffer> chapters = buildPdfChapterBuffers(doc, stripper);
            for (PdfChapterBuffer chapter : chapters) {
                flushChapterBufferToChunks(chapter, sourceLabel, pdfFile.toString(), true, true);
            }
        }

        for (Path linkedWordFile : linkedWordFiles) {
            loadSupplementarySource(linkedWordFile.toString(), loadedSources);
        }
    }

    // Laadt een extra bronbestand als kennisbron.
    // Het bestandstype bepaalt of het als PDF, Word of JSON-webarchief wordt ingelezen.
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
            return;
        }

        String lower = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            loadSupplementaryPdf(path);
        } else if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            WordProcessing.loadSupplementaryWordDocument(this, path);
        } else if (lower.endsWith(".json")) {
            WebPageProcessing.loadSupplementaryJson(this, path);
        }
    }

    // Leest een extra PDF-bron op dezelfde manier als de hoofdgids.
    private void loadSupplementaryPdf(Path pdfPath) throws Exception {
        String sourceLabel = buildSourceLabel(pdfPath);
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<PdfChapterBuffer> chapters = buildPdfChapterBuffers(doc, stripper);
            for (PdfChapterBuffer chapter : chapters) {
                flushChapterBufferToChunks(chapter, sourceLabel, pdfPath.toString(), true, false);
            }
        }
    }

    private void addOrMergePdfChunk(List<PendingPdfChunk> pendingChunks,
                                    ChunkDraft draft,
                                    int page,
                                    String sourceLabel,
                                    String sourcePath,
                                    boolean sourceIsPdf,
                                    boolean primaryGuide) {
        if (draft == null || draft.getText() == null || draft.getText().isBlank()) {
            return;
        }

        String draftText = draft.getText().trim();
        Set<String> draftScope = draft.getFunctionScope() == null
                ? Set.of()
                : new LinkedHashSet<>(draft.getFunctionScope());
        int draftWords = countWords(draftText);
        boolean draftLooksFragmentary = PendingPdfChunk.looksFragmentaryStatic(draftText);

        PendingPdfChunk current = pendingChunks.isEmpty() ? null : pendingChunks.get(pendingChunks.size() - 1);
        if (current != null && current.canMergeWith(draftScope, draftWords, 800, draftLooksFragmentary)) {
            current.append(draftText, draftWords);
            return;
        }

        pendingChunks.add(new PendingPdfChunk(
                draftText,
                draftScope,
                page,
                sourceLabel,
                sourcePath,
                sourceIsPdf,
                primaryGuide));
    }

    private List<PdfChapterBuffer> buildPdfChapterBuffers(PDDocument doc, PDFTextStripper stripper) throws Exception {
        List<PdfChapterBuffer> chapters = new ArrayList<>();
        if (doc == null || stripper == null) {
            return chapters;
        }

        PdfChapterBuffer current = null;
        for (int page = 1; page <= doc.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);

            String pageText = stripper.getText(doc);
            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            ChapterHeaderMatch chapterHeader = detectChapterHeaderMatch(pageText);
            if (chapterHeader != null) {
                String beforeHeader = extractTextBeforeLine(pageText, chapterHeader.lineIndex);
                String chapterText = extractTextFromLine(pageText, chapterHeader.lineIndex, chapterHeader.lineCount);
                String chapterNumber = extractChapterNumber(chapterHeader.title);

                if (current == null) {
                    current = new PdfChapterBuffer(page, null, null);
                }

                if (!beforeHeader.isBlank()) {
                    current.appendFragment(page, beforeHeader);
                }

                if (!current.isEmpty()) {
                    chapters.add(current);
                }

                current = new PdfChapterBuffer(page, chapterHeader.title, chapterNumber);
                if (!chapterText.isBlank()) {
                    current.appendFragment(page, chapterText);
                }

                String afterHeader = extractTextAfterLine(pageText, chapterHeader.lineIndex + chapterHeader.lineCount - 1);
                if (!afterHeader.isBlank()) {
                    current.appendFragment(page, afterHeader);
                }
            } else {
                if (current == null) {
                    current = new PdfChapterBuffer(page, null, null);
                }
                current.appendPage(page, pageText);
            }
        }

        if (current != null && !current.isEmpty()) {
            chapters.add(current);
        }

        return chapters;
    }

    private void flushChapterBufferToChunks(PdfChapterBuffer chapter,
                                            String sourceLabel,
                                            String sourcePath,
                                            boolean sourceIsPdf,
                                            boolean primaryGuide) throws Exception {
        if (chapter == null || chapter.isEmpty()) {
            return;
        }

        String chapterText = chapter.getText();
        Set<String> chapterScope = detectFunctionLabels(chapterText);

        List<ChunkDraft> drafts;
        if (countWords(chapterText) <= 800) {
            drafts = List.of(new ChunkDraft(chapterText.trim(), chapterScope));
        } else {
            drafts = chunkTextWithFunctionScope(chapterText, 800, new LinkedHashSet<>());
        }

        List<PendingPdfChunk> pendingChunks = new ArrayList<>();
        int runningWords = 0;
        for (ChunkDraft draft : drafts) {
            int draftPage = chapter.pageForWordOffset(runningWords + 1);
            addOrMergePdfChunk(pendingChunks, draft, draftPage, sourceLabel, sourcePath, sourceIsPdf, primaryGuide);
            runningWords += countWords(draft.getText());
        }

        flushPendingPdfChunks(pendingChunks);
    }

    private void flushPendingPdfChunks(List<PendingPdfChunk> pendingChunks) throws Exception {
        if (pendingChunks == null || pendingChunks.isEmpty()) {
            return;
        }

        for (PendingPdfChunk pending : pendingChunks) {
            if (pending == null || pending.text == null || pending.text.isBlank()) {
                continue;
            }

            chunks.add(new ChunkEmbedding(
                    pending.text,
                    openAIService.embed(pending.text),
                    pending.page,
                    pending.functionScope,
                    pending.sourceLabel,
                    null,
                    null,
                    pending.sourcePath,
                    pending.sourceIsPdf,
                    pending.primaryGuide));
        }
    }

    // Zoekt in de PDF naar bestandsnamen en linkverwijzingen naar documenten.
    // Alleen bestaande .doc/.docx-bestanden in de map van de PDF worden meegenomen.
    private Set<Path> discoverLinkedWordFiles(Path pdfFile) throws Exception {
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

    // Probeert een URI uit de PDF om te zetten naar een lokaal bestandspad.
    // Niet-bestandslinks zoals mailto: worden genegeerd.
    private void addLinkedWordFileFromUri(Path baseDir, String uriText, Set<Path> linkedFiles) {
        try {
            URI uri = URI.create(uriText.trim());
            String scheme = uri.getScheme();
            if (scheme != null && !scheme.equalsIgnoreCase("file")) {
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

    private String detectChapterHeader(String pageText) {
        ChapterHeaderMatch match = detectChapterHeaderMatch(pageText);
        return match == null ? null : match.title;
    }

    private ChapterHeaderMatch detectChapterHeaderMatch(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            return null;
        }

        String[] lines = pageText.split("\\R");
        String bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;
        int bestCandidateLineIndex = -1;
        int bestCandidateLineCount = 1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }

            int score = chapterHeaderScore(line, i);
            String candidate = line;
            int candidateLineCount = 1;

            if (i + 1 < lines.length) {
                String nextLine = lines[i + 1] == null ? "" : lines[i + 1].trim();
                if (isLikelyChapterContinuation(nextLine)) {
                    String merged = line + " " + nextLine;
                    int mergedScore = chapterHeaderScore(merged, i);
                    if (mergedScore > score) {
                        score = mergedScore;
                        candidate = merged;
                        candidateLineCount = 2;
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
                bestCandidateLineCount = candidateLineCount;
                bestCandidateLineIndex = i;
            }
        }

        return bestScore >= 8 ? new ChapterHeaderMatch(bestCandidate, bestCandidateLineIndex, bestCandidateLineCount) : null;
    }

    private String extractTextBeforeLine(String pageText, int lineIndex) {
        return extractTextRange(pageText, 0, Math.max(0, lineIndex));
    }

    private String extractTextFromLine(String pageText, int lineIndex, int lineCount) {
        int start = Math.max(0, lineIndex);
        int end = Math.max(start, lineIndex + Math.max(1, lineCount));
        return extractTextRange(pageText, start, end);
    }

    private String extractTextAfterLine(String pageText, int lineIndex) {
        String[] lines = pageText == null ? new String[0] : pageText.split("\\R");
        if (lines.length == 0 || lineIndex + 1 >= lines.length) {
            return "";
        }
        return extractTextRange(pageText, lineIndex + 1, lines.length);
    }

    private String extractTextRange(String pageText, int startLine, int endLineExclusive) {
        if (pageText == null || pageText.isBlank()) {
            return "";
        }

        String[] lines = pageText.split("\\R");
        if (lines.length == 0) {
            return "";
        }

        int start = Math.max(0, startLine);
        int end = Math.min(lines.length, Math.max(start, endLineExclusive));
        if (start >= end) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(System.lineSeparator());
            }
            sb.append(line);
        }

        return sb.toString().trim();
    }

    private int chapterHeaderScore(String line, int lineIndex) {
        if (line == null || line.isBlank()) {
            return Integer.MIN_VALUE;
        }

        String trimmed = normalizeChapterText(line);
        if (trimmed.isBlank() || trimmed.length() > 90) {
            return Integer.MIN_VALUE;
        }

        if (!CHAPTER_HEADER_PATTERN.matcher(trimmed).matches()) {
            return Integer.MIN_VALUE;
        }

        String afterNumber = normalizeChapterText(trimmed.replaceFirst("^\\s*\\d{1,2}\\.\\s*", ""));
        int wordCount = countWords(afterNumber);
        if (wordCount < 1 || wordCount > 10) {
            return Integer.MIN_VALUE;
        }

        if (afterNumber.endsWith(".")) {
            return Integer.MIN_VALUE;
        }

        if (!looksLikeMainChapterTitle(afterNumber)) {
            return Integer.MIN_VALUE;
        }

        int score = 10;
        if (lineIndex <= 3) {
            score += 3;
        } else if (lineIndex <= 7) {
            score += 1;
        }

        if (wordCount <= 4) {
            score += 2;
        }

        if (Character.isUpperCase(afterNumber.charAt(0))) {
            score += 1;
        }

        return score;
    }

    private boolean looksLikeMainChapterTitle(String text) {
        String normalized = normalizeChapterText(text);
        if (normalized.isBlank()) {
            return false;
        }

        if (normalized.length() <= 18) {
            return true;
        }

        if (normalized.equals(normalized.toUpperCase(Locale.ROOT))) {
            return true;
        }

        String[] words = normalized.split("\\s+");
        int significantWords = 0;
        for (String word : words) {
            String clean = word.replaceAll("[^\\p{L}\\p{N}]", "");
            if (clean.isBlank()) {
                continue;
            }
            significantWords++;
            if (clean.length() > 1 && Character.isUpperCase(clean.charAt(0))) {
                continue;
            }
            return false;
        }

        return significantWords >= 2;
    }

    private String normalizeChapterText(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2212', '-');

        return normalized.replaceAll("\\s+", " ").trim();
    }

    private boolean isLikelyChapterContinuation(String line) {
        String normalized = normalizeChapterText(line);
        if (normalized.isBlank()) {
            return false;
        }

        if (normalized.matches("^\\d{1,2}\\.\\s+.*")) {
            return false;
        }

        if (normalized.length() > 45) {
            return false;
        }

        int words = countWords(normalized);
        if (words < 2 || words > 6) {
            return false;
        }

        String upper = normalized.toUpperCase(Locale.ROOT);
        return normalized.equals(upper) || Character.isUpperCase(normalized.charAt(0));
    }

    private static final class ChapterHeaderMatch {
        private final String title;
        private final int lineIndex;
        private final int lineCount;

        private ChapterHeaderMatch(String title, int lineIndex, int lineCount) {
            this.title = title;
            this.lineIndex = lineIndex;
            this.lineCount = lineCount;
        }
    }

    private static int countWordsLocal(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private static final class PendingPdfChunk {
        private String text;
        private final Set<String> functionScope;
        private final int page;
        private final String sourceLabel;
        private final String sourcePath;
        private final boolean sourceIsPdf;
        private final boolean primaryGuide;
        private int wordCount;

        private PendingPdfChunk(String text,
                                Set<String> functionScope,
                                int page,
                                String sourceLabel,
                                String sourcePath,
                                boolean sourceIsPdf,
                                boolean primaryGuide) {
            this.text = text;
            this.functionScope = functionScope == null ? Set.of() : new LinkedHashSet<>(functionScope);
            this.page = page;
            this.sourceLabel = sourceLabel;
            this.sourcePath = sourcePath;
            this.sourceIsPdf = sourceIsPdf;
            this.primaryGuide = primaryGuide;
            this.wordCount = countWordsStatic(text);
        }

        private boolean canMergeWith(Set<String> nextScope, int nextWordCount, int maxWords) {
            return canMergeWith(nextScope, nextWordCount, maxWords, false);
        }

        private boolean canMergeWith(Set<String> nextScope, int nextWordCount, int maxWords, boolean nextLooksFragmentary) {
            if (nextWordCount <= 0) {
                return false;
            }
            if (!scopesEqual(functionScope, nextScope)) {
                return false;
            }

            if (wordCount + nextWordCount <= maxWords) {
                return true;
            }

            if ((nextLooksFragmentary || looksFragmentaryStatic(text))
                    && wordCount + nextWordCount <= maxWords + PdfProcessing.PDF_FRAGMENT_MAX_SPILL
                    && nextWordCount <= PdfProcessing.PDF_FRAGMENT_WORD_LIMIT) {
                return true;
            }

            return false;
        }

        private void append(String extraText, int extraWords) {
            if (extraText == null || extraText.isBlank() || extraWords <= 0) {
                return;
            }

            this.text = this.text + " " + extraText.trim();
            this.wordCount += extraWords;
        }

        private static int countWordsStatic(String text) {
            if (text == null || text.isBlank()) {
                return 0;
            }
            return text.trim().split("\\s+").length;
        }

        private static boolean scopesEqual(Set<String> left, Set<String> right) {
            if (left == null || left.isEmpty()) {
                return right == null || right.isEmpty();
            }
            if (right == null || right.isEmpty()) {
                return false;
            }
            return new LinkedHashSet<>(left).equals(new LinkedHashSet<>(right));
        }

        private static boolean looksFragmentaryStatic(String text) {
            if (text == null || text.isBlank()) {
                return true;
            }

            String trimmed = text.trim();
            int words = countWordsStatic(trimmed);
            if (words <= PdfProcessing.PDF_FRAGMENT_WORD_LIMIT) {
                return true;
            }

            return !trimmed.endsWith(".")
                    && !trimmed.endsWith("!")
                    && !trimmed.endsWith("?")
                    && !trimmed.endsWith(":")
                    && !trimmed.endsWith(";")
                    && !trimmed.endsWith(")");
        }
    }

    private String extractChapterNumber(String chapterTitle) {
        if (chapterTitle == null || chapterTitle.isBlank()) {
            return null;
        }

        java.util.regex.Matcher matcher = Pattern.compile("^\\s*(\\d{1,2})\\.\\s*").matcher(chapterTitle.trim());
        if (!matcher.find()) {
            return null;
        }

        return matcher.group(1);
    }

    private static final class PdfChapterBuffer {
        private final int startPage;
        private int endPage;
        private final String chapterTitle;
        private final String chapterNumber;
        private final List<PageEntry> pages = new ArrayList<>();

        private PdfChapterBuffer(int startPage, String chapterTitle, String chapterNumber) {
            this.startPage = startPage;
            this.endPage = startPage;
            this.chapterTitle = chapterTitle;
            this.chapterNumber = chapterNumber;
        }

        private void appendPage(int pageNumber, String pageText) {
            if (pageText == null || pageText.isBlank()) {
                return;
            }

            pages.add(new PageEntry(pageNumber, pageText.trim()));
            this.endPage = Math.max(this.endPage, pageNumber);
        }

        private void appendFragment(int pageNumber, String textFragment) {
            if (textFragment == null || textFragment.isBlank()) {
                return;
            }

            pages.add(new PageEntry(pageNumber, textFragment.trim()));
            this.endPage = Math.max(this.endPage, pageNumber);
        }

        private boolean isEmpty() {
            return pages.isEmpty();
        }

        private String getText() {
            StringBuilder builder = new StringBuilder();
            for (PageEntry page : pages) {
                if (page == null || page.text == null || page.text.isBlank()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append("\n");
                }
                builder.append(page.text);
            }
            return builder.toString().trim();
        }

        private int getStartPage() {
            return startPage;
        }

        private int getEndPage() {
            return endPage;
        }

        private boolean shouldStartNewChapter(String detectedChapterNumber) {
            if (detectedChapterNumber == null || detectedChapterNumber.isBlank()) {
                return false;
            }

            if (chapterNumber == null || chapterNumber.isBlank()) {
                return true;
            }

            return !chapterNumber.equals(detectedChapterNumber);
        }

        private int pageForWordOffset(int wordOffset) {
            if (pages.isEmpty()) {
                return startPage;
            }

            if (pages.size() == 1) {
                return pages.get(0).pageNumber;
            }

            if (wordOffset <= 1) {
                return pages.get(0).pageNumber;
            }

            int wordsSeen = 0;
            int totalWords = 0;
            for (PageEntry page : pages) {
                totalWords += countWordsLocal(page.text);
            }

            if (totalWords <= 0) {
                return startPage;
            }

            for (PageEntry page : pages) {
                int pageWords = countWordsLocal(page.text);
                wordsSeen += pageWords;
                if (wordsSeen >= wordOffset) {
                    return page.pageNumber;
                }
            }

            return endPage;
        }
    }

    private static final class PageEntry {
        private final int pageNumber;
        private final String text;

        private PageEntry(int pageNumber, String text) {
            this.pageNumber = pageNumber;
            this.text = text;
        }
    }
}
