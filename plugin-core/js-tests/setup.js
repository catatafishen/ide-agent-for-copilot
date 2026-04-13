// Setup: load the web components into the happy-dom environment
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import vm from 'node:vm';

const bundlePath = resolve(__dirname, '../chat-ui/dist/chat-components.js');
const code = readFileSync(bundlePath, 'utf-8');

// Provide a minimal _bridge stub
globalThis._bridge = {
    openFile: () => {
    },
    openUrl: () => {
    },
    setCursor: () => {
    },
    loadMore: () => {
    },
    quickReply: () => {
    },
    openScratch: () => {
    },
    showToolPopup: () => {
    },
};

// Use vm.Script with a filename so V8 coverage can associate the code with a URL
// and follow the inline sourcemaps back to the TypeScript sources.
// The filename must point to the dist/ directory because esbuild generates
// sourcemap source paths relative to the output file location.
const script = new vm.Script(code, {
    filename: resolve(__dirname, '../chat-ui/dist/chat-components.js'),
});
script.runInThisContext();
