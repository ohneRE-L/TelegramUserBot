// File: sendBot/src/main/java/com/example/TelegramUserBot.java
package com.example;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.net.URL;
import java.util.*;


public class TelegramUserBot extends org.telegram.telegrambots.bots.TelegramLongPollingBot {
    private final String token;
    private final String botUsername;
    private final long ownerId;
    private final Set<Long> userIds = new HashSet<>();
    private final HashMap<Long, String> userNames = new HashMap<>();
    private boolean waitingBatchFile = false;
    private Long waitingMediaForUserId = null; // Новая переменная состояния для отправки медиа
    private String waitingSendUserId = null; // Состояние для ожидания user_id при отправке сообщения
    private String waitingRemoveUserId = null; // Состояние для ожидания user_id при удалении
    private boolean waitingSendAll = false; // Состояние для ожидания текста сообщения для отправки всем
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
            // Обработка callback-запросов от inline-кнопок
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
                return;
            }
            
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
                    // Обработка кнопок владельца
                    if (text.equals("📋 Список пользователей")) {
                        handleListCommand(message);
                        return;
                    }
                    if (text.equals("✉️ Отправить сообщение")) {
                        waitingSendUserId = "waiting";
                        sendMessageWithOwnerKeyboard("Выберите пользователя из списка или отправьте его ID:", message.getChatId());
                        sendUsersListInline(message.getChatId(), "send");
                        return;
                    }
                    if (text.equals("📤 Отправить медиа")) {
                        waitingMediaForUserId = null;
                        waitingSendUserId = "waiting_media";
                        sendMessageWithOwnerKeyboard("Выберите пользователя из списка или отправьте его ID:", message.getChatId());
                        sendUsersListInline(message.getChatId(), "media");
                        return;
                    }
                    if (text.equals("🗑️ Удалить пользователя")) {
                        waitingRemoveUserId = "waiting";
                        sendMessageWithOwnerKeyboard("Выберите пользователя для удаления:", message.getChatId());
                        sendUsersListInline(message.getChatId(), "remove");
                        return;
                    }
                    if (text.equals("📢 Отправить всем")) {
                        waitingSendAll = true;
                        sendMessageWithOwnerKeyboard("Отправьте текст сообщения, которое будет отправлено всем пользователям:", message.getChatId());
                        return;
                    }
                    if (text.equals("📁 Массовая рассылка")) {
                        waitingBatchFile = true;
                        sendMessageWithOwnerKeyboard("Пожалуйста, отправьте файл с парами <user_id> <сообщение>.", message.getChatId());
                        return;
                    }
                    if (text.equals("❌ Отмена")) {
                        waitingBatchFile = false;
                        waitingMediaForUserId = null;
                        waitingSendUserId = null;
                        waitingRemoveUserId = null;
                        waitingSendAll = false;
                        sendMessageWithOwnerKeyboard("Операция отменена.", message.getChatId());
                        return;
                    }
                    
                    // Обработка состояния ожидания текста для отправки всем
                    if (waitingSendAll && !text.startsWith("/")) {
                        handleSendToAll(text, message.getChatId());
                        waitingSendAll = false;
                        return;
                    }
                    
                    // Обработка состояния ожидания user_id для отправки сообщения
                    if (waitingSendUserId != null && waitingSendUserId.equals("waiting") && !text.startsWith("/")) {
                        try {
                            long targetUserId = Long.parseLong(text.trim());
                            if (!userIds.contains(targetUserId)) {
                                sendMessageWithOwnerKeyboard("Пользователь с ID " + targetUserId + " не найден в списке.", message.getChatId());
                                waitingSendUserId = null;
                                return;
                            }
                            waitingSendUserId = String.valueOf(targetUserId);
                            sendMessageWithOwnerKeyboard("Отправьте текст сообщения для пользователя " + targetUserId + ":", message.getChatId());
                        } catch (NumberFormatException e) {
                            sendMessageWithOwnerKeyboard("Ошибка: user_id должен быть числом. Попробуйте снова или нажмите 'Отмена'.", message.getChatId());
                        }
                        return;
                    }
                    
                    // Обработка состояния ожидания текста сообщения
                    if (waitingSendUserId != null && !waitingSendUserId.equals("waiting") && !waitingSendUserId.equals("waiting_media") && !text.startsWith("/")) {
                        try {
                            long targetUserId = Long.parseLong(waitingSendUserId);
                            execute(SendMessage.builder().chatId(String.valueOf(targetUserId)).text(text).build());
                            sendMessageWithOwnerKeyboard("Сообщение отправлено пользователю " + targetUserId, message.getChatId());
                            waitingSendUserId = null;
                        } catch (Exception e) {
                            sendMessageWithOwnerKeyboard("Ошибка отправки: " + e.getMessage(), message.getChatId());
                            waitingSendUserId = null;
                        }
                        return;
                    }
                    
                    // Обработка состояния ожидания user_id для удаления
                    if (waitingRemoveUserId != null && waitingRemoveUserId.equals("waiting") && !text.startsWith("/")) {
                        try {
                            long targetUserId = Long.parseLong(text.trim());
                            if (userIds.remove(targetUserId)) {
                                userNames.remove(targetUserId);
                                saveUsersToFile();
                                sendMessageWithOwnerKeyboard("Пользователь " + targetUserId + " удалён.", message.getChatId());
                            } else {
                                sendMessageWithOwnerKeyboard("Пользователь с ID " + targetUserId + " не найден.", message.getChatId());
                            }
                            waitingRemoveUserId = null;
                        } catch (NumberFormatException e) {
                            sendMessageWithOwnerKeyboard("Ошибка: user_id должен быть числом. Попробуйте снова или нажмите 'Отмена'.", message.getChatId());
                        }
                        return;
                    }
                    
                    // 1. Обработка команды /sendbatch и загрузки файла
                    if (text.startsWith("/sendbatch")) {
                        waitingBatchFile = true;
                        sendMessageWithOwnerKeyboard("Пожалуйста, отправьте файл с парами <user_id> <сообщение>.", message.getChatId());
                        return;
                    }
                    if (waitingBatchFile && message.hasDocument()) {
                        try {
                            handleSendBatch(message);
                            waitingBatchFile = false;
                            sendMessageWithOwnerKeyboard("Массовая рассылка завершена. Результаты выше.", message.getChatId());
                        } catch (Exception e) {
                            e.printStackTrace();
                            sendMessageWithOwnerKeyboard("Ошибка при обработке /sendbatch: " + e.getMessage(), message.getChatId());
                            waitingBatchFile = false;
                        }
                        return;
                    }

                    // 2. Обработка состояния ожидания user_id для отправки медиа
                    if (waitingSendUserId != null && waitingSendUserId.equals("waiting_media") && !text.startsWith("/")) {
                        try {
                            long targetUserId = Long.parseLong(text.trim());
                            if (!userIds.contains(targetUserId)) {
                                sendMessageWithOwnerKeyboard("Пользователь с ID " + targetUserId + " не найден в списке.", message.getChatId());
                                waitingSendUserId = null;
                                return;
                            }
                            waitingMediaForUserId = targetUserId;
                            waitingSendUserId = null;
                            sendMessageWithOwnerKeyboard("Отправьте фото или документ для пользователя " + targetUserId + ".", message.getChatId());
                        } catch (NumberFormatException e) {
                            sendMessageWithOwnerKeyboard("Ошибка: user_id должен быть числом. Попробуйте снова или нажмите 'Отмена'.", message.getChatId());
                        }
                        return;
                    }
                    
                    // Инициация команды /sendmedia (старый способ через команду)
                    if (text.startsWith("/sendmedia ")) {
                        String[] parts = text.split(" ", 2);
                        if (parts.length < 2) {
                            sendMessageWithOwnerKeyboard("Ошибка: используйте /sendmedia <user_id>", message.getChatId());
                            return;
                        }
                        try {
                            long targetUserId = Long.parseLong(parts[1]);
                            if (!userIds.contains(targetUserId)) {
                                sendMessageWithOwnerKeyboard("Пользователь с ID " + targetUserId + " не найден в списке.", message.getChatId());
                                return;
                            }
                            waitingMediaForUserId = targetUserId;
                            sendMessageWithOwnerKeyboard("Отправьте фото или документ для пользователя " + targetUserId + ".", message.getChatId());
                        } catch (NumberFormatException e) {
                            sendMessageWithOwnerKeyboard("Ошибка: user_id должен быть числом.", message.getChatId());
                        }
                        return;
                    }

                    // 3. Обработка загрузки медиа ПОСЛЕ команды /sendmedia
                    if (waitingMediaForUserId != null) {
                        try {
                            if (message.hasPhoto()) {
                                handleSendPhotoToUser(message, waitingMediaForUserId);
                                sendMessageWithOwnerKeyboard("Фото отправлено пользователю " + waitingMediaForUserId + ".", message.getChatId());
                            } else if (message.hasDocument()) {
                                handleSendDocumentToUser(message, waitingMediaForUserId);
                                sendMessageWithOwnerKeyboard("Документ отправлен пользователю " + waitingMediaForUserId + ".", message.getChatId());
                            } else {
                                sendMessageWithOwnerKeyboard("Ожидалось фото или документ. Отправка медиа отменена.", message.getChatId());
                            }
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                            sendMessageWithOwnerKeyboard("Ошибка при отправке медиа: " + e.getMessage(), message.getChatId());
                        } finally {
                            waitingMediaForUserId = null; // Всегда сбрасываем состояние
                        }
                        return; // Обработано, выходим
                    }

                    // 4. Обработка других команд владельца (например, /send, /list, /remove)
                    if (text.startsWith("/")) {
                        handleOwnerCommand(message, text);
                        return; // Обработано, выходим
                    }
                    
                    // Если владелец отправил обычное сообщение (не команду и не в состоянии ожидания)
                    if (!text.isEmpty() && waitingSendUserId == null && waitingRemoveUserId == null && !waitingBatchFile && waitingMediaForUserId == null && !waitingSendAll) {
                        sendMessageWithOwnerKeyboard("Используйте кнопки меню или команды для управления ботом.", message.getChatId());
                    }
                }

                // Логика для ПОЛЬЗОВАТЕЛЕЙ (и общие команды)
                if (text.equals("/start")) {
                    if (fromId == ownerId) {
                        sendMessageWithOwnerKeyboard("Привет, владелец! Используйте кнопки меню для управления ботом.", message.getChatId());
                    } else {
                        sendMessageWithKeyboard("Привет! Я бот. Вы можете установить имя через /name <имя> или кнопки ниже.", message.getChatId(), true);
                    }
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
                    sendMessageWithOwnerKeyboard("Ошибка: используйте /send <user_id> <message>", message.getChatId());
                    return;
                }
                try {
                    long userId = Long.parseLong(parts[1]);
                    String msg = parts[2];
                    if (userIds.contains(userId)) {
                        try {
                            execute(SendMessage.builder().chatId(String.valueOf(userId)).text(msg).build());
                            sendMessageWithOwnerKeyboard("Сообщение отправлено пользователю " + userId, message.getChatId());
                        } catch (TelegramApiException e) {
                            sendMessageWithOwnerKeyboard("Ошибка отправки: " + e.getMessage(), message.getChatId());
                        }
                    } else {
                        sendMessageWithOwnerKeyboard("Пользователь с ID " + userId + " не найден.", message.getChatId());
                    }
                } catch (NumberFormatException e) {
                    sendMessageWithOwnerKeyboard("Ошибка: user_id должен быть числом.", message.getChatId());
                }
                break;
            case "/list":
                handleListCommand(message);
                break;
            case "/remove":
                if (parts.length < 2) {
                    sendMessageWithOwnerKeyboard("Ошибка: используйте /remove <user_id>", message.getChatId());
                    return;
                }
                try {
                    long userId = Long.parseLong(parts[1]);
                    if (userIds.remove(userId)) {
                        userNames.remove(userId);
                        saveUsersToFile();
                        sendMessageWithOwnerKeyboard("Пользователь " + userId + " удалён.", message.getChatId());
                    } else {
                        sendMessageWithOwnerKeyboard("Пользователь с ID " + userId + " не найден.", message.getChatId());
                    }
                } catch (NumberFormatException e) {
                    sendMessageWithOwnerKeyboard("Ошибка: user_id должен быть числом.", message.getChatId());
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
                    sendMessageWithOwnerKeyboard("Ошибка: используйте /sendall <message>", message.getChatId());
                    return;
                }
                String messageText = text.substring("/sendall ".length()).trim();
                if (messageText.isEmpty()) {
                    sendMessageWithOwnerKeyboard("Ошибка: сообщение не может быть пустым.", message.getChatId());
                    return;
                }
                handleSendToAll(messageText, message.getChatId());
                break;
            default:
                sendMessageWithOwnerKeyboard("Неизвестная команда. Используйте кнопки меню.", message.getChatId());
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
            sendMessageWithOwnerKeyboard(report.toString(), message.getChatId());
        } catch (Exception e) {
            e.printStackTrace();
            sendMessageWithOwnerKeyboard("Ошибка при обработке файла: " + e.getMessage(), message.getChatId());
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
    
    private ReplyKeyboardMarkup getOwnerKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("📋 Список пользователей"));
        row1.add(new KeyboardButton("✉️ Отправить сообщение"));
        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("📢 Отправить всем"));
        row2.add(new KeyboardButton("📤 Отправить медиа"));
        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton("🗑️ Удалить пользователя"));
        row3.add(new KeyboardButton("📁 Массовая рассылка"));
        KeyboardRow row4 = new KeyboardRow();
        row4.add(new KeyboardButton("❌ Отмена"));

        List<KeyboardRow> rows = new ArrayList<>();
        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);
        return keyboard;
    }
    
    private void sendMessageWithOwnerKeyboard(String text, long chatId) {
        SendMessage.SendMessageBuilder builder = SendMessage.builder().chatId(String.valueOf(chatId)).text(text);
        builder.replyMarkup(getOwnerKeyboard());
        try {
            execute(builder.build());
        } catch (TelegramApiException ignored) {
        }
    }
    
    private void handleListCommand(Message message) {
        if (userIds.isEmpty()) {
            sendMessageWithOwnerKeyboard("Список пользователей пуст.", message.getChatId());
        } else {
            StringBuilder sb = new StringBuilder("📋 Зарегистрированные пользователи:\n\n");
            for (Long id : userIds) {
                String name = userNames.getOrDefault(id, "<без имени>");
                sb.append("👤 ").append(name).append("\nID: ").append(id).append("\n\n");
            }
            sendMessageWithOwnerKeyboard(sb.toString(), message.getChatId());
        }
    }
    
    private void sendUsersListInline(long chatId, String action) {
        if (userIds.isEmpty()) {
            sendMessageWithOwnerKeyboard("Список пользователей пуст.", chatId);
            return;
        }
        
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        
        for (Long id : userIds) {
            String name = userNames.getOrDefault(id, "<без имени>");
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(name + " (" + id + ")");
            button.setCallbackData(action + "_" + id);
            
            List<InlineKeyboardButton> row = new ArrayList<>();
            row.add(button);
            rows.add(row);
        }
        
        markup.setKeyboard(rows);
        
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text("Выберите пользователя:")
                    .replyMarkup(markup)
                    .build();
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
    
    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        long fromId = callbackQuery.getFrom().getId();
        String data = callbackQuery.getData();
        
        if (fromId != ownerId) {
            try {
                execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                        .callbackQueryId(callbackQuery.getId())
                        .text("Эта функция доступна только владельцу бота")
                        .showAlert(true)
                        .build());
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
            return;
        }
        
        try {
            execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQuery.getId())
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
        
        long chatId = callbackQuery.getMessage().getChatId();
        
        if (data.startsWith("send_")) {
            long userId = Long.parseLong(data.substring(5));
            waitingSendUserId = String.valueOf(userId);
            sendMessageWithOwnerKeyboard("Отправьте текст сообщения для пользователя " + userId + ":", chatId);
        } else if (data.startsWith("media_")) {
            long userId = Long.parseLong(data.substring(6));
            waitingMediaForUserId = userId;
            waitingSendUserId = null;
            sendMessageWithOwnerKeyboard("Отправьте фото или документ для пользователя " + userId + ":", chatId);
        } else if (data.startsWith("remove_")) {
            long userId = Long.parseLong(data.substring(7));
            if (userIds.remove(userId)) {
                userNames.remove(userId);
                saveUsersToFile();
                sendMessageWithOwnerKeyboard("Пользователь " + userId + " удалён.", chatId);
            } else {
                sendMessageWithOwnerKeyboard("Пользователь с ID " + userId + " не найден.", chatId);
            }
            waitingRemoveUserId = null;
        }
    }
    
    private void handleSendToAll(String messageText, long ownerChatId) {
        if (userIds.isEmpty()) {
            sendMessageWithOwnerKeyboard("Список пользователей пуст. Некому отправить сообщение.", ownerChatId);
            return;
        }
        
        int success = 0;
        int fail = 0;
        StringBuilder report = new StringBuilder("📢 Результаты рассылки всем пользователям:\n\n");
        
        for (Long userId : userIds) {
            try {
                execute(SendMessage.builder()
                        .chatId(String.valueOf(userId))
                        .text(messageText)
                        .build());
                String userName = userNames.getOrDefault(userId, "<без имени>");
                report.append("✅ ").append(userName).append(" (").append(userId).append(")\n");
                success++;
            } catch (TelegramApiException e) {
                String userName = userNames.getOrDefault(userId, "<без имени>");
                report.append("❌ ").append(userName).append(" (").append(userId).append(") — ошибка: ").append(e.getMessage()).append("\n");
                fail++;
            }
        }
        
        report.append("\n📊 Итого: ").append(success).append(" успешно, ").append(fail).append(" ошибок из ").append(userIds.size()).append(" пользователей.");
        sendMessageWithOwnerKeyboard(report.toString(), ownerChatId);
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