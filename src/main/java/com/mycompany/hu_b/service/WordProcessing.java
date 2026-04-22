package com.mycompany.hu_b.service;

import com.mycompany.hu_b.model.ChunkDraft;
import com.mycompany.hu_b.model.ChunkEmbedding;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WordProcessing {

    private WordProcessing() {
    }

    // Leest een extra Word-document en splitst het per pagina in chunks.
    public static void loadSupplementaryWordDocument(KnowledgeProcessingUtils processing, Path guidePath) throws Exception {
        if (processing == null || guidePath == null) {
            return;
        }

        Set<String> activeFunctionScope = new java.util.LinkedHashSet<>();
        List<String> pages = extractWordPages(guidePath);
        String sourceLabel = processing.buildSourceLabel(guidePath);

        int pageNumber = 1;
        for (String pageText : pages) {
            List<ChunkDraft> drafts = processing.chunkTextWithFunctionScope(pageText, 800, activeFunctionScope);
            for (ChunkDraft draft : drafts) {
                processing.chunks.add(new ChunkEmbedding(
                        draft.getText(),
                        processing.openAIService.embed(draft.getText()),
                        pageNumber,
                        draft.getFunctionScope(),
                        sourceLabel,
                        null,
                        null,
                        guidePath.toAbsolutePath().normalize().toString(),
                        false,
                        false));
            }
            pageNumber++;
        }
    }

    // Leest de inhoud van een Word-document uit als losse tekstpagina's.
    // Voor .docx gebruiken we XWPF, voor .doc gebruiken we HWPF.
    public static List<String> extractWordPages(Path guidePath) throws Exception {
        List<String> pages = new ArrayList<>();
        if (guidePath == null) {
            return pages;
        }

        String normalizedPath = guidePath.toString().toLowerCase(Locale.ROOT);
        if (normalizedPath.endsWith(".docx")) {
            try (java.io.FileInputStream input = new java.io.FileInputStream(guidePath.toFile());
                    org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(input);
                    org.apache.poi.xwpf.extractor.XWPFWordExtractor extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
                String fullText = extractor.getText();
                pages.addAll(splitWordPages(fullText));
            }
            return pages;
        }

        try (java.io.FileInputStream input = new java.io.FileInputStream(guidePath.toFile());
                org.apache.poi.hwpf.HWPFDocument document = new org.apache.poi.hwpf.HWPFDocument(input);
                org.apache.poi.hwpf.extractor.WordExtractor extractor = new org.apache.poi.hwpf.extractor.WordExtractor(document)) {
            String fullText = extractor.getText();
            pages.addAll(splitWordPages(fullText));
        }

        return pages;
    }

    // Splitst Word-tekst op basis van page breaks.
    // Dit geeft echte paginablokken als het document page breaks bevat; anders blijft alles op pagina 1.
    public static List<String> splitWordPages(String text) {
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
}
