package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	tgbotapi "github.com/go-telegram-bot-api/telegram-bot-api/v5"
	qrcode "github.com/skip2/go-qrcode"
)

type UserState int

const (
	StateStart UserState = iota
	StateWaitingForName
	StateWaitingForMsgSend
	StateWaitingForMedia
	StateWaitingForBroadcastMedia
	StateWaitingForRename
	StateWaitingForSearch
)

const (
	BtnUsersList       = "📋 Список пользователей"
	BtnSendMsg         = "✉️ Отправить сообщение"
	BtnSendAll         = "📢 Отправить всем"
	BtnSendMedia       = "📤 Отправить медиа"
	BtnSendMediaAll    = "🖼️ Рассылка медиа всем"
	BtnRemoveUser      = "🗑️ Удалить пользователя"
	BtnBanUser         = "🚫 Забанить"
	BtnUnbanUser       = "✅ Разбанить"
	BtnRenameUser      = "✏️ Переименовать"
	BtnCancel          = "❌ Отмена"
	BtnStatistics      = "📊 Статистика"
	BtnUserInfo        = "ℹ️ Информация о пользователе"
	BtnBackup          = "💾 Бэкап панели 3x-ui"
	BtnMenuUsers       = "👥 Пользователи"
	BtnMenuBroadcast   = "📢 Рассылка"
	BtnMenuPanel       = "⚙️ Панель 3x-ui"
	BtnHelp            = "Помощь"
	BtnSetName         = "Указать имя"
	BtnMyName          = "Моё имя"
	BtnMyKeys          = "🔑 Мои ключи"
	BtnMySubscription  = "📊 Моя подписка"
	BtnSetupGuide      = "📖 Как настроить"
)

type UserSession struct {
	State        UserState
	TargetUserID int64
}

type Bot struct {
	api      *tgbotapi.BotAPI
	config   *Config
	storage  *Storage
	xui      *XuiClient
	checker  *ExpiryChecker
	sessions map[int64]*UserSession
	sessMu   sync.Mutex
	replyMap map[int]int64
	replyMu  sync.RWMutex
}

func NewBot(api *tgbotapi.BotAPI, cfg *Config, storage *Storage, xui *XuiClient) *Bot {
	b := &Bot{
		api:      api,
		config:   cfg,
		storage:  storage,
		xui:      xui,
		sessions: make(map[int64]*UserSession),
		replyMap: make(map[int]int64),
	}
	if xui != nil {
		b.checker = NewExpiryChecker(storage, xui, b, cfg.XuiCheckIntervalHr, cfg.XuiNotifyDays)
	}
	return b
}

func (b *Bot) getSession(userID int64) *UserSession {
	b.sessMu.Lock()
	defer b.sessMu.Unlock()
	sess, ok := b.sessions[userID]
	if !ok {
		sess = &UserSession{State: StateStart}
		b.sessions[userID] = sess
	}
	return sess
}

func (b *Bot) Start(ctx context.Context) {
	// Set bot commands for Telegram Menu button
	commands := tgbotapi.NewSetMyCommands(
		tgbotapi.BotCommand{Command: "start", Description: "Главное меню"},
		tgbotapi.BotCommand{Command: "menu", Description: "Показать кнопки управления"},
		tgbotapi.BotCommand{Command: "help", Description: "Справка"},
	)
	if _, err := b.api.Request(commands); err != nil {
		log.Printf("Failed to set bot commands: %v", err)
	}

	if b.checker != nil {
		b.checker.Start(ctx)
	}

	// Auto-save ticker
	go func() {
		ticker := time.NewTicker(1 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				b.storage.SaveIfDirty()
			case <-ctx.Done():
				return
			}
		}
	}()

	u := tgbotapi.NewUpdate(0)
	u.Timeout = 60
	updates := b.api.GetUpdatesChan(u)

	log.Printf("Bot @%s started successfully", b.api.Self.UserName)

	for {
		select {
		case <-ctx.Done():
			return
		case update, ok := <-updates:
			if !ok {
				return
			}
			b.handleUpdate(update)
		}
	}
}

var userPattern = regexp.MustCompile(`\((\d{5,15})\)`)

func (b *Bot) extractTargetIDFromMessage(replyMsg *tgbotapi.Message) int64 {
	if replyMsg == nil {
		return 0
	}
	if replyMsg.ForwardFrom != nil && replyMsg.ForwardFrom.ID != b.config.OwnerID {
		return replyMsg.ForwardFrom.ID
	}
	text := replyMsg.Text
	if text == "" {
		text = replyMsg.Caption
	}
	if text != "" {
		matches := userPattern.FindStringSubmatch(text)
		if len(matches) > 1 {
			id, err := strconv.ParseInt(matches[1], 10, 64)
			if err == nil && id != b.config.OwnerID {
				return id
			}
		}
	}
	return 0
}

func (b *Bot) handleUpdate(update tgbotapi.Update) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Recovered from panic in handleUpdate: %v", r)
		}
	}()

	if update.CallbackQuery != nil {
		b.handleCallback(update.CallbackQuery)
		return
	}

	if update.Message == nil {
		return
	}

	msg := update.Message
	fromID := msg.From.ID

	user, exists := b.storage.Get(fromID)
	if exists && user.Banned && fromID != b.config.OwnerID {
		return
	}

	tgUsername := msg.From.UserName

	if !exists {
		newUser := &UserRecord{
			ID:              fromID,
			Name:            "",
			Banned:          false,
			XuiUsername:     "",
			ExpiryDate:      0,
			LastNotifiedDay: -1,
			TgUsername:      tgUsername,
		}
		b.storage.Set(newUser)
		log.Printf("New user registered: %s (ID: %d)", msg.From.FirstName, fromID)

		if fromID != b.config.OwnerID {
			notif := fmt.Sprintf("🆕 Новый пользователь: %s", msg.From.FirstName)
			if tgUsername != "" {
				notif += fmt.Sprintf(" (@%s)", tgUsername)
			}
			notif += fmt.Sprintf(" (%d)", fromID)
			b.notifyOwner(notif)
		}

		if msg.Text != "/start" {
			b.sendWelcomeMessage(msg.Chat.ID)
		}
	} else if tgUsername != user.TgUsername {
		user.TgUsername = tgUsername
		b.storage.MarkDirty()
	}

	session := b.getSession(fromID)

	// Support native Telegram Reply from owner
	if fromID == b.config.OwnerID && msg.ReplyToMessage != nil {
		b.replyMu.RLock()
		targetID, ok := b.replyMap[msg.ReplyToMessage.MessageID]
		b.replyMu.RUnlock()

		if !ok {
			targetID = b.extractTargetIDFromMessage(msg.ReplyToMessage)
			if targetID > 0 {
				ok = true
			}
		}

		if ok {
			targetName := fmt.Sprintf("%d", targetID)
			if u, exists := b.storage.Get(targetID); exists && u.Name != "" {
				targetName = fmt.Sprintf("%s (%d)", u.Name, targetID)
			}

			if msg.Text != "" {
				if err := b.sendToUser(targetID, msg.Text); err != nil {
					b.sendMessage(msg.Chat.ID, fmt.Sprintf("❌ Ошибка отправки пользователю %s: %s", targetName, err.Error()))
				} else {
					sentMsg, _ := b.sendMessageEx(msg.Chat.ID, fmt.Sprintf("✅ Ответ отправлен пользователю %s", targetName))
					if sentMsg.MessageID > 0 {
						b.replyMu.Lock()
						b.replyMap[sentMsg.MessageID] = targetID
						b.replyMu.Unlock()
					}
				}
				return
			} else if len(msg.Photo) > 0 || msg.Document != nil {
				b.handleSendMedia(msg, targetID)
				return
			}
		}
	}

	if fromID == b.config.OwnerID && msg.Text != "" && (strings.HasPrefix(msg.Text, "/") || b.isOwnerCommandButton(msg.Text)) {
		b.handleOwnerCommand(msg)
		return
	}

	b.handleUserMessage(msg, session)
}

