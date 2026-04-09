/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.hu_b.service;

import com.mycompany.hu_b.util.HttpRetriesTimeouts;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

// Verzorgt alle communicatie met de OpenAI API.
// De class controleert of de API-key aanwezig is,
// maakt embeddings van tekst voor semantic search
// en stuurt prompts naar het chatmodel om antwoorden te laten genereren.

public class OpenAI {

// API-key wordt opgehaald
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

// HTTP client met timeouts en retry instellingen
    private final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

// Controleert of de API-key aanwezig is
// Zo niet -> applicatie stopt met duidelijke foutmelding
    public void validateApiKey() {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY ontbreekt. Voeg deze omgevingsvariabele toe.");
        }
    }

// Roept de embedding API aan en en zet tekst om naar een vector (lijst met getallen)  
    public List<Double> embed(String input) throws Exception {
// Bouwt JSON body voor request
        JSONObject body = new JSONObject()
                .put("model", "text-embedding-3-small")
                .put("input", input);

// Maakt Http request
        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/embeddings")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = HttpRetriesTimeouts.executeWithRetries(CLIENT, request, "Embedding")) {
            JSONObject json = new JSONObject(response.body().string());

            JSONArray arr = json.getJSONArray("data")
                    .getJSONObject(0)
                    .getJSONArray("embedding");

            List<Double> vector = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                vector.add(arr.getDouble(i));
            }

            return vector;
        }
    }

// Roept het chatmodel aan om een antwoord te genereren
    public String chat(String systemPrompt) throws Exception {
// Maakt message array 
        JSONArray messages = new JSONArray()
                .put(new JSONObject()
                        .put("role", "system")
                        .put("content", systemPrompt));

// Bouwt request body        
        JSONObject body = new JSONObject()
                .put("model", "gpt-4o-mini")
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("top_p", 0);

// Maakt HTTP request
        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                .build();

// Voert request uit met retry-mechanisme
        try (Response response = HttpRetriesTimeouts.executeWithRetries(CLIENT, request, "Chat")) {
// Parse JSON response
            JSONObject json = new JSONObject(response.body().string());

// Haal het gegenereerde antwoord uit de response
            return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
        }
    }
}