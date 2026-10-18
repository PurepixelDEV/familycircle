// WebSocket check for the HTTPS proxy test: device login, wss connect with bearer token, expect "hello";
// then a browser-like connect from a foreign Origin must be rejected.
// Usage: node scripts/ws-check.mjs https://localhost:9443 <email> <password>
// (self-signed test certificate: run with NODE_TLS_REJECT_UNAUTHORIZED=0)
const [base, email, password] = process.argv.slice(2);

const res = await fetch(`${base}/api/v1/auth/token`, {
  method: "POST",
  headers: { "Content-Type": "application/json", "X-FC-CSRF": "1" },
  body: JSON.stringify({ grant_type: "password", email, password, device_name: "ws-check" }),
});
if (!res.ok) throw new Error(`token: ${res.status} ${await res.text()}`);
const { access_token } = await res.json();
const wsUrl = base.replace(/^http/, "ws") + "/api/v1/ws";

function open(headers) {
  return new Promise((resolve) => {
    const ws = new WebSocket(wsUrl, { headers });
    const timer = setTimeout(() => resolve({ ok: false, reason: "timeout" }), 5000);
    ws.onmessage = (m) => {
      clearTimeout(timer);
      ws.close();
      resolve({ ok: true, msg: JSON.parse(String(m.data)) });
    };
    ws.onerror = () => {
      clearTimeout(timer);
      resolve({ ok: false, reason: "rejected" });
    };
  });
}

const good = await open({ Authorization: `Bearer ${access_token}` });
if (!good.ok || good.msg.type !== "hello") throw new Error(`wss via proxy failed: ${JSON.stringify(good)}`);
console.log("  ✓ wss through the proxy: hello received");

const evil = await open({ Authorization: `Bearer ${access_token}`, Origin: "https://evil.example" });
if (evil.ok) throw new Error("cross-origin WebSocket was accepted");
console.log("  ✓ cross-origin WebSocket rejected");
