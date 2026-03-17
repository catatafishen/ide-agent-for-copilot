import * as esbuild from 'esbuild';
import * as fs from 'fs';

const banner = `/*
 * ⚠️  AUTO-GENERATED FILE - DO NOT EDIT DIRECTLY!
 *
 * This file is built from TypeScript sources in plugin-core/chat-ui/src/
 *
 * To make changes:
 *   1. Edit the TypeScript files in plugin-core/chat-ui/src/
 *   2. Run: cd plugin-core/chat-ui && npm run build
 *   3. The changes will be compiled into this file
 *
 * See plugin-core/chat-ui/README.md for more information.
 */

`;

async function build() {
    await esbuild.build({
        entryPoints: ['src/index.ts'],
        bundle: true,
        format: 'iife',
        globalName: '__chatUI',
        outfile: '../src/main/resources/chat/chat-components.js',
        target: 'es2022',
    });

    // Prepend banner to the output file
    const outputPath = '../src/main/resources/chat/chat-components.js';
    const content = fs.readFileSync(outputPath, 'utf8');
    fs.writeFileSync(outputPath, banner + content);

    console.log('✓ Built with header comment');
}

build().catch(() => process.exit(1));
