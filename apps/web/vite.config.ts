/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development the API runs separately (docker compose -f compose.dev.yaml up -d);
// Vite proxies /api (incl. WebSocket) so cookies stay same-origin.
const apiTarget = process.env.FC_API_URL ?? "http://localhost:9092";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: apiTarget, ws: true, changeOrigin: false },
    },
  },
  build: {
    target: "es2022",
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["src/test/setup.ts"],
    css: false,
  },
});
