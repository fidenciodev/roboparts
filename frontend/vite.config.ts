import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { appShellWorker } from "./build/appShellWorker.ts";

export default defineConfig({
  plugins: [react(), tailwindcss(), appShellWorker()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
  preview: { host: "127.0.0.1", port: 4173 },
  test: {
    pool: "threads",
    maxWorkers: 1,
    environment: "jsdom",
    globalSetup: ["./src/test/globalSetup.mjs"],
    setupFiles: ["./src/test/setup.ts"],
    restoreMocks: true,
    clearMocks: true,
    unstubGlobals: true,
  },
});
