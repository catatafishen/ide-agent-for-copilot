.PHONY: build clean test run

BINARY_NAME=copilot-sidecar
GOOS?=$(shell go env GOOS)
GOARCH?=$(shell go env GOARCH)

build:
	@echo "Building $(BINARY_NAME) for $(GOOS)/$(GOARCH)..."
	GOOS=$(GOOS) GOARCH=$(GOARCH) go build -o bin/$(BINARY_NAME)$(if $(filter windows,$(GOOS)),.exe,) ./cmd/sidecar

build-all: clean
	@echo "Building for all platforms..."
	@$(MAKE) build GOOS=windows GOARCH=amd64
	@$(MAKE) build GOOS=darwin GOARCH=amd64
	@$(MAKE) build GOOS=darwin GOARCH=arm64
	@$(MAKE) build GOOS=linux GOARCH=amd64

clean:
	@echo "Cleaning..."
	rm -rf bin/ dist/

test:
	go test -v ./...

run: build
	./bin/$(BINARY_NAME)$(if $(filter windows,$(shell go env GOOS)),.exe,)

deps:
	go mod download
	go mod tidy