func (b *Bot) isOwnerCommandButton(text string) bool {
	switch text {
	case BtnMenuUsers, BtnMenuBroadcast, BtnMenuPanel, BtnCancel,
		BtnUsersList, BtnSendMsg, BtnStatistics, BtnSendMedia,
		BtnRemoveUser, BtnSendAll, BtnSendMediaAll, BtnBanUser,
		BtnUnbanUser, BtnRenameUser, BtnUserInfo, BtnBackup,
		"🔄 Синхронизация 3x-ui":
		return true
	default:
		return false
	}
}

func (b *Bot) handleOwnerCommand(msg *tgbotapi.Message) {
	text := msg.Text
	chatID := msg.Chat.ID
	session := b.getSession(b.config.OwnerID)

	if strings.HasPrefix(text, "/find") {
		b.handleFindCommand(chatID, strings.TrimPrefix(text, "/find"))
		return
	}

	switch text {
	case "/start", "/menu":
		b.sendWelcomeMessage(chatID)
	case "/help":
		b.sendHelp(chatID, true)
	case "🔄 Синхронизация 3x-ui":
		b.handleSyncCommand(chatID, 0)
	case BtnMenuUsers:
		b.sendMenuUsers(chatID, 0)
	case BtnMenuBroadcast:
		b.sendMenuBroadcast(chatID, 0)
	case BtnMenuPanel:
		b.sendMenuPanel(chatID, 0)
	case BtnStatistics:
		b.handleStatisticsCommand(chatID, 0)
	case BtnBackup:
		b.handleBackupCommand(chatID)
	case BtnUsersList, "/list":
		b.handleListCommand(chatID, 0)
	case BtnSendMsg:
		b.sendUsersListInline(chatID, 0, "send", 0, "back_broadcast")
	case BtnSendAll:
		session.State = StateWaitingForMsgSend
		session.TargetUserID = -1
		b.sendMessage(chatID, "Введите текст для рассылки всем пользователям:")
	case BtnSendMedia:
		b.sendUsersListInline(chatID, 0, "media", 0, "back_broadcast")
	case BtnSendMediaAll:
		session.State = StateWaitingForBroadcastMedia
		b.sendMessage(chatID, "Пришлите фото или видео с подписью, которое нужно разослать всем:")
	case BtnRemoveUser:
		b.sendUsersListInline(chatID, 0, "remove", 0, "back_users")
	case BtnBanUser:
		b.sendUsersListInline(chatID, 0, "ban", 0, "back_users")
	case BtnUnbanUser:
		b.sendBannedUsersListInline(chatID, 0, 0)
	case BtnUserInfo:
		b.sendUsersListInline(chatID, 0, "info", 0, "back_users")
	case BtnRenameUser:
		b.sendUsersListInline(chatID, 0, "rename", 0, "back_users")
	case BtnCancel:
		session.State = StateStart
		b.sendMessage(chatID, "Действие отменено.")
	default:
		b.sendWelcomeMessage(chatID)
	}
}

func (b *Bot) handleUserMessage(msg *tgbotapi.Message, session *UserSession) {
	text := msg.Text
	chatID := msg.Chat.ID
	fromID := msg.From.ID

	switch session.State {
	case StateWaitingForName:
		if text != "" {
			if u, ok := b.storage.Get(fromID); ok {
				u.Name = text
				b.storage.MarkDirty()
			}
			b.sendMessageWithKeyboard(chatID, "Имя сохранено: "+text, true)
			session.State = StateStart
		} else {
			b.sendMessage(chatID, "Пожалуйста, введите текстовое имя.")
		}
	case StateWaitingForMsgSend:
		if fromID == b.config.OwnerID && text != "" {
			if session.TargetUserID == -1 {
				b.handleSendToAllAsync(text, chatID)
			} else {
				targetName := fmt.Sprintf("%d", session.TargetUserID)
				if u, ok := b.storage.Get(session.TargetUserID); ok && u.Name != "" {
					targetName = fmt.Sprintf("%s (%d)", u.Name, session.TargetUserID)
				}
				if err := b.sendToUser(session.TargetUserID, text); err != nil {
					b.sendMessage(chatID, fmt.Sprintf("❌ Ошибка отправки пользователю %s: %s", targetName, err.Error()))
				} else {
					b.sendMessage(chatID, fmt.Sprintf("✅ Сообщение отправлено пользователю %s", targetName))
				}
			}
			session.State = StateStart
		}
	case StateWaitingForMedia:
		if fromID == b.config.OwnerID && (len(msg.Photo) > 0 || msg.Document != nil) {
			b.handleSendMedia(msg, session.TargetUserID)
			session.State = StateStart
		} else if fromID == b.config.OwnerID {
			b.sendMessage(chatID, "Пожалуйста, отправьте фото или документ (файл).")
		}
	case StateWaitingForBroadcastMedia:
		if fromID == b.config.OwnerID && (len(msg.Photo) > 0 || msg.Video != nil) {
			b.handleBroadcastMedia(msg)
			session.State = StateStart
		} else if fromID == b.config.OwnerID {
			b.sendMessage(chatID, "Пожалуйста, отправьте фото или видео.")
		}
	case StateWaitingForRename:
		if fromID == b.config.OwnerID && text != "" {
			targetID := session.TargetUserID
			if u, ok := b.storage.Get(targetID); ok {
				u.Name = text
				b.storage.MarkDirty()
			}
			b.sendMessage(chatID, fmt.Sprintf("Пользователь %d переименован в: %s", targetID, text))
			session.State = StateStart
		}
	case StateWaitingForSearch:
		if fromID == b.config.OwnerID && text != "" {
			b.handleFindCommand(chatID, text)
			session.State = StateStart
		}
	case StateStart:
		fallthrough
	default:
		if text == "/start" || text == "/menu" {
			b.sendWelcomeMessage(chatID)
		} else if text == BtnSetName || strings.HasPrefix(text, "/name") {
			name := ""
			if strings.HasPrefix(text, "/name") {
				name = strings.TrimSpace(strings.TrimPrefix(text, "/name"))
			}
			if name == "" {
				session.State = StateWaitingForName
				b.sendMessage(chatID, "Введите ваше имя:")
			} else {
				if u, ok := b.storage.Get(fromID); ok {
					u.Name = name
					b.storage.MarkDirty()
				}
				b.sendMessageWithKeyboard(chatID, "Имя сохранено: "+name, true)
			}
		} else if text == BtnMyName {
			name := "<не указано>"
			if u, ok := b.storage.Get(fromID); ok && u.Name != "" {
				name = u.Name
			}
			b.sendMessage(chatID, "Ваше имя: "+name)
		} else if text == BtnMyKeys {
			b.handleMyKeysCommand(fromID, chatID)
		} else if text == BtnMySubscription {
			b.handleMySubscriptionCommand(fromID, chatID)
		} else if text == BtnSetupGuide {
			b.handleSetupGuideCommand(chatID)
		} else if text == BtnHelp || text == "/help" {
			b.sendHelp(chatID, fromID == b.config.OwnerID)
		} else if fromID != b.config.OwnerID && text != "" && !strings.HasPrefix(text, "/") {
			b.forwardToOwner(msg)
		} else if fromID != b.config.OwnerID {
			b.sendMessageWithKeyboard(chatID, "Я вас не понимаю. Пожалуйста, используйте кнопки меню.", true)
		} else {
			// Owner typed direct text: check if matches "<User> <Message>"
			var bestMatch *UserRecord
			lowerText := strings.ToLower(text)
			for _, u := range b.storage.All() {
				if u.Name != "" && strings.HasPrefix(lowerText, strings.ToLower(u.Name)+" ") {
					if bestMatch == nil || len(u.Name) > len(bestMatch.Name) {
						bestMatch = u
					}
				}
			}

			if bestMatch != nil {
				directMsg := strings.TrimSpace(text[len(bestMatch.Name):])
				if directMsg != "" {
					if err := b.sendToUser(bestMatch.ID, directMsg); err != nil {
						b.sendMessage(chatID, fmt.Sprintf("❌ Ошибка отправки пользователю %s: %s", bestMatch.Name, err.Error()))
					} else {
						b.sendMessage(chatID, fmt.Sprintf("✅ Сообщение отправлено пользователю %s", bestMatch.Name))
					}
				} else {
					b.sendMessage(chatID, fmt.Sprintf("Вы не ввели текст сообщения для %s", bestMatch.Name))
				}
			} else {
				b.sendMessage(chatID, "Команда не распознана. Используйте меню или введите '<Имя> <Сообщение>'.")
			}
		}
	}
}

