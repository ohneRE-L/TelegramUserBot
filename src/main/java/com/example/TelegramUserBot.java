package com.example;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.commands.DeleteMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.*;
import java.io.File;
import java.io.InputStream;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Properties;
import java.util.stream.Collectors;

public class TelegramUserBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(TelegramUserBot.class);

    private final String botUsername;
    private final long ownerId;

    public static final String BTN_USERS_LIST = "📋 Список пользователей";
    public static final String BTN_SEND_MSG = "✉️ Отправить сообщение";
    public static final String BTN_SEND_ALL = "📢 Отправить всем";
    public static final String BTN_SEND_MEDIA = "📤 Отправить медиа";
    public static final String BTN_SEND_MEDIA_ALL = "🖼️ Рассылка медиа всем";
    public static final String BTN_REMOVE_USER = "🗑️ Удалить пользователя";
    public static final String BTN_BAN_USER = "🚫 Забанить";
    public static final String BTN_UNBAN_USER = "✅ Разбанить";
    public static final String BTN_RENAME_USER = "✏️ Переименовать";
    public static final String BTN_CANCEL = "❌ Отмена";
    public static final String BTN_STATISTICS = "📊 Статистика";
    public static final String BTN_USER_INFO = "ℹ️ Информация о пользователе";
    public static final String BTN_BACKUP = "💾 Бэкап панели 3x-ui";
    // Main menu category buttons
    public static final String BTN_MENU_USERS = "👥 Пользователи";
    public static final String BTN_MENU_BROADCAST = "📢 Рассылка";
    public static final String BTN_MENU_PANEL = "⚙️ Панель 3x-ui";

    public static final String BTN_HELP = "Помощь";
    public static final String BTN_SET_NAME = "Указать имя";
    public static final String BTN_MY_NAME = "Моё имя";
    public static final String BTN_MY_KEYS = "🔑 Мои ключи";

    public static class UserRecord {
        public final long id;
        public volatile String name;
        public volatile boolean banned;
        public volatile String xuiUsername;
        public volatile long expiryDate;
        public volatile int lastNotifiedDay;
        public volatile String tgUsername;

        public UserRecord(long id, String name, boolean banned, String xuiUsername, long expiryDate, int lastNotifiedDay, String tgUsername) {
            this.id = id;
            this.name = name;
            this.banned = banned;
            this.xuiUsername = xuiUsername;
            this.expiryDate = expiryDate;
            this.lastNotifiedDay = lastNotifiedDay;
            this.tgUsername = tgUsername;
        }
    }

    public Map<Long, UserRecord> getUsers() {
        return users;
    }

    private final Map<Long, UserRecord> users = new ConcurrentHashMap<>();

    public enum UserState {
        START,
        WAITING_FOR_NAME,
        WAITING_FOR_MSG_SEND,
        WAITING_FOR_MEDIA,
        WAITING_FOR_BROADCAST_MEDIA,
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

    private XuiApiClient xuiApiClient;
    @SuppressWarnings("FieldCanBeLocal")
    private XuiExpiryChecker expiryChecker;
    private final Properties configProps = new Properties();
    public TelegramUserBot(String token, String botUsername, long ownerId) {
        super(token);
        this.botUsername = botUsername;
        this.ownerId = ownerId;
        loadUsersFromFile();
        scheduler.scheduleAtFixedRate(this::saveIfDirty, 1, 1, TimeUnit.MINUTES);
        deleteBotCommands();
        loadXuiConfiguration();
    }

    private void loadXuiConfiguration() {
        try {
            Path configPath = Paths.get("config.properties");
            if (Files.exists(configPath)) {
                try (InputStream is = Files.newInputStream(configPath)) {
                    configProps.load(is);
                }
            } else {
                try (InputStream is = getClass().getClassLoader().getResourceAsStream("config.properties")) {
                    if (is != null) {
                        configProps.load(is);
                    }
                }
            }
            
            String panelUrl = configProps.getProperty("xui.panel.url");
            String panelUsername = configProps.getProperty("xui.panel.username");
            String panelPassword = configProps.getProperty("xui.panel.password");
            String checkInterval = configProps.getProperty("xui.check.interval.hours", "6");
            String notifyDays = configProps.getProperty("xui.notify.days", "3,1,0");
            String apiToken = configProps.getProperty("xui.api.token");
            int subPort = Integer.parseInt(configProps.getProperty("xui.sub.port", "10882"));
            String subPath = configProps.getProperty("xui.sub.path", "/sub/");
            if (panelUrl != null && (apiToken != null || (panelUsername != null && panelPassword != null))) {
                xuiApiClient = new XuiApiClient(panelUrl, panelUsername, panelPassword, apiToken, subPort, subPath);
                expiryChecker = new XuiExpiryChecker(this, xuiApiClient, 
                    Integer.parseInt(checkInterval), notifyDays);
                expiryChecker.start();
                log.info("3x-ui integration initialized (discovery mode: all inbounds)");
            } else {
                log.warn("3x-ui configuration missing, expiry checking disabled");
            }
        } catch (Exception e) {
            log.error("Failed to load 3x-ui configuration", e);
        }
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public Long findTelegramIdByUsername(String xuiUsername) {
        for (UserRecord user : users.values()) {
            if (xuiUsername.equalsIgnoreCase(user.xuiUsername)) {
                return user.id;
            }
        }
        for (UserRecord user : users.values()) {
            if (xuiUsername.equalsIgnoreCase(user.name)) {
                return user.id;
            }
        }
        return null;
    }

    public boolean isUserBanned(long tgId) {
        UserRecord user = users.get(tgId);
        return user != null && user.banned;
    }


    private void handleSyncCommand(long chatId, Integer messageId) {
        if (xuiApiClient == null) {
            sendOrEditMessage("❌ 3x-ui интеграция не настроена", chatId, messageId, null);
            return;
        }
        sendOrEditMessage("🔄 Синхронизация с 3x-ui...", chatId, messageId, null);
        new Thread(() -> {
            try {
                Map<String, Long> panelClients = xuiApiClient.getAllClientsExpiry();
                int synced = 0;
                int usernamesFetched = 0;
                List<String> notFound = new ArrayList<>();
                for (UserRecord user : users.values()) {
                    boolean matched = false;
                    if (user.xuiUsername != null && !user.xuiUsername.isEmpty()) {
                        if (panelClients.containsKey(user.xuiUsername)) {
                            user.expiryDate = panelClients.get(user.xuiUsername);
                            user.lastNotifiedDay = -1;
                            synced++;
                            matched = true;
                        }
                    }
                    if (!matched && user.name != null && !user.name.isEmpty()) {
                        if (panelClients.containsKey(user.name)) {
                            user.expiryDate = panelClients.get(user.name);
                            user.xuiUsername = user.name;
                            user.lastNotifiedDay = -1;
                            synced++;
                            matched = true;
                        }
                    }
                    if (!matched && !user.banned) {
                        notFound.add(user.name.isEmpty() ? String.valueOf(user.id) : user.name);
                    }
                    if (user.tgUsername == null || user.tgUsername.isEmpty()) {
                        try {
                            Chat chat = execute(GetChat.builder().chatId(String.valueOf(user.id)).build());
                            if (chat.getUserName() != null && !chat.getUserName().isEmpty()) {
                                user.tgUsername = chat.getUserName();
                                usernamesFetched++;
                            }
                            Thread.sleep(200);
                        } catch (Exception ignored) {}
                    }
                }
                markDirty();
                StringBuilder sb = new StringBuilder("✅ Синхронизация завершена!\n");
                sb.append("Синхронизировано ключей: ").append(synced).append("\n");
                sb.append("Восстановлено никнеймов: ").append(usernamesFetched);
                if (!notFound.isEmpty()) {
                    sb.append("\n\n⚠️ Не найдены в панели 3x-ui:");
                    for (String name : notFound) sb.append("\n- ").append(name);
                    sb.append("\n\n(Проверьте Email в панели у этих пользователей)");
                }
                final String report = sb.toString();
                InlineKeyboardMarkup markup = null;
                if (messageId != null) {
                    markup = InlineKeyboardMarkup.builder().keyboard(Collections.singletonList(
                            Collections.singletonList(InlineKeyboardButton.builder().text("🔙 В меню").callbackData("back_panel").build())
                    )).build();
                }
                final InlineKeyboardMarkup finalMarkup = markup;
                scheduler.execute(() -> sendOrEditMessage(report, chatId, messageId, finalMarkup));
            } catch (Exception e) {
                log.error("Sync failed", e);
                scheduler.execute(() -> sendOrEditMessage("❌ Ошибка синхронизации: " + e.getMessage(), chatId, messageId, null));
            }
        }).start();
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
                    return;
                }

                String tgUser = message.getFrom().getUserName();
                String tgUserStr = (tgUser != null && !tgUser.isEmpty()) ? tgUser : "";

                if (u == null) {
                    users.put(fromId, new UserRecord(fromId, "", false, "", 0L, -1, tgUserStr));
                    log.info("New user registered: {} (ID: {})", message.getFrom().getFirstName(), fromId);
                    markDirty();
                    if (fromId != ownerId) {
                        String notificationName = message.getFrom().getFirstName();
                        if (!tgUserStr.isEmpty()) {
                            notificationName += " (@" + tgUserStr + ")";
                        }
                        notifyOwner("🆕 Новый пользователь: " + notificationName + " (" + fromId + ")", ownerId);
                    }
                    if (!message.hasText() || !message.getText().equals("/start")) {
                        sendWelcomeMessage(message);
                    }
                } else {
                    if (!tgUserStr.equals(u.tgUsername)) {
                        u.tgUsername = tgUserStr;
                        markDirty();
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
        return text.equals(BTN_MENU_USERS) || text.equals(BTN_MENU_BROADCAST) ||
                text.equals(BTN_MENU_PANEL) || text.equals(BTN_CANCEL) ||
                // Legacy direct commands still supported via inline callbacks
                text.equals(BTN_USERS_LIST) || text.equals(BTN_SEND_MSG) || text.equals(BTN_STATISTICS) ||
                text.equals(BTN_SEND_MEDIA) || text.equals(BTN_REMOVE_USER) ||
                text.equals(BTN_SEND_ALL) || text.equals(BTN_SEND_MEDIA_ALL) ||
                text.equals(BTN_BAN_USER) || text.equals(BTN_UNBAN_USER) ||
                text.equals(BTN_RENAME_USER) || text.equals(BTN_USER_INFO) || text.equals(BTN_BACKUP) ||
                text.equals("🔄 Синхронизация 3x-ui");
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
            case "🔄 Синхронизация 3x-ui":
                handleSyncCommand(chatId, null);
                break;
            case BTN_MENU_USERS:
                sendMenuUsers(chatId, null);
                break;
            case BTN_MENU_BROADCAST:
                sendMenuBroadcast(chatId, null);
                break;
            case BTN_MENU_PANEL:
                sendMenuPanel(chatId, null);
                break;
            case BTN_STATISTICS:
                handleStatisticsCommand(chatId);
                break;
            case BTN_BACKUP:
                handleBackupCommand(chatId);
                break;
            case BTN_USERS_LIST:
            case "/list":
                handleListCommand(chatId);
                break;
            case BTN_SEND_MSG:
                sendUsersListInline(chatId, null, "send", 0, "back_broadcast");
                break;
            case BTN_SEND_ALL:
                session.setState(UserState.WAITING_FOR_MSG_SEND);
                session.setTargetUserId(-1L);
                sendMessage("Введите текст для рассылки всем пользователям:", chatId);
                break;
            case BTN_SEND_MEDIA:
                sendUsersListInline(chatId, null, "media", 0, "back_broadcast");
                break;
            case BTN_SEND_MEDIA_ALL:
                session.setState(UserState.WAITING_FOR_BROADCAST_MEDIA);
                sendMessage("Пришлите фото или видео с подписью, которое нужно разослать всем:", chatId);
                break;
            case BTN_REMOVE_USER:
                sendUsersListInline(chatId, null, "remove", 0, "back_users");
                break;
            case BTN_BAN_USER:
                sendUsersListInline(chatId, null, "ban", 0, "back_users");
                break;
            case BTN_UNBAN_USER:
                sendBannedUsersListInline(chatId, null, 0);
                break;
            case BTN_USER_INFO:
                sendUsersListInline(chatId, null, "info", 0, "back_users");
                break;
            case BTN_RENAME_USER:
                sendUsersListInline(chatId, null, "rename", 0, "back_users");
                break;
            case BTN_CANCEL:
                session.setState(UserState.START);
                sendMessage("Действие отменено.", chatId);
                break;
        }
    }

    private void sendMenuUsers(long chatId, Integer messageId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(Arrays.asList(
                InlineKeyboardButton.builder().text("📋 Список").callbackData("menu_list").build(),
                InlineKeyboardButton.builder().text("ℹ️ Информация").callbackData("menu_info").build()));
        rows.add(Arrays.asList(
                InlineKeyboardButton.builder().text("✏️ Переименовать").callbackData("menu_rename").build(),
                InlineKeyboardButton.builder().text("🗑️ Удалить").callbackData("menu_remove").build()));
        rows.add(Arrays.asList(
                InlineKeyboardButton.builder().text("🚫 Забанить").callbackData("menu_ban").build(),
                InlineKeyboardButton.builder().text("✅ Разбанить").callbackData("menu_unban").build()));
        rows.add(Collections.singletonList(
                InlineKeyboardButton.builder().text("📊 Статистика").callbackData("menu_stats").build()));
        sendOrEditMessage("👥 *Пользователи*", chatId, messageId, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void sendMenuBroadcast(long chatId, Integer messageId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(Collections.singletonList(
                InlineKeyboardButton.builder().text("✉️ Сообщение юзеру").callbackData("menu_send_msg").build()));
        rows.add(Collections.singletonList(
                InlineKeyboardButton.builder().text("📢 Текст всем").callbackData("menu_send_all").build()));
        rows.add(Collections.singletonList(
                InlineKeyboardButton.builder().text("📤 Медиа юзеру").callbackData("menu_send_media").build()));
        rows.add(Collections.singletonList(
                InlineKeyboardButton.builder().text("🖼️ Медиа всем").callbackData("menu_send_media_all").build()));
        sendOrEditMessage("📢 *Рассылка*", chatId, messageId, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void sendMenuPanel(long chatId, Integer messageId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(Collections.singletonList(
                InlineKeyboardButton.builder().text("🔄 Синхронизация").callbackData("menu_sync").build()));
        rows.add(Collections.singletonList(
                InlineKeyboardButton.builder().text("💾 Бэкап").callbackData("menu_backup").build()));
        sendOrEditMessage("⚙️ *Панель 3x-ui*", chatId, messageId, InlineKeyboardMarkup.builder().keyboard(rows).build());
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
            case WAITING_FOR_BROADCAST_MEDIA:
                if (fromId == ownerId && (message.hasPhoto() || message.hasVideo())) {
                    handleBroadcastMedia(message);
                    session.setState(UserState.START);
                } else if (fromId == ownerId) {
                    sendMessage("Пожалуйста, отправьте фото или видео.", chatId);
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
                } else if (text.equals(BTN_MY_KEYS)) {
                    handleMyKeysCommand(fromId, chatId);
                } else if (text.equals(BTN_HELP) || text.equals("/help")) {
                    sendHelp(chatId, fromId == ownerId);
                } else if (fromId != ownerId && !text.isEmpty() && !text.startsWith("/")) {
                    forwardToOwner(message);
                } else if (fromId != ownerId) {
                    sendMessageWithKeyboard("Я вас не понимаю. Пожалуйста, используйте кнопки меню.", chatId, true);
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

    private void handleBroadcastMedia(Message message) {
        String caption = message.getCaption();
        List<UserRecord> targets = users.values().stream().filter(u -> !u.banned).collect(Collectors.toList());
        
        if (message.hasPhoto()) {
            String fileId = message.getPhoto().get(message.getPhoto().size() - 1).getFileId();
            sendMessage("Запускаю рассылку фото " + targets.size() + " пользователям...", ownerId);
            for (UserRecord u : targets) {
                try {
                    execute(SendPhoto.builder().chatId(String.valueOf(u.id)).photo(new InputFile(fileId)).caption(caption).build());
                } catch (Exception ignored) {}
            }
            sendMessage("✅ Рассылка фото завершена!", ownerId);
        } else if (message.hasVideo()) {
            String fileId = message.getVideo().getFileId();
            sendMessage("Запускаю рассылку видео " + targets.size() + " пользователям...", ownerId);
            for (UserRecord u : targets) {
                try {
                    execute(SendVideo.builder().chatId(String.valueOf(u.id)).video(new InputFile(fileId)).caption(caption).build());
                } catch (Exception ignored) {}
            }
            sendMessage("✅ Рассылка видео завершена!", ownerId);
        } else {
            sendMessage("❌ Ошибка: вы не прислали фото или видео.", ownerId);
        }
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

    private void handleMyKeysCommand(long userId, long chatId) {
        UserRecord u = users.get(userId);
        if (u == null || u.xuiUsername == null || u.xuiUsername.isEmpty()) {
            sendMessage("У вас пока нет привязанных ключей подписки.", chatId);
            return;
        }

        if (xuiApiClient != null) {
            sendMessage("⏳ Запрашиваю ваши ключи из панели...", chatId);
            List<String> links = xuiApiClient.getClientLinks(u.xuiUsername);
            String subUrl = xuiApiClient.getSubscriptionUrl(u.xuiUsername);

            if ((links != null && !links.isEmpty()) || (subUrl != null && !subUrl.isEmpty())) {
                StringBuilder sb = new StringBuilder("🔑 <b>Ваши доступы:</b>\n\n");
                if (subUrl != null && !subUrl.isEmpty()) {
                    sb.append("🔗 <b>Ссылка на подписку:</b>\n<code>").append(subUrl).append("</code>\n\n");
                }
                if (links != null && !links.isEmpty()) {
                    sb.append("🛡 <b>Прямые ключи:</b>\n");
                    for (String link : links) {
                        sb.append("<code>").append(link).append("</code>\n\n");
                    }
                }
                sendMessage(sb.toString().trim(), chatId);
            } else {
                sendMessage("❌ Ключи и подписка не найдены или неактивны.", chatId);
            }
        } else {
            sendMessage("❌ Связь с сервером ключей временно недоступна.", chatId);
        }
    }

    private void handleCallbackQuery(CallbackQuery query) {
        String data = query.getData();
        long chatId = query.getMessage().getChatId();
        UserSession session = sessions.computeIfAbsent(ownerId, k -> new UserSession());
        try {
            execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(query.getId()).build());

            Integer msgId = query.getMessage().getMessageId();

            // --- Sub-menu callbacks ---
            switch (data) {
                case "back_users":     sendMenuUsers(chatId, msgId); return;
                case "back_broadcast": sendMenuBroadcast(chatId, msgId); return;
                case "back_panel":     sendMenuPanel(chatId, msgId); return;

                case "menu_list":   handleListCommand(chatId, msgId); return;
                case "menu_stats":  handleStatisticsCommand(chatId, msgId); return;
                case "menu_sync":   handleSyncCommand(chatId, msgId); return;
                case "menu_backup": handleBackupCommand(chatId); return; // backup sends file, can't be edited
                case "menu_info":   sendUsersListInline(chatId, msgId, "info", 0, "back_users"); return;
                case "menu_rename": sendUsersListInline(chatId, msgId, "rename", 0, "back_users"); return;
                case "menu_remove": sendUsersListInline(chatId, msgId, "remove", 0, "back_users"); return;
                case "menu_ban":    sendUsersListInline(chatId, msgId, "ban", 0, "back_users"); return;
                case "menu_unban":  sendBannedUsersListInline(chatId, msgId, 0); return;
                case "menu_send_msg":
                    sendUsersListInline(chatId, msgId, "send", 0, "back_broadcast"); return;
                case "menu_send_all":
                    session.setState(UserState.WAITING_FOR_MSG_SEND);
                    session.setTargetUserId(-1L);
                    sendOrEditMessage("Введите текст для рассылки всем пользователям:", chatId, msgId, 
                        InlineKeyboardMarkup.builder().keyboard(Collections.singletonList(Collections.singletonList(InlineKeyboardButton.builder().text("🔙 Отмена").callbackData("back_broadcast").build()))).build());
                    return;
                case "menu_send_media":
                    sendUsersListInline(chatId, msgId, "media", 0, "back_broadcast"); return;
                case "menu_send_media_all":
                    session.setState(UserState.WAITING_FOR_BROADCAST_MEDIA);
                    sendOrEditMessage("Пришлите фото или видео с подписью, которое нужно разослать всем:", chatId, msgId, 
                        InlineKeyboardMarkup.builder().keyboard(Collections.singletonList(Collections.singletonList(InlineKeyboardButton.builder().text("🔙 Отмена").callbackData("back_broadcast").build()))).build());
                    return;
            }

            if (data.startsWith("page_")) {
                String[] parts = data.split("_");
                String action = parts[1];
                int page = Integer.parseInt(parts[2]);
                if (action.equals("unban")) {
                    sendBannedUsersListInline(chatId, query.getMessage().getMessageId(), page);
                } else {
                    String backData = (action.equals("send") || action.equals("media")) ? "back_broadcast" : "back_users";
                    sendUsersListInline(chatId, query.getMessage().getMessageId(), action, page, backData);
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
                        sendToUser(uid, "Ты забанен.");
                    } else if (data.startsWith("unban_")) {
                        long uid = Long.parseLong(data.substring(6));
                        if (users.containsKey(uid))
                            users.get(uid).banned = false;
                        markDirty();
                        sendMessage("Пользователь " + uid + " убран из бан-листа.", chatId);
                        sendToUser(uid, "Ты помилован.");
                    } else if (data.startsWith("info_")) {
                        long uid = Long.parseLong(data.substring(5));
                        UserRecord u = users.get(uid);
                        if (u != null) {
                            String trafficInfo = "";
                            String clientLink = "";
                            if (xuiApiClient != null && !u.xuiUsername.isEmpty()) {
                                JSONObject traffic = xuiApiClient.getClientTraffic(u.xuiUsername);
                                if (traffic != null && traffic.optBoolean("success")) {
                                    JSONObject obj = traffic.optJSONObject("obj");
                                    if (obj != null) {
                                        long up = obj.optLong("up", 0);
                                        long down = obj.optLong("down", 0);
                                        trafficInfo = "\nТрафик: ↑" + formatTraffic(up) + " ↓" + formatTraffic(down);
                                    }
                                }
                                String subLink = xuiApiClient.getSubscriptionUrl(u.xuiUsername);
                                if (subLink != null) clientLink = "\n\n🔗 <b>Подписка:</b>\n<code>" + subLink + "</code>";
                            }

                            String uInfo = "ℹ️ Информация о пользователе:\n\n" +
                                    "ID: <code>" + u.id + "</code>\n" +
                                    "Имя: " + (u.name.isEmpty() ? "не указано" : u.name) + "\n" +
                                    "Юзернейм TG: " + (u.tgUsername.isEmpty() ? "нет" : "@" + u.tgUsername) + "\n" +
                                    "Юзернейм 3x-ui: " + (u.xuiUsername.isEmpty() ? "отсутствует" : u.xuiUsername) + 
                                    trafficInfo + "\n" +
                                    "Истекает: " + (u.expiryDate > 0 ? new java.util.Date(u.expiryDate).toString() : "неограниченно") + "\n" +
                                    "Бан: " + (u.banned ? "Да 🚫" : "Нет ✅") +
                                    clientLink;
                            
                            InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(Arrays.asList(
                                Collections.singletonList(InlineKeyboardButton.builder().text("🔄 Сбросить трафик").callbackData("reset_traffic_" + uid).build()),
                                Collections.singletonList(InlineKeyboardButton.builder().text("🔙 В меню").callbackData("menu_info").build())
                            )).build();
                            
                            sendOrEditMessage(uInfo, chatId, query.getMessage().getMessageId(), markup);
                        } else {
                            sendOrEditMessage("Пользователь не найден.", chatId, query.getMessage().getMessageId(), null);
                        }
                    } else if (data.startsWith("reset_traffic_")) {
                        long uid = Long.parseLong(data.substring(14));
                        UserRecord u = users.get(uid);
                        if (u != null && xuiApiClient != null && !u.xuiUsername.isEmpty()) {
                            if (xuiApiClient.resetClientTraffic(u.xuiUsername)) {
                                sendMessage("✅ Трафик для " + u.xuiUsername + " сброшен.", chatId);
                            } else {
                                sendMessage("❌ Не удалось сбросить трафик.", chatId);
                            }
                        }
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

    private void sendUsersListInline(long chatId, Integer messageId, String action, int page, String backCallback) {
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

        if (backCallback != null) {
            rows.add(Collections.singletonList(InlineKeyboardButton.builder().text("🔙 В меню").callbackData(backCallback).build()));
        }

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        try {
            if (messageId != null) {
                execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText.builder()
                        .chatId(String.valueOf(chatId)).messageId(messageId).text("Выберите пользователя:")
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

        rows.add(Collections.singletonList(InlineKeyboardButton.builder().text("🔙 В меню").callbackData("back_users").build()));

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        try {
            if (messageId != null) {
                execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText.builder()
                        .chatId(String.valueOf(chatId)).messageId(messageId).text("Выберите пользователя для разбана:")
                        .replyMarkup(markup).build());
            } else {
                execute(SendMessage.builder().chatId(String.valueOf(chatId)).text("Выберите пользователя для разбана:")
                        .replyMarkup(markup).build());
            }
        } catch (Exception e) {
            log.error("Failed to send banned inline keyboard", e);
        }
    }

    private void handleStatisticsCommand(long chatId, Integer messageId) {
        long total = users.size();
        long banned = users.values().stream().filter(u -> u.banned).count();
        long active = total - banned;

        String stats = "📊 Статистика бота:\n\n" +
                "Всего пользователей: " + total + "\n" +
                "Активных: " + active + "\n" +
                "В бане: " + banned;
        InlineKeyboardMarkup markup = null;
        if (messageId != null) {
            markup = InlineKeyboardMarkup.builder().keyboard(Collections.singletonList(
                    Collections.singletonList(InlineKeyboardButton.builder().text("🔙 В меню").callbackData("back_users").build())
            )).build();
        }
        sendOrEditMessage(stats, chatId, messageId, markup);
    }

    private void handleStatisticsCommand(long chatId) {
        handleStatisticsCommand(chatId, null);
    }

    private void handleListCommand(long chatId, Integer messageId) {
        if (users.isEmpty()) {
            sendOrEditMessage("Список пользователей пуст.", chatId, messageId, null);
            return;
        }
        StringBuilder sb = new StringBuilder("📋 Зарегистрированные пользователи:\n\n");
        users.values().forEach(u -> {
            String status = u.banned ? " 🚫 (Забанен)" : "";
            sb.append("👤 ").append(u.name.isEmpty() ? String.valueOf(u.id) : u.name).append(" (").append(u.id)
                    .append(")").append(status).append("\n");
        });
        InlineKeyboardMarkup markup = null;
        if (messageId != null) {
            markup = InlineKeyboardMarkup.builder().keyboard(Collections.singletonList(
                    Collections.singletonList(InlineKeyboardButton.builder().text("🔙 В меню").callbackData("back_users").build())
            )).build();
        }
        sendOrEditMessage(sb.toString(), chatId, messageId, markup);
    }

    private void handleListCommand(long chatId) {
        handleListCommand(chatId, null);
    }

    public void sendToUser(long userId, String text) {
        try {
            execute(SendMessage.builder().chatId(String.valueOf(userId)).text(text).parseMode("HTML").build());
        } catch (TelegramApiException e) {
            log.error("Failed to send to user {}", userId, e);
            if (e.getMessage() != null &&
                    (e.getMessage().contains("blocked") || e.getMessage().contains("deactivated"))) {
                users.remove(userId);
                markDirty();
            }
        }
    }

    public void sendMessageToUser(long userId, String message) {
        sendToUser(userId, message);
    }

    private void sendMessage(String text, long chatId) {
        sendMessageWithKeyboard(text, chatId, false);
    }

    private void sendOrEditMessage(String text, long chatId, Integer messageId, InlineKeyboardMarkup markup) {
        try {
            if (messageId != null) {
                org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText edit = 
                    org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText.builder()
                        .chatId(String.valueOf(chatId))
                        .messageId(messageId)
                        .text(text)
                        .parseMode("HTML")
                        .build();
                if (markup != null) edit.setReplyMarkup(markup);
                execute(edit);
            } else {
                SendMessage send = SendMessage.builder()
                        .chatId(String.valueOf(chatId))
                        .text(text)
                        .parseMode("HTML")
                        .build();
                if (markup != null) send.setReplyMarkup(markup);
                execute(send);
            }
        } catch (Exception e) {
            log.error("Failed to send or edit message", e);
        }
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
        String help = isOwner ? "👑 Команды владельца:\n" +
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
                        createRow(BTN_MY_NAME, BTN_MY_KEYS)))
                .resizeKeyboard(true)
                .build();
    }

    private ReplyKeyboardMarkup getOwnerKeyboard() {
        return ReplyKeyboardMarkup.builder()
                .keyboard(Arrays.asList(
                        createRow(BTN_MENU_USERS, BTN_MENU_BROADCAST),
                        createRow(BTN_MENU_PANEL, BTN_CANCEL)))
                .resizeKeyboard(true)
                .build();
    }

    private void handleBackupCommand(long chatId) {
        if (xuiApiClient != null) {
            sendMessage("⏳ Начинаю скачивание бэкапа...", chatId);
            File dbFile = xuiApiClient.downloadDb();
            if (dbFile != null && dbFile.exists()) {
                try {
                    execute(SendDocument.builder()
                            .chatId(String.valueOf(chatId))
                            .document(new InputFile(dbFile, "x-ui-backup.db"))
                            .caption("📦 Полный бэкап базы данных (SQLite)")
                            .build());
                    if (!dbFile.delete()) {
                        log.warn("Failed to delete temporary backup file: {}", dbFile.getAbsolutePath());
                    }
                } catch (TelegramApiException e) {
                    log.error("Failed to send backup file", e);
                    sendMessage("❌ Ошибка при отправке файла.", chatId);
                }
            } else {
                sendMessage("❌ Не удалось получить файл бэкапа из панели.", chatId);
            }
        }
    }

    private String formatTraffic(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %cB", bytes / Math.pow(1024, exp), pre);
    }

    public void markDirty() {
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
                obj.put("xuiUsername", u.xuiUsername);
                obj.put("expiryDate", u.expiryDate);
                obj.put("lastNotifiedDay", u.lastNotifiedDay);
                obj.put("tgUsername", u.tgUsername);
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
                boolean banned = obj.optBoolean("banned", false);
                String xuiUsername = obj.optString("xuiUsername", "");
                long expiryDate = obj.optLong("expiryDate", 0L);
                int lastNotifiedDay = obj.optInt("lastNotifiedDay", -1);
                String tgUsername = obj.optString("tgUsername", "");
                users.put(id, new UserRecord(id, name, banned, xuiUsername, expiryDate, lastNotifiedDay, tgUsername));
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