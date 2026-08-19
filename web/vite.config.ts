import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type ProxyOptions } from "vite";

const apiTarget = process.env.MEMORYOS_API_URL ?? "http://127.0.0.1:18080";
const apiProxy: ProxyOptions = {
  target: apiTarget,
  changeOrigin: false,
  xfwd: true,
  // Production keeps Secure cookies; loopback-only Vite removes the flag at its local HTTP boundary.
  configure(proxy) {
    proxy.on("proxyRes", (proxyResponse) => {
      const cookies = proxyResponse.headers["set-cookie"];
      if (cookies) {
        proxyResponse.headers["set-cookie"] = cookies.map((cookie) =>
          cookie.replace(/;\s*Secure\b/gi, ""),
        );
      }
    });
  },
};

export default defineConfig({
  plugins: [
    tailwindcss(),
    tanstackRouter({
      target: "react",
      autoCodeSplitting: true,
    }),
    react(),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    host: "127.0.0.1",
    port: 8080,
    strictPort: true,
    proxy: {
      "/api": { ...apiProxy },
      "/actuator": { ...apiProxy },
      "/login/oauth2": { ...apiProxy },
      "/logout": { ...apiProxy },
      "/oauth2": { ...apiProxy },
    },
  },
});
