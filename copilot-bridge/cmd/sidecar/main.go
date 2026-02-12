package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/yourusername/intellij-copilot-plugin/copilot-bridge/internal/copilot"
	"github.com/yourusername/intellij-copilot-plugin/copilot-bridge/internal/server"
)

const (
	defaultPort = 0 // 0 = dynamic port allocation
)

func main() {
	port := flag.Int("port", defaultPort, "Server port (0 for dynamic allocation)")
	pluginCallbackURL := flag.String("callback", "", "Plugin callback URL for tool execution")
	debug := flag.Bool("debug", false, "Enable debug logging")
	mockMode := flag.Bool("mock", false, "Run in mock mode (for development/testing)")
	flag.Parse()

	if *debug {
		log.SetFlags(log.LstdFlags | log.Lshortfile)
	} else {
		log.SetFlags(log.LstdFlags)
	}

	log.Printf("Starting Copilot Sidecar...")
	log.Printf("Port: %d (0 = dynamic)", *port)
	log.Printf("Plugin callback URL: %s", *pluginCallbackURL)

	var srv *server.Server
	var err error

	if *mockMode {
		// Development mode: use mock client
		log.Printf("⚠️  Running in MOCK mode (for development only)")
		mockClient := copilot.NewMockClient()
		srv, err = server.NewWithClient(*port, *pluginCallbackURL, mockClient)
		if err != nil {
			log.Fatalf("Failed to create server with mock client: %v", err)
		}
	} else {
		// Production mode: SDK client will be created and initialized lazily on first use
		log.Printf("📡 Starting server (SDK will initialize on first request)...")
		srv, err = server.NewWithLazySDK(*port, *pluginCallbackURL)
		if err != nil {
			log.Fatalf("❌ Failed to create server: %v", err)
		}
	}

	if err := srv.Start(); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}

	log.Printf("Sidecar listening on http://localhost:%d", srv.Port())
	fmt.Printf("SIDECAR_PORT=%d\n", srv.Port()) // For plugin to parse
	
	if !*mockMode {
		log.Printf("✅ Server ready. SDK will initialize when needed.")
		log.Printf("💡 If not authenticated, run: gh auth login")
	}

	// Wait for interrupt signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutting down...")
	if err := srv.Shutdown(); err != nil {
		log.Printf("Error during shutdown: %v", err)
	}
}
