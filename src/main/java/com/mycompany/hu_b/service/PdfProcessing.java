package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkDraft;
import com.mycompany.hu_b.model.ChunkEmbedding;
import java.net.URI;
import java.nio.file.Path;
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
                            true));
                }
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
}
