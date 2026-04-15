package com.mycompany.hu_b.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

// Haalt webpagina's op en bewaart de gescrapede inhoud als JSON-bestand.
// De JSON bevat de url, titel, bronnaam en de tekstregels die later als kennisbron worden ingelezen.
public class WebPageArchiveService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

// Haalt meerdere webpagina's op en bewaart ze als JSON in de opgegeven map.
// Als ophalen mislukt maar een eerdere cache-versie bestaat, gebruiken we die bestaande JSON.
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
                Path targetFile = outputDirectory.resolve(buildJsonFileName(url));
                if (Files.exists(targetFile)) {
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

// Haalt een webpagina op en schrijft de tekstuele inhoud weg als JSON.
    private Path archiveSinglePage(String url, Path outputDirectory) throws IOException {
        Document document = fetchDocument(url);
        String title = extractTitle(document, url);
        String source = extractSource(document, url);
        List<String> contentLines = extractContentLines(document, url);
        Path targetFile = outputDirectory.resolve(buildJsonFileName(url));
        writeJson(targetFile, url, title, source, contentLines);
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

// Bepaalt een titel op basis van de pagina-tekst.
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

// Bepaalt de bronnaam van de webpagina op basis van het domein.
    private String extractSource(Document document, String url) {
        String host = null;

        if (url != null && !url.isBlank()) {
            try {
                URI uri = URI.create(url.trim());
                host = uri.getHost();
            } catch (IllegalArgumentException ignored) {
                host = null;
            }
        }

        if (host == null || host.isBlank()) {
            return document != null && document.title() != null && !document.title().isBlank()
                    ? document.title().trim()
                    : "webpagina";
        }

        String normalized = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        return normalized;
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
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("");
            if (!rowText.isBlank()) {
                lines.add(rowText);
            }
        }
    }

// Schrijft de webpagina weg als JSON-bestand.
    private void writeJson(Path outputFile, String url, String title, String source, List<String> contentLines) throws IOException {
        WebPageArchiveRecord record = new WebPageArchiveRecord(url, title, source, contentLines);
        try (Writer writer = Files.newBufferedWriter(outputFile)) {
            GSON.toJson(record, writer);
        }
    }

// Maakt een veilige bestandsnaam voor de opgeslagen JSON.
    private String buildJsonFileName(String url) {
        String hostPart = "webpagina";
        if (url != null && !url.isBlank()) {
            try {
                URI uri = URI.create(url.trim());
                if (uri.getHost() != null && !uri.getHost().isBlank()) {
                    hostPart = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
                }
            } catch (IllegalArgumentException ignored) {
                hostPart = "webpagina";
            }
        }

        String titlePart = fallbackTitleFromUrl(url).toLowerCase(Locale.ROOT);
        titlePart = titlePart.replaceAll("[^\\p{L}\\p{Nd}]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");

        if (titlePart.isBlank()) {
            titlePart = "webpagina";
        }

        return hostPart + "_" + titlePart + ".json";
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

    private static final class WebPageArchiveRecord {
        private final String url;
        private final String title;
        private final String source;
        private final List<String> content;

        private WebPageArchiveRecord(String url, String title, String source, List<String> content) {
            this.url = url;
            this.title = title;
            this.source = source;
            this.content = content == null ? List.of() : List.copyOf(content);
        }
    }
}
