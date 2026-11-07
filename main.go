package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	tgbotapi "github.com/go-telegram-bot-api/telegram-bot-api/v5"
)

func main() {
	log.Println("Starting TelegramUserBot (Go edition)...")

	cfg, err := LoadConfig()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	if cfg.BotToken == "" || cfg.OwnerID == 0 {
		log.Fatalf("bot.token and owner.id are required in config.properties or environment!")
	}

	usersPath := "users.json"
	if _, err := os.Stat(usersPath); os.IsNotExist(err) {
		if execPath, err := os.Executable(); err == nil {
			p := filepath.Join(filepath.Dir(execPath), "users.json")
			if _, err := os.Stat(p); err == nil {
				usersPath = p
			}
		}
		if _, err := os.Stat(usersPath); os.IsNotExist(err) {
			if _, err := os.Stat("/bots/sendBot/users.json"); err == nil {
				usersPath = "/bots/sendBot/users.json"
			}
		}
	}

	storage := NewStorage(usersPath)
	if err := storage.Load(); err != nil {
		log.Printf("Warning: failed to load users.json: %v", err)
	}

	var xui *XuiClient
	if cfg.XuiPanelURL != "" && (cfg.XuiAPIToken != "" || (cfg.XuiPanelUsername != "" && cfg.XuiPanelPassword != "")) {
		xui = NewXuiClient(
			cfg.XuiPanelURL,
			cfg.XuiPanelUsername,
			cfg.XuiPanelPassword,
			cfg.XuiAPIToken,
			cfg.XuiSubPort,
			cfg.XuiSubPath,
			cfg.XuiInboundPort,
		)
		log.Printf("3x-ui integration initialized (panel: %s)", cfg.XuiPanelURL)
	} else {
		log.Printf("3x-ui configuration missing, expiry checking disabled")
	}

	api, err := tgbotapi.NewBotAPI(cfg.BotToken)
	if err != nil {
		log.Fatalf("Failed to initialize Telegram Bot API: %v", err)
	}

	bot := NewBot(api, cfg, storage, xui)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		<-ctx.Done()
		log.Println("Shutting down... saving users.json")
		if err := storage.Save(); err != nil {
			log.Printf("Error saving storage on shutdown: %v", err)
		}
	}()

	bot.Start(ctx)
	log.Println("Bot stopped gracefully.")
}
