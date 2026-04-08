/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

// Voorbereidende stap
// Bevat al wel tekst en function scope; zonder embedding

package com.mycompany.hu_b.model;

import java.util.LinkedHashSet;
import java.util.Set;

public class ChunkDraft {
    private final String text;
    private final Set<String> functionScope;

    public ChunkDraft(String text, Set<String> functionScope) {
        this.text = text;
        this.functionScope = functionScope == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(functionScope);
    }

    public String getText() {
        return text;
    }

    public Set<String> getFunctionScope() {
        return functionScope;
    }
}