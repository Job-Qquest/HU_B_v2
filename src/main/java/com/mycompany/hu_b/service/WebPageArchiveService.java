package com.mycompany.hu_b.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

// Haalt webpagina's op, zet de tekst om naar een lokale PDF en bewaart die
// in dezelfde map als de overige kennisbronnen.
// De opgeslagen PDF's worden daarna door de bestaande kennisbron-loader ingelezen.
public class WebPageArchiveService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";
    private static final float PAGE_MARGIN = 48f;
    private static final float TITLE_FONT_SIZE = 16f;
    private static final float BODY_FONT_SIZE = 11f;
    private static final float LINE_GAP = BODY_FONT_SIZE * 1.35f;

// Haalt meerdere webpagina's op en bewaart ze als PDF in de opgegeven map.
// Als ophalen mislukt maar een eerdere cache-versie bestaat, gebruiken we die bestaande PDF.
    public List<Path> archivePages(List<String> urls, Path outputDirectory) throws IOException {
        List<Path> archivedFiles = new ArrayList<>();
        if (urls == null || urls.isEmpty()) {
            return archivedFiles;
        }

        Files.createDirectories(outputDirectory);

        List<String> failures = new ArrayList<>();
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }

            try {
                Path targetFile = archiveSinglePage(url, outputDirectory);
                archivedFiles.add(targetFile);
            } catch (Exception ex) {
                Path targetFile = outputDirectory.resolve(buildPdfFileName(url));
                if (Files.exists(targetFile)) {
                    // Oude cache is nog bruikbaar als de live-pagina tijdelijk niet bereikbaar is.
                    archivedFiles.add(targetFile);
                } else {
                    failures.add(url + " -> " + ex.getMessage());
                }
            }
        }

        if (!failures.isEmpty() && archivedFiles.isEmpty()) {
            throw new IOException("Webpagina's konden niet worden opgeslagen: " + String.join(" | ", failures));
        }

        return archivedFiles;
    }

// Haalt een webpagina op en schrijft de tekstuele inhoud weg als PDF.
    private Path archiveSinglePage(String url, Path outputDirectory) throws IOException {
        Document document = fetchDocument(url);
        String title = extractTitle(document, url);
        List<String> lines = extractContentLines(document, url);
        Path targetFile = outputDirectory.resolve(buildPdfFileName(url));
        writePdf(targetFile, title, url, lines);
        return targetFile;
    }

// Haalt de HTML op met een nette user-agent zodat de pagina als gewone browser wordt behandeld.
    private Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(20_000)
                .followRedirects(true)
                .get();
    }

// Bepaalt een titel voor de PDF op basis van de pagina-tekst.
// Als een h1 ontbreekt, vallen we terug op de HTML-titel of de URL.
    private String extractTitle(Document document, String url) {
        if (document == null) {
            return fallbackTitleFromUrl(url);
        }

        Element h1 = document.selectFirst("h1");
        if (h1 != null && !h1.text().isBlank()) {
            return h1.text().trim();
        }

        if (document.title() != null && !document.title().isBlank()) {
            return document.title().trim();
        }

        return fallbackTitleFromUrl(url);
    }

// Haalt de tekstuele inhoud uit de webpagina.
// We richten ons op headings, paragrafen, lijstitems en tabellen, omdat dat de kern van de uitleg vormt.
    private List<String> extractContentLines(Document document, String url) {
        List<String> lines = new ArrayList<>();
        if (document == null) {
            return lines;
        }

        Element root = firstNonNull(
                document.selectFirst("main"),
                document.selectFirst("article"),
                document.body());

        if (root == null) {
            lines.add(url);
            return lines;
        }

        root.select("script, style, noscript, nav, footer, aside, form, button, svg, iframe").remove();

        Elements blocks = root.select("h1, h2, h3, h4, p, li, table");
        for (Element block : blocks) {
            String tag = block.tagName().toLowerCase(Locale.ROOT);
            if ("table".equals(tag)) {
                extractTableLines(block, lines);
                continue;
            }

            String text = block.text().trim();
            if (text.isBlank()) {
                continue;
            }

            if (tag.startsWith("h")) {
                lines.add(text.toUpperCase(Locale.ROOT));
                lines.add("");
            } else {
                lines.add(text);
            }
        }

        if (lines.isEmpty()) {
            lines.add(url);
        }

        return lines;
    }

// Zet tabelrijen om naar simpele tekstregels zodat informatie niet verloren gaat.
    private void extractTableLines(Element table, List<String> lines) {
        for (Element row : table.select("tr")) {
            List<String> cells = row.select("th, td").eachText();
            String rowText = cells.stream()
                    .map(String::trim)
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining(" | "));
            if (!rowText.isBlank()) {
                lines.add(rowText);
            }
        }
    }

