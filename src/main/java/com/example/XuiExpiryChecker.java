package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class XuiExpiryChecker {
    private static final Logger log = LoggerFactory.getLogger(XuiExpiryChecker.class);
    private final TelegramUserBot bot;
    private final XuiApiClient apiClient;
    private final int checkIntervalHours;
    private final int[] notifyDays;
    
    public XuiExpiryChecker(TelegramUserBot bot, XuiApiClient apiClient, 
                            int checkIntervalHours, String notifyDaysStr) {
        this.bot = bot;
        this.apiClient = apiClient;
        this.checkIntervalHours = checkIntervalHours;
        
        // Parse notification days (e.g., "3,1,0" -> [3,1,0])
        String[] days = notifyDaysStr.split(",");
        this.notifyDays = new int[days.length];
        for (int i = 0; i < days.length; i++) {
            notifyDays[i] = Integer.parseInt(days[i].trim());
        }
    }
    
    public void start() {
        // Run first check after 10 seconds
        bot.getScheduler().schedule(() -> {
            checkExpiryAndNotify();
            // Then schedule periodic checks
            bot.getScheduler().scheduleAtFixedRate(
                this::checkExpiryAndNotify,
                checkIntervalHours, 
                checkIntervalHours, 
                TimeUnit.HOURS
            );
        }, 10, TimeUnit.SECONDS);
        
        log.info("Expiry checker started. Check interval: {} hours, Notify days: {}", 
                 checkIntervalHours, notifyDays);
    }
    
    public void checkExpiryAndNotify() {
        log.info("Checking expiry dates from 3x-ui...");
        
        try {
            Map<String, Long> clientsExpiry = apiClient.getAllClientsExpiry();
            if (clientsExpiry.isEmpty()) {
                log.warn("No clients retrieved from 3x-ui");
                return;
            }
            
            long now = System.currentTimeMillis();
            int notifiedCount = 0;
            
            for (Map.Entry<String, Long> entry : clientsExpiry.entrySet()) {
                String xuiUsername = entry.getKey();
                long expiryTime = entry.getValue();
                
                // expiryTime = 0 means no expiry (unlimited)
                if (expiryTime == 0) {
                    log.info("Client {} has unlimited expiry (0), skipping.", xuiUsername);
                    continue;
                }
                
                long daysUntilExpiry = TimeUnit.MILLISECONDS.toDays(expiryTime - now);
                log.info("Client {}: expiryTime={}, daysUntilExpiry={}", xuiUsername, expiryTime, daysUntilExpiry);
                
                // Check if we need to notify for any of the configured days
                boolean shouldNotify = false;
                
                for (int day : notifyDays) {
                    if (daysUntilExpiry == day) {
                        shouldNotify = true;
                        break;
                    }
                }
                
                if (shouldNotify) {
                    // Find user by username
                    Long tgId = bot.findTelegramIdByUsername(xuiUsername);
                    if (tgId != null && !bot.isUserBanned(tgId)) {
                        String message = generateNotificationMessage(xuiUsername, daysUntilExpiry);
                        bot.sendMessageToUser(tgId, message);
                        notifiedCount++;
                        log.info("Sent expiry notification to {} ({} days left)", xuiUsername, daysUntilExpiry);
                    }
                }
            }
            
            if (notifiedCount > 0) {
                log.info("Sent {} expiry notifications", notifiedCount);
            }
            
        } catch (Exception e) {
            log.error("Error during expiry check: {}", e.getMessage(), e);
        }
    }
    
    private String generateNotificationMessage(String username, long daysLeft) {
        if (daysLeft == 0) {
            return "⚠️ <b>ВНИМАНИЕ!</b> ⚠️\n\n" +
                   "Ваш VPN-ключ (<b>" + username + "</b>) истекает <b>СЕГОДНЯ</b>!\n\n" +
                   "Пожалуйста, свяжитесь с администратором для продления доступа.\n\n" +
                   "Спасибо, что остаетесь с нами! 🌟";
        } else if (daysLeft == 1) {
            return "🔔 <b>НАПОМИНАНИЕ</b> 🔔\n\n" +
                   "Ваш VPN-ключ (<b>" + username + "</b>) истекает <b>ЗАВТРА</b>!\n\n" +
                   "Пожалуйста, свяжитесь с администратором для продления доступа, чтобы избежать перебоев.\n\n" +
                   "💫 Хорошего дня!";
        } else {
            return "📅 <b>Уведомление о подписке</b> 📅\n\n" +
                   "Ваш VPN-ключ (<b>" + username + "</b>) истекает через <b>" + daysLeft + " дней</b>.\n\n" +
                   "Рекомендуем продлить доступ заранее.\n\n" +
                   "Для продления свяжитесь с администратором.\n\n" +
                   "Спасибо за использование нашего сервиса! 🚀";
        }
    }
}
