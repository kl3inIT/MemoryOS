import { createRequire } from "module";
const require = createRequire("C:/Users/Admin/AppData/Local/nvm/v26.5.1/node_modules/@figwright/mcp/package.json");
const { encode, decode } = require("@msgpack/msgpack");

async function callTool(toolName, args = {}) {
  const rpc = {
    requestId: "req-" + Date.now(),
    toolName,
    args,
    buildId: 0
  };
  const body = Buffer.from(encode(rpc));
  const res = await fetch("http://127.0.0.1:3055/rpc", {
    method: "POST",
    headers: { "content-type": "application/msgpack" },
    body
  });
  const buf = new Uint8Array(await res.arrayBuffer());
  const decoded = decode(buf);
  console.log(JSON.stringify(decoded, null, 2));
}

callTool("scan_nodes_by_types", { types: ["FRAME"] });