func (b *Bot) forwardToOwner(msg *tgbotapi.Message) {
	fromID := msg.From.ID
	senderName := msg.From.FirstName
	if u, ok := b.storage.Get(fromID); ok && u.Name != "" {
		senderName = u.Name
	}
	prefix := fmt.Sprintf("📩 [От %s (%d)]:\n", senderName, fromID)

	var sentMsg tgbotapi.Message
	var err error
	if msg.Text != "" {
		m := tgbotapi.NewMessage(b.config.OwnerID, prefix+msg.Text)
		sentMsg, err = b.api.Send(m)
	} else {
		fwd := tgbotapi.NewForward(b.config.OwnerID, msg.Chat.ID, msg.MessageID)
		sentMsg, err = b.api.Send(fwd)
		b.notifyOwner(prefix + "отправил(а) медиа (ответьте свайпом/Reply для связи).")
	}

	if err == nil && sentMsg.MessageID > 0 {
		b.replyMu.Lock()
		b.replyMap[sentMsg.MessageID] = fromID
		b.replyMu.Unlock()
	}
}

func (b *Bot) handleSendMedia(msg *tgbotapi.Message, targetUserID int64) {
	targetName := fmt.Sprintf("%d", targetUserID)
	if u, exists := b.storage.Get(targetUserID); exists && u.Name != "" {
		targetName = fmt.Sprintf("%s (%d)", u.Name, targetUserID)
	}

	if len(msg.Photo) > 0 {
		highest := msg.Photo[len(msg.Photo)-1]
		photo := tgbotapi.NewPhoto(targetUserID, tgbotapi.FileID(highest.FileID))
		photo.Caption = msg.Caption
		if _, err := b.api.Send(photo); err != nil {
			b.sendMessage(msg.Chat.ID, fmt.Sprintf("❌ Ошибка отправки фото пользователю %s: %s", targetName, err.Error()))
			return
		}
	} else if msg.Document != nil {
		doc := tgbotapi.NewDocument(targetUserID, tgbotapi.FileID(msg.Document.FileID))
		doc.Caption = msg.Caption
		if _, err := b.api.Send(doc); err != nil {
			b.sendMessage(msg.Chat.ID, fmt.Sprintf("❌ Ошибка отправки документа пользователю %s: %s", targetName, err.Error()))
			return
		}
	}
	sentMsg, _ := b.sendMessageEx(msg.Chat.ID, fmt.Sprintf("✅ Медиа отправлено пользователю %s", targetName))
	if sentMsg.MessageID > 0 {
		b.replyMu.Lock()
		b.replyMap[sentMsg.MessageID] = targetUserID
		b.replyMu.Unlock()
	}
}

func (b *Bot) handleBroadcastMedia(msg *tgbotapi.Message) {
	targets := make([]*UserRecord, 0)
	for _, u := range b.storage.All() {
		if !u.Banned {
			targets = append(targets, u)
		}
	}

	caption := msg.Caption

	if len(msg.Photo) > 0 {
		fileID := msg.Photo[len(msg.Photo)-1].FileID
		b.sendMessage(b.config.OwnerID, fmt.Sprintf("Запускаю рассылку фото %d пользователям...", len(targets)))
		go func() {
			for _, u := range targets {
				photo := tgbotapi.NewPhoto(u.ID, tgbotapi.FileID(fileID))
				photo.Caption = caption
				_, _ = b.api.Send(photo)
				time.Sleep(50 * time.Millisecond)
			}
			b.sendMessage(b.config.OwnerID, "✅ Рассылка фото завершена!")
		}()
	} else if msg.Video != nil {
		fileID := msg.Video.FileID
		b.sendMessage(b.config.OwnerID, fmt.Sprintf("Запускаю рассылку видео %d пользователям...", len(targets)))
		go func() {
			for _, u := range targets {
				video := tgbotapi.NewVideo(u.ID, tgbotapi.FileID(fileID))
				video.Caption = caption
				_, _ = b.api.Send(video)
				time.Sleep(50 * time.Millisecond)
			}
			b.sendMessage(b.config.OwnerID, "✅ Рассылка видео завершена!")
		}()
	} else {
		b.sendMessage(b.config.OwnerID, "❌ Ошибка: вы не прислали фото или видео.")
	}
}

func (b *Bot) handleSendToAllAsync(text string, ownerChatID int64) {
	targets := make([]*UserRecord, 0)
	for _, u := range b.storage.All() {
		if !u.Banned {
			targets = append(targets, u)
		}
	}

	if len(targets) == 0 {
		b.sendMessage(ownerChatID, "Нет пользователей для рассылки.")
		return
	}

	b.sendMessage(ownerChatID, "📢 Рассылка запущена в фоне...")

	go func() {
		success, fail := 0, 0
		for _, u := range targets {
			msg := tgbotapi.NewMessage(u.ID, text)
			msg.ParseMode = "HTML"
			if _, err := b.api.Send(msg); err != nil {
				fail++
			} else {
				success++
			}
			time.Sleep(50 * time.Millisecond)
		}
		b.sendMessage(ownerChatID, fmt.Sprintf("📢 Рассылка завершена: %d успешно, %d ошибок.", success, fail))
	}()
}

