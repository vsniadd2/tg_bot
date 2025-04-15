package org.telegram.botai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OpenRouterService {

    @Value("${api.key}")
    private String OPENROUTER_API_KEY; //grok model
    private static final String OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private final OkHttpClient client;

    public OpenRouterService() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public String askOpenRouter(String question) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "openai/gpt-4-turbo");

        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");


        JSONArray contentArray = new JSONArray();

        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", question + " пиши красиво без * в твоем ответе");
        contentArray.put(textContent);

        message.put("content", contentArray);
        messages.put(message);
        requestBody.put("messages", messages);

        RequestBody body = RequestBody.create(requestBody.toString(), MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(OPENROUTER_API_URL)
                .post(body)
                .addHeader("Authorization", "Bearer " + OPENROUTER_API_KEY)
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "YOUR_WEBSITE_URL")
                .addHeader("X-Title", "YOUR_APP_NAME")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ошибка при запросе к OpenRouter API: " + response.body().string());
            }

            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);

            log.info("Полный ответ от API: {}", jsonResponse.toString(2));

            if (jsonResponse.has("error")) {
                String errorMessage = jsonResponse.getJSONObject("error").getString("message");
                throw new IOException("Ошибка API: " + errorMessage);
            }

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