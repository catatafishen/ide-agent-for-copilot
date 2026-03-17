# Chat UI - TypeScript Source

This directory contains the **TypeScript source files** for the chat UI components.

## ⚠️ Important: Do NOT edit the generated JavaScript file!

The file `plugin-core/src/main/resources/chat/chat-components.js` is **auto-generated** from these TypeScript sources. Any edits made directly to that file will be lost on the next build.

## Making Changes

1. **Edit the TypeScript files** in `plugin-core/chat-ui/src/`
2. **Build** by running:
   ```bash
   cd plugin-core/chat-ui
   npm run build
   ```
3. The changes will be compiled into `plugin-core/src/main/resources/chat/chat-components.js`

## Project Structure

```
plugin-core/chat-ui/
├── src/                         # TypeScript source files (EDIT THESE)
│   ├── index.ts                 # Entry point
│   ├── ChatController.ts        # Main controller
│   ├── toolDisplayName.ts       # Tool display name mappings
│   ├── helpers.ts               # Utility functions
│   ├── types.ts                 # TypeScript types
│   └── components/              # UI components
│       ├── ChatContainer.ts
│       ├── ToolChip.ts
│       └── ...
├── build.js                     # Build script (adds header comment)
├── package.json                 # npm dependencies and scripts
└── tsconfig.json                # TypeScript configuration

plugin-core/src/main/resources/chat/
└── chat-components.js           # Generated output (DO NOT EDIT)
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
- **NEVER** edit `plugin-core/src/main/resources/chat/chat-components.js` directly
- **ALWAYS** edit the TypeScript source files in `plugin-core/chat-ui/src/`
- **ALWAYS** run `npm run build` after making changes
- The generated file has a header comment warning against direct edits