func (b *Bot) handleMyKeysCommand(userID, chatID int64) {
	u, exists := b.storage.Get(userID)
	if !exists || u.XuiUsername == "" {
		b.sendMessage(chatID, "У вас пока нет привязанных ключей подписки.")
		return
	}

	if b.xui == nil {
		b.sendMessage(chatID, "❌ Связь с сервером ключей временно недоступна.")
		return
	}

	b.sendMessage(chatID, "⏳ Запрашиваю ваши ключи из панели...")

	links, _ := b.xui.GetClientLinks(u.XuiUsername)
	subURL := b.xui.GetSubscriptionURL(u.XuiUsername)

	if len(links) > 0 || subURL != "" {
		if subURL != "" {
			qrBytes, err := qrcode.Encode(subURL, qrcode.Medium, 300)
			if err == nil {
				caption := fmt.Sprintf("📱 <b>QR-код вашей подписки</b>\n\nОтсканируйте в приложении или скопируйте ссылку:\n<code>%s</code>", subURL)
				photo := tgbotapi.NewPhoto(chatID, tgbotapi.FileBytes{
					Name:  "subscription.png",
					Bytes: qrBytes,
				})
				photo.Caption = caption
				photo.ParseMode = "HTML"
				_, _ = b.api.Send(photo)
			} else {
				b.sendMessage(chatID, fmt.Sprintf("🔗 <b>Ссылка на подписку:</b>\n<code>%s</code>", subURL))
			}
		}

		if len(links) > 0 {
			var sb strings.Builder
			sb.WriteString("🛡 <b>Прямые ключи:</b>\n\n")
			for _, l := range links {
				sb.WriteString(fmt.Sprintf("<code>%s</code>\n\n", l))
			}
			b.sendMessage(chatID, strings.TrimSpace(sb.String()))
		}
	} else {
		b.sendMessage(chatID, "❌ Ключи и подписка не найдены или неактивны.")
	}
}

func (b *Bot) handleMySubscriptionCommand(userID, chatID int64) {
	u, exists := b.storage.Get(userID)
	if !exists || u.XuiUsername == "" {
		b.sendMessage(chatID, "У вас пока нет привязанной подписки. Свяжитесь с администратором для получения доступа.")
		return
	}

	if b.xui == nil {
		b.sendMessage(chatID, "❌ Связь с сервером ключей временно недоступна.")
		return
	}

	up, down, err := b.xui.GetClientTraffic(u.XuiUsername)
	trafficStr := ""
	if err == nil {
		total := up + down
		trafficStr = fmt.Sprintf("\n📊 <b>Использовано трафика:</b>\n   ↑ Загрузка: %s\n   ↓ Скачивание: %s\n   Σ Всего: %s\n",
			formatTraffic(up), formatTraffic(down), formatTraffic(total))
	}

	statusStr := "🟢 Активна"
	expiryStr := "♾️ Неограниченно"
	if u.ExpiryDate > 0 {
		now := time.Now().UnixMilli()
		diff := u.ExpiryDate - now
		expTime := time.UnixMilli(u.ExpiryDate).Format("02.01.2006 15:04")
		if diff <= 0 {
			statusStr = "🔴 Истекла"
			expiryStr = fmt.Sprintf("Истекла (%s)", expTime)
		} else {
			days := diff / (24 * 60 * 60 * 1000)
			hours := (diff % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
			if days > 0 {
				expiryStr = fmt.Sprintf("до %s (осталось: %d дн. %d ч.)", expTime, days, hours)
			} else {
				expiryStr = fmt.Sprintf("до %s (осталось: %d ч.)", expTime, hours)
			}
		}
	}

	res := fmt.Sprintf("📊 <b>Ваша подписка:</b>\n\n"+
		"🔑 <b>Логин:</b> <code>%s</code>\n"+
		"📅 <b>Срок действия:</b> %s\n"+
		"Статус: %s\n%s",
		u.XuiUsername, expiryStr, statusStr, trafficStr)

	b.sendMessage(chatID, res)
}

func (b *Bot) handleSetupGuideCommand(chatID int64) {
	guide := "📖 <b>Как настроить подключение к VPN:</b>\n\n" +
		"1️⃣ Нажмите <b>«🔑 Мои ключи»</b> выше, чтобы получить QR-код или ссылку на подписку.\n\n" +
		"2️⃣ <b>Установите приложение для вашего устройства:</b>\n\n" +
		"📱 <b>Android:</b>\n" +
		"• Приложения: <b>v2rayNG</b> или <b>Happ</b> (Google Play)\n" +
		"• Нажмите <b>+</b> ➔ <i>«Импорт из буфера»</i> или <i>«Сканировать QR-код»</i>.\n\n" +
		"🍏 <b>iOS (iPhone / iPad):</b>\n" +
		"• Приложения: <b>Streisand</b>, <b>Happ</b> или <b>Shadowrocket</b> (App Store)\n" +
		"• Нажмите <b>+</b> ➔ отсканируйте QR-код или вставьте ссылку подписки.\n\n" +
		"💻 <b>Windows:</b>\n" +
		"• Приложения: <b>v2rayN</b> или <b>Hiddify</b>\n" +
		"• Скопируйте ссылку подписки ➔ <i>Подписка</i> ➔ <i>Добавить</i> ➔ <i>Обновить</i>.\n\n" +
		"🍏 <b>macOS:</b>\n" +
		"• <b>Streisand</b> (App Store) или <b>V2RayXS</b>\n\n" +
		"3️⃣ Выберите сервер и нажмите <b>Подключиться (Connect)</b>! 🚀"

	b.sendMessage(chatID, guide)
}

func (b *Bot) handleSyncCommand(chatID int64, messageID int) {
	if b.xui == nil {
		b.sendOrEditMessage(chatID, messageID, "❌ 3x-ui интеграция не настроена", nil)
		return
	}

	b.sendOrEditMessage(chatID, messageID, "🔄 Синхронизация с 3x-ui...", nil)

	go func() {
		panelClients, err := b.xui.GetAllClientsExpiry()
		if err != nil {
			b.sendOrEditMessage(chatID, messageID, "❌ Ошибка синхронизации: "+err.Error(), nil)
			return
		}

		synced := 0
		usernamesFetched := 0
		var notFound []string

		for _, u := range b.storage.All() {
			matched := false
			if u.XuiUsername != "" {
				if exp, ok := panelClients[u.XuiUsername]; ok {
					u.ExpiryDate = exp
					u.LastNotifiedDay = -1
					synced++
					matched = true
				}
			}
			if !matched && u.Name != "" {
				if exp, ok := panelClients[u.Name]; ok {
					u.ExpiryDate = exp
					u.XuiUsername = u.Name
					u.LastNotifiedDay = -1
					synced++
					matched = true
				}
			}
			if !matched && !u.Banned {
				name := u.Name
				if name == "" {
					name = strconv.FormatInt(u.ID, 10)
				}
				notFound = append(notFound, name)
			}

			if u.TgUsername == "" {
				chatConfig := tgbotapi.ChatInfoConfig{
					ChatConfig: tgbotapi.ChatConfig{ChatID: u.ID},
				}
				if chat, err := b.api.GetChat(chatConfig); err == nil && chat.UserName != "" {
					u.TgUsername = chat.UserName
					usernamesFetched++
				}
				time.Sleep(200 * time.Millisecond)
			}
		}

		b.storage.MarkDirty()

		var sb strings.Builder
		sb.WriteString("✅ Синхронизация завершена!\n")
		sb.WriteString(fmt.Sprintf("Синхронизировано ключей: %d\n", synced))
		sb.WriteString(fmt.Sprintf("Восстановлено никнеймов: %d", usernamesFetched))
		if len(notFound) > 0 {
			sb.WriteString("\n\n⚠️ Не найдены в панели 3x-ui:")
			for _, name := range notFound {
				sb.WriteString("\n- " + name)
			}
			sb.WriteString("\n\n(Проверьте Email в панели у этих пользователей)")
		}

		var markup *tgbotapi.InlineKeyboardMarkup
		if messageID > 0 {
			kb := tgbotapi.NewInlineKeyboardMarkup(
				tgbotapi.NewInlineKeyboardRow(
					tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "back_panel"),
				),
			)
			markup = &kb
		}
		b.sendOrEditMessage(chatID, messageID, sb.String(), markup)
	}()
}

func (b *Bot) handleBackupCommand(chatID int64) {
	if b.xui == nil {
		b.sendMessage(chatID, "❌ 3x-ui интеграция не настроена.")
		return
	}

	b.sendMessage(chatID, "⏳ Начинаю скачивание бэкапа...")
	dbPath, err := b.xui.DownloadDB()
	if err != nil {
		b.sendMessage(chatID, "❌ Не удалось получить файл бэкапа из панели: "+err.Error())
		return
	}
	defer os.Remove(dbPath)

	doc := tgbotapi.NewDocument(chatID, tgbotapi.FilePath(dbPath))
	doc.Caption = "📦 Полный бэкап базы данных (SQLite)"
	if _, err := b.api.Send(doc); err != nil {
		b.sendMessage(chatID, "❌ Ошибка при отправке файла: "+err.Error())
	}
}

func (b *Bot) handleCallback(query *tgbotapi.CallbackQuery) {
	callback := tgbotapi.NewCallback(query.ID, "")
	_, _ = b.api.Request(callback)

	fromID := query.From.ID
	if u, exists := b.storage.Get(fromID); exists && u.Banned && fromID != b.config.OwnerID {
		return
	}

	data := query.Data
	chatID := query.Message.Chat.ID
	msgID := query.Message.MessageID
	session := b.getSession(b.config.OwnerID)

	switch data {
	case "back_users":
		b.sendMenuUsers(chatID, msgID)
		return
	case "back_broadcast":
		b.sendMenuBroadcast(chatID, msgID)
		return
	case "back_panel":
		b.sendMenuPanel(chatID, msgID)
		return
	case "menu_list":
		b.handleListCommand(chatID, msgID)
		return
	case "menu_stats":
		b.handleStatisticsCommand(chatID, msgID)
		return
	case "menu_sync":
		b.handleSyncCommand(chatID, msgID)
		return
	case "menu_backup":
		b.handleBackupCommand(chatID)
		return
	case "menu_info":
		b.sendUsersListInline(chatID, msgID, "info", 0, "back_users")
		return
	case "menu_rename":
		b.sendUsersListInline(chatID, msgID, "rename", 0, "back_users")
		return
	case "menu_remove":
		b.sendUsersListInline(chatID, msgID, "remove", 0, "back_users")
		return
	case "menu_ban":
		b.sendUsersListInline(chatID, msgID, "ban", 0, "back_users")
		return
	case "menu_unban":
		b.sendBannedUsersListInline(chatID, msgID, 0)
		return
	case "menu_send_msg":
		b.sendUsersListInline(chatID, msgID, "send", 0, "back_broadcast")
		return
	case "menu_send_all":
		session.State = StateWaitingForMsgSend
		session.TargetUserID = -1
		cancelMarkup := tgbotapi.NewInlineKeyboardMarkup(
			tgbotapi.NewInlineKeyboardRow(
				tgbotapi.NewInlineKeyboardButtonData("🔙 Отмена", "back_broadcast"),
			),
		)
		b.sendOrEditMessage(chatID, msgID, "Введите текст для рассылки всем пользователям:", &cancelMarkup)
		return
	case "menu_send_media":
		b.sendUsersListInline(chatID, msgID, "media", 0, "back_broadcast")
		return
	case "menu_send_media_all":
		session.State = StateWaitingForBroadcastMedia
		cancelMarkup := tgbotapi.NewInlineKeyboardMarkup(
			tgbotapi.NewInlineKeyboardRow(
				tgbotapi.NewInlineKeyboardButtonData("🔙 Отмена", "back_broadcast"),
			),
		)
		b.sendOrEditMessage(chatID, msgID, "Пришлите фото или видео с подписью, которое нужно разослать всем:", &cancelMarkup)
		return
	case "menu_server_status":
		b.handleServerStatusCommand(chatID, msgID)
		return
	case "menu_expiring":
		b.handleExpiringUsersCommand(chatID, msgID)
		return
	case "menu_search":
		session.State = StateWaitingForSearch
		cancelMarkup := tgbotapi.NewInlineKeyboardMarkup(
			tgbotapi.NewInlineKeyboardRow(
				tgbotapi.NewInlineKeyboardButtonData("🔙 Отмена", "back_users"),
			),
		)
		b.sendOrEditMessage(chatID, msgID, "🔍 Введите имя, @юзернейм или ID пользователя для поиска:", &cancelMarkup)
		return
	}

	if strings.HasPrefix(data, "page_") {
		parts := strings.Split(data, "_")
		if len(parts) >= 3 {
			action := parts[1]
			page, _ := strconv.Atoi(parts[2])
			if action == "unban" {
				b.sendBannedUsersListInline(chatID, msgID, page)
			} else {
				back := "back_users"
				if action == "send" || action == "media" {
					back = "back_broadcast"
				}
				b.sendUsersListInline(chatID, msgID, action, page, back)
			}
		}
		return
	}

	if data == "ignore" {
		return
	}

	if strings.HasPrefix(data, "send_") {
		uid, _ := strconv.ParseInt(data[5:], 10, 64)
		session.TargetUserID = uid
		session.State = StateWaitingForMsgSend
		b.sendMessage(chatID, fmt.Sprintf("Введите сообщение для пользователя %d:", uid))
	} else if strings.HasPrefix(data, "media_") {
		uid, _ := strconv.ParseInt(data[6:], 10, 64)
		session.TargetUserID = uid
		session.State = StateWaitingForMedia
		b.sendMessage(chatID, fmt.Sprintf("Отправьте медиа (фото или файл) для пользователя %d:", uid))
	} else if strings.HasPrefix(data, "remove_") {
		uid, _ := strconv.ParseInt(data[7:], 10, 64)
		if b.storage.Delete(uid) {
			b.sendMessage(chatID, fmt.Sprintf("Пользователь %d удален.", uid))
		}
	} else if strings.HasPrefix(data, "ban_") {
		uid, _ := strconv.ParseInt(data[4:], 10, 64)
		if u, ok := b.storage.Get(uid); ok {
			u.Banned = true
			b.storage.MarkDirty()
			b.sendMessage(chatID, fmt.Sprintf("Пользователь %d добавлен в бан-лист.", uid))
		}
	} else if strings.HasPrefix(data, "unban_") {
		uid, _ := strconv.ParseInt(data[6:], 10, 64)
		if u, ok := b.storage.Get(uid); ok {
			u.Banned = false
			b.storage.MarkDirty()
			b.sendMessage(chatID, fmt.Sprintf("Пользователь %d убран из бан-листа.", uid))
			b.sendToUser(uid, "Ты помилован.")
		}
	} else if strings.HasPrefix(data, "info_") {
		uid, _ := strconv.ParseInt(data[5:], 10, 64)
		b.showUserInfo(chatID, msgID, uid)
	} else if strings.HasPrefix(data, "reset_traffic_") {
		uid, _ := strconv.ParseInt(data[14:], 10, 64)
		if u, ok := b.storage.Get(uid); ok && b.xui != nil && u.XuiUsername != "" {
			if b.xui.ResetClientTraffic(u.XuiUsername) {
				b.sendMessage(chatID, fmt.Sprintf("✅ Трафик для %s сброшен.", u.XuiUsername))
			} else {
				b.sendMessage(chatID, "❌ Не удалось сбросить трафик.")
			}
		}
	} else if strings.HasPrefix(data, "rename_") {
		uid, _ := strconv.ParseInt(data[7:], 10, 64)
		session.TargetUserID = uid
		session.State = StateWaitingForRename
		current := "не указано"
		if u, ok := b.storage.Get(uid); ok && u.Name != "" {
			current = u.Name
		}
		b.sendMessage(chatID, fmt.Sprintf("Введите новое имя для пользователя %d (текущее: %s):", uid, current))
	}
}

func (b *Bot) showUserInfo(chatID int64, messageID int, uid int64) {
	u, ok := b.storage.Get(uid)
	if !ok {
		b.sendOrEditMessage(chatID, messageID, "Пользователь не найден.", nil)
		return
	}

	trafficInfo := ""
	clientLink := ""
	if b.xui != nil && u.XuiUsername != "" {
		up, down, err := b.xui.GetClientTraffic(u.XuiUsername)
		if err == nil {
			trafficInfo = fmt.Sprintf("\nТрафик: ↑%s ↓%s (Σ %s)", formatTraffic(up), formatTraffic(down), formatTraffic(up+down))
		}
		if subLink := b.xui.GetSubscriptionURL(u.XuiUsername); subLink != "" {
			clientLink = fmt.Sprintf("\n\n🔗 <b>Подписка:</b>\n<code>%s</code>", subLink)
		}
	}

	name := u.Name
	if name == "" {
		name = "не указано"
	}
	tgUser := "нет"
	if u.TgUsername != "" {
		tgUser = "@" + u.TgUsername
	}
	xuiUser := "отсутствует"
	if u.XuiUsername != "" {
		xuiUser = u.XuiUsername
	}
	expiry := "неограниченно"
	if u.ExpiryDate > 0 {
		now := time.Now().UnixMilli()
		diff := u.ExpiryDate - now
		dateStr := time.UnixMilli(u.ExpiryDate).Format("02.01.2006 15:04")
		if diff <= 0 {
			expiry = fmt.Sprintf("🔴 Истёк (%s)", dateStr)
		} else {
			days := diff / (24 * 60 * 60 * 1000)
			expiry = fmt.Sprintf("%s (осталось: %d дн.)", dateStr, days)
		}
	}
	banStatus := "Нет ✅"
	if u.Banned {
		banStatus = "Да 🚫"
	}

	uInfo := fmt.Sprintf("ℹ️ Информация о пользователе:\n\n"+
		"ID: <code>%d</code>\n"+
		"Имя: %s\n"+
		"Юзернейм TG: %s\n"+
		"Юзернейм 3x-ui: %s%s\n"+
		"Истекает: %s\n"+
		"Бан: %s%s", u.ID, name, tgUser, xuiUser, trafficInfo, expiry, banStatus, clientLink)

	markup := tgbotapi.NewInlineKeyboardMarkup(
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🔄 Сбросить трафик", fmt.Sprintf("reset_traffic_%d", uid)),
			tgbotapi.NewInlineKeyboardButtonData("✉️ Написать", fmt.Sprintf("send_%d", uid)),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "menu_info"),
		),
	)
	b.sendOrEditMessage(chatID, messageID, uInfo, &markup)
}

func (b *Bot) handleFindCommand(chatID int64, query string) {
	query = strings.TrimSpace(query)
	if query == "" {
		b.sendMessage(chatID, "Использование: <code>/find <имя, юзернейм или ID></code>")
		return
	}

	matches := b.storage.Search(query)
	if len(matches) == 0 {
		b.sendMessage(chatID, fmt.Sprintf("❌ Пользователь по запросу «%s» не найден.", query))
		return
	}

	if len(matches) == 1 {
		b.showUserInfo(chatID, 0, matches[0].ID)
		return
	}

	var rows [][]tgbotapi.InlineKeyboardButton
	for _, u := range matches {
		name := u.Name
		if name == "" {
			name = strconv.FormatInt(u.ID, 10)
		}
		btn := tgbotapi.NewInlineKeyboardButtonData(
			fmt.Sprintf("%s [%d]", name, u.ID),
			fmt.Sprintf("info_%d", u.ID),
		)
		rows = append(rows, tgbotapi.NewInlineKeyboardRow(btn))
	}
	rows = append(rows, tgbotapi.NewInlineKeyboardRow(
		tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "back_users"),
	))
	markup := tgbotapi.NewInlineKeyboardMarkup(rows...)
	b.sendOrEditMessage(chatID, 0, fmt.Sprintf("🔍 Найдено пользователей: %d", len(matches)), &markup)
}

func (b *Bot) handleServerStatusCommand(chatID int64, messageID int) {
	if b.xui == nil {
		b.sendOrEditMessage(chatID, messageID, "❌ 3x-ui интеграция не настроена.", nil)
		return
	}

	status, err := b.xui.GetServerStatus()
	if err != nil {
		b.sendOrEditMessage(chatID, messageID, "❌ Ошибка получения статуса сервера: "+err.Error(), nil)
		return
	}

	uptimeStr := ""
	if status.Uptime > 0 {
		days := status.Uptime / 86400
		hours := (status.Uptime % 86400) / 3600
		mins := (status.Uptime % 3600) / 60
		uptimeStr = fmt.Sprintf("%d дн. %d ч. %d мин.", days, hours, mins)
	} else {
		uptimeStr = "н/д"
	}

	memPct := 0.0
	if status.MemTotal > 0 {
		memPct = float64(status.MemCurrent) / float64(status.MemTotal) * 100
	}

	diskPct := 0.0
	if status.DiskTotal > 0 {
		diskPct = float64(status.DiskCurrent) / float64(status.DiskTotal) * 100
	}

	xrayVer := status.XrayVersion
	if xrayVer == "" {
		xrayVer = "v1.8+"
	}

	text := fmt.Sprintf("🩺 <b>Статус сервера 3x-ui:</b>\n\n"+
		"⚡ <b>CPU:</b> %.1f%%\n"+
		"🧠 <b>RAM:</b> %s / %s (%.1f%%)\n"+
		"💾 <b>Диск:</b> %s / %s (%.1f%%)\n"+
		"🛡 <b>Xray:</b> %s (%s)\n"+
		"⏱ <b>Аптайм:</b> %s",
		status.CPU,
		formatTraffic(status.MemCurrent), formatTraffic(status.MemTotal), memPct,
		formatTraffic(status.DiskCurrent), formatTraffic(status.DiskTotal), diskPct,
		status.XrayState, xrayVer,
		uptimeStr,
	)

	markup := tgbotapi.NewInlineKeyboardMarkup(
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🔄 Обновить", "menu_server_status"),
			tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "back_panel"),
		),
	)
	b.sendOrEditMessage(chatID, messageID, text, &markup)
}

func (b *Bot) handleExpiringUsersCommand(chatID int64, messageID int) {
	expiring := b.storage.GetExpiring(7)
	if len(expiring) == 0 {
		markup := tgbotapi.NewInlineKeyboardMarkup(
			tgbotapi.NewInlineKeyboardRow(
				tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "back_users"),
			),
		)
		b.sendOrEditMessage(chatID, messageID, "✅ В ближайшие 7 дней истекающих подписок нет.", &markup)
		return
	}

	now := time.Now().UnixMilli()
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("⚠️ <b>Истекающие подписки (следующие 7 дней, всего %d):</b>\n\n", len(expiring)))

	for _, u := range expiring {
		diff := u.ExpiryDate - now
		days := diff / (24 * 60 * 60 * 1000)
		hours := (diff % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)

		name := u.Name
		if name == "" {
			name = strconv.FormatInt(u.ID, 10)
		}
		xuiUser := u.XuiUsername
		if xuiUser != "" {
			xuiUser = " (" + xuiUser + ")"
		}

		timeStr := ""
		if diff <= 0 {
			timeStr = "<b>СЕГОДНЯ / ИСТЁК</b>"
		} else if days == 0 {
			timeStr = fmt.Sprintf("<b>сегодня (~%d ч.)</b>", hours)
		} else if days == 1 {
			timeStr = fmt.Sprintf("<b>завтра (~%d ч.)</b>", hours)
		} else {
			timeStr = fmt.Sprintf("через <b>%d дн.</b>", days)
		}

		dateStr := time.UnixMilli(u.ExpiryDate).Format("02.01.2006")
		sb.WriteString(fmt.Sprintf("👤 %s%s ➔ %s (%s)\n", name, xuiUser, timeStr, dateStr))
	}

	markup := tgbotapi.NewInlineKeyboardMarkup(
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "back_users"),
		),
	)
	b.sendOrEditMessage(chatID, messageID, sb.String(), &markup)
}

func (b *Bot) sendUsersListInline(chatID int64, messageID int, action string, page int, backCallback string) {
	all := b.storage.All()
	var list []*UserRecord
	for _, u := range all {
		if action == "remove" || !u.Banned {
			list = append(list, u)
		}
	}

	sort.Slice(list, func(i, j int) bool {
		return list[i].ID < list[j].ID
	})

	if len(list) == 0 {
		b.sendMessage(chatID, "Список пользователей пуст.")
		return
	}

	limit := 50
	totalPages := int(math.Ceil(float64(len(list)) / float64(limit)))
	p := page
	if p < 0 {
		p = 0
	}
	if p >= totalPages {
		p = totalPages - 1
	}

	start := p * limit
	end := start + limit
	if end > len(list) {
		end = len(list)
	}

	var rows [][]tgbotapi.InlineKeyboardButton
	for _, u := range list[start:end] {
		name := u.Name
		if name == "" {
			name = strconv.FormatInt(u.ID, 10)
		}
		btn := tgbotapi.NewInlineKeyboardButtonData(
			fmt.Sprintf("%s [%d]", name, u.ID),
			fmt.Sprintf("%s_%d", action, u.ID),
		)
		rows = append(rows, tgbotapi.NewInlineKeyboardRow(btn))
	}

	if totalPages > 1 {
		var navRow []tgbotapi.InlineKeyboardButton
		if p > 0 {
			navRow = append(navRow, tgbotapi.NewInlineKeyboardButtonData("⬅️ Назад", fmt.Sprintf("page_%s_%d", action, p-1)))
		}
		navRow = append(navRow, tgbotapi.NewInlineKeyboardButtonData(fmt.Sprintf("%d/%d", p+1, totalPages), "ignore"))
		if p < totalPages-1 {
			navRow = append(navRow, tgbotapi.NewInlineKeyboardButtonData("Вперед ➡️", fmt.Sprintf("page_%s_%d", action, p+1)))
		}
		rows = append(rows, navRow)
	}

	if backCallback != "" {
		rows = append(rows, tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", backCallback),
		))
	}

	markup := tgbotapi.NewInlineKeyboardMarkup(rows...)
	b.sendOrEditMessage(chatID, messageID, "Выберите пользователя:", &markup)
}

func (b *Bot) sendBannedUsersListInline(chatID int64, messageID int, page int) {
	all := b.storage.All()
	var bannedList []*UserRecord
	for _, u := range all {
		if u.Banned {
			bannedList = append(bannedList, u)
		}
	}

	sort.Slice(bannedList, func(i, j int) bool {
		return bannedList[i].ID < bannedList[j].ID
	})

	if len(bannedList) == 0 {
		b.sendMessage(chatID, "Бан-лист пуст.")
		return
	}

	limit := 50
	totalPages := int(math.Ceil(float64(len(bannedList)) / float64(limit)))
	p := page
	if p < 0 {
		p = 0
	}
	if p >= totalPages {
		p = totalPages - 1
	}

	start := p * limit
	end := start + limit
	if end > len(bannedList) {
		end = len(bannedList)
	}

	var rows [][]tgbotapi.InlineKeyboardButton
	for _, u := range bannedList[start:end] {
		name := u.Name
		if name == "" {
			name = strconv.FormatInt(u.ID, 10)
		}
		btn := tgbotapi.NewInlineKeyboardButtonData(
			fmt.Sprintf("%s [%d]", name, u.ID),
			fmt.Sprintf("unban_%d", u.ID),
		)
		rows = append(rows, tgbotapi.NewInlineKeyboardRow(btn))
	}

	if totalPages > 1 {
		var navRow []tgbotapi.InlineKeyboardButton
		if p > 0 {
			navRow = append(navRow, tgbotapi.NewInlineKeyboardButtonData("⬅️ Назад", fmt.Sprintf("page_unban_%d", p-1)))
		}
		navRow = append(navRow, tgbotapi.NewInlineKeyboardButtonData(fmt.Sprintf("%d/%d", p+1, totalPages), "ignore"))
		if p < totalPages-1 {
			navRow = append(navRow, tgbotapi.NewInlineKeyboardButtonData("Вперед ➡️", fmt.Sprintf("page_unban_%d", p+1)))
		}
		rows = append(rows, navRow)
	}

	rows = append(rows, tgbotapi.NewInlineKeyboardRow(
		tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "back_users"),
	))

	markup := tgbotapi.NewInlineKeyboardMarkup(rows...)
	b.sendOrEditMessage(chatID, messageID, "Выберите пользователя для разбана:", &markup)
}

func (b *Bot) handleStatisticsCommand(chatID int64, messageID int) {
	total, active, banned := b.storage.TotalAndBanned()
	stats := fmt.Sprintf("📊 Статистика бота:\n\n"+
		"Всего пользователей: %d\n"+
		"Активных: %d\n"+
		"В бане: %d", total, active, banned)

	var markup *tgbotapi.InlineKeyboardMarkup
	if messageID > 0 {
		kb := tgbotapi.NewInlineKeyboardMarkup(
			tgbotapi.NewInlineKeyboardRow(
				tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "back_users"),
			),
		)
		markup = &kb
	}
	b.sendOrEditMessage(chatID, messageID, stats, markup)
}

func (b *Bot) handleListCommand(chatID int64, messageID int) {
	users := b.storage.All()
	if len(users) == 0 {
		b.sendOrEditMessage(chatID, messageID, "Список пользователей пуст.", nil)
		return
	}

	sort.Slice(users, func(i, j int) bool {
		return users[i].ID < users[j].ID
	})

	var sb strings.Builder
	sb.WriteString("📋 Зарегистрированные пользователи:\n\n")
	for _, u := range users {
		name := u.Name
		if name == "" {
			name = strconv.FormatInt(u.ID, 10)
		}
		status := ""
		if u.Banned {
			status = " 🚫 (Забанен)"
		}
		sb.WriteString(fmt.Sprintf("👤 %s (%d)%s\n", name, u.ID, status))
	}

	var markup *tgbotapi.InlineKeyboardMarkup
	if messageID > 0 {
		kb := tgbotapi.NewInlineKeyboardMarkup(
			tgbotapi.NewInlineKeyboardRow(
				tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "back_users"),
			),
		)
		markup = &kb
	}
	b.sendOrEditMessage(chatID, messageID, sb.String(), markup)
}

