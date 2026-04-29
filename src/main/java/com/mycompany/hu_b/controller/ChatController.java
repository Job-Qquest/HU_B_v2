/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.controller;

import com.mycompany.hu_b.service.ChatbotAntwoord;
import com.mycompany.hu_b.service.KnowledgeChunkCache;
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
    private final KnowledgeChunkCache chunkCache;

    private volatile boolean knowledgeReady = false;
    private volatile boolean knowledgeLoading = false;

// Initialiseert alle onderdelen van de chatbot
    public ChatController(AppVenster view) {
        this.view = view;
        this.openAIService = new OpenAI();
        this.knowledgeService = new PdfProcessing(openAIService);
        this.answerService = new ChatbotAntwoord(knowledgeService, openAIService);
        this.webPageArchiveService = new WebPageArchiveService();
        this.view.setRememberedMessageLimit(answerService.getMaxHistoryMessages());
        this.chunkCache = new KnowledgeChunkCache();
    }

// Methode die wordt aangeroepen wanneer gebruiker een vraag stelt
    public void send(String question) {
        if (question == null || question.trim().isEmpty()) {
            return;
        }

        if (isCelebrationTrigger(question)) {
            view.addUserBubble(question, true);
            view.clearInput();
            SwingUtilities.invokeLater(() -> {
                view.playCelebration();
                view.addAssistantBubble("Hoera! 🎉", false);
            });
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
                        -> view.addAssistantBubbleAnimated(finalAnswer, true));

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

    private boolean isCelebrationTrigger(String question) {
        if (question == null) {
            return false;
        }

        String normalized = question.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }

        return normalized.matches(".*\\bhoera\\b.*");
    }

