package com.mycompany.hu_b.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mycompany.hu_b.model.ChunkDraft;
import com.mycompany.hu_b.model.ChunkEmbedding;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WebPageProcessing {

    private WebPageProcessing() {
    }

    // Leest een JSON-webarchief en zet de opgeslagen tekst om naar chunks.
    public static void loadSupplementaryJson(KnowledgeProcessingUtils processing, Path jsonPath) throws Exception {
        if (processing == null || jsonPath == null) {
            return;
        }

        try (Reader reader = java.nio.file.Files.newBufferedReader(jsonPath)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                return;
            }

            if (root.isJsonArray()) {
                JsonArray array = root.getAsJsonArray();
                for (JsonElement element : array) {
                    if (element != null && element.isJsonObject()) {
                        loadArchivedWebPage(processing, element.getAsJsonObject(), jsonPath, false);
                    }
                }
                return;
            }

            if (root.isJsonObject()) {
                loadArchivedWebPage(processing, root.getAsJsonObject(), jsonPath, true);
            }
        }
    }

    // Zet een opgeslagen webpagina om naar chunks en embeddings.
    private static void loadArchivedWebPage(KnowledgeProcessingUtils processing,
                                            JsonObject object,
                                            Path jsonPath,
                                            boolean treatAsSinglePage) throws Exception {
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

        String sourceLabel = title != null ? title : processing.buildSourceLabel(jsonPath);
        String sourceUrl = url;
        String sourceName = source;
        Set<String> activeFunctionScope = new LinkedHashSet<>();
        int pageNumber = treatAsSinglePage ? 1 : 0;

        String pageText = String.join("\n", contentLines);
        List<ChunkDraft> drafts = processing.chunkTextWithFunctionScope(pageText, 800, activeFunctionScope);
        for (ChunkDraft draft : drafts) {
            processing.chunks.add(new ChunkEmbedding(
                    draft.getText(),
                    processing.openAIService.embed(draft.getText()),
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
    private static List<String> extractContentLinesFromJson(JsonObject object) {
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
    private static String getStringOrNull(JsonObject object, String key) {
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
}
