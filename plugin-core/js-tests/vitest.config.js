import {defineConfig} from 'vitest/config';

export default defineConfig({
    test: {
        environment: 'happy-dom',
        globals: true,
        setupFiles: ['./setup.js'],
        coverage: {
            provider: 'v8',
            // lcov for Codecov upload; text-summary for CI log
            reporter: ['lcov', 'text-summary'],
            reportsDirectory: './coverage',
            // Tests import TypeScript source directly (via setup.js → index.ts).
            // V8 tracks coverage per-file through Vitest's module transforms.
            include: ['../chat-ui/src/**/*.ts'],
            exclude: ['**/*.d.ts'],
        },
    },
});
