package com.mycompany.hu_b.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.mycompany.hu_b.model.ChunkEmbedding;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Beheert een eenvoudige lokale JSON-cache met alle chunks en embeddings.
// Hiermee hoeft de app bij een gewone opstart niet opnieuw alle bronbestanden te lezen.
public final class KnowledgeChunkCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CACHE_FILE_NAME = "chunks-cache.json";

    // Controleert of de cache nog geldig is voor de opgegeven bronbestanden.
    public boolean isCacheValid(Path cachePath, List<Path> sourceFiles) {
        if (cachePath == null || sourceFiles == null || sourceFiles.isEmpty() || !Files.exists(cachePath)) {
            return false;
        }

        try {
            CacheFile cacheFile = readCacheFile(cachePath);
            if (cacheFile == null
                    || cacheFile.sources == null
                    || cacheFile.sources.isEmpty()
                    || cacheFile.chunks == null
                    || cacheFile.chunks.isEmpty()) {
                return false;
            }

            Map<String, SourceFingerprint> expected = buildFingerprints(sourceFiles);
            if (expected.size() != cacheFile.sources.size()) {
                return false;
            }

            for (SourceFingerprint stored : cacheFile.sources) {
                if (stored == null || stored.path == null || stored.path.isBlank()) {
                    return false;
                }

                SourceFingerprint current = expected.get(stored.path);
                if (current == null) {
                    return false;
                }

                if (current.lastModified != stored.lastModified || current.size != stored.size) {
                    return false;
                }
            }

            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // Leest de chunkcache in en zet de opgeslagen data terug naar ChunkEmbedding-objecten.
    public List<ChunkEmbedding> loadChunks(Path cachePath) throws IOException {
        CacheFile cacheFile = readCacheFile(cachePath);
        List<ChunkEmbedding> chunks = new ArrayList<>();
        if (cacheFile == null || cacheFile.chunks == null) {
            return chunks;
        }

        for (CachedChunk cachedChunk : cacheFile.chunks) {
            if (cachedChunk == null || cachedChunk.text == null) {
                continue;
            }

            chunks.add(new ChunkEmbedding(
                    cachedChunk.text,
                    cachedChunk.embedding == null ? List.of() : cachedChunk.embedding,
                    cachedChunk.page,
                    cachedChunk.functionScope == null ? Set.of() : new LinkedHashSet<>(cachedChunk.functionScope),
                    cachedChunk.sourceLabel,
                    cachedChunk.sourceUrl,
                    cachedChunk.sourceName,
                    cachedChunk.sourceIsPdf,
                    cachedChunk.primaryGuide));
        }

        return chunks;
    }

    // Schrijft alle chunks weg naar de lokale cache.
    public void saveChunks(Path cachePath, List<ChunkEmbedding> chunks, List<Path> sourceFiles) throws IOException {
        if (cachePath == null) {
            throw new IllegalArgumentException("cachePath mag niet null zijn.");
        }

        CacheFile cacheFile = new CacheFile();
        cacheFile.sources = new ArrayList<>(buildFingerprints(sourceFiles).values());
        cacheFile.chunks = new ArrayList<>();

        if (chunks != null) {
            for (ChunkEmbedding chunk : chunks) {
                if (chunk == null) {
                    continue;
                }

                CachedChunk cachedChunk = new CachedChunk();
                cachedChunk.text = chunk.getText();
                cachedChunk.embedding = chunk.getEmbedding() == null ? List.of() : new ArrayList<>(chunk.getEmbedding());
                cachedChunk.page = chunk.getPage();
                cachedChunk.functionScope = chunk.getFunctionScope() == null
                        ? List.of()
                        : new ArrayList<>(chunk.getFunctionScope());
                cachedChunk.sourceLabel = chunk.getSourceLabel();
                cachedChunk.sourceUrl = chunk.getSourceUrl();
                cachedChunk.sourceName = chunk.getSourceName();
                cachedChunk.sourceIsPdf = chunk.isSourcePdf();
                cachedChunk.primaryGuide = chunk.isPrimaryGuide();
                cacheFile.chunks.add(cachedChunk);
            }
        }

        Path parent = cachePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(cachePath)) {
            GSON.toJson(cacheFile, writer);
        }
    }

    // Geeft het standaard cachebestand terug in dezelfde map als de gids.
    public Path resolveDefaultCachePath(Path guidePath) {
        Path baseDir = guidePath == null ? Path.of(".") : guidePath.toAbsolutePath().normalize().getParent();
        if (baseDir == null) {
            baseDir = Path.of(".").toAbsolutePath().normalize();
        }
        return baseDir.resolve(CACHE_FILE_NAME);
    }

    // Maakt een fingerprint van alle bronbestanden.
    public Map<String, SourceFingerprint> buildFingerprints(List<Path> sourceFiles) throws IOException {
        Map<String, SourceFingerprint> fingerprints = new LinkedHashMap<>();
        if (sourceFiles == null) {
            return fingerprints;
        }

        for (Path sourceFile : sourceFiles) {
            SourceFingerprint fingerprint = fingerprint(sourceFile);
            if (fingerprint != null) {
                fingerprints.put(fingerprint.path, fingerprint);
            }
        }

        return fingerprints;
    }

    private CacheFile readCacheFile(Path cachePath) throws IOException {
        if (cachePath == null || !Files.exists(cachePath)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(cachePath)) {
            return GSON.fromJson(reader, CacheFile.class);
        }
    }

    private SourceFingerprint fingerprint(Path sourceFile) throws IOException {
        if (sourceFile == null) {
            return null;
        }

        Path normalized = sourceFile.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return null;
        }

        BasicFileAttributes attrs = Files.readAttributes(normalized, BasicFileAttributes.class);
        SourceFingerprint fingerprint = new SourceFingerprint();
        fingerprint.path = normalized.toString();
        fingerprint.lastModified = attrs.lastModifiedTime().toMillis();
        fingerprint.size = attrs.size();
        return fingerprint;
    }

    private static final class CacheFile {
        @SerializedName("sources")
        private List<SourceFingerprint> sources = List.of();
        @SerializedName("chunks")
        private List<CachedChunk> chunks = List.of();
    }

    private static final class SourceFingerprint {
        @SerializedName("path")
        private String path;
        @SerializedName("lastModified")
        private long lastModified;
        @SerializedName("size")
        private long size;
    }

    private static final class CachedChunk {
        @SerializedName("text")
        private String text;
        @SerializedName("embedding")
        private List<Double> embedding = List.of();
        @SerializedName("page")
        private int page;
        @SerializedName("functionScope")
        private List<String> functionScope = List.of();
        @SerializedName("sourceLabel")
        private String sourceLabel;
        @SerializedName("sourceUrl")
        private String sourceUrl;
        @SerializedName("sourceName")
        private String sourceName;
        @SerializedName("sourceIsPdf")
        private boolean sourceIsPdf;
        @SerializedName("primaryGuide")
        private boolean primaryGuide;
    }
}
