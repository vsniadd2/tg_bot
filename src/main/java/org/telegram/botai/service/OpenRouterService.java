package org.telegram.botai.service;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class OpenRouterService {
    private static final String OPENROUTER_API_KEY = "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";
    private static final String OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";

    public String askOpenRouter(String question) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();


        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "qwen/qwq-32b:free");
        requestBody.put("messages", new JSONArray()
                .put(new JSONObject()
                        .put("role", "user")
                        .put("content", question + "\nНапиши ответ без следующих символов: '*' \n" +
                                "Оформи красиво под Telegram!!!!")
                )
        );
        requestBody.put("max_tokens", 2400);

        RequestBody body = RequestBody.create(requestBody.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(OPENROUTER_API_URL)
                .post(body)
                .addHeader("Authorization", "Bearer " + OPENROUTER_API_KEY)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ошибка при запросе к OpenRouter API: " + response.body().string());
            }

            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);

            System.out.println("Полный ответ от API: " + jsonResponse.toString(2));

            // Проверяем наличие ошибки в ответе API
            if (jsonResponse.has("error")) {
                String errorMessage = jsonResponse.getJSONObject("error").getString("message");
                throw new IOException("Ошибка API: " + errorMessage);
            }

            // Проверяем, содержит ли ответ choices
            if (!jsonResponse.has("choices") || jsonResponse.getJSONArray("choices").length() == 0) {
                throw new IOException("Ответ от API не содержит choices. Полный ответ: " + jsonResponse.toString(2));
            }

            JSONArray choices = jsonResponse.getJSONArray("choices");
            JSONObject firstChoice = choices.getJSONObject(0);

            if (!firstChoice.has("message") || !firstChoice.getJSONObject("message").has("content")) {
                throw new IOException("Ответ от API не содержит ожидаемого поля 'message.content'.");
            }

            return firstChoice.getJSONObject("message").getString("content");
        }
    }
}