// Methode die bij het opstarten wordt aangeroepen om de kennisbron te laden
// Ook dit gebeurt in een aparte thread (kan lang duren)
    public void startKnowledgeLoading() {
        loadKnowledgeAsync(false);
    }

    // Forceert een volledige herlaadbeurt van websites en documenten.
    // De cache wordt hierbij bewust genegeerd zodat bijgewerkte bronnen direct worden opgehaald.
    public void refreshKnowledge() {
        loadKnowledgeAsync(true);
    }

    private void loadKnowledgeAsync(boolean forceReload) {
        synchronized (this) {
            if (knowledgeLoading) {
                SwingUtilities.invokeLater(() -> view.addAssistantBubble(
                        forceReload
                                ? "Ik ben al bezig met het vernieuwen van de bronnen."
                                : "Ik ben al bezig met het laden van de bronnen.",
                        false));
                return;
            }
            knowledgeLoading = true;
        }

        final boolean hadKnowledgeBeforeReload = knowledgeReady;
        SwingUtilities.invokeLater(() -> {
            view.setSendEnabled(false);
            view.setRefreshEnabled(false);
            view.addAssistantBubble(
                    forceReload
                            ? "Ik ververs nu alle websites en documenten. Dit kan even duren..."
                            : "Ik laad nu de personeelsgids. Een moment geduld...",
                    false);
        });

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
                Path cacheFile = chunkCache.resolveDefaultCachePath(guideFile);
                KnowledgeLoadContext loadContext = prepareKnowledgeLoadContext(guideFile, archiveDirectory, cacheFile);

                if (!forceReload && chunkCache.isCacheValid(cacheFile, loadContext.sourceFiles())) {
                    try {
                        knowledgeService.replaceChunks(chunkCache.loadChunks(cacheFile));
                        knowledgeReady = true;

                        SwingUtilities.invokeLater(() -> {
                            view.setSendEnabled(true);
                            view.setRefreshEnabled(true);
                            view.replaceLastAssistantBubble("De kennisbron is geladen uit de cache. Je kunt nu vragen stellen.", false);
                        });
                        return;
                    } catch (Exception cacheEx) {
                        System.out.println("Cache kon niet worden geladen, opnieuw opbouwen: " + cacheEx.getMessage());
                    }
                }

                List<Path> webArchiveFiles = webPageArchiveService.archivePages(loadContext.websiteLinks(), archiveDirectory);
                List<String> rebuiltSupplementarySources = new ArrayList<>(loadContext.supplementarySources());
                for (Path webArchiveFile : webArchiveFiles) {
                    if (webArchiveFile != null) {
                        rebuiltSupplementarySources.add(webArchiveFile.toString());
                    }
                }

                PdfProcessing stagedKnowledgeService = new PdfProcessing(openAIService);
                stagedKnowledgeService.loadGuide(resolveGuidePath(), rebuiltSupplementarySources);
                chunkCache.saveChunks(cacheFile, stagedKnowledgeService.getChunks(), loadContext.sourceFiles());
                knowledgeService.replaceChunks(stagedKnowledgeService.getChunks());
                knowledgeReady = true;

                SwingUtilities.invokeLater(() -> {
                    view.setSendEnabled(true);
                    view.setRefreshEnabled(true);
                    view.replaceLastAssistantBubble(
                            forceReload
                                    ? "De bronnen zijn opnieuw ingeladen en de cache is ververst. Je kunt nu vragen stellen."
                                    : "De kennisbron is opgebouwd en opgeslagen in de cache. Je kunt nu vragen stellen.",
                            false);
                });

            } catch (Exception ex) {
                ex.printStackTrace();

                SwingUtilities.invokeLater(() -> {
                    if (hadKnowledgeBeforeReload) {
                        knowledgeReady = true;
                        view.setSendEnabled(true);
                        view.replaceLastAssistantBubble("Vernieuwen mislukt, maar de bestaande kennisbron blijft actief.", false);
                    } else {
                        knowledgeReady = false;
                        view.setSendEnabled(false);
                        view.replaceLastAssistantBubble(
                                "Opstartfout: " + ex.getMessage() + "\n\nTip: controleer OPENAI_API_KEY en je internetverbinding.",
                                false);
                    }
                    view.setRefreshEnabled(true);
                });
            } finally {
                synchronized (ChatController.this) {
                    knowledgeLoading = false;
                }
            }
        }).start();
    }

    // De hoofdgids blijft altijd de PDF; daaruit halen we de verwijzingen naar extra bronnen.
    private String resolveGuidePath() {
        return "personeelsgids.pdf";
    }

    private KnowledgeLoadContext prepareKnowledgeLoadContext(Path guideFile, Path archiveDirectory, Path cacheFile) throws Exception {
        // Leest bestand lijstWebsites.txt en maakt een lijst met websitelinks
        // die wordt gebruikt om te scrapen.
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

        // Maakt een lijst met alle word en pdf bestanden in de map waar
        // de personeelsgids in staat.
        List<String> supplementarySources = new ArrayList<>();
        File directory = archiveDirectory.toFile();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file == null || file.isDirectory()) {
                    continue;
                }

                String name = file.getName();
                if (name == null) {
                    continue;
                }

                String lowerName = name.toLowerCase(Locale.ROOT);
                String guideName = guideFile.getFileName() == null
                        ? ""
                        : guideFile.getFileName().toString().toLowerCase(Locale.ROOT);
                String cacheName = cacheFile.getFileName() == null
                        ? ""
                        : cacheFile.getFileName().toString().toLowerCase(Locale.ROOT);

                if (lowerName.equals(guideName) || lowerName.equals(cacheName)) {
                    continue;
                }

                if (lowerName.endsWith(".pdf")
                        || lowerName.endsWith(".doc")
                        || lowerName.endsWith(".docx")) {
                    supplementarySources.add(file.toPath().toAbsolutePath().normalize().toString());
                }
            }
        }

        List<Path> sourceFiles = new ArrayList<>();
        sourceFiles.add(guideFile);
        if (Files.exists(websitesList)) {
            sourceFiles.add(websitesList);
        }
        for (String source : supplementarySources) {
            sourceFiles.add(Path.of(source));
        }

        return new KnowledgeLoadContext(websiteLinks, supplementarySources, sourceFiles);
    }

    private record KnowledgeLoadContext(List<String> websiteLinks, List<String> supplementarySources, List<Path> sourceFiles) {
    }
}
