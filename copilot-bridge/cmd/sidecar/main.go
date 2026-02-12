package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/yourusername/intellij-copilot-plugin/copilot-bridge/internal/server"
)

const (
	defaultPort = 0 // 0 = dynamic port allocation
)

func main() {
	port := flag.Int("port", defaultPort, "Server port (0 for dynamic allocation)")
	pluginCallbackURL := flag.String("callback", "", "Plugin callback URL for tool execution")
	debug := flag.Bool("debug", false, "Enable debug logging")
	flag.Parse()

	if *debug {
		log.SetFlags(log.LstdFlags | log.Lshortfile)
	} else {
		log.SetFlags(log.LstdFlags)
	}

	log.Printf("Starting Copilot Sidecar...")
	log.Printf("Port: %d (0 = dynamic)", *port)
	log.Printf("Plugin callback URL: %s", *pluginCallbackURL)

	// Create and start the RPC server
	srv, err := server.New(*port, *pluginCallbackURL)
	if err != nil {
		log.Fatalf("Failed to create server: %v", err)
	}

	if err := srv.Start(); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}

	log.Printf("Sidecar listening on http://localhost:%d", srv.Port())
	fmt.Printf("SIDECAR_PORT=%d\n", srv.Port()) // For plugin to parse

	// Wait for interrupt signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutting down...")
	if err := srv.Shutdown(); err != nil {
		log.Printf("Error during shutdown: %v", err)
	}
}
