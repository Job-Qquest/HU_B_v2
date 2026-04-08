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

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    private final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public void validateApiKey() {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY ontbreekt. Voeg deze omgevingsvariabele toe.");
        }
    }

    // Roept de embedding-API aan en retourneert de numerieke vector van de invoertekst.
    
    public List<Double> embed(String input) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", "text-embedding-3-small")
                .put("input", input);

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

    public String chat(String systemPrompt) throws Exception {
        JSONArray messages = new JSONArray()
                .put(new JSONObject()
                        .put("role", "system")
                        .put("content", systemPrompt));

        JSONObject body = new JSONObject()
                .put("model", "gpt-4o-mini")
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("top_p", 0);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = HttpRetriesTimeouts.executeWithRetries(CLIENT, request, "Chat")) {
            JSONObject json = new JSONObject(response.body().string());

            return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
        }
    }
}