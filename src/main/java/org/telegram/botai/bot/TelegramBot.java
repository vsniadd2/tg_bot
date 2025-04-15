package org.telegram.botai.bot;

import com.google.zxing.WriterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.botai.service.OpenRouterService;
import org.telegram.botai.utils.QRCodeGenerator;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;


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
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);


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
                sendMessage(chatId, "В настоящее время ИИ к сожалениию не работает(");
                //todo fix AI -> openrouter ограниченный
                aiModeActive.put(chatId, true);
                sendMessage(chatId, "\uD83D\uDD2E Режим ИИ активирован. Напишите ваш вопрос:");
            } else if (messageText.equals("О разработчике")) {
                aiModeActive.put(chatId, false);
                sendAboutDeveloper(chatId);

            } else if (Boolean.TRUE.equals(aiModeActive.get(chatId))) {
                handleAiRequestAsync(chatId, messageText);
            } else if (messageText.equals("Портфолио")) {
                sendPortfolioPhoto(chatId);
            } else if (messageText.equals("/commands")) {
                sendHelpMessage(chatId);
            } else if (messageText.equals("/generate_qr")) {
                sendMessage(chatId, "Пожалуйста, введите текст или ссылку для генерации QR-кода:");
            } else if (messageText.startsWith("/generate_qr ")) {
                String qrContent = messageText.substring("/generate_qr ".length());
                sendQrCode(chatId, qrContent);
            } else if (messageText.equals("help") || messageText.equals("/help")) {
                sendHelpMessage(chatId);
            } else {
                sendMessage(chatId, "выберите действие:");
            }
        }
    }

    //todo в челом работает,но можно допилить ассинхронность
    private CompletableFuture<Void> handleAiRequestAsync(long chatId, String messageText) {
        // Создаем CompletableFuture для typing сообщения
        CompletableFuture<Integer> typingMessageFuture = sendTypingMessage(chatId);

        // Создаем executor для периодической отправки typing статуса
        ScheduledExecutorService typingExecutor = Executors.newScheduledThreadPool(1);
        ScheduledFuture<?> typingTask = typingExecutor.scheduleAtFixedRate(() -> {
            try {
                SendChatAction chatAction = new SendChatAction();
                chatAction.setChatId(String.valueOf(chatId));
                chatAction.setAction(ActionType.TYPING);
                execute(chatAction);
            } catch (Exception e) {
                log.error("Ошибка в 'typing' процессе: ", e);
            }
        }, 0, 5, TimeUnit.SECONDS);

        // Основная цепочка асинхронных операций
        return typingMessageFuture.thenCompose(typingMessageId -> {
            return CompletableFuture.supplyAsync(() -> {
                        try {
                            return openRouterService.askOpenRouter(messageText);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }, executorService)
                    .thenAccept(aiResponse -> {
                        // Останавливаем typing статус
                        typingTask.cancel(true);
                        typingExecutor.shutdown();

                        // Удаляем сообщение "печатает..." если оно было отправлено
                        if (typingMessageId != null) {
                            deleteMessage(chatId, typingMessageId);
                        }

                        // Отправляем ответ ИИ
                        sendMessage(chatId, "\uD83E\uDD16 AI Answer: \n" + aiResponse);
                    })
                    .exceptionally(e -> {
                        // Обработка ошибок
                        typingTask.cancel(true);
                        typingExecutor.shutdown();

                        if (typingMessageId != null) {
                            deleteMessage(chatId, typingMessageId);
                        }

                        sendMessage(chatId, "❌ Error processing request. Please try again..");
                        log.error("Ошибка при обработке запроса: ", e);
                        return null;
                    });
        });
    }


    private CompletableFuture<Void> sendAboutDeveloper(long chatId) {
        return CompletableFuture.runAsync(() -> {
            String aboutText = "\n" + "Иван   \n" + "Бек-энд Java разработчик\n" + "\n" + "Являюсь бек-энд Java разработчиком с глубокими знаниями в области разработки веб-приложений. Мой профессиональный стек включает:\n" + "\n" + "- **Spring Boot**: создание мощных и масштабируемых приложений с использованием этого фреймворка, который ускоряет процесс разработки.\n" + "- **Spring Data JPA**: эффективная работа с базами данных, включая упрощение доступа к данным и управление сущностями.\n" + "- **Hibernate**: опыт в ORM для управления постоянным состоянием объектов, что позволяет значительно упростить взаимодействие с базами данных.\n" + "\n" + "Я обладаю навыками построения RESTful API, обеспечивая надежную и быструю связь между фронт-эндом и бек-эндом. \n" + "\n";
            sendMessage(chatId, aboutText);
        }, executorService);


    }

    private CompletableFuture<Integer> sendTypingMessage(long chatId) {
        return CompletableFuture.supplyAsync(() -> {
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

        }, executorService);

    }

    private void deleteMessage(long chatId, int messageId) {
        try {
            execute(new DeleteMessage(String.valueOf(chatId), messageId));
        } catch (TelegramApiException e) {
            log.error("Ошибка при удалении сообщения: {}", e.getMessage());
        }
    }

    private CompletableFuture<Void> sendWelcomeMessage(long chatId) {
        return CompletableFuture.runAsync(() -> {
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
            row1.add("help");

            keyboard.add(row);
            keyboard.add(row1);
            keyboardMarkup.setKeyboard(keyboard);
            keyboardMarkup.setResizeKeyboard(true);
            message.setReplyMarkup(keyboardMarkup);

            try {
                execute(message);
            } catch (TelegramApiException e) {
                throw new RuntimeException("Ошибка при отправке", e); // Пробрасываем исключение
            }
        }).thenRun(() -> {
            log.info("Сообщение успешно отправлено в чат {}", chatId); // Логируем успех
        }).exceptionally(ex -> {
            log.error("Не удалось отправить приветствие: {}", ex.getMessage()); // Логируем ошибку
            return null;
        });
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

    //todo - вставить ссылки для фото портфолио
    private void sendPortfolioPhoto(long chatId) {
        try {
            List<InputMedia> medias = new ArrayList<>();

            InputMediaPhoto photo = new InputMediaPhoto();
            photo.setMedia("https://ekskursii.by/images/obj4/20088/0_.jpg");
            InputMediaPhoto photo1 = new InputMediaPhoto();
            photo1.setMedia("https://cdn2.tu-tu.ru/image/pagetree_node_data/1/01e9ab120a7c5eb5923719e0ae3c8bc4/");
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

    private void sendQrCode(long chatId, String qrContent) {
        executorService.submit(() -> {
            try {
                // Генерация QR-кода и сохранение в файл
                String filePath = "qr_" + UUID.randomUUID() + ".png";
                QRCodeGenerator.generateQRCode(qrContent, filePath, 300, 300);

                // Отправка QR-кода через бота
                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(chatId);
                sendPhoto.setPhoto(new InputFile(new File(filePath)));

                execute(sendPhoto);

            } catch (WriterException | IOException e) {
                log.error("Ошибка при генерации QR-кода: {}", e.getMessage());
            } catch (TelegramApiException e) {
                log.error("Ошибка при отправке QR-кода: {}", e.getMessage());
            }
        });

    }

    private void sendHelpMessage(long chatId) {
        executorService.submit(() -> {
            StringBuilder helpText = new StringBuilder();
            helpText.append("ℹ️ *Доступные команды:*\n\n");

            helpText.append("*/start* - Начать работу с ботом & Перезагрузить бота\n");
            helpText.append("*/help* - Показать это сообщение\n");
            helpText.append("*/generate_qr [текст]* - Сгенерировать QR-код\n");
            helpText.append("*/commands* - Список команд (альтернатива /help)\n\n");


            helpText.append("*Меню бота:*\n");
            helpText.append("• Использовать ИИ(Beta version) - Активировать ИИ-режим\n");
            helpText.append("• О разработчике - Информация о разработчике\n");
            helpText.append("• Портфолио - Примеры работ\n");

            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText(helpText.toString());
            message.setParseMode("Markdown");
            try {
                execute(message);
            } catch (TelegramApiException e) {
                log.info("Ошибка в Help method");
                throw new RuntimeException(e);
            }
        });

    }
}