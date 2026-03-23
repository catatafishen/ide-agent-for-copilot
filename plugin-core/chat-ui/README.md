# Chat UI - TypeScript Source

This directory contains the **TypeScript source files** for the chat UI components.

## ⚠️ Important: Do NOT edit the generated JavaScript file!

The file `plugin-core/build/generated/resources/chat-ui/chat/chat-components.js` is **auto-generated** from these
TypeScript sources. Any edits made directly to that file will be lost on the next build.

## Making Changes

1. **Edit the TypeScript files** in `plugin-core/chat-ui/src/`
2. **Build** by running:
   ```bash
   cd plugin-core/chat-ui
   npm run build
   ```
3. The changes will be compiled into `plugin-core/chat-ui/dist/chat-components.js` and then synced to the plugin
   resources by Gradle.

## Project Structure

```
plugin-core/chat-ui/
├── src/                         # TypeScript source files (EDIT THESE)
│   ├── index.ts                 # Entry point
│   ├── ...
├── build.js                     # Build script (adds header comment)
├── dist/                        # Generated JS (from esbuild)
├── package.json                 # npm dependencies and scripts
└── tsconfig.json                # TypeScript configuration

plugin-core/build/generated/resources/chat-ui/chat/
└── chat-components.js           # Final bundled output (synced by Gradle)
└── chat.css                     # Synced by Gradle
```

## Development

### Watch mode

For continuous compilation during development:

```bash
npm run build:watch
```

### Type checking

To check TypeScript types without building:

```bash
npm run typecheck
```

## For AI Agents

If you're an AI agent making changes to the chat UI:

- **NEVER** edit any files under `plugin-core/build/generated/resources/chat-ui/chat/` directly
- **ALWAYS** edit the TypeScript/CSS source files in `plugin-core/chat-ui/src/`
- **ALWAYS** run `./gradlew :plugin-core:buildChatUi` or a full build after making changes
- The generated files have a header comment warning against direct edits
