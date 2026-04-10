package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.commands.DeleteMyCommands;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TelegramUserBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(TelegramUserBot.class);

    private final String token;
    private final String botUsername;
    private final long ownerId;
    
    private final Set<Long> userIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> userNames = new ConcurrentHashMap<>();
    private final Set<Long> bannedUsers = ConcurrentHashMap.newKeySet();
    
    public enum UserState {
        START,
        WAITING_FOR_NAME,
        WAITING_FOR_MSG_SEND,
        WAITING_FOR_MEDIA,
        WAITING_FOR_BATCH_FILE,
        WAITING_FOR_PHOTO_ALL,
        WAITING_FOR_RENAME
    }

    public static class UserSession {
        private UserState state = UserState.START;
        private Long targetUserId;

        public UserState getState() { return state; }
        public void setState(UserState state) { this.state = state; }
        public Long getTargetUserId() { return targetUserId; }
        public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }
    }

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final String USERS_FILE = "users.json";

    public TelegramUserBot(String token, String botUsername, long ownerId) {
        super(token);
        this.token = token;
        this.botUsername = botUsername;
        this.ownerId = ownerId;
        loadUsersFromFile();
        scheduler.scheduleAtFixedRate(this::saveIfDirty, 1, 1, TimeUnit.MINUTES);
        deleteBotCommands();
    }

    private void deleteBotCommands() {
        try {
            execute(DeleteMyCommands.builder().build());
        } catch (TelegramApiException e) {
            log.error("Failed to delete bot commands", e);
        }
    }

    @Override public String getBotUsername() { return botUsername; }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                long fromId = update.getCallbackQuery().getFrom().getId();
                if (bannedUsers.contains(fromId) && fromId != ownerId) return;
                handleCallbackQuery(update.getCallbackQuery());
                return;
            }
            if (update.hasMessage()) {
                Message message = update.getMessage();
                long fromId = message.getFrom().getId();
                
                if (bannedUsers.contains(fromId) && fromId != ownerId) {
                    return; // Ignore banned users
                }

                if (userIds.add(fromId)) {
                    log.info("New user registered: {} (ID: {})", message.getFrom().getFirstName(), fromId);
                    markDirty();
                    sendWelcomeMessage(message);
                }
                UserSession session = sessions.computeIfAbsent(fromId, k -> new UserSession());
                if (fromId == ownerId && message.hasText() && (message.getText().startsWith("/") || isOwnerCommandButton(message.getText()))) {
                    handleOwnerCommand(message);
                    return;
                }
                handleUserMessage(message, session);
            }
        } catch (Exception e) {
            log.error("Error processing update", e);
            if (update.hasMessage()) notifyOwner("Internal error: " + e.getMessage(), ownerId);
        }
    }

    private boolean isOwnerCommandButton(String text) {
        return text.equals("📋 Список пользователей") || text.equals("✉️ Отправить сообщение") ||
                text.equals("📤 Отправить медиа") || text.equals("🗑️ Удалить пользователя") ||
               text.equals("📢 Отправить всем") || text.equals("🖼️ Рассылка фото") ||
               text.equals("📁 Массовая рассылка") || text.equals("❌ Отмена") ||
               text.equals("🚫 Забанить") || text.equals("✅ Разбанить") ||
               text.equals("✏️ Переименовать");
    }

    private void sendWelcomeMessage(Message message) {
        String welcome = "👋 Добро пожаловать!\nЯ бот для рассылки уведомлений. Используйте кнопки или /help для справки.";
        sendMessageWithKeyboard(welcome, message.getChatId(), true);
        if (message.getChatId() != ownerId) {
            notifyOwner("🆕 Новый пользователь: " + message.getFrom().getFirstName() + " (" + message.getFrom().getId() + ")", ownerId);
        }
    }

    private void handleOwnerCommand(Message message) {
        String text = message.getText();
        long chatId = message.getChatId();
        UserSession session = sessions.computeIfAbsent(ownerId, k -> new UserSession());

        switch (text) {
            case "📋 Список пользователей":
            case "/list":
                handleListCommand(chatId);
                break;
            case "✉️ Отправить сообщение":
                sendUsersListInline(chatId, "send");
                break;
            case "📢 Отправить всем":
                session.setState(UserState.WAITING_FOR_MSG_SEND);
                session.setTargetUserId(-1L);
                sendMessage("Введите текст для рассылки всем пользователям:", chatId);
                break;
            case "📤 Отправить медиа":
                sendUsersListInline(chatId, "media");
                break;
            case "🖼️ Рассылка фото":
                session.setState(UserState.WAITING_FOR_PHOTO_ALL);
                sendMessage("Отправьте фото с подписью (по желанию) для рассылки всем:", chatId);
                break;
            case "🗑️ Удалить пользователя":
                sendUsersListInline(chatId, "remove");
                break;
            case "🚫 Забанить":
                sendUsersListInline(chatId, "ban");
                break;
            case "✅ Разбанить":
                sendBannedUsersListInline(chatId);
                break;
            case "✏️ Переименовать":
                sendUsersListInline(chatId, "rename");
                break;
            case "📁 Массовая рассылка":
                session.setState(UserState.WAITING_FOR_BATCH_FILE);
                sendMessage("Пожалуйста, отправьте .txt файл с форматом 'ID сообщение' на каждой строке:", chatId);
                break;
            case "❌ Отмена":
                session.setState(UserState.START);
                sendMessage("Действие отменено.", chatId);
                break;
        }
    }

    private void handleUserMessage(Message message, UserSession session) {
        String text = message.hasText() ? message.getText() : "";
        long chatId = message.getChatId();
        long fromId = message.getFrom().getId();

        switch (session.getState()) {
            case WAITING_FOR_NAME:
                if (!text.isEmpty()) {
                    userNames.put(fromId, text);
                    markDirty();
                    sendMessageWithKeyboard("Имя сохранено: " + text, chatId, true);
                    session.setState(UserState.START);
                } else {
                    sendMessage("Пожалуйста, введите текстовое имя.", chatId);
                }
                break;
            case WAITING_FOR_MSG_SEND:
                if (fromId == ownerId && !text.isEmpty()) {
                    if (session.getTargetUserId() == -1L) {
                        handleSendToAllAsync(text, chatId);
                    } else {
                        sendToUser(session.getTargetUserId(), text);
                        sendMessage("Сообщение отправлено пользователю " + session.getTargetUserId(), chatId);
                    }
                    session.setState(UserState.START);
                }
                break;
            case WAITING_FOR_MEDIA:
                if (fromId == ownerId && (message.hasPhoto() || message.hasDocument())) {
                    handleSendMedia(message, session.getTargetUserId());
                    session.setState(UserState.START);
                } else if (fromId == ownerId) {
                    sendMessage("Пожалуйста, отправьте фото или документ (файл).", chatId);
                }
                break;
            case WAITING_FOR_BATCH_FILE:
                if (fromId == ownerId && message.hasDocument()) {
                    handleSendBatchAsync(message);
                    session.setState(UserState.START);
                } else if (fromId == ownerId) {
                    sendMessage("Пожалуйста, отправьте текстовый файл (.txt).", chatId);
                }
                break;
            case WAITING_FOR_PHOTO_ALL:
                if (fromId == ownerId && message.hasPhoto()) {
                    handleSendPhotoToAllAsync(message, chatId);
                    session.setState(UserState.START);
                } else if (fromId == ownerId) {
                    sendMessage("Пожалуйста, отправьте фото.", chatId);
                }
                break;
            case WAITING_FOR_RENAME:
                if (fromId == ownerId && !text.isEmpty()) {
                    long targetId = session.getTargetUserId();
                    userNames.put(targetId, text);
                    markDirty();
                    sendMessage("Пользователь " + targetId + " переименован в: " + text, chatId);
                    session.setState(UserState.START);
                }
                break;
            case START:
            default:
                if (text.equals("/start")) {
                    sendWelcomeMessage(message);
                } else if (text.equals("Указать имя") || text.startsWith("/name")) {
                    String name = text.startsWith("/name") ? text.replace("/name", "").trim() : "";
                    if (name.isEmpty()) {
                        session.setState(UserState.WAITING_FOR_NAME);
                        sendMessage("Введите ваше имя:", chatId);
                    } else {
                        userNames.put(fromId, name);
                        markDirty();
                        sendMessageWithKeyboard("Имя сохранено: " + name, chatId, true);
                    }
                } else if (text.equals("Моё имя")) {
                    String name = userNames.getOrDefault(fromId, "<не указано>");
                    sendMessage("Ваше имя: " + name, chatId);
                } else if (text.equals("Помощь") || text.equals("/help")) {
                    sendHelp(chatId, fromId == ownerId);
                } else if (fromId != ownerId) {
                    forwardToOwner(message);
                }
                break;
        }
    }

    private void forwardToOwner(Message message) {
        long fromId = message.getFrom().getId();
        String senderName = userNames.getOrDefault(fromId, message.getFrom().getFirstName());
        String prefix = "📩 [От " + senderName + " (" + fromId + ")]: ";
        try {
            if (message.hasText()) {
                notifyOwner(prefix + message.getText(), ownerId);
            } else {
                execute(ForwardMessage.builder().chatId(String.valueOf(ownerId)).fromChatId(String.valueOf(fromId)).messageId(message.getMessageId()).build());
                notifyOwner(prefix + "отправил(а) медиа.", ownerId);
            }
        } catch (TelegramApiException e) {
            log.error("Failed to forward message to owner", e);
        }
    }

    private void sleep50ms() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleSendToAllAsync(String text, long ownerChatId) {
        executor.submit(() -> {
            int success = 0, fail = 0;
            for (Long userId : userIds) {
                if (bannedUsers.contains(userId)) continue;
                try {
                    execute(SendMessage.builder().chatId(String.valueOf(userId)).text(text).parseMode("HTML").build());
                    success++;
                    sleep50ms();
                } catch (Exception e) {
                    log.error("Failed to send to {}: {}", userId, e.getMessage());
                    fail++;
                }
            }
            sendMessage("📢 Рассылка завершена: " + success + " успешно, " + fail + " ошибок.", ownerChatId);
        });
        sendMessage("📢 Рассылка запущена в фоне...", ownerChatId);
    }

    private void handleSendPhotoToAllAsync(Message message, long ownerChatId) {
        List<PhotoSize> photos = message.getPhoto();
        String fileId = photos.get(photos.size() - 1).getFileId();
        String caption = message.getCaption();

        executor.submit(() -> {
            int success = 0, fail = 0;
            for (Long userId : userIds) {
                if (bannedUsers.contains(userId)) continue;
                try {
                    execute(SendPhoto.builder()
                            .chatId(String.valueOf(userId))
                            .photo(new InputFile(fileId))
                            .caption(caption)
                            .parseMode("HTML")
                            .build());
                    success++;
                    sleep50ms();
                } catch (Exception e) {
                    fail++;
                }
            }
            sendMessage("🖼️ Рассылка фото завершена: " + success + " успешно, " + fail + " ошибок.", ownerChatId);
        });
        sendMessage("🖼️ Рассылка фото запущена в фоне...", ownerChatId);
    }

    private void handleSendBatchAsync(Message message) {
        Document doc = message.getDocument();
        executor.submit(() -> {
            try {
                org.telegram.telegrambots.meta.api.objects.File file = execute(GetFile.builder().fileId(doc.getFileId()).build());
                URL url = new URL(file.getFileUrl(token));
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                    String line;
                    int success = 0, fail = 0;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        int spaceIdx = line.indexOf(' ');
                        if (spaceIdx > 0) {
                            try {
                                long uId = Long.parseLong(line.substring(0, spaceIdx).trim());
                                if (bannedUsers.contains(uId)) {
                                    fail++;
                                    continue;
                                }
                                String msg = line.substring(spaceIdx + 1).trim();
                                execute(SendMessage.builder().chatId(String.valueOf(uId)).text(msg).parseMode("HTML").build());
                                success++;
                                sleep50ms();
                            } catch (Exception e) { fail++; }
                        }
                    }
                    sendMessage("📁 Пакетная отправка завершена: " + success + " успешно, " + fail + " ошибок.", message.getChatId());
                }
            } catch (Exception e) {
                log.error("Batch send failed", e);
                sendMessage("❌ Ошибка пакетной отправки: " + e.getMessage(), message.getChatId());
            }
        });
        sendMessage("📁 Файл принят, рассылка запущена...", message.getChatId());
    }

    private void handleSendMedia(Message message, long targetUserId) {
        try {
            if (message.hasPhoto()) {
                PhotoSize photo = message.getPhoto().get(message.getPhoto().size() - 1);
                execute(SendPhoto.builder().chatId(String.valueOf(targetUserId)).photo(new InputFile(photo.getFileId())).caption(message.getCaption()).build());
            } else if (message.hasDocument()) {
                execute(SendDocument.builder().chatId(String.valueOf(targetUserId)).document(new InputFile(message.getDocument().getFileId())).caption(message.getCaption()).build());
            }
            sendMessage("Медиа отправлено пользователю " + targetUserId, message.getChatId());
        } catch (TelegramApiException e) {
            log.error("Failed to send media", e);
            sendMessage("Ошибка отправки медиа: " + e.getMessage(), message.getChatId());
        }
    }

    private void handleCallbackQuery(CallbackQuery query) {
        String data = query.getData();
        long chatId = query.getMessage().getChatId();
        UserSession session = sessions.computeIfAbsent(ownerId, k -> new UserSession());
        try {
            execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder().callbackQueryId(query.getId()).build());
            if (data.startsWith("send_")) {
                long uid = Long.parseLong(data.substring(5));
                session.setTargetUserId(uid);
                session.setState(UserState.WAITING_FOR_MSG_SEND);
                sendMessage("Введите сообщение для пользователя " + uid + ":", chatId);
            } else if (data.startsWith("media_")) {
                long uid = Long.parseLong(data.substring(6));
                session.setTargetUserId(uid);
                session.setState(UserState.WAITING_FOR_MEDIA);
                sendMessage("Отправьте медиа (фото или файл) для пользователя " + uid + ":", chatId);
            } else if (data.startsWith("remove_")) {
                long uid = Long.parseLong(data.substring(7));
                if (userIds.remove(uid)) {
                    userNames.remove(uid);
                    bannedUsers.remove(uid);
                    markDirty();
                    sendMessage("Пользователь " + uid + " удален.", chatId);
                }
            } else if (data.startsWith("ban_")) {
                long uid = Long.parseLong(data.substring(4));
                bannedUsers.add(uid);
                markDirty();
                sendMessage("Пользователь " + uid + " добавлен в бан-лист.", chatId);
            } else if (data.startsWith("unban_")) {
                long uid = Long.parseLong(data.substring(6));
                bannedUsers.remove(uid);
                markDirty();
                sendMessage("Пользователь " + uid + " убран из бан-листа.", chatId);
            } else if (data.startsWith("rename_")) {
                long uid = Long.parseLong(data.substring(7));
                session.setTargetUserId(uid);
                session.setState(UserState.WAITING_FOR_RENAME);
                sendMessage("Введите новое имя для пользователя " + uid + " (текущее: " + userNames.getOrDefault(uid, "не указано") + "):", chatId);
            }
        } catch (Exception e) {
            log.error("Callback error", e);
        }
    }

    private void sendUsersListInline(long chatId, String action) {
        if (userIds.isEmpty()) {
            sendMessage("Список пользователей пуст.", chatId);
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        userIds.stream().limit(50).forEach(id -> {
            String name = userNames.getOrDefault(id, String.valueOf(id));
            InlineKeyboardButton btn = InlineKeyboardButton.builder().text(name + " [" + id + "]").callbackData(action + "_" + id).build();
            rows.add(Collections.singletonList(btn));
        });
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        try {
            execute(SendMessage.builder().chatId(String.valueOf(chatId)).text("Выберите пользователя:").replyMarkup(markup).build());
        } catch (Exception e) {
            log.error("Failed to send inline keyboard", e);
        }
    }

    private void sendBannedUsersListInline(long chatId) {
        if (bannedUsers.isEmpty()) {
            sendMessage("Бан-лист пуст.", chatId);
            return;
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        bannedUsers.stream().limit(50).forEach(id -> {
            String name = userNames.getOrDefault(id, String.valueOf(id));
            InlineKeyboardButton btn = InlineKeyboardButton.builder().text(name + " [" + id + "]").callbackData("unban_" + id).build();
            rows.add(Collections.singletonList(btn));
        });
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        try {
            execute(SendMessage.builder().chatId(String.valueOf(chatId)).text("Выберите пользователя для разбана:").replyMarkup(markup).build());
        } catch (Exception e) {
            log.error("Failed to send banned inline keyboard", e);
        }
    }

    private void handleListCommand(long chatId) {
        if (userIds.isEmpty()) {
            sendMessage("Список пользователей пуст.", chatId);
            return;
        }
        StringBuilder sb = new StringBuilder("📋 Зарегистрированные пользователи:\n\n");
        userIds.forEach(id -> {
            String status = bannedUsers.contains(id) ? " 🚫 (Забанен)" : "";
            sb.append("👤 ").append(userNames.getOrDefault(id, String.valueOf(id))).append(" (").append(id).append(")").append(status).append("\n");
        });
        sendMessage(sb.toString(), chatId);
    }

    private void sendToUser(long userId, String text) {
        try {
            execute(SendMessage.builder().chatId(String.valueOf(userId)).text(text).parseMode("HTML").build());
        } catch (TelegramApiException e) {
            log.error("Failed to send to user {}", userId, e);
            if (e.getMessage().contains("blocked") || e.getMessage().contains("deactivated")) {
                userIds.remove(userId);
                userNames.remove(userId);
                markDirty();
            }
        }
    }

    private void sendMessage(String text, long chatId) {
        sendMessageWithKeyboard(text, chatId, false);
    }

    private void notifyOwner(String text, long chatId) {
        try {
            execute(SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build());
        } catch (Exception ignored) {}
    }

    private void sendMessageWithKeyboard(String text, long chatId, boolean withUserKeyboard) {
        try {
            SendMessage sm = SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(chatId == ownerId ? getOwnerKeyboard() : (withUserKeyboard ? getUserKeyboard() : null))
                    .build();
            execute(sm);
        } catch (Exception e) {
            log.error("Failed to send message to {}", chatId, e);
        }
    }

    private void sendHelp(long chatId, boolean isOwner) {
        String help = isOwner ? 
            "👑 **Команды владельца:**\n" +
            "• Список пользователей — просмотр всех\n" +
            "• Отправить сообщение — выбор через меню\n" +
            "• Отправить всем — рассылка текстового сообщения\n" +
            "• Отправить медиа — рассылка фото/файлов\n" +
            "• Массовая рассылка — загрузка .txt файла\n" +
            "• Удалить пользователя — исключение из базы" :
            "📖 Доступные кнопки:\n" +
            "• Указать имя — задать ваше имя для владельца\n" +
            "• Моё имя — посмотреть текущее имя\n" +
            "• Помощь — эта справка";
        sendMessage(help, chatId);
    }

    private ReplyKeyboardMarkup getUserKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("Помощь"));
        row1.add(new KeyboardButton("Указать имя"));
        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("Моё имя"));
        return ReplyKeyboardMarkup.builder()
                .keyboard(Arrays.asList(row1, row2))
                .resizeKeyboard(true)
                .build();
    }

    private ReplyKeyboardMarkup getOwnerKeyboard() {
        KeyboardRow r1 = new KeyboardRow(); r1.add("📋 Список пользователей"); r1.add("✉️ Отправить сообщение");
        KeyboardRow r2 = new KeyboardRow(); r2.add("📢 Отправить всем"); r2.add("🖼️ Рассылка фото");
        KeyboardRow r3 = new KeyboardRow(); r3.add("📤 Отправить медиа"); r3.add("🗑️ Удалить пользователя");
        KeyboardRow r4 = new KeyboardRow(); r4.add("🚫 Забанить"); r4.add("✅ Разбанить");
        KeyboardRow r5 = new KeyboardRow(); r5.add("📁 Массовая рассылка"); r5.add("✏️ Переименовать");
        KeyboardRow r6 = new KeyboardRow(); r6.add("❌ Отмена");
        return ReplyKeyboardMarkup.builder()
                .keyboard(Arrays.asList(r1, r2, r3, r4, r5, r6))
                .resizeKeyboard(true)
                .build();
    }

    private void markDirty() { dirty.set(true); }

    private void saveIfDirty() {
        if (dirty.getAndSet(false)) {
            saveUsersToFile();
        }
    }

    private void saveUsersToFile() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            userIds.forEach(id -> {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("id", id);
                obj.put("name", userNames.getOrDefault(id, ""));
                obj.put("banned", bannedUsers.contains(id));
                arr.put(obj);
            });
            org.json.JSONObject root = new org.json.JSONObject().put("users", arr);
            Files.writeString(Paths.get(USERS_FILE), root.toString(2));
            log.info("Users saved to {}", USERS_FILE);
        } catch (Exception e) {
            log.error("Save failed", e);
        }
    }

    private void loadUsersFromFile() {
        try {
            Path path = Paths.get(USERS_FILE);
            if (!Files.exists(path)) return;
            org.json.JSONObject root = new org.json.JSONObject(Files.readString(path));
            org.json.JSONArray arr = root.getJSONArray("users");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                long id = obj.getLong("id");
                String name = obj.optString("name", "");
                userIds.add(id);
                if (!name.isEmpty()) userNames.put(id, name);
                if (obj.optBoolean("banned", false)) bannedUsers.add(id);
            }
            log.info("Loaded {} users", userIds.size());
        } catch (Exception e) {
            log.error("Load failed", e);
        }
    }

    public static void main(String[] args) {
        Properties props = new Properties();
        try {
            Path configPath = Paths.get("config.properties");
            if (Files.exists(configPath)) {
                try (InputStream is = Files.newInputStream(configPath)) { props.load(is); }
            } else {
                try (InputStream is = TelegramUserBot.class.getClassLoader().getResourceAsStream("config.properties")) {
                    if (is != null) props.load(is);
                }
            }
        } catch (IOException e) {
            log.error("Config load failed", e);
        }

        String token = props.getProperty("bot.token");
        String ownerIdStr = props.getProperty("owner.id");
        if (token == null || ownerIdStr == null) {
            System.err.println("bot.token and owner.id are required in config.properties!");
            return;
        }

        try {
            long ownerId = Long.parseLong(ownerIdStr);
            String botUsername = props.getProperty("bot.username", "TelegramUserBot");
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            TelegramUserBot bot = new TelegramUserBot(token, botUsername, ownerId);
            botsApi.registerBot(bot);
            log.info("Bot started successfully");
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                bot.saveUsersToFile();
                bot.executor.shutdown();
                bot.scheduler.shutdown();
            }));
        } catch (Exception e) {
            log.error("Startup error", e);
        }
    }
}