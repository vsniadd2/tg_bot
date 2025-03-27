package org.telegram.botai.service;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class DeepseekService {
    private static final String DEEPSEEK_API_KEY = "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";
    private static final String DEEPSEEK_API_URL = "https://openrouter.ai/api/v1/chat/completions1";

    public String askDeepseek(String question) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "deepseek/deepseek-chat-v3-0324:free"); // Попробуйте другую модель
        requestBody.put("messages", new JSONArray()
                .put(new JSONObject()
                        .put("role", "user")
                        .put("content", question + "\nНапиши развернутый ответ без использования символов '*' в форматировании. Оформи ответ красиво с абзацами и четкой структурой.")
                )
        );
        //requestBody.put("max_tokens", 5000); // Увеличьте лимит токенов

        RequestBody body = RequestBody.create(requestBody.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(DEEPSEEK_API_URL)
                .post(body)
                .addHeader("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ошибка при запросе к Deepseek API: " + response.body().string());
            }

            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);

            System.out.println("Полный ответ от API: " + jsonResponse.toString(2));

            JSONArray choices = jsonResponse.getJSONArray("choices");
            if (choices.length() > 0) {
                JSONObject firstChoice = choices.getJSONObject(0);
                JSONObject message = firstChoice.getJSONObject("message");

                String content = message.getString("content").replaceAll("[*#_~]", "");


                if (content.isEmpty()) {
                    String finishReason = firstChoice.getString("finish_reason");
                    System.out.println("Ответ от API пустой. Причина: " + finishReason);
                    return "Извините, не удалось сгенерировать ответ. Попробуйте переформулировать запрос.";
                }

                return content;
            } else {
                throw new IOException("Ответ от API не содержит choices.");
            }
        }
    }
}
