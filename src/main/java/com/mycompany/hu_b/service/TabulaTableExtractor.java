package com.mycompany.hu_b.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

/**
 * Hulpmodule om tabellen uit PDF's met Tabula te extraheren.
 */
public final class TabulaTableExtractor {

    private TabulaTableExtractor() {
    }

    public static void main(String[] args) throws Exception {
        Path pdfPath;
        Path outputDir;

        if (args.length == 0) {
            pdfPath = Path.of("personeelsgids.pdf");
            outputDir = Path.of("target", "tabula-tables");
        } else {
            pdfPath = Path.of(args[0]);
            outputDir = args.length > 1 ? Path.of(args[1]) : Path.of("target", "tabula-tables");
        }

        writeTablesToDirectory(pdfPath, outputDir);
        System.out.println("Tabula-tabellen geschreven naar: " + outputDir.toAbsolutePath().normalize());
    }

    public static List<String> extractTablesAsText(Path pdfPath) throws IOException {
        List<String> tables = new ArrayList<>();
        if (pdfPath == null || !Files.exists(pdfPath)) {
            return tables;
        }

        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                tables.addAll(extractPageTablesAsText(document, pageNumber));
            }
        }

        return tables;
    }

    public static List<String> extractPageTablesAsText(PDDocument document, int pageNumber) throws IOException {
        List<String> result = new ArrayList<>();
        if (document == null || pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
            return result;
        }

        ObjectExtractor extractor = new ObjectExtractor(document);
        Page page = extractor.extract(pageNumber);
        List<Table> tables = new ArrayList<>();
        tables.addAll(new SpreadsheetExtractionAlgorithm().extract(page));
        tables.addAll(new BasicExtractionAlgorithm().extract(page));

        Set<String> seenSignatures = new LinkedHashSet<>();
        int tableIndex = 1;
        for (Table table : tables) {
            String tableText = renderTable(table);
            if (tableText.isBlank()) {
                continue;
            }

            String signature = normalizeSignature(tableText);
            if (!seenSignatures.add(signature)) {
                continue;
            }

            result.add("Tabel " + pageNumber + "." + tableIndex + "\n" + tableText);
            tableIndex++;
        }

        return result;
    }

    public static void writeTablesToDirectory(Path pdfPath, Path outputDir) throws IOException {
        if (pdfPath == null || outputDir == null) {
            return;
        }

        List<String> tables = extractTablesAsText(pdfPath);
        if (tables.isEmpty()) {
            return;
        }

        Files.createDirectories(outputDir);
        String baseName = stripExtension(pdfPath.getFileName() == null ? "document" : pdfPath.getFileName().toString());

        for (int i = 0; i < tables.size(); i++) {
            Path outputFile = outputDir.resolve(baseName + "-table-" + String.format(Locale.ROOT, "%03d", i + 1) + ".txt");
            Files.writeString(outputFile, tables.get(i), StandardCharsets.UTF_8);
        }
    }

    private static String renderTable(Table table) {
        if (table == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (List<RectangularTextContainer> row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (RectangularTextContainer cell : row) {
                String text = cell == null ? "" : cell.getText();
                if (text == null) {
                    text = "";
                }
                text = text.replace("\r", " ").replaceAll("\\s+", " ").trim();
                cells.add(text);
            }

            while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
                cells.remove(cells.size() - 1);
            }

            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }

            builder.append(String.join("\t", cells)).append('\n');
        }

        return builder.toString().trim();
    }

    private static String normalizeSignature(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "document";
        }

        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
