export default {
  input: "../openapi.yml",
  output: "src/lib/hey-api",
  plugins: [
    "@hey-api/client-fetch",
    "@hey-api/typescript",
    "@hey-api/sdk",
    "@tanstack/react-query",
  ],
};
