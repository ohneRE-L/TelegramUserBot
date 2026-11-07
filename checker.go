package main

import (
	"context"
	"fmt"
	"log"
	"time"
)

type BotSender interface {
	SendMessage(chatID int64, text string) error
}

type ExpiryChecker struct {
	storage       *Storage
	xui           *XuiClient
	sender        BotSender
	checkInterval time.Duration
	notifyDays    []int
}

func NewExpiryChecker(storage *Storage, xui *XuiClient, sender BotSender, checkIntervalHours int, notifyDays []int) *ExpiryChecker {
	return &ExpiryChecker{
		storage:       storage,
		xui:           xui,
		sender:        sender,
		checkInterval: time.Duration(checkIntervalHours) * time.Hour,
		notifyDays:    notifyDays,
	}
}

func (c *ExpiryChecker) Start(ctx context.Context) {
	log.Printf("Expiry checker started. Interval: %v, Notify days: %v", c.checkInterval, c.notifyDays)

	go func() {
		// Run initial check after 10 seconds
		select {
		case <-time.After(10 * time.Second):
			c.CheckExpiryAndNotify()
		case <-ctx.Done():
			return
		}

		ticker := time.NewTicker(c.checkInterval)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				c.CheckExpiryAndNotify()
			case <-ctx.Done():
				return
			}
		}
	}()
}

func (c *ExpiryChecker) CheckExpiryAndNotify() {
	log.Printf("Checking expiry dates from 3x-ui...")

	clientsExpiry, err := c.xui.GetAllClientsExpiry()
	if err != nil {
		log.Printf("Error fetching clients from 3x-ui: %v", err)
		return
	}
	if len(clientsExpiry) == 0 {
		log.Printf("No clients retrieved from 3x-ui")
		return
	}

	now := time.Now().UnixMilli()
	notifiedCount := 0

	for xuiUsername, expiryTime := range clientsExpiry {
		if expiryTime <= 0 {
			continue
		}

		diff := expiryTime - now
		daysUntilExpiry := diff / (24 * 60 * 60 * 1000)
		dayThreshold := int(daysUntilExpiry)

		// If expired > 24h ago, skip
		if diff < -86400000 {
			continue
		}

		tgID, found := c.storage.FindIDByUsername(xuiUsername)
		if !found {
			continue
		}

		user, exists := c.storage.Get(tgID)
		if !exists || user.Banned {
			continue
		}

		// Reset notification if expiry date changed
		if user.ExpiryDate != expiryTime {
			user.ExpiryDate = expiryTime
			user.LastNotifiedDay = -1
			c.storage.MarkDirty()
			log.Printf("Reset lastNotifiedDay for %s due to expiry change", xuiUsername)
		}

		isNotifyDay := false
		for _, d := range c.notifyDays {
			if dayThreshold == d {
				isNotifyDay = true
				break
			}
		}

		if isNotifyDay && user.LastNotifiedDay != dayThreshold {
			log.Printf("Triggering notification for %s (%d days left)", xuiUsername, dayThreshold)
			msg := c.generateNotificationMessage(xuiUsername, dayThreshold)
			if err := c.sender.SendMessage(tgID, msg); err == nil {
				user.LastNotifiedDay = dayThreshold
				c.storage.MarkDirty()
				notifiedCount++
			} else {
				log.Printf("Failed to notify %s (%d): %v", xuiUsername, tgID, err)
			}
		}
	}

	if notifiedCount > 0 {
		log.Printf("Sent %d expiry notifications", notifiedCount)
	}
}

func (c *ExpiryChecker) generateNotificationMessage(username string, daysLeft int) string {
	if daysLeft == 0 {
		return fmt.Sprintf("⚠️ <b>ВНИМАНИЕ!</b> ⚠️\n\n"+
			"Ваш VPN-ключ (<b>%s</b>) истекает <b>СЕГОДНЯ</b>!\n\n"+
			"Пожалуйста, свяжитесь с администратором для продления доступа.\n\n"+
			"Спасибо, что остаетесь с нами! 🌟", username)
	} else if daysLeft == 1 {
		return fmt.Sprintf("🔔 <b>НАПОМИНАНИЕ</b> 🔔\n\n"+
			"Ваш VPN-ключ (<b>%s</b>) истекает <b>ЗАВТРА</b>!\n\n"+
			"Пожалуйста, свяжитесь с администратором для продления доступа, чтобы избежать перебоев.\n\n"+
			"💫 Хорошего дня!", username)
	} else {
		return fmt.Sprintf("📅 <b>Уведомление о подписке</b> 📅\n\n"+
			"Ваш VPN-ключ (<b>%s</b>) истекает через <b>%d дней</b>.\n\n"+
			"Рекомендуем продлить доступ заранее.\n\n"+
			"Для продления свяжитесь с администратором.\n\n"+
			"Спасибо за использование нашего сервиса! 🚀", username, daysLeft)
	}
}
