/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

// Voorbereidende stap
// Bevat al wel tekst en bijbehorende functielabels; zonder embedding
// Dit zal vervolgens omgezet worden naar een definitieve chunk met embedding (ChunkEmbedding)

package com.mycompany.hu_b.model;

import java.util.LinkedHashSet;
import java.util.Set;

public class ChunkDraft {
    private final String text;
    private final Set<String> functionScope;
    
// Maakt een nieuwe ChunkDraft met tekst en functielabels
    public ChunkDraft(String text, Set<String> functionScope) {
        this.text = text;
        this.functionScope = functionScope == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(functionScope);
    }

// Geeft de tekst van deze chunk terug
    public String getText() {
        return text;
    }

// Geeft de functielabels terug die bij deze chunk horen
    public Set<String> getFunctionScope() {
        return functionScope;
    }
}