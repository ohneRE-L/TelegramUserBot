import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public class TelegramUserBot extends TelegramLongPollingBot {
    private final String botToken;
    private final long ownerId;
    private final Set<Long> userIds = new HashSet<>();
    private final String botUsername;

    public TelegramUserBot(String botToken, String botUsername, long ownerId) {
        this.botToken = 7470332104:AAH5AMkKWx-5fYuyw1gBJf-VQTVxcB-nbqU;
        this.botUsername = MyServerFaz_Send_Bot;
        this.ownerId = 5516363342;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            long fromId = message.getFrom().getId();
            String text = message.getText();

            // Регистрация пользователя
            if (!userIds.contains(fromId)) {
                userIds.add(fromId);
            }

            // Проверка владельца
            if (fromId == ownerId && text.startsWith("/")) {
                handleOwnerCommand(message, text);
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
                        sb.append(id).append("\n");
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
                        notifyOwner("Пользователь " + userId + " удалён.", message.getChatId());
                    } else {
                        notifyOwner("Пользователь с ID " + userId + " не найден.", message.getChatId());
                    }
                } catch (NumberFormatException e) {
                    notifyOwner("Ошибка: user_id должен быть числом.", message.getChatId());
                }
                break;
            default:
                notifyOwner("Неизвестная команда.", message.getChatId());
        }
    }

    private void notifyOwner(String text, long chatId) {
        try {
            execute(SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build());
        } catch (TelegramApiException ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (FileInputStream is = TelegramUserBot.class.getClassLoader().getResourceAsStream("config.properties")) {
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
        long ownerId;
        try {
            ownerId = Long.parseLong(ownerIdStr);
        } catch (NumberFormatException e) {
            System.err.println("owner.id должен быть числом");
            return;
        }
        String botUsername = "YOUR_BOT_USERNAME_HERE"; // Укажите имя бота
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new TelegramUserBot(token, botUsername, ownerId));
        System.out.println("Бот запущен.");
    }
} 