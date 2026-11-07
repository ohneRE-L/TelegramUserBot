package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	tgbotapi "github.com/go-telegram-bot-api/telegram-bot-api/v5"
)

type UserState int

const (
	StateStart UserState = iota
	StateWaitingForName
	StateWaitingForMsgSend
	StateWaitingForMedia
	StateWaitingForBroadcastMedia
	StateWaitingForRename
)

const (
	BtnUsersList     = "📋 Список пользователей"
	BtnSendMsg       = "✉️ Отправить сообщение"
	BtnSendAll       = "📢 Отправить всем"
	BtnSendMedia     = "📤 Отправить медиа"
	BtnSendMediaAll  = "🖼️ Рассылка медиа всем"
	BtnRemoveUser    = "🗑️ Удалить пользователя"
	BtnBanUser       = "🚫 Забанить"
	BtnUnbanUser     = "✅ Разбанить"
	BtnRenameUser    = "✏️ Переименовать"
	BtnCancel        = "❌ Отмена"
	BtnStatistics    = "📊 Статистика"
	BtnUserInfo      = "ℹ️ Информация о пользователе"
	BtnBackup        = "💾 Бэкап панели 3x-ui"
	BtnMenuUsers     = "👥 Пользователи"
	BtnMenuBroadcast = "📢 Рассылка"
	BtnMenuPanel     = "⚙️ Панель 3x-ui"
	BtnHelp          = "Помощь"
	BtnSetName       = "Указать имя"
	BtnMyName        = "Моё имя"
	BtnMyKeys        = "🔑 Мои ключи"
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
}

func NewBot(api *tgbotapi.BotAPI, cfg *Config, storage *Storage, xui *XuiClient) *Bot {
	b := &Bot{
		api:      api,
		config:   cfg,
		storage:  storage,
		xui:      xui,
		sessions: make(map[int64]*UserSession),
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
	// Delete commands on start
	delCmds := tgbotapi.NewDeleteMyCommands()
	if _, err := b.api.Request(delCmds); err != nil {
		log.Printf("Failed to delete bot commands: %v", err)
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

	switch text {
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
				b.sendToUser(session.TargetUserID, text)
				b.sendMessage(chatID, fmt.Sprintf("Сообщение отправлено пользователю %d", session.TargetUserID))
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
	case StateStart:
		fallthrough
	default:
		if text == "/start" {
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
					b.sendToUser(bestMatch.ID, directMsg)
					b.sendMessage(chatID, fmt.Sprintf("Сообщение отправлено пользователю %s", bestMatch.Name))
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
	prefix := fmt.Sprintf("📩 [От %s (%d)]: ", senderName, fromID)

	if msg.Text != "" {
		b.notifyOwner(prefix + msg.Text)
	} else {
		fwd := tgbotapi.NewForward(b.config.OwnerID, msg.Chat.ID, msg.MessageID)
		_, _ = b.api.Send(fwd)
		b.notifyOwner(prefix + "отправил(а) медиа.")
	}
}

func (b *Bot) handleSendMedia(msg *tgbotapi.Message, targetUserID int64) {
	if len(msg.Photo) > 0 {
		highest := msg.Photo[len(msg.Photo)-1]
		photo := tgbotapi.NewPhoto(targetUserID, tgbotapi.FileID(highest.FileID))
		photo.Caption = msg.Caption
		if _, err := b.api.Send(photo); err != nil {
			b.sendMessage(msg.Chat.ID, "Ошибка отправки фото: "+err.Error())
			return
		}
	} else if msg.Document != nil {
		doc := tgbotapi.NewDocument(targetUserID, tgbotapi.FileID(msg.Document.FileID))
		doc.Caption = msg.Caption
		if _, err := b.api.Send(doc); err != nil {
			b.sendMessage(msg.Chat.ID, "Ошибка отправки документа: "+err.Error())
			return
		}
	}
	b.sendMessage(msg.Chat.ID, fmt.Sprintf("Медиа отправлено пользователю %d", targetUserID))
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
		var sb strings.Builder
		sb.WriteString("🔑 <b>Ваши доступы:</b>\n\n")
		if subURL != "" {
			sb.WriteString(fmt.Sprintf("🔗 <b>Ссылка на подписку:</b>\n<code>%s</code>\n\n", subURL))
		}
		if len(links) > 0 {
			sb.WriteString("🛡 <b>Прямые ключи:</b>\n")
			for _, l := range links {
				sb.WriteString(fmt.Sprintf("<code>%s</code>\n\n", l))
			}
		}
		b.sendMessage(chatID, strings.TrimSpace(sb.String()))
	} else {
		b.sendMessage(chatID, "❌ Ключи и подписка не найдены или неактивны.")
	}
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
		u, ok := b.storage.Get(uid)
		if !ok {
			b.sendOrEditMessage(chatID, msgID, "Пользователь не найден.", nil)
			return
		}

		trafficInfo := ""
		clientLink := ""
		if b.xui != nil && u.XuiUsername != "" {
			up, down, err := b.xui.GetClientTraffic(u.XuiUsername)
			if err == nil {
				trafficInfo = fmt.Sprintf("\nТрафик: ↑%s ↓%s", formatTraffic(up), formatTraffic(down))
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
			expiry = time.UnixMilli(u.ExpiryDate).Format("02.01.2006 15:04:05")
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
			),
			tgbotapi.NewInlineKeyboardRow(
				tgbotapi.NewInlineKeyboardButtonData("🔙 В меню", "menu_info"),
			),
		)
		b.sendOrEditMessage(chatID, msgID, uInfo, &markup)
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
			tgbotapi.NewInlineKeyboardButtonData("ℹ️ Информация", "menu_info"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("✏️ Переименовать", "menu_rename"),
			tgbotapi.NewInlineKeyboardButtonData("🗑️ Удалить", "menu_remove"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("🚫 Забанить", "menu_ban"),
			tgbotapi.NewInlineKeyboardButtonData("✅ Разбанить", "menu_unban"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("📊 Статистика", "menu_stats"),
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
			tgbotapi.NewInlineKeyboardButtonData("🔄 Синхронизация", "menu_sync"),
		),
		tgbotapi.NewInlineKeyboardRow(
			tgbotapi.NewInlineKeyboardButtonData("💾 Бэкап", "menu_backup"),
		),
	)
	b.sendOrEditMessage(chatID, messageID, "⚙️ Панель 3x-ui", &markup)
}

func (b *Bot) sendWelcomeMessage(chatID int64) {
	welcome := "👋 Добро пожаловать!\nЯ бот для рассылки уведомлений. Используйте кнопки или /help для справки."
	b.sendMessageWithKeyboard(chatID, welcome, true)
}

func (b *Bot) sendHelp(chatID int64, isOwner bool) {
	var help string
	if isOwner {
		help = "👑 Команды владельца:\n" +
			"• Список пользователей — просмотр всех\n" +
			"• Отправить сообщение — выбор через меню\n" +
			"• Отправить всем — рассылка текстового сообщения\n" +
			"• Отправить медиа — рассылка фото/файлов\n" +
			"• Удалить пользователя — исключение из базы"
	} else {
		help = "📖 Доступные кнопки:\n" +
			"• Указать имя — задать ваше имя для владельца\n" +
			"• Моё имя — посмотреть текущее имя\n" +
			"• Помощь — эта справка"
	}
	b.sendMessage(chatID, help)
}

func (b *Bot) sendToUser(userID int64, text string) {
	msg := tgbotapi.NewMessage(userID, text)
	msg.ParseMode = "HTML"
	if _, err := b.api.Send(msg); err != nil {
		log.Printf("Failed to send to user %d: %v", userID, err)
		errStr := err.Error()
		if strings.Contains(errStr, "blocked") || strings.Contains(errStr, "deactivated") {
			b.storage.Delete(userID)
		}
	}
}

func (b *Bot) SendMessage(chatID int64, text string) error {
	msg := tgbotapi.NewMessage(chatID, text)
	msg.ParseMode = "HTML"
	_, err := b.api.Send(msg)
	return err
}

func (b *Bot) sendMessage(chatID int64, text string) {
	b.sendMessageWithKeyboard(chatID, text, false)
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

func (b *Bot) sendMessageWithKeyboard(chatID int64, text string, withUserKeyboard bool) {
	msg := tgbotapi.NewMessage(chatID, text)
	msg.ParseMode = "HTML"
	if chatID == b.config.OwnerID {
		msg.ReplyMarkup = b.getOwnerKeyboard()
	} else if withUserKeyboard {
		msg.ReplyMarkup = b.getUserKeyboard()
	}
	if _, err := b.api.Send(msg); err != nil {
		log.Printf("Failed to send message to %d: %v", chatID, err)
	}
}

func (b *Bot) getUserKeyboard() tgbotapi.ReplyKeyboardMarkup {
	return tgbotapi.NewReplyKeyboard(
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnHelp),
			tgbotapi.NewKeyboardButton(BtnSetName),
		),
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnMyName),
			tgbotapi.NewKeyboardButton(BtnMyKeys),
		),
	)
}

func (b *Bot) getOwnerKeyboard() tgbotapi.ReplyKeyboardMarkup {
	return tgbotapi.NewReplyKeyboard(
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnMenuUsers),
			tgbotapi.NewKeyboardButton(BtnMenuBroadcast),
		),
		tgbotapi.NewKeyboardButtonRow(
			tgbotapi.NewKeyboardButton(BtnMenuPanel),
			tgbotapi.NewKeyboardButton(BtnCancel),
		),
	)
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