func (b *Bot) sendMenuUsers(chatID int64, messageID int) {
	markup := tgbotapi.NewInlineKeyboardMarkup(
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("📋 Список", "menu_list"),
			tgbotapi.NewInlineKeyboardButtonData("🔍 Поиск", "menu_search"),
			tgbotapi.NewInlineKeyboardButtonData("ℹ️ Инфо", "menu_info"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("⚠️ Истекающие (7 дн.)", "menu_expiring"),
			tgbotapi.NewInlineKeyboardButtonData("📊 Статистика", "menu_stats"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("✏️ Переименовать", "menu_rename"),
			tgbotapi.NewInlineKeyboardButtonData("🗑️ Удалить", "menu_remove"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🚫 Забанить", "menu_ban"),
			tgbotapi.NewInlineKeyboardButtonData("✅ Разбанить", "menu_unban"),
		),
	)
	b.sendOrEditMessage(chatID, messageID, "👥 Пользователи", &markup)
}

func (b *Bot) sendMenuBroadcast(chatID int64, messageID int) {
	markup := tgbotapi.NewInlineKeyboardMarkup(
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("✉️ Сообщение юзеру", "menu_send_msg"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("📢 Текст всем", "menu_send_all"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("📤 Медиа юзеру", "menu_send_media"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🖼️ Медиа всем", "menu_send_media_all"),
		),
	)
	b.sendOrEditMessage(chatID, messageID, "📢 Рассылка", &markup)
}

func (b *Bot) sendMenuPanel(chatID int64, messageID int) {
	markup := tgbotapi.NewInlineKeyboardMarkup(
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🩺 Статус сервера", "menu_server_status"),
			tgbotapi.NewInlineKeyboardButtonData("🔄 Синхронизация", "menu_sync"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("💾 Бэкап", "menu_backup"),
		),
	)
	b.sendOrEditMessage(chatID, messageID, "⚙️ Панель 3x-ui", &markup)
}

func (b *Bot) sendWelcomeMessage(chatID int64) {
	var welcome string
	if chatID == b.config.OwnerID {
		welcome = "👋 Панель управления ботом.\nИспользуйте кнопки меню ниже:"
	} else {
		welcome = "👋 Добро пожаловать!\nЯ бот для уведомлений и доступа к VPN. Используйте кнопки ниже или /help для справки."
	}
	b.sendMessageWithKeyboard(chatID, welcome, true)
}

func (b *Bot) sendHelp(chatID int64, isOwner bool) {
	var help string
	if isOwner {
		help = "👑 <b>Команды администратора:</b>\n\n" +
			"• <b>/find &lt;запрос&gt;</b> — быстрый поиск юзера\n" +
			"• <b>Ответ (Reply)</b> на сообщение юзера — отправляет ответ сразу в личку\n" +
			"• <b>Список пользователей</b> — просмотр всех и их статуса\n" +
			"• <b>Рассылка</b> — текст или фото/видео всем клиентам\n" +
			"• <b>Статус сервера</b> — нагрузка CPU, RAM, диск и Xray в панели"
	} else {
		help = "📖 <b>Доступные функции:</b>\n\n" +
			"• <b>📊 Моя подписка</b> — остаток дней, использованный трафик и статус\n" +
			"• <b>🔑 Мои ключи</b> — QR-код и ссылки для подключения\n" +
			"• <b>📖 Как настроить</b> — инструкция по установке для Android, iOS, Windows, Mac\n" +
			"• <b>Указать имя</b> — задать имя, чтобы администратор знал, чей это аккаунт\n" +
			"• Напишите любое сообщение в чат бота, чтобы связаться с администратором."
	}
	b.sendMessage(chatID, help)
}

func (b *Bot) sendToUser(userID int64, text string) error {
	msg := tgbotapi.NewMessage(userID, text)
	msg.ParseMode = "HTML"
	if _, err := b.api.Send(msg); err != nil {
		msg.ParseMode = ""
		if _, err2 := b.api.Send(msg); err2 != nil {
			log.Printf("Failed to send to user %d: %v", userID, err2)
			errStr := err2.Error()
			if strings.Contains(errStr, "blocked") || strings.Contains(errStr, "deactivated") {
				b.storage.Delete(userID)
			}
			return err2
		}
	}
	return nil
}

func (b *Bot) SendMessage(chatID int64, text string) error {
	msg := tgbotapi.NewMessage(chatID, text)
	msg.ParseMode = "HTML"
	_, err := b.api.Send(msg)
	return err
}

func (b *Bot) sendMessage(chatID int64, text string) {
	_, _ = b.sendMessageWithKeyboard(chatID, text, true)
}

func (b *Bot) sendMessageEx(chatID int64, text string) (tgbotapi.Message, error) {
	return b.sendMessageWithKeyboard(chatID, text, true)
}

func (b *Bot) notifyOwner(text string) {
	msg := tgbotapi.NewMessage(b.config.OwnerID, text)
	_, _ = b.api.Send(msg)
}

func (b *Bot) sendOrEditMessage(chatID int64, messageID int, text string, markup *tgbotapi.InlineKeyboardMarkup) {
	if messageID > 0 {
		edit := tgbotapi.NewEditMessageText(chatID, messageID, text)
		edit.ParseMode = "HTML"
		if markup != nil {
			edit.ReplyMarkup = markup
		}
		if _, err := b.api.Send(edit); err == nil {
			return
		}
	}

	msg := tgbotapi.NewMessage(chatID, text)
	msg.ParseMode = "HTML"
	if markup != nil {
		msg.ReplyMarkup = markup
	}
	_, _ = b.api.Send(msg)
}

func (b *Bot) sendMessageWithKeyboard(chatID int64, text string, withUserKeyboard bool) (tgbotapi.Message, error) {
	msg := tgbotapi.NewMessage(chatID, text)
	msg.ParseMode = "HTML"
	if chatID == b.config.OwnerID {
		msg.ReplyMarkup = b.getOwnerKeyboard()
	} else if withUserKeyboard {
		msg.ReplyMarkup = b.getUserKeyboard()
	}
	sent, err := b.api.Send(msg)
	if err != nil {
		msg.ParseMode = ""
		sent, err = b.api.Send(msg)
		if err != nil {
			log.Printf("Failed to send message to %d: %v", chatID, err)
		}
	}
	return sent, err
}

func (b *Bot) getUserKeyboard() tgbotapi.ReplyKeyboardMarkup {
	kb := tgbotapi.NewReplyKeyboard(
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnMySubscription),
			tgbotapi.NewKeyboardButton(BtnMyKeys),
		),
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnSetupGuide),
			tgbotapi.NewKeyboardButton(BtnHelp),
		),
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnMyName),
			tgbotapi.NewKeyboardButton(BtnSetName),
		),
	)
	kb.ResizeKeyboard = true
	return kb
}

func (b *Bot) getOwnerKeyboard() tgbotapi.ReplyKeyboardMarkup {
	kb := tgbotapi.NewReplyKeyboard(
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnMenuUsers),
			tgbotapi.NewKeyboardButton(BtnMenuBroadcast),
		),
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnMenuPanel),
			tgbotapi.NewKeyboardButton(BtnCancel),
		),
	)
	kb.ResizeKeyboard = true
	return kb
}

func formatTraffic(bytes int64) string {
	if bytes <= 0 {
		return "0 B"
	}
	if bytes < 1024 {
		return fmt.Sprintf("%d B", bytes)
	}
	units := []string{"B", "KB", "MB", "GB", "TB", "PB", "EB"}
	b := float64(bytes)
	idx := 0
	for b >= 1024 && idx < len(units)-1 {
		b /= 1024
		idx++
	}
	return fmt.Sprintf("%.2f %s", b, units[idx])
}
