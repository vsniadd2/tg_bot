package org.telegram.botai.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.botai.service.OpenRouterService;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBot extends TelegramLongPollingBot {
    @Value("${bot.name}")
    private String botName;
    @Value("${bot.token}")
    private String botToken;

    private final OpenRouterService openRouterService;
    private final Map<Long, Boolean> aiModeActive = new ConcurrentHashMap<>();

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String userName = update.getMessage().getFrom().getUserName();

            log.info("Received message from @{} (chatId: {}): {}", userName, chatId, messageText);

            if (messageText.equals("/start")) {
                aiModeActive.put(chatId, false);
                sendWelcomeMessage(chatId);
            } else if (messageText.equals("Использовать ИИ(Beta version)")) {
                aiModeActive.put(chatId, true);
                sendMessage(chatId, "\uD83D\uDD2E Режим ИИ активирован. Напишите ваш вопрос:");
            } else if (messageText.equals("О разработчике")) {
                aiModeActive.put(chatId, false);
                sendAboutDeveloper(chatId);

            } else if (Boolean.TRUE.equals(aiModeActive.get(chatId))) {
                handleAiRequest(chatId, messageText);
            } else if (messageText.equals("Портфолио")) {
                sendPortfolioPhoto(chatId);
            } else if (messageText.equals("/commands")) {
                //todo - список всех команд
                sendMessage(chatId, "привет еблан");
            } else {
                sendMessage(chatId, "выберите действие:");
            }
        }
    }

    private void handleAiRequest(long chatId, String messageText) {
        Thread typingThread = null;
        Integer typingMessageId = null;
        try {
            typingMessageId = sendTypingMessage(chatId);

            // Создаем и запускаем поток с индикатором печатания
            typingThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        SendChatAction chatAction = new SendChatAction();
                        chatAction.setChatId(String.valueOf(chatId));
                        chatAction.setAction(ActionType.TYPING);
                        execute(chatAction);
                        Thread.sleep(5000);
                    } catch (Exception e) {
                        log.error("Ошибка в потоке typing: {}", e.getMessage());
                        break;
                    }
                }
            });
            typingThread.start();

            String aiResponse = openRouterService.askOpenRouter(messageText);
            log.info("AI response: {}", aiResponse);

            if (typingThread != null) {
                typingThread.interrupt();
            }

            if (typingMessageId != null) {
                deleteMessage(chatId, typingMessageId);
            }


            sendMessage(chatId, "\uD83E\uDD16 Ответ ИИ: \n" + aiResponse);

        } catch (IOException e) {
            log.error("Ошибка при запросе к Deepseek API: {}", e.getMessage());

            if (typingThread != null) {
                typingThread.interrupt();
            }

            if (typingMessageId != null) {
                deleteMessage(chatId, typingMessageId);
            }

            sendMessage(chatId, "❌ Ошибка при обработке запроса. Пожалуйста, попробуйте еще раз.");

        } finally {
            if (typingThread != null && typingThread.isAlive()) {
                typingThread.interrupt();
            }
        }
    }

    private void sendAboutDeveloper(long chatId) {
        String aboutText = "\n" +
                "Иван   \n" +
                "Бек-энд Java разработчик\n" +
                "\n" +
                "Являюсь бек-энд Java разработчиком с глубокими знаниями в области разработки веб-приложений. Мой профессиональный стек включает:\n" +
                "\n" +
                "- **Spring Boot**: создание мощных и масштабируемых приложений с использованием этого фреймворка, который ускоряет процесс разработки.\n" +
                "- **Spring Data JPA**: эффективная работа с базами данных, включая упрощение доступа к данным и управление сущностями.\n" +
                "- **Hibernate**: опыт в ORM для управления постоянным состоянием объектов, что позволяет значительно упростить взаимодействие с базами данных.\n" +
                "\n" +
                "Я обладаю навыками построения RESTful API, обеспечивая надежную и быструю связь между фронт-эндом и бек-эндом. \n" +
                "\n";
        sendMessage(chatId, aboutText);
    }

    private Integer sendTypingMessage(long chatId) {
        try {
            SendChatAction chatAction = new SendChatAction();
            chatAction.setChatId(String.valueOf(chatId));
            chatAction.setAction(ActionType.TYPING);
            execute(chatAction);

            SendMessage typingMessage = new SendMessage();
            typingMessage.setChatId(chatId);
            typingMessage.setText("ИИ печатает ответ...");

            Message sentMessage = execute(typingMessage);
            return sentMessage.getMessageId();

        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения 'печатает...': {}", e.getMessage());
            return null;
        }

    }

    private void deleteMessage(long chatId, int messageId) {
        try {
            execute(new DeleteMessage(String.valueOf(chatId), messageId));
        } catch (TelegramApiException e) {
            log.error("Ошибка при удалении сообщения: {}", e.getMessage());
        }
    }

    private void sendWelcomeMessage(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Привет, вот мой функционал:");

        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row = new KeyboardRow();
        row.add("Использовать ИИ(Beta version)");
        row.add("О разработчике");

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Портфолио");

        keyboard.add(row);
        keyboard.add(row1);
        keyboardMarkup.setKeyboard(keyboard);
        keyboardMarkup.setResizeKeyboard(true);
        message.setReplyMarkup(keyboardMarkup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения: {}", e.getMessage());
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения: {}", e.getMessage());
        }
    }

    //todo - вставитьссылки для фото портфолио
    private void sendPortfolioPhoto(long chatId) {
        try {
            List<InputMedia> medias = new ArrayList<>();

            InputMediaPhoto photo = new InputMediaPhoto();
            photo.setMedia("ссылку1");

            InputMediaPhoto photo1 = new InputMediaPhoto();
            photo.setMedia("ссылку2");

            medias.add(photo);
            medias.add(photo1);

            SendMediaGroup mediaGroup = new SendMediaGroup();
            mediaGroup.setChatId(chatId);
            mediaGroup.setMedias(medias);

            execute(mediaGroup);

        } catch (TelegramApiException e) {
            log.error("ошибка фото портфолио");
            throw new RuntimeException(e);
        }
    }

}