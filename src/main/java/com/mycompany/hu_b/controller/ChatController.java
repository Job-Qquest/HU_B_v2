/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.controller;

import com.mycompany.hu_b.service.ChatbotAntwoord;
import com.mycompany.hu_b.service.OpenAI;
import com.mycompany.hu_b.service.PdfProcessing;
import com.mycompany.hu_b.service.WebPageArchiveService;
import com.mycompany.hu_b.ui.AppVenster;
import com.mycompany.hu_b.util.HttpRetriesTimeouts;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.SwingUtilities;

// De controller verwerkt verzonden vragen, controleert of de kennisbron geladen is,
// start het laden van de personeelsgids en haalt antwoorden op via ChatbotAntwoord.
// Resultaten en foutmeldingen worden teruggezet in het AppVenster (de UI).
public class ChatController {

    private final AppVenster view;
    private final OpenAI openAIService;
    private final PdfProcessing knowledgeService;
    private final ChatbotAntwoord answerService;
    private final WebPageArchiveService webPageArchiveService;

    private volatile boolean knowledgeReady = false;

// Initialiseert alle onderdelen van de chatbot
    public ChatController(AppVenster view) {
        this.view = view;
        this.openAIService = new OpenAI();
        this.knowledgeService = new PdfProcessing(openAIService);
        this.answerService = new ChatbotAntwoord(knowledgeService, openAIService);
        this.webPageArchiveService = new WebPageArchiveService();
        this.view.setRememberedMessageLimit(answerService.getMaxHistoryMessages());
    }

// Methode die wordt aangeroepen wanneer gebruiker een vraag stelt
    public void send(String question) {
        if (question == null || question.trim().isEmpty()) {
            return;
        }

        if (!knowledgeReady) {
            view.addAssistantBubble("De gids is nog niet klaar met laden. Probeer het zo opnieuw.", false);
            return;
        }

// Toon de vraag van de gebruiker in de UI en maak het invoerveld leeg
        view.addUserBubble(question, true);
        view.clearInput();

// Start een nieuwe thread zodat de UI niet vastloopt tijdens API-calls        
        new Thread(() -> {
            try {
                String answer = answerService.ask(question);

                if (answer == null || answer.trim().isEmpty()) {
                    answer = "Sorry, ik kon geen antwoord genereren.";
                }

                String finalAnswer = answer;

// Zet het antwoord terug in de UI
                SwingUtilities.invokeLater(()
                        -> view.addAssistantBubble(finalAnswer, true));

            } catch (Exception ex) {
                ex.printStackTrace();

                String msg = ex.getMessage() == null
                        ? "Onbekende fout (check console)"
                        : ex.getMessage();

                if (HttpRetriesTimeouts.isTimeoutException(ex)) {
                    msg = "timeout bij het ophalen van een antwoord van de AI-service. Probeer het opnieuw.";
                }

                String finalMsg = msg;
                SwingUtilities.invokeLater(() -> {
                    view.addAssistantBubble("Er ging iets mis: " + finalMsg, false);
                });
            }
        }).start();
    }

// Methode die bij het opstarten wordt aangeroepen om de kennisbron te laden
// Ook dit gebeurt in een aparte thread (kan lang duren)
    public void startKnowledgeLoading() {
        new Thread(() -> {
            try {
                openAIService.validateApiKey();

// Laat eerst de webpagina's archiveren naar lokale JSON-bestanden in dezelfde map als de gids.
                Path guideFile = Path.of(resolveGuidePath()).toAbsolutePath().normalize();
                Path archiveDirectory = guideFile.getParent();
                if (archiveDirectory == null) {
                    // Fallback voor het geval de gids zonder oudermap wordt aangeroepen.
                    archiveDirectory = Path.of(".").toAbsolutePath().normalize();
                }
                String archiveDirectories = archiveDirectory.toString();
                
                //Leest bestand lijstWebsites.txt en maakt een lijst met websitelinks
                //die wordt gebruikt om te scrapen
                Path websitesList = Path.of("lijstWebsites.txt").toAbsolutePath().normalize();
                List<String> websiteLinks = new ArrayList<>();
                if (Files.exists(websitesList)) {
                    for (String rawLink : Files.readAllLines(websitesList)) {
                        if (rawLink == null) {
                            continue;
                        }

                        String trimmed = rawLink.trim();
                        if (!trimmed.isEmpty()) {
                            websiteLinks.add(trimmed);
                        }
                    }
                } else {
                    System.out.println("lijstWebsites.txt niet gevonden op " + websitesList);
                }

                List<Path> webArchiveFiles = webPageArchiveService.archivePages(websiteLinks, archiveDirectory);
                
                
                //Maakt een lijst met alle word en pdf bestanden in de map waar 
                //de personeelsgids in staat
                File directory = new File(archiveDirectories);
                File[] files = directory.listFiles();
                List<String> supplementarySources = new ArrayList<>();

                if (files != null) {
                    for (File file : files) {
                        String name = file.getName();
                        if (name == null) {
                            continue;
                        }

                        String lowerName = name.toLowerCase(Locale.ROOT);
                        if (lowerName.endsWith(".pdf")
                                || lowerName.endsWith(".doc")
                                || lowerName.endsWith(".docx")) {
                            supplementarySources.add(lowerName);
                        }
                    }
                }
                //// Laad de personeelsgids en maak embeddings.
//// De extra documenten komen uit de PDF en de webpagina's worden eerst lokaal als JSON opgeslagen.
//                List<String> supplementarySources = new ArrayList<>(List.of(
//                        "Adviezen m.b.t. gezond in een auto rijden.docx",
//                        "Gezond beeldschermwerk.docx",
//                        "Pensioenreglement ZwitserLeven 1-1-2018.pdf",
//                        "Pensioenreglement ZwitserLeven Bijlagen 1-1-2018.pdf",
//                        "PG4 - Vergoedingen.pdf",
//                        "Psychosociale arbeidsbelasting.docx",
//                        "Werkwijzer-Poortwachter.pdf"
//                ));
                for (Path webArchiveFile : webArchiveFiles) {
                    supplementarySources.add(webArchiveFile.toString());
                }

                knowledgeService.loadGuide(resolveGuidePath(), supplementarySources);
                knowledgeReady = true;

                SwingUtilities.invokeLater(() -> {
                    view.setSendEnabled(true);
                    view.addAssistantBubble("De personeelsgids is geladen. Je kunt nu vragen stellen.", false);
                });

            } catch (Exception ex) {
                ex.printStackTrace();

                SwingUtilities.invokeLater(() -> {
                    view.addAssistantBubble("Opstartfout: " + ex.getMessage(), false);
                    view.addAssistantBubble("Tip: controleer OPENAI_API_KEY en je internetverbinding.", false);
                });
            }
        }).start();
    }

    // De hoofdgids blijft altijd de PDF; daaruit halen we de verwijzingen naar extra bronnen.
    private String resolveGuidePath() {
        return "personeelsgids.pdf";
    }
}
