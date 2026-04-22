/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/*
 Deze class stelt een definitieve chunk uit de personeelsgids voor.
 
 Een ChunkEmbedding bevat:
 - de tekst uit de PDF
 - de embedding-vector (AI-representatie van de tekst)
 - het paginanummer uit de bron
 - de functielabels (functionScope)
 - optioneel de naam van het bronbestand voor documenten zonder pagina-indeling

 Deze class wordt gebruikt tijdens het zoeken (retrieval) om relevante informatie
 te vinden op basis van semantische overeenkomst (embeddings).
*/
package com.mycompany.hu_b.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ChunkEmbedding {
    private final String text;
    private final List<Double> embedding;
    private final int page;
    private final Set<String> functionScope;
    private final String sourceLabel;
    private final String sourceUrl;
    private final String sourceName;
    private final String sourcePath;
    private final boolean sourceIsPdf;
    private final boolean primaryGuide;
    
// Maakt een definitieve chunk met alle benodigde informatie    
    public ChunkEmbedding(String text, List<Double> embedding, int page, Set<String> functionScope) {
        this(text, embedding, page, functionScope, null, null, null, null, false, false);
    }

// Maakt een definitieve chunk met een optionele bronnaam voor Word- of PDF-bijlagen
    public ChunkEmbedding(String text, List<Double> embedding, int page, Set<String> functionScope, String sourceLabel) {
        this(text, embedding, page, functionScope, sourceLabel, null, null, null, false, false);
    }

    public ChunkEmbedding(String text, List<Double> embedding, int page, Set<String> functionScope, String sourceLabel, boolean sourceIsPdf) {
        this(text, embedding, page, functionScope, sourceLabel, null, null, null, sourceIsPdf, false);
    }

    public ChunkEmbedding(String text,
                          List<Double> embedding,
                          int page,
                          Set<String> functionScope,
                          String sourceLabel,
                          String sourceUrl,
                          String sourceName,
                          String sourcePath,
                          boolean sourceIsPdf) {
        this(text, embedding, page, functionScope, sourceLabel, sourceUrl, sourceName, sourcePath, sourceIsPdf, false);
    }

    public ChunkEmbedding(String text,
                          List<Double> embedding,
                          int page,
                          Set<String> functionScope,
                          String sourceLabel,
                          String sourceUrl,
                          String sourceName,
                          String sourcePath,
                          boolean sourceIsPdf,
                          boolean primaryGuide) {
        this.text = text;
        this.embedding = embedding;
        this.page = page;
        this.functionScope = functionScope == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(functionScope);
        this.sourceLabel = sourceLabel == null || sourceLabel.isBlank() ? null : sourceLabel.trim();
        this.sourceUrl = sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl.trim();
        this.sourceName = sourceName == null || sourceName.isBlank() ? null : sourceName.trim();
        this.sourcePath = sourcePath == null || sourcePath.isBlank() ? null : sourcePath.trim();
        this.sourceIsPdf = sourceIsPdf;
        this.primaryGuide = primaryGuide;
    }

// Tekst van de chunk
    public String getText() {
        return text;
    }

// Embedding-vector
    public List<Double> getEmbedding() {
        return embedding;
    }

// Paginanummer
    public int getPage() {
        return page;
    }

// Functielabels die bij de chunk horen
    public Set<String> getFunctionScope() {
        return functionScope;
    }

// Optionele titel of bestandsnaam van de bron
    public String getSourceLabel() {
        return sourceLabel;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public boolean isSourcePdf() {
        return sourceIsPdf;
    }

    public boolean isPrimaryGuide() {
        return primaryGuide;
    }
}
