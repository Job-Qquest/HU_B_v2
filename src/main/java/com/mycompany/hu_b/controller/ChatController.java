/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.controller;

import com.mycompany.hu_b.service.AnswerService;
import com.mycompany.hu_b.service.KnowledgeService;
import com.mycompany.hu_b.service.OpenAIService;
import com.mycompany.hu_b.ui.MainFrame;
import com.mycompany.hu_b.util.HttpUtil;
import javax.swing.SwingUtilities;

public class ChatController {

    private final MainFrame view;
    private final OpenAIService openAIService;
    private final KnowledgeService knowledgeService;
    private final AnswerService answerService;

    private volatile boolean knowledgeReady = false;

    public ChatController(MainFrame view) {
        this.view = view;
        this.openAIService = new OpenAIService();
        this.knowledgeService = new KnowledgeService(openAIService);
        this.answerService = new AnswerService(knowledgeService, openAIService);
    }

    public void send(String question) {
        if (question == null || question.trim().isEmpty()) return;

        if (!knowledgeReady) {
            view.addAssistantBubble("De gids is nog niet klaar met laden. Probeer het zo opnieuw.");
            return;
        }

        view.addUserBubble(question);
        view.clearInput();

        new Thread(() -> {
            try {
                String answer = answerService.ask(question);

                if (answer == null || answer.trim().isEmpty()) {
                    answer = "Sorry, ik kon geen antwoord genereren.";
                }

                String finalAnswer = answer;

                SwingUtilities.invokeLater(() ->
                        view.addAssistantBubble(finalAnswer));

            } catch (Exception ex) {
                ex.printStackTrace();

                String msg = ex.getMessage() == null
                        ? "Onbekende fout (check console)"
                        : ex.getMessage();

                if (HttpUtil.isTimeoutException(ex)) {
                    msg = "timeout bij het ophalen van een antwoord van de AI-service. Probeer het opnieuw.";
                }

                String finalMsg = msg;
                SwingUtilities.invokeLater(() -> {
                    view.addAssistantBubble("Er ging iets mis: " + finalMsg);
                });
            }
        }).start();
    }

    public void startKnowledgeLoading() {
        new Thread(() -> {
            try {
                openAIService.validateApiKey();

                knowledgeService.loadGuide("personeelsgids.pdf");
                knowledgeReady = true;

                SwingUtilities.invokeLater(() -> {
                    view.setSendEnabled(true);
                    view.addAssistantBubble("De personeelsgids is geladen. Je kunt nu vragen stellen.");
                });

            } catch (Exception ex) {
                ex.printStackTrace();

                SwingUtilities.invokeLater(() -> {
                    view.addAssistantBubble("Opstartfout: " + ex.getMessage());
                    view.addAssistantBubble("Tip: controleer OPENAI_API_KEY en je internetverbinding.");
                });
            }
        }).start();
    }
}