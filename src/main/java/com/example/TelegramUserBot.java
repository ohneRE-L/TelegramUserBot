// File: sendBot/src/main/java/com/example/TelegramUserBot.java
package com.example;

import java.io.InputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.HashMap;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.File;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

// Новые импорты для отправки медиа и пересылки сообщений
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;


public class TelegramUserBot extends org.telegram.telegrambots.bots.TelegramLongPollingBot {
    private final String token;
    private final String botUsername;
    private final long ownerId;
    private final Set<Long> userIds = new HashSet<>();
    private final HashMap<Long, String> userNames = new HashMap<>();
    private boolean waitingBatchFile = false;
    private Long waitingMediaForUserId = null; // Новая переменная состояния для отправки медиа
    private boolean waitingMediaForAll = false; // Переменная состояния для отправки медиа всем
    private final String USERS_FILE = "users.json";

    public TelegramUserBot(String token, String botUsername, long ownerId) {
        this.token = token;
        this.botUsername = botUsername;
        this.ownerId = ownerId;
        loadUsersFromFile();
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                Message message = update.getMessage();
                long fromId = message.getFrom().getId();
                String text = message.hasText() ? message.getText() : "";

                // Регистрация пользователя
                if (!userIds.contains(fromId)) {
                    userIds.add(fromId);
                    saveUsersToFile();
                }

                // Логика для ВЛАДЕЛЬЦА
                if (fromId == ownerId) {
                    // 1. Обработка команды /sendbatch и загрузки файла
                    if (text.startsWith("/sendbatch")) {
                        waitingBatchFile = true;
                        notifyOwner("Пожалуйста, отправьте файл с парами <user_id> <сообщение>.", message.getChatId());
                        return;
                    }
                    if (waitingBatchFile && message.hasDocument()) {
                        try {
                            handleSendBatch(message);
                            waitingBatchFile = false;
                        } catch (Exception e) {
                            e.printStackTrace();
                            notifyOwner("Ошибка при обработке /sendbatch: " + e.getMessage(), message.getChatId());
                            waitingBatchFile = false;
                        }
                        return;
                    }

                    // 2. Инициация команды /sendmedia
                    if (text.startsWith("/sendmedia ")) {
                        String[] parts = text.split(" ", 2); // Разбить на команду и аргумент
                        if (parts.length < 2) {
                            notifyOwner("Ошибка: используйте /sendmedia <user_id>", message.getChatId());
                            return;
                        }
                        try {
                            long targetUserId = Long.parseLong(parts[1]);
                            if (!userIds.contains(targetUserId)) {
                                notifyOwner("Пользователь с ID " + targetUserId + " не найден в списке.", message.getChatId());
                                return;
                            }
                            waitingMediaForUserId = targetUserId;
                            notifyOwner("Отправьте фото или документ для пользователя " + targetUserId + ".", message.getChatId());
                        } catch (NumberFormatException e) {
                            notifyOwner("Ошибка: user_id должен быть числом.", message.getChatId());
                        }
                        return; // Важно выйти после установки состояния
                    }

                    // 3. Обработка загрузки медиа ПОСЛЕ команды /sendmedia
                    if (waitingMediaForUserId != null) {
                        try {
                            if (message.hasPhoto()) {
                                handleSendPhotoToUser(message, waitingMediaForUserId);
                                notifyOwner("Фото отправлено пользователю " + waitingMediaForUserId + ".", message.getChatId());
                            } else if (message.hasDocument()) {
                                handleSendDocumentToUser(message, waitingMediaForUserId);
                                notifyOwner("Документ отправлен пользователю " + waitingMediaForUserId + ".", message.getChatId());
                            } else {
                                notifyOwner("Ожидалось фото или документ. Отправка медиа отменена.", message.getChatId());
                            }
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                            notifyOwner("Ошибка при отправке медиа: " + e.getMessage(), message.getChatId());
                        } finally {
                            waitingMediaForUserId = null; // Всегда сбрасываем состояние
                        }
                        return; // Обработано, выходим
                    }

                    // 4. Обработка загрузки медиа ПОСЛЕ команды /sendmediaall
                    if (waitingMediaForAll) {
                        try {
                            if (message.hasPhoto()) {
                                handleSendPhotoToAll(message);
                            } else if (message.hasDocument()) {
                                handleSendDocumentToAll(message);
                            } else {
                                notifyOwner("Ожидалось фото или документ. Рассылка медиа отменена.", message.getChatId());
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            notifyOwner("Ошибка при рассылке медиа: " + e.getMessage(), message.getChatId());
                        } finally {
                            waitingMediaForAll = false; // Всегда сбрасываем состояние
                        }
                        return; // Обработано, выходим
                    }

                    // 5. Обработка других команд владельца (например, /send, /list, /remove)
                    if (text.startsWith("/")) {
                        handleOwnerCommand(message, text);
                        return; // Обработано, выходим
                    }
                }

                // Логика для ПОЛЬЗОВАТЕЛЕЙ (и общие команды)
                if (text.equals("/start")) {
                    sendMessageWithKeyboard("Привет! Я бот. Вы можете установить имя через /name <имя> или кнопки ниже.", message.getChatId(), true);
                    return;
                }
                if (text.equals("Указать имя")) {
                    sendMessageWithKeyboard("Пожалуйста, отправьте команду /name <ваше имя> (например: /name Иван)", message.getChatId(), true);
                    return;
                }
                if (text.equals("Моё имя")) {
                    String name = userNames.getOrDefault(fromId, "<имя не указано>");
                    sendMessageWithKeyboard("Ваше имя: " + name, message.getChatId(), true);
                    return;
                }
                if (text.equals("Помощь") || text.equals("/help")) { // Объединяем обработку
                    StringBuilder help = new StringBuilder();
                    help.append("Доступные команды:\n");
                    help.append("/start — приветствие и клавиатура\n");
                    help.append("/name <имя> — указать или изменить своё имя\n");
                    help.append("/help или Помощь — список команд\n");
                    help.append("Также вы можете написать мне. Никаких команд для этого не требуется. Просто пишите сообщение\n");
                    help.append("\n");
                    // Команды только для владельца
                    if (fromId == ownerId) {
                        help.append("Для владельца:\n");
                        help.append("/list — список всех пользователей с именами и ID\n");
                        help.append("/send <user_id> <сообщение> — отправить сообщение пользователю\n");
                        help.append("/sendall <сообщение> — отправить сообщение всем пользователям\n");
                        help.append("/remove <user_id> — удалить пользователя из списка\n");
                        help.append("/sendbatch — отправить сообщение всем пользователям из файла (файл должен быть прикреплён)\n");
                        help.append("/sendmedia <user_id> — отправить фото или документ пользователю (следующим сообщением после команды)\n");
                        help.append("/sendmediaall — отправить фото или документ всем пользователям (следующим сообщением после команды)\n");
                    }
                    sendMessageWithKeyboard(help.toString(), message.getChatId(), true);
                    return;
                }
                if (text.startsWith("/name ")) {
                    String name = text.substring(6).trim();
                    if (name.isEmpty()) {
                        sendMessageWithKeyboard("Пожалуйста, укажите имя после команды /name", message.getChatId(), true);
                    } else {
                        userNames.put(fromId, name);
                        saveUsersToFile();
                        sendMessageWithKeyboard("Ваше имя сохранено как: " + name, message.getChatId(), true);
                    }
                    return;
                }

                // ПЕРЕСЫЛКА СООБЩЕНИЙ ПОЛЬЗОВАТЕЛЕЙ ВЛАДЕЛЬЦУ
                if (fromId != ownerId) { // Если сообщение не от владельца
                    String senderName = userNames.getOrDefault(fromId, "Пользователь");
                    String ownerMessagePrefix = "[Сообщение от " + senderName + " (" + fromId + ")]: ";

                    if (message.hasText()) {
                        notifyOwner(ownerMessagePrefix + message.getText(), ownerId);
                    } else if (message.hasPhoto()) {
                        ForwardMessage forwardPhoto = ForwardMessage.builder()
                                .chatId(String.valueOf(ownerId))
                                .fromChatId(String.valueOf(fromId))
                                .messageId(message.getMessageId())
                                .build();
                        execute(forwardPhoto);
                        notifyOwner(ownerMessagePrefix + "отправил(а) фото.", ownerId);
                    } else if (message.hasDocument()) {
                        ForwardMessage forwardDocument = ForwardMessage.builder()
                                .chatId(String.valueOf(ownerId))
                                .fromChatId(String.valueOf(fromId))
                                .messageId(message.getMessageId())
                                .build();
                        execute(forwardDocument);
                        notifyOwner(ownerMessagePrefix + "отправил(а) документ.", ownerId);
                    } else if (message.hasVideo()) {
                        ForwardMessage forwardVideo = ForwardMessage.builder()
                                .chatId(String.valueOf(ownerId))
                                .fromChatId(String.valueOf(fromId))
                                .messageId(message.getMessageId())
                                .build();
                        execute(forwardVideo);
                        notifyOwner(ownerMessagePrefix + "отправил(а) видео.", ownerId);
                    } else if (message.hasAudio()) {
                        ForwardMessage forwardAudio = ForwardMessage.builder()
                                .chatId(String.valueOf(ownerId))
                                .fromChatId(String.valueOf(fromId))
                                .messageId(message.getMessageId())
                                .build();
                        execute(forwardAudio);
                        notifyOwner(ownerMessagePrefix + "отправил(а) аудио.", ownerId);
                    } else if (message.hasVoice()) {
                        ForwardMessage forwardVoice = ForwardMessage.builder()
                                .chatId(String.valueOf(ownerId))
                                .fromChatId(String.valueOf(fromId))
                                .messageId(message.getMessageId())
                                .build();
                        execute(forwardVoice);
                        notifyOwner(ownerMessagePrefix + "отправил(а) голосовое сообщение.", ownerId);
                    } else if (message.hasSticker()) {
                        ForwardMessage forwardSticker = ForwardMessage.builder()
                                .chatId(String.valueOf(ownerId))
                                .fromChatId(String.valueOf(fromId))
                                .messageId(message.getMessageId())
                                .build();
                        execute(forwardSticker);
                        notifyOwner(ownerMessagePrefix + "отправил(а) стикер.", ownerId);
                    } else {
                        // Для других типов сообщений, которые не обрабатываются явно
                        notifyOwner(ownerMessagePrefix + "отправил(а) неподдерживаемый тип сообщения.", ownerId);
                    }
                    // Нет return, так как бот не должен отвечать на каждое пересланное сообщение.
                    // Ответы на команды пользователя уже были обработаны выше.
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Общая обработка ошибок, возможно, оповещение владельца о критических ошибках
            if (update.hasMessage()) {
                notifyOwner("Произошла внутренняя ошибка при обработке сообщения от " + update.getMessage().getFrom().getId() + ": " + e.getMessage(), ownerId);
            }
        }
    }

    private void handleOwnerCommand(Message message, String text) {
        String[] parts = text.split(" ", 3);
        String command = parts[0];
        switch (command) {
            case "/send":
                if (parts.length < 3) {
                    notifyOwner("Ошибка: используйте /send <user_id> <message>", message.getChatId());
                    return;
                }
                try {
                    long userId = Long.parseLong(parts[1]);
                    String msg = parts[2];
                    if (userIds.contains(userId)) {
                        try {
                            execute(SendMessage.builder().chatId(String.valueOf(userId)).text(msg).build());
                            notifyOwner("Сообщение отправлено пользователю " + userId, message.getChatId());
                        } catch (TelegramApiException e) {
                            notifyOwner("Ошибка отправки: " + e.getMessage(), message.getChatId());
                        }
                    } else {
                        notifyOwner("Пользователь с ID " + userId + " не найден.", message.getChatId());
                    }
                } catch (NumberFormatException e) {
                    notifyOwner("Ошибка: user_id должен быть числом.", message.getChatId());
                }
                break;
            case "/list":
                if (userIds.isEmpty()) {
                    notifyOwner("Список пользователей пуст.", message.getChatId());
                } else {
                    StringBuilder sb = new StringBuilder("Зарегистрированные пользователи:\n");
                    for (Long id : userIds) {
                        String name = userNames.getOrDefault(id, "<без имени>");
                        sb.append(id).append(" — ").append(name).append("\n");
                    }
                    notifyOwner(sb.toString(), message.getChatId());
                }
                break;
            case "/remove":
                if (parts.length < 2) {
                    notifyOwner("Ошибка: используйте /remove <user_id>", message.getChatId());
                    return;
                }
                try {
                    long userId = Long.parseLong(parts[1]);
                    if (userIds.remove(userId)) {
                        userNames.remove(userId);
                        saveUsersToFile();
                        notifyOwner("Пользователь " + userId + " удалён.", message.getChatId());
                    } else {
                        notifyOwner("Пользователь с ID " + userId + " не найден.", message.getChatId());
                    }
                } catch (NumberFormatException e) {
                    notifyOwner("Ошибка: user_id должен быть числом.", message.getChatId());
                }
                break;
            case "/sendbatch":
                // Эта команда обрабатывается в onUpdateReceived, здесь игнорируем
                break;
            case "/sendmedia":
                // Эта команда обрабатывается в onUpdateReceived, здесь игнорируем
                break;
            case "/sendall":
                if (parts.length < 2) {
                    notifyOwner("Ошибка: используйте /sendall <сообщение>", message.getChatId());
                    return;
                }
                String broadcastMessage = text.substring(8).trim(); // Берем все после "/sendall "
                if (userIds.isEmpty()) {
                    notifyOwner("Нет пользователей для отправки сообщения.", message.getChatId());
                    return;
                }
                try {
                    int success = 0, fail = 0;
                    StringBuilder report = new StringBuilder("Результат рассылки:\n");
                    for (Long userId : userIds) {
                        try {
                            execute(SendMessage.builder().chatId(String.valueOf(userId)).text(broadcastMessage).build());
                            success++;
                        } catch (TelegramApiException e) {
                            fail++;
                            report.append("Ошибка для пользователя ").append(userId).append(": ").append(e.getMessage()).append("\n");
                        }
                    }
                    report.append("\nИтого: ").append(success).append(" успешно, ").append(fail).append(" ошибок.");
                    notifyOwner(report.toString(), message.getChatId());
                } catch (Exception e) {
                    notifyOwner("Ошибка при рассылке: " + e.getMessage(), message.getChatId());
                }
                break;
            case "/sendmediaall":
                if (userIds.isEmpty()) {
                    notifyOwner("Нет пользователей для отправки медиа.", message.getChatId());
                    return;
                }
                waitingMediaForAll = true;
                notifyOwner("Отправьте фото или документ для рассылки всем пользователям.", message.getChatId());
                break;
            case "/help":
                // Показываем справку для владельца
                StringBuilder help = new StringBuilder();
                help.append("Доступные команды:\n");
                help.append("/start — приветствие и клавиатура\n");
                help.append("/name <имя> — указать или изменить своё имя\n");
                help.append("/help или Помощь — список команд\n");
                help.append("Также вы можете написать мне. Никаких команд для этого не требуется. Просто пишите сообщение\n");
                help.append("\n");
                help.append("Для владельца:\n");
                help.append("/list — список всех пользователей с именами и ID\n");
                help.append("/send <user_id> <сообщение> — отправить сообщение пользователю\n");
                help.append("/sendall <сообщение> — отправить сообщение всем пользователям\n");
                help.append("/remove <user_id> — удалить пользователя из списка\n");
                help.append("/sendbatch — отправить сообщение всем пользователям из файла (файл должен быть прикреплён)\n");
                help.append("/sendmedia <user_id> — отправить фото или документ пользователю (следующим сообщением после команды)\n");
                help.append("/sendmediaall — отправить фото или документ всем пользователям (следующим сообщением после команды)\n");
                notifyOwner(help.toString(), message.getChatId());
                break;
            default:
                notifyOwner("Неизвестная команда.", message.getChatId());
        }
    }

    private void handleSendBatch(Message message) {
        Document doc = message.getDocument();
        String fileId = doc.getFileId();
        try {
            org.telegram.telegrambots.meta.api.objects.File file = execute(new GetFile(fileId));
            String fileUrl = file.getFileUrl(getBotToken());
            URL url = new URL(fileUrl);
            BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
            String line;
            int success = 0, fail = 0, total = 0;
            StringBuilder report = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                total++;
                line = line.trim();
                if (line.isEmpty()) continue;
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx < 0) {
                    report.append("Строка ").append(total).append(": неверный формат\n");
                    fail++;
                    continue;
                }
                String idStr = line.substring(0, spaceIdx).trim();
                String msg = line.substring(spaceIdx + 1).trim();
                try {
                    long userId = Long.parseLong(idStr);
                    execute(SendMessage.builder().chatId(String.valueOf(userId)).text(msg).build());
                    report.append("Строка ").append(total).append(": отправлено пользователю ").append(userId).append("\n");
                    success++;
                } catch (Exception e) {
                    e.printStackTrace();
                    report.append("Строка ").append(total).append(": ошибка для user_id ").append(idStr).append(" — ").append(e.getMessage()).append("\n");
                    fail++;
                }
            }
            reader.close();
            report.append("\nИтого: ").append(success).append(" успешно, ").append(fail).append(" ошибок.");
            notifyOwner(report.toString(), message.getChatId());
        } catch (Exception e) {
            e.printStackTrace();
            notifyOwner("Ошибка при обработке файла: " + e.getMessage(), message.getChatId());
        }
    }

    // Новый метод для отправки фото пользователю
    private void handleSendPhotoToUser(Message message, long targetUserId) throws TelegramApiException {
        List<PhotoSize> photos = message.getPhoto();
        if (photos == null || photos.isEmpty()) {
            throw new TelegramApiException("В сообщении не найдено фото.");
        }
        // Получаем самое большое фото (обычно последнее в списке)
        PhotoSize largestPhoto = photos.get(photos.size() - 1);
        SendPhoto sendPhoto = SendPhoto.builder()
                .chatId(String.valueOf(targetUserId))
                .photo(new InputFile(largestPhoto.getFileId()))
                .caption(message.getCaption()) // Пересылаем оригинальную подпись
                .build();
        execute(sendPhoto);
    }

    // Новый метод для отправки документа пользователю
    private void handleSendDocumentToUser(Message message, long targetUserId) throws TelegramApiException {
        Document document = message.getDocument();
        if (document == null) {
            throw new TelegramApiException("В сообщении не найден документ.");
        }
        SendDocument sendDocument = SendDocument.builder()
                .chatId(String.valueOf(targetUserId))
                .document(new InputFile(document.getFileId()))
                .caption(message.getCaption()) // Пересылаем оригинальную подпись
                .build();
        execute(sendDocument);
    }

    // Метод для отправки фото всем пользователям
    private void handleSendPhotoToAll(Message message) throws TelegramApiException {
        List<PhotoSize> photos = message.getPhoto();
        if (photos == null || photos.isEmpty()) {
            throw new TelegramApiException("В сообщении не найдено фото.");
        }
        // Получаем самое большое фото (обычно последнее в списке)
        PhotoSize largestPhoto = photos.get(photos.size() - 1);
        
        int success = 0, fail = 0;
        StringBuilder report = new StringBuilder("Результат рассылки фото:\n");
        
        for (Long userId : userIds) {
            try {
                SendPhoto sendPhoto = SendPhoto.builder()
                        .chatId(String.valueOf(userId))
                        .photo(new InputFile(largestPhoto.getFileId()))
                        .caption(message.getCaption()) // Пересылаем оригинальную подпись
                        .build();
                execute(sendPhoto);
                success++;
            } catch (TelegramApiException e) {
                fail++;
                report.append("Ошибка для пользователя ").append(userId).append(": ").append(e.getMessage()).append("\n");
            }
        }
        
        report.append("\nИтого: ").append(success).append(" успешно, ").append(fail).append(" ошибок.");
        notifyOwner(report.toString(), message.getChatId());
    }

    // Метод для отправки документа всем пользователям
    private void handleSendDocumentToAll(Message message) throws TelegramApiException {
        Document document = message.getDocument();
        if (document == null) {
            throw new TelegramApiException("В сообщении не найден документ.");
        }
        
        int success = 0, fail = 0;
        StringBuilder report = new StringBuilder("Результат рассылки документа:\n");
        
        for (Long userId : userIds) {
            try {
                SendDocument sendDocument = SendDocument.builder()
                        .chatId(String.valueOf(userId))
                        .document(new InputFile(document.getFileId()))
                        .caption(message.getCaption()) // Пересылаем оригинальную подпись
                        .build();
                execute(sendDocument);
                success++;
            } catch (TelegramApiException e) {
                fail++;
                report.append("Ошибка для пользователя ").append(userId).append(": ").append(e.getMessage()).append("\n");
            }
        }
        
        report.append("\nИтого: ").append(success).append(" успешно, ").append(fail).append(" ошибок.");
        notifyOwner(report.toString(), message.getChatId());
    }

    private void notifyOwner(String text, long chatId) {
        try {
            execute(SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build());
        } catch (TelegramApiException ignored) {
            // Игнорируем ошибки при отправке уведомлений владельцу, чтобы не зацикливаться
        }
    }

    private void sendMessage(String text, long chatId) {
        sendMessageWithKeyboard(text, chatId, true);
    }

    private void sendMessageWithKeyboard(String text, long chatId, boolean withKeyboard) {
        SendMessage.SendMessageBuilder builder = SendMessage.builder().chatId(String.valueOf(chatId)).text(text);
        if (withKeyboard) {
            builder.replyMarkup(getUserKeyboard());
        }
        try {
            execute(builder.build());
        } catch (TelegramApiException ignored) {
            // Игнорируем ошибки при отправке сообщений, если бот не может связаться с пользователем
        }
    }

    private ReplyKeyboardMarkup getUserKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("Помощь"));
        row1.add(new KeyboardButton("Указать имя")); // Добавляем кнопку "Указать имя"
        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("Моё имя")); // Добавляем кнопку "Моё имя"

        List<KeyboardRow> rows = new ArrayList<>();
        rows.add(row1);
        rows.add(row2); // Добавляем второй ряд кнопок

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false); // Клавиатура остается видимой
        return keyboard;
    }

    private void saveUsersToFile() {
        try {
            JSONArray arr = new JSONArray();
            for (Long id : userIds) {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("name", userNames.getOrDefault(id, ""));
                arr.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("users", arr);
            try (FileWriter fw = new FileWriter(USERS_FILE)) {
                fw.write(root.toString(2));
            }
        } catch (Exception e) {
            System.err.println("Ошибка при сохранении users.json: " + e.getMessage());
        }
    }

    private void loadUsersFromFile() {
        try {
            java.io.File file = new java.io.File(USERS_FILE);
            if (!file.exists()) return;
            try (FileReader fr = new FileReader(file)) {
                StringBuilder sb = new StringBuilder();
                int c;
                while ((c = fr.read()) != -1) sb.append((char)c);
                JSONObject root = new JSONObject(sb.toString());
                JSONArray arr = root.getJSONArray("users");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    long id = obj.getLong("id");
                    String name = obj.optString("name", "");
                    userIds.add(id);
                    if (!name.isEmpty()) userNames.put(id, name);
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка при загрузке users.json: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Старт main");
        Properties props = new Properties();
        try (InputStream is = TelegramUserBot.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (is == null) {
                System.err.println("config.properties не найден в resources!");
                return;
            }
            System.out.println("config.properties найден");
            props.load(is);
        } catch (IOException e) {
            System.err.println("Не удалось загрузить config.properties: " + e.getMessage());
            return;
        }
        String token = props.getProperty("bot.token");
        String ownerIdStr = props.getProperty("owner.id");
        if (token == null || ownerIdStr == null) {
            System.err.println("bot.token и owner.id должны быть указаны в config.properties");
            return;
        }
        System.out.println("Токен и owner.id считаны");
        long ownerId;
        try {
            ownerId = Long.parseLong(ownerIdStr);
        } catch (NumberFormatException e) {
            System.err.println("owner.id должен быть числом");
            return;
        }
        System.out.println("owner.id корректен");
        String botUsername = "MyServerFaz_Send_Bot"; // Здесь можно взять из config.properties, если нужно
        try {
            System.out.println("Пробую зарегистрировать бота...");
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new TelegramUserBot(token, botUsername, ownerId));
            System.out.println("Бот запущен.");
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Ошибка при запуске бота: " + e.getMessage());
        }
    }
}