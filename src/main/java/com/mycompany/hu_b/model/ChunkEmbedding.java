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

// Maakt een definitieve chunk met alle benodigde informatie    
    public ChunkEmbedding(String text, List<Double> embedding, int page, Set<String> functionScope) {
        this.text = text;
        this.embedding = embedding;
        this.page = page;
        this.functionScope = functionScope == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(functionScope);
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
}