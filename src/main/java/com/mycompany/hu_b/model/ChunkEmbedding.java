/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

// Definitieve chunk met embedding

package com.mycompany.hu_b.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ChunkEmbedding {
    private final String text;
    private final List<Double> embedding;
    private final int page;
    private final Set<String> functionScope;

// Slaat per tekstdeel de broninhoud, embedding-vector en paginaverwijzing op.
    
    public ChunkEmbedding(String text, List<Double> embedding, int page, Set<String> functionScope) {
        this.text = text;
        this.embedding = embedding;
        this.page = page;
        this.functionScope = functionScope == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(functionScope);
    }

    public String getText() {
        return text;
    }

    public List<Double> getEmbedding() {
        return embedding;
    }

    public int getPage() {
        return page;
    }

    public Set<String> getFunctionScope() {
        return functionScope;
    }
}