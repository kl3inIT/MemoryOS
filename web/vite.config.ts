import { fileURLToPath, URL } from "node:url";
import { sentryVitePlugin } from "@sentry/vite-plugin";
import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { readdirSync, readFileSync } from "node:fs";
import { defineConfig, type Plugin, type ProxyOptions } from "vite";

const apiTarget = process.env.MEMORYOS_API_URL ?? "http://127.0.0.1:18080";
const sentryBuildConfiguration = {
  authToken: process.env.SENTRY_AUTH_TOKEN,
  org: process.env.SENTRY_ORG,
  project: process.env.SENTRY_PROJECT,
  release: process.env.SENTRY_RELEASE,
};
const sentrySourceMapsEnabled = Object.values(sentryBuildConfiguration).every(Boolean);
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

/**
 * pdf.js loads its image decoders from `wasmUrl` by fixed file names. They are emitted under a directory named for
 * the pdf.js release, so the immutable asset cache never mixes decoders from another release.
 */
function pdfjsDecoders(): Plugin {
  const root = fileURLToPath(new URL("./node_modules/pdfjs-dist/", import.meta.url));
  const version = (JSON.parse(readFileSync(`${root}package.json`, "utf8")) as { version: string })
    .version;
  const directory = `assets/pdfjs-${version}/`;
  const files = readdirSync(`${root}wasm`).filter((name) =>
    /^(jbig2|openjpeg)[\w-]*\.(wasm|js)$/.test(name),
  );
  const read = (name: string) => readFileSync(`${root}wasm/${name}`);
  return {
    name: "memoryos-pdfjs-decoders",
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const path = request.url?.split("?")[0] ?? "";
        const name = path.startsWith(`/${directory}`) ? path.slice(directory.length + 1) : "";
        if (!files.includes(name)) return next();
        response.setHeader(
          "Content-Type",
          name.endsWith(".wasm") ? "application/wasm" : "text/javascript",
        );
        response.end(read(name));
      });
    },
    generateBundle() {
      for (const name of files) {
        this.emitFile({ type: "asset", fileName: `${directory}${name}`, source: read(name) });
      }
    },
  };
}

export default defineConfig({
  plugins: [
    pdfjsDecoders(),
    tailwindcss(),
    tanstackRouter({
      target: "react",
      autoCodeSplitting: true,
    }),
    react(),
    sentryVitePlugin({
      authToken: sentryBuildConfiguration.authToken,
      org: sentryBuildConfiguration.org,
      project: sentryBuildConfiguration.project,
      disable: !sentrySourceMapsEnabled,
      telemetry: false,
      release: {
        name: sentryBuildConfiguration.release,
        setCommits: false,
      },
      sourcemaps: {
        assets: "./dist/**",
        filesToDeleteAfterUpload: "./dist/**/*.map",
      },
    }),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  // The PDF evidence view is lazy-loaded; pre-bundle it so the dev server does not discover it
  // mid-session and reload open pages.
  optimizeDeps: {
    include: ["react-pdf"],
  },
  build: {
    assetsInlineLimit: 0,
    sourcemap: sentrySourceMapsEnabled ? "hidden" : false,
  },
  server: {
    host: "127.0.0.1",
    port: 8080,
    strictPort: true,
    proxy: {
      "/api": { ...apiProxy },
      "/actuator": { ...apiProxy },
      "/invite": { ...apiProxy },
      "/login/oauth2": { ...apiProxy },
      "/logout": { ...apiProxy },
      "/oauth2": { ...apiProxy },
    },
  },
});