// Schrijft de opgehaalde webpagina weg als PDF.
// De PDF is een tekstuele snapshot, niet een visuele kopie van de originele website.
    private void writePdf(Path outputFile, String title, String url, List<String> lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PdfWriteState state = new PdfWriteState();
            state.page = new PDPage(PDRectangle.A4);
            document.addPage(state.page);

            float pageWidth = state.page.getMediaBox().getWidth();
            float pageHeight = state.page.getMediaBox().getHeight();
            float usableWidth = pageWidth - (PAGE_MARGIN * 2);
            state.y = pageHeight - PAGE_MARGIN;

            state.contentStream = new PDPageContentStream(document, state.page, AppendMode.OVERWRITE, true, true);
            state.contentStream.beginText();
            state.contentStream.setLeading(LINE_GAP);
            state.contentStream.setFont(bold, TITLE_FONT_SIZE);
            state.contentStream.newLineAtOffset(PAGE_MARGIN, state.y);

            for (String titleLine : wrapText(title, bold, TITLE_FONT_SIZE, usableWidth)) {
                state = writeLine(document, state, bold, TITLE_FONT_SIZE, titleLine);
            }

            state = writeLine(document, state, regular, BODY_FONT_SIZE, "");
            state = writeLine(document, state, regular, BODY_FONT_SIZE, "Bron URL: " + url);
            state = writeLine(document, state, regular, BODY_FONT_SIZE, "");

            for (String rawLine : lines) {
                if (rawLine == null) {
                    continue;
                }

                if (rawLine.isBlank()) {
                    state = writeLine(document, state, regular, BODY_FONT_SIZE, "");
                    continue;
                }

                PDFont font = rawLine.equals(rawLine.toUpperCase(Locale.ROOT)) && rawLine.length() < 120
                        ? bold
                        : regular;
                String line = rawLine;
                for (String wrappedLine : wrapText(line, font, BODY_FONT_SIZE, usableWidth)) {
                    state = writeLine(document, state, font, BODY_FONT_SIZE, wrappedLine);
                }
            }

            state.contentStream.endText();
            state.contentStream.close();

            document.save(outputFile.toFile());
        }
    }

// Schrijft één regel weg en maakt indien nodig een nieuwe pagina aan.
    private PdfWriteState writeLine(PDDocument document,
                            PdfWriteState state,
                            PDFont font,
                            float fontSize,
                            String line) throws IOException {
        float minY = PAGE_MARGIN;
        float lineHeight = fontSize * 1.35f;

        if (state.y - lineHeight < minY) {
            state.contentStream.endText();
            state.contentStream.close();

            state.page = new PDPage(PDRectangle.A4);
            document.addPage(state.page);
            state.contentStream = new PDPageContentStream(document, state.page, AppendMode.OVERWRITE, true, true);
            state.contentStream.beginText();
            state.contentStream.setLeading(lineHeight);
            state.contentStream.setFont(font, fontSize);
            state.y = state.page.getMediaBox().getHeight() - PAGE_MARGIN;
            state.contentStream.newLineAtOffset(PAGE_MARGIN, state.y);
        } else {
            state.contentStream.setFont(font, fontSize);
        }

        if (line.isBlank()) {
            state.contentStream.newLineAtOffset(0, -lineHeight);
            state.y -= lineHeight;
            return state;
        }

        state.contentStream.showText(escapePdfText(line));
        state.contentStream.newLineAtOffset(0, -lineHeight);
        state.y -= lineHeight;
        return state;
    }

// Splitst een regel op zodat deze past binnen de beschikbare PDF-breedte.
    private List<String> wrapText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> wrappedLines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            wrappedLines.add("");
            return wrappedLines;
        }

        StringBuilder currentLine = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
            float textWidth = font.getStringWidth(testLine) / 1000f * fontSize;
            if (textWidth > maxWidth && currentLine.length() > 0) {
                wrappedLines.add(currentLine.toString());
                currentLine.setLength(0);
                currentLine.append(word);
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(' ');
                }
                currentLine.append(word);
            }
        }

        if (currentLine.length() > 0) {
            wrappedLines.add(currentLine.toString());
        }

        return wrappedLines;
    }

// Escaped tekst zodat PDFBox speciale tekens veilig kan schrijven.
    private String escapePdfText(String text) {
        if (text == null) {
            return "";
        }

        return text.replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

// Maakt een veilige bestandsnaam voor de opgeslagen PDF.
    private String buildPdfFileName(String url) {
        String titlePart = fallbackTitleFromUrl(url).toLowerCase(Locale.ROOT);
        titlePart = titlePart.replaceAll("[^\\p{L}\\p{Nd}]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");

        if (titlePart.isBlank()) {
            titlePart = "webpagina";
        }

        return "Rijksoverheid_" + titlePart + ".pdf";
    }

// Valt terug op de laatste url-sectie als er geen duidelijke titel beschikbaar is.
    private String fallbackTitleFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "webpagina";
        }

        String[] parts = url.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i] != null && !parts[i].isBlank()) {
                return parts[i].replace('-', ' ');
            }
        }

        return "webpagina";
    }

// Kleine helper om drie mogelijke waarden te testen zonder extra boilerplate.
    @SafeVarargs
    private final <T> T firstNonNull(T... candidates) {
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

// Houdt de actieve PDF-schrijfstatus bij terwijl we nieuwe pagina's toevoegen.
    private static final class PdfWriteState {
        private PDPage page;
        private PDPageContentStream contentStream;
        private float y;
    }
}
