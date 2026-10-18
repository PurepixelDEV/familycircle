import type { Location } from "./client";

export type LiveEvent =
  | { type: "hello"; user_id: string; server_time: string }
  | { type: "location"; user_id: string; location: Location }
  | { type: "location_hidden"; user_id: string }
  | { type: "family_changed"; family_id: string };

export type LiveStatus = "connecting" | "open" | "closed";

/**
 * Keeps a WebSocket to /api/v1/ws open with exponential backoff. `onOpen` fires on every
 * (re)connect so callers can refetch state they may have missed while disconnected.
 */
export function connectLive(handlers: {
  onEvent: (e: LiveEvent) => void;
  onStatus?: (s: LiveStatus) => void;
  onOpen?: () => void;
}): () => void {
  let ws: WebSocket | undefined;
  let stopped = false;
  let retry = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;

  const open = () => {
    if (stopped) return;
    handlers.onStatus?.("connecting");
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    ws = new WebSocket(`${proto}//${location.host}/api/v1/ws`);
    ws.onopen = () => {
      retry = 0;
      handlers.onStatus?.("open");
      handlers.onOpen?.();
    };
    ws.onmessage = (msg) => {
      try {
        handlers.onEvent(JSON.parse(String(msg.data)) as LiveEvent);
      } catch {
        // ignore malformed messages
      }
    };
    ws.onclose = () => {
      handlers.onStatus?.("closed");
      if (stopped) return;
      const delay = Math.min(30_000, 1000 * 2 ** retry) * (0.75 + Math.random() * 0.5);
      retry++;
      timer = setTimeout(open, delay);
    };
  };

  const onVisible = () => {
    if (document.visibilityState === "visible" && ws?.readyState === WebSocket.CLOSED) {
      clearTimeout(timer);
      retry = 0;
      open();
    }
  };
  document.addEventListener("visibilitychange", onVisible);
  open();

  return () => {
    stopped = true;
    clearTimeout(timer);
    document.removeEventListener("visibilitychange", onVisible);
    ws?.close();
  };
}
