package com.mycompany.hu_b.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Deze class bevat de centrale lijst met functieprofielen en bijbehorende trefwoorden.
// De methods in deze class herkennen op basis van tekst of een stuk inhoud
// bij een bepaalde functie of doelgroep hoort.
public class FunctionProfile {

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
                "fieldmanager", "fieldmanager tc"
        ));
        profiles.put("TC coördinator", Arrays.asList(
                "tc coördinator", "tc coordinator", "coördinator", "coordinator"
        ));
        profiles.put("Business unit manager", Arrays.asList(
                "business unit manager", "bu manager"
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

        String normalized = normalizeForMatching(text);
        for (Map.Entry<String, List<String>> profile : FUNCTION_PROFILES.entrySet()) {
            for (String keyword : profile.getValue()) {
                if (normalized.contains(normalizeForMatching(keyword))) {
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

        String normalizedLine = normalizeForMatching(line);
        if (normalizedLine.length() > 50) {
            return labels;
        }

        String[] words = normalizedLine.split("\\s+");
        if (words.length > 5 || normalizedLine.matches(".*\\d.*")) {
            return labels;
        }

        for (Map.Entry<String, List<String>> profile : FUNCTION_PROFILES.entrySet()) {
            for (String keyword : profile.getValue()) {
                String normalizedKeyword = normalizeForMatching(keyword);
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

    private static String normalizeForMatching(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();
    }
}
