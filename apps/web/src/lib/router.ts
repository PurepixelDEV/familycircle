import { useSyncExternalStore } from "react";

// A deliberately tiny history router: the app has five screens.

const listeners = new Set<() => void>();

function subscribe(fn: () => void) {
  listeners.add(fn);
  window.addEventListener("popstate", fn);
  return () => {
    listeners.delete(fn);
    window.removeEventListener("popstate", fn);
  };
}

export function navigate(path: string, replace = false) {
  if (path === location.pathname + location.search) return;
  if (replace) history.replaceState(null, "", path);
  else history.pushState(null, "", path);
  listeners.forEach((fn) => fn());
}

export function usePath(): string {
  return useSyncExternalStore(subscribe, () => location.pathname);
}

export function queryParam(name: string): string | null {
  return new URLSearchParams(location.search).get(name);
}
