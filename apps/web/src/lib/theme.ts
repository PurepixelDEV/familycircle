export type ThemePref = "system" | "light" | "dark";

export function loadTheme(): ThemePref {
  try {
    const v = localStorage.getItem("fc.theme");
    if (v === "light" || v === "dark") return v;
  } catch {
    // storage unavailable
  }
  return "system";
}

export function applyTheme(pref: ThemePref) {
  const root = document.documentElement;
  if (pref === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", pref);
  try {
    if (pref === "system") localStorage.removeItem("fc.theme");
    else localStorage.setItem("fc.theme", pref);
  } catch {
    // ignore
  }
}

/** Whether the page currently renders dark (used to pick the map style variant). */
export function isDark(): boolean {
  const attr = document.documentElement.getAttribute("data-theme");
  if (attr) return attr === "dark";
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}
