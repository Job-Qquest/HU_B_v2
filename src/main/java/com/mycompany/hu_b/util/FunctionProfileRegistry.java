/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.util;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FunctionProfileRegistry {

    private static final Map<String, List<String>> FUNCTION_PROFILES = createFunctionProfiles();

    private static Map<String, List<String>> createFunctionProfiles() {
        Map<String, List<String>> profiles = new LinkedHashMap<>();
        profiles.put("Talentclass Consultant", Arrays.asList(
                "talentclass", "talent class", "tc consultant", "tc-consultant", "tc consultants"
        ));
        profiles.put("Accountmanager", Arrays.asList(
                "accountmanager"
        ));
        profiles.put("Recruiter", Arrays.asList(
                "recruiter", "corporate recruiter", "stage", "werkstudent"
        ));
        profiles.put("Fieldmanager TC", Arrays.asList(
                "fieldmanager", "fieldmanager TC"
        ));
        profiles.put("TC coördinator", Arrays.asList(
                "TC coördinator", "coördinator"
        ));
        profiles.put("Business unit manager", Arrays.asList(
                "business unit manager", "BU manager"
        ));
        return profiles;
    }

    public static Map<String, List<String>> getFunctionProfiles() {
        return FUNCTION_PROFILES;
    }

    public static Set<String> detectFunctionLabels(String text) {
        Set<String> labels = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return labels;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> profile : FUNCTION_PROFILES.entrySet()) {
            for (String keyword : profile.getValue()) {
                if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                    labels.add(profile.getKey());
                    break;
                }
            }
        }

        return labels;
    }

    public static Set<String> detectFunctionHeaderLabels(String line) {
        Set<String> labels = new LinkedHashSet<>();
        if (line == null || line.isBlank()) {
            return labels;
        }

        String normalizedLine = line.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();

        if (normalizedLine.length() > 90) {
            return labels;
        }

        for (Map.Entry<String, List<String>> profile : FUNCTION_PROFILES.entrySet()) {
            for (String keyword : profile.getValue()) {
                String normalizedKeyword = keyword.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                        .trim();
                if (normalizedKeyword.isEmpty()) {
                    continue;
                }

                if (normalizedLine.equals(normalizedKeyword)
                        || normalizedLine.startsWith(normalizedKeyword + " ")
                        || normalizedLine.endsWith(" " + normalizedKeyword)
                        || normalizedLine.contains(" " + normalizedKeyword + " ")) {
                    labels.add(profile.getKey());
                    break;
                }
            }
        }

        return labels;
    }
}