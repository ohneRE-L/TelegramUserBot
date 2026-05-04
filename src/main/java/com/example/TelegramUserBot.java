package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.commands.DeleteMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TelegramUserBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(TelegramUserBot.class);

    private final String botUsername;
    private final long ownerId;

    public static final String BTN_USERS_LIST = "📋 Список пользователей";
    public static final String BTN_SEND_MSG = "✉️ Отправить сообщение";
    public static final String BTN_SEND_ALL = "📢 Отправить всем";
    public static final String BTN_SEND_MEDIA = "📤 Отправить медиа";
    public static final String BTN_SEND_PHOTO_ALL = "🖼️ Рассылка фото";
    public static final String BTN_REMOVE_USER = "🗑️ Удалить пользователя";
    public static final String BTN_BAN_USER = "🚫 Забанить";
    public static final String BTN_UNBAN_USER = "✅ Разбанить";
    public static final String BTN_RENAME_USER = "✏️ Переименовать";
    public static final String BTN_CANCEL = "❌ Отмена";
    public static final String BTN_STATISTICS = "📊 Статистика";

    public static final String BTN_HELP = "Помощь";
    public static final String BTN_SET_NAME = "Указать имя";
    public static final String BTN_MY_NAME = "Моё имя";

    public static class UserRecord {
        public final long id;
        public volatile String name;
        public volatile boolean banned;

        public UserRecord(long id, String name, boolean banned) {
            this.id = id;
            this.name = name;
            this.banned = banned;
        }
    }

    private final Map<Long, UserRecord> users = new ConcurrentHashMap<>();

    public enum UserState {
        START,
        WAITING_FOR_NAME,
        WAITING_FOR_MSG_SEND,
        WAITING_FOR_MEDIA,
        WAITING_FOR_PHOTO_ALL,
        WAITING_FOR_RENAME
    }

    public static class UserSession {
        private UserState state = UserState.START;
        private Long targetUserId;

        public UserState getState() {
            return state;
        }

        public void setState(UserState state) {
            this.state = state;
        }

        public Long getTargetUserId() {
            return targetUserId;
        }

        public void setTargetUserId(Long targetUserId) {
            this.targetUserId = targetUserId;
        }
    }

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final String USERS_FILE = "users.json";

    public TelegramUserBot(String token, String botUsername, long ownerId) {
        super(token);
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

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                long fromId = update.getCallbackQuery().getFrom().getId();
                UserRecord u = users.get(fromId);
                if (u != null && u.banned && fromId != ownerId)
                    return;
                handleCallbackQuery(update.getCallbackQuery());
                return;
            }
            if (update.hasMessage()) {
                Message message = update.getMessage();
                long fromId = message.getFrom().getId();

                UserRecord u = users.get(fromId);
                if (u != null && u.banned && fromId != ownerId) {
                    return; // Ignore banned users
                }

                if (u == null) {
                    users.put(fromId, new UserRecord(fromId, "", false));
                    log.info("New user registered: {} (ID: {})", message.getFrom().getFirstName(), fromId);
                    markDirty();
                    if (fromId != ownerId) {
                        notifyOwner("🆕 Новый пользователь: " + message.getFrom().getFirstName() + " (" + fromId + ")", ownerId);
                    }
                    if (!message.hasText() || !message.getText().equals("/start")) {
                        sendWelcomeMessage(message);
                    }
                }
                UserSession session = sessions.computeIfAbsent(fromId, k -> new UserSession());
                if (fromId == ownerId && message.hasText()
                        && (message.getText().startsWith("/") || isOwnerCommandButton(message.getText()))) {
                    handleOwnerCommand(message);
                    return;
                }
                handleUserMessage(message, session);
            }
        } catch (Exception e) {
            log.error("Error processing update", e);
            if (update.hasMessage())
                notifyOwner("Internal error: " + e.getMessage(), ownerId);
        }
    }

    private boolean isOwnerCommandButton(String text) {
        return text.equals(BTN_USERS_LIST) || text.equals(BTN_SEND_MSG) || text.equals(BTN_STATISTICS) ||
                text.equals(BTN_SEND_MEDIA) || text.equals(BTN_REMOVE_USER) ||
                text.equals(BTN_SEND_ALL) || text.equals(BTN_SEND_PHOTO_ALL) ||
                text.equals(BTN_CANCEL) || text.equals(BTN_BAN_USER) ||
                text.equals(BTN_UNBAN_USER) || text.equals(BTN_RENAME_USER);
    }

    private void sendWelcomeMessage(Message message) {
        String welcome = "👋 Добро пожаловать!\nЯ бот для рассылки уведомлений. Используйте кнопки или /help для справки.";
        sendMessageWithKeyboard(welcome, message.getChatId(), true);
    }

    private void handleOwnerCommand(Message message) {
        String text = message.getText();
        long chatId = message.getChatId();
        UserSession session = sessions.computeIfAbsent(ownerId, k -> new UserSession());

        switch (text) {
            case BTN_STATISTICS:
                handleStatisticsCommand(chatId);
                break;
            case BTN_USERS_LIST:
            case "/list":
                handleListCommand(chatId);
                break;
            case BTN_SEND_MSG:
                sendUsersListInline(chatId, null, "send", 0);
                break;
            case BTN_SEND_ALL:
                session.setState(UserState.WAITING_FOR_MSG_SEND);
                session.setTargetUserId(-1L);
                sendMessage("Введите текст для рассылки всем пользователям:", chatId);
                break;
            case BTN_SEND_MEDIA:
                sendUsersListInline(chatId, null, "media", 0);
                break;
            case BTN_SEND_PHOTO_ALL:
                session.setState(UserState.WAITING_FOR_PHOTO_ALL);
                sendMessage("Отправьте фото с подписью (по желанию) для рассылки всем:", chatId);
                break;
            case BTN_REMOVE_USER:
                sendUsersListInline(chatId, null, "remove", 0);
                break;
            case BTN_BAN_USER:
                sendUsersListInline(chatId, null, "ban", 0);
                break;
            case BTN_UNBAN_USER:
                sendBannedUsersListInline(chatId, null, 0);
                break;
            case BTN_RENAME_USER:
                sendUsersListInline(chatId, null, "rename", 0);
                break;
            case BTN_CANCEL:
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
                    users.get(fromId).name = text;
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
                    if (users.containsKey(targetId)) {
                        users.get(targetId).name = text;
                        markDirty();
                    }
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
                        users.get(fromId).name = name;
                        markDirty();
                        sendMessageWithKeyboard("Имя сохранено: " + name, chatId, true);
                    }
                } else if (text.equals(BTN_MY_NAME)) {
                    UserRecord u = users.get(fromId);
                    String name = (u != null && !u.name.isEmpty()) ? u.name : "<не указано>";
                    sendMessage("Ваше имя: " + name, chatId);
                } else if (text.equals(BTN_HELP) || text.equals("/help")) {
                    sendHelp(chatId, fromId == ownerId);
                } else if (fromId != ownerId) {
                    forwardToOwner(message);
                } else {
                    UserRecord bestMatch = null;
                    for (UserRecord u : users.values()) {
                        if (u.name != null && !u.name.trim().isEmpty() 
                                && text.toLowerCase().startsWith(u.name.toLowerCase() + " ")) {
                            if (bestMatch == null || u.name.length() > bestMatch.name.length()) {
                                bestMatch = u;
                            }
                        }
                    }
                    if (bestMatch != null) {
                        String msg = text.substring(bestMatch.name.length()).trim();
                        if (!msg.isEmpty()) {
                            sendToUser(bestMatch.id, msg);
                            sendMessage("Сообщение отправлено пользователю " + bestMatch.name, chatId);
                        } else {
                            sendMessage("Вы не ввели текст сообщения для " + bestMatch.name, chatId);
                        }
                    } else {
                        sendMessage("Команда не распознана. Используйте меню или введите '<Имя> <Сообщение>'.", chatId);
                    }
                }
                break;
        }
    }

    private void forwardToOwner(Message message) {
        long fromId = message.getFrom().getId();
        UserRecord u = users.get(fromId);
        String senderName = (u != null && !u.name.isEmpty()) ? u.name : message.getFrom().getFirstName();
        String prefix = "📩 [От " + senderName + " (" + fromId + ")]: ";
        try {
            if (message.hasText()) {
                notifyOwner(prefix + message.getText(), ownerId);
            } else {
                execute(ForwardMessage.builder().chatId(String.valueOf(ownerId)).fromChatId(String.valueOf(fromId))
                        .messageId(message.getMessageId()).build());
                notifyOwner(prefix + "отправил(а) медиа.", ownerId);
            }
        } catch (TelegramApiException e) {
            log.error("Failed to forward message to owner", e);
        }
    }

    private void handleSendToAllAsync(String text, long ownerChatId) {
        List<UserRecord> targetUsers = new ArrayList<>();
        users.values().stream().filter(u -> !u.banned).forEach(targetUsers::add);
        if (targetUsers.isEmpty()) {
            sendMessage("Нет пользователей для рассылки.", ownerChatId);
            return;
        }
        sendMessage("📢 Рассылка запущена в фоне...", ownerChatId);
        scheduleBatch(targetUsers, 0, text, null, null, ownerChatId, 0, 0);
    }

    private void handleSendPhotoToAllAsync(Message message, long ownerChatId) {
        List<PhotoSize> photos = message.getPhoto();
        String fileId = photos.get(photos.size() - 1).getFileId();
        String caption = message.getCaption();

        List<UserRecord> targetUsers = new ArrayList<>();
        users.values().stream().filter(u -> !u.banned).forEach(targetUsers::add);
        if (targetUsers.isEmpty()) {
            sendMessage("Нет пользователей для рассылки.", ownerChatId);
            return;
        }
        sendMessage("🖼️ Рассылка фото запущена в фоне...", ownerChatId);
        scheduleBatch(targetUsers, 0, null, fileId, caption, ownerChatId, 0, 0);
    }

    private void scheduleBatch(List<UserRecord> list, int index, String text, String fileId, String caption,
            long ownerChatId, int success, int fail) {
        if (index >= list.size()) {
            String type = fileId != null ? "🖼️ Рассылка фото" : "📢 Рассылка";
            sendMessage(type + " завершена: " + success + " успешно, " + fail + " ошибок.", ownerChatId);
            return;
        }
        long userId = list.get(index).id;
        try {
            if (fileId != null) {
                execute(SendPhoto.builder().chatId(String.valueOf(userId)).photo(new InputFile(fileId)).caption(caption)
                        .parseMode("HTML").build());
            } else {
                execute(SendMessage.builder().chatId(String.valueOf(userId)).text(text).parseMode("HTML").build());
            }
            success++;
        } catch (Exception e) {
            log.error("Broadcast failed for user {}", userId, e);
            fail++;
        }

        int finalSuccess = success;
        int finalFail = fail;
        scheduler.schedule(
                () -> scheduleBatch(list, index + 1, text, fileId, caption, ownerChatId, finalSuccess, finalFail), 50,
                TimeUnit.MILLISECONDS);
    }

    private void handleSendMedia(Message message, long targetUserId) {
        try {
            if (message.hasPhoto()) {
                PhotoSize photo = message.getPhoto().get(message.getPhoto().size() - 1);
                execute(SendPhoto.builder().chatId(String.valueOf(targetUserId)).photo(new InputFile(photo.getFileId()))
                        .caption(message.getCaption()).build());
            } else if (message.hasDocument()) {
                execute(SendDocument.builder().chatId(String.valueOf(targetUserId))
                        .document(new InputFile(message.getDocument().getFileId())).caption(message.getCaption())
                        .build());
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
            execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(query.getId()).build());
            if (data.startsWith("page_")) {
                String[] parts = data.split("_");
                String action = parts[1];
                int page = Integer.parseInt(parts[2]);
                if (action.equals("unban")) {
                    sendBannedUsersListInline(chatId, query.getMessage().getMessageId(), page);
                } else {
                    sendUsersListInline(chatId, query.getMessage().getMessageId(), action, page);
                }
            } else {
                if (!data.equals("ignore")) {
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
                        if (users.remove(uid) != null) {
                            markDirty();
                            sendMessage("Пользователь " + uid + " удален.", chatId);
                        }
                    } else if (data.startsWith("ban_")) {
                        long uid = Long.parseLong(data.substring(4));
                        if (users.containsKey(uid))
                            users.get(uid).banned = true;
                        markDirty();
                        sendMessage("Пользователь " + uid + " добавлен в бан-лист.", chatId);
                        sendToUser(uid, "Ты забанен по причине того, что ты долбоеб");
                    } else if (data.startsWith("unban_")) {
                        long uid = Long.parseLong(data.substring(6));
                        if (users.containsKey(uid))
                            users.get(uid).banned = false;
                        markDirty();
                        sendMessage("Пользователь " + uid + " убран из бан-листа.", chatId);
                        sendToUser(uid, "Великий администатор решил тебя помиловать");
                    } else if (data.startsWith("rename_")) {
                        long uid = Long.parseLong(data.substring(7));
                        session.setTargetUserId(uid);
                        session.setState(UserState.WAITING_FOR_RENAME);
                        UserRecord u = users.get(uid);
                        sendMessage("Введите новое имя для пользователя " + uid + " (текущее: "
                                + ((u != null && !u.name.isEmpty()) ? u.name : "не указано") + "):", chatId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Callback error", e);
        }
    }

    private void sendUsersListInline(long chatId, Integer messageId, String action, int page) {
        List<UserRecord> list = new ArrayList<>();
        if ("remove".equals(action)) {
            list.addAll(users.values());
        } else {
            users.values().stream().filter(u -> !u.banned).forEach(list::add);
        }

        if (list.isEmpty()) {
            sendMessage("Список пользователей пуст.", chatId);
            return;
        }

        ArrayList<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int limit = 50;
        int totalPages = (int) Math.ceil((double) list.size() / limit);
        int p = Math.max(0, Math.min(page, totalPages - 1));

        list.stream().skip((long) p * limit).limit(limit).forEach(u -> {
            String name = u.name.isEmpty() ? String.valueOf(u.id) : u.name;
            InlineKeyboardButton btn = InlineKeyboardButton.builder().text(name + " [" + u.id + "]")
                    .callbackData(action + "_" + u.id).build();
            rows.add(Collections.singletonList(btn));
        });

        if (totalPages > 1) {
            List<InlineKeyboardButton> navRow = new ArrayList<>();
            if (p > 0) {
                navRow.add(InlineKeyboardButton.builder().text("⬅️ Назад")
                        .callbackData("page_" + action + "_" + (p - 1)).build());
            }
            navRow.add(InlineKeyboardButton.builder().text((p + 1) + "/" + totalPages).callbackData("ignore").build());
            if (p < totalPages - 1) {
                navRow.add(InlineKeyboardButton.builder().text("Вперед ➡️")
                        .callbackData("page_" + action + "_" + (p + 1)).build());
            }
            rows.add(navRow);
        }

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        try {
            if (messageId != null) {
                execute(EditMessageReplyMarkup.builder().chatId(String.valueOf(chatId)).messageId(messageId)
                        .replyMarkup(markup).build());
            } else {
                execute(SendMessage.builder().chatId(String.valueOf(chatId)).text("Выберите пользователя:")
                        .replyMarkup(markup).build());
            }
        } catch (Exception e) {
            log.error("Failed to send inline keyboard", e);
        }
    }

    private void sendBannedUsersListInline(long chatId, Integer messageId, int page) {
        List<UserRecord> bannedList = new ArrayList<>();
        users.values().stream().filter(u -> u.banned).forEach(bannedList::add);

        if (bannedList.isEmpty()) {
            sendMessage("Бан-лист пуст.", chatId);
            return;
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int limit = 50;
        int totalPages = (int) Math.ceil((double) bannedList.size() / limit);
        int p = Math.max(0, Math.min(page, totalPages - 1));

        bannedList.stream().skip((long) p * limit).limit(limit).forEach(u -> {
            String name = u.name.isEmpty() ? String.valueOf(u.id) : u.name;
            InlineKeyboardButton btn = InlineKeyboardButton.builder().text(name + " [" + u.id + "]")
                    .callbackData("unban_" + u.id).build();
            rows.add(Collections.singletonList(btn));
        });

        if (totalPages > 1) {
            List<InlineKeyboardButton> navRow = new ArrayList<>();
            if (p > 0) {
                navRow.add(
                        InlineKeyboardButton.builder().text("⬅️ Назад").callbackData("page_unban_" + (p - 1)).build());
            }
            navRow.add(InlineKeyboardButton.builder().text((p + 1) + "/" + totalPages).callbackData("ignore").build());
            if (p < totalPages - 1) {
                navRow.add(
                        InlineKeyboardButton.builder().text("Вперед ➡️").callbackData("page_unban_" + (p + 1)).build());
            }
            rows.add(navRow);
        }

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        try {
            if (messageId != null) {
                execute(EditMessageReplyMarkup.builder().chatId(String.valueOf(chatId)).messageId(messageId)
                        .replyMarkup(markup).build());
            } else {
                execute(SendMessage.builder().chatId(String.valueOf(chatId)).text("Выберите пользователя для разбана:")
                        .replyMarkup(markup).build());
            }
        } catch (Exception e) {
            log.error("Failed to send banned inline keyboard", e);
        }
    }

    private void handleStatisticsCommand(long chatId) {
        long total = users.size();
        long banned = users.values().stream().filter(u -> u.banned).count();
        long active = total - banned;

        String stats = "📊 Статистика бота:\n\n" +
                "Всего пользователей: " + total + "\n" +
                "Активных: " + active + "\n" +
                "В бане: " + banned;
        sendMessage(stats, chatId);
    }

    private void handleListCommand(long chatId) {
        if (users.isEmpty()) {
            sendMessage("Список пользователей пуст.", chatId);
            return;
        }
        StringBuilder sb = new StringBuilder("📋 Зарегистрированные пользователи:\n\n");
        users.values().forEach(u -> {
            String status = u.banned ? " 🚫 (Забанен)" : "";
            sb.append("👤 ").append(u.name.isEmpty() ? String.valueOf(u.id) : u.name).append(" (").append(u.id)
                    .append(")").append(status).append("\n");
        });
        sendMessage(sb.toString(), chatId);
    }

    private void sendToUser(long userId, String text) {
        try {
            execute(SendMessage.builder().chatId(String.valueOf(userId)).text(text).parseMode("HTML").build());
        } catch (TelegramApiException e) {
            log.error("Failed to send to user {}", userId, e);
            if (e.getMessage().contains("blocked") || e.getMessage().contains("deactivated")) {
                users.remove(userId);
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
        } catch (Exception ignored) {
        }
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
        String help = isOwner ? "👑 **Команды владельца:**\n" +
                "• Список пользователей — просмотр всех\n" +
                "• Отправить сообщение — выбор через меню\n" +
                "• Отправить всем — рассылка текстового сообщения\n" +
                "• Отправить медиа — рассылка фото/файлов\n" +
                "• Удалить пользователя — исключение из базы"
                : "📖 Доступные кнопки:\n" +
                        "• Указать имя — задать ваше имя для владельца\n" +
                        "• Моё имя — посмотреть текущее имя\n" +
                        "• Помощь — эта справка";
        sendMessage(help, chatId);
    }

    private KeyboardRow createRow(String... buttons) {
        KeyboardRow row = new KeyboardRow();
        Arrays.stream(buttons).forEach(row::add);
        return row;
    }

    private ReplyKeyboardMarkup getUserKeyboard() {
        return ReplyKeyboardMarkup.builder()
                .keyboard(Arrays.asList(
                        createRow(BTN_HELP, BTN_SET_NAME),
                        createRow(BTN_MY_NAME)))
                .resizeKeyboard(true)
                .build();
    }

    private ReplyKeyboardMarkup getOwnerKeyboard() {
        return ReplyKeyboardMarkup.builder()
                .keyboard(Arrays.asList(
                        createRow(BTN_USERS_LIST, BTN_STATISTICS),
                        createRow(BTN_SEND_MSG, BTN_SEND_ALL),
                        createRow(BTN_SEND_MEDIA, BTN_SEND_PHOTO_ALL),
                        createRow(BTN_BAN_USER, BTN_UNBAN_USER),
                        createRow(BTN_REMOVE_USER, BTN_RENAME_USER),
                        createRow(BTN_CANCEL)))
                .resizeKeyboard(true)
                .build();
    }

    private void markDirty() {
        dirty.set(true);
    }

    private void saveIfDirty() {
        if (dirty.getAndSet(false)) {
            saveUsersToFile();
        }
    }

    private void saveUsersToFile() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            users.values().forEach(u -> {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("id", u.id);
                obj.put("name", u.name);
                obj.put("banned", u.banned);
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
            if (!Files.exists(path))
                return;
            org.json.JSONObject root = new org.json.JSONObject(Files.readString(path));
            org.json.JSONArray arr = root.getJSONArray("users");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                long id = obj.getLong("id");
                String name = obj.optString("name", "");
                users.put(id, new UserRecord(id, name, obj.optBoolean("banned", false)));
            }
            log.info("Loaded {} users", users.size());
        } catch (Exception e) {
            log.error("Load failed", e);
        }
    }

    public static void main(String[] args) {
        Properties props = new Properties();
        try {
            Path configPath = Paths.get("config.properties");
            if (Files.exists(configPath)) {
                try (InputStream is = Files.newInputStream(configPath)) {
                    props.load(is);
                }
            } else {
                try (InputStream is = TelegramUserBot.class.getClassLoader().getResourceAsStream("config.properties")) {
                    if (is != null)
                        props.load(is);
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
                bot.scheduler.shutdown();
            }));
        } catch (Exception e) {
            log.error("Startup error", e);
        }
    }
}