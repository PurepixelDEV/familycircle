import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { de, type MessageKey } from "./de";
import { en } from "./en";

export type Locale = "de" | "en";

const catalogs: Record<Locale, Record<MessageKey, string>> = { de, en };

export function detectLocale(): Locale {
  try {
    const stored = localStorage.getItem("fc.locale");
    if (stored === "de" || stored === "en") return stored;
  } catch {
    // storage unavailable (private mode) – fall through
  }
  return navigator.language.toLowerCase().startsWith("de") ? "de" : "en";
}

export function format(template: string, vars?: Record<string, string | number>): string {
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m));
}

type Ctx = {
  locale: Locale;
  setLocale: (l: Locale) => void;
  t: (key: MessageKey, vars?: Record<string, string | number>) => string;
};

const I18nContext = createContext<Ctx | null>(null);

export function I18nProvider({ children, initial }: { children: ReactNode; initial?: Locale }) {
  const [locale, setLocaleState] = useState<Locale>(initial ?? detectLocale());
  const setLocale = useCallback((l: Locale) => {
    setLocaleState(l);
    document.documentElement.lang = l;
    try {
      localStorage.setItem("fc.locale", l);
    } catch {
      // ignore
    }
  }, []);
  const t = useCallback((key: MessageKey, vars?: Record<string, string | number>) => format(catalogs[locale][key], vars), [locale]);
  const value = useMemo(() => ({ locale, setLocale, t }), [locale, setLocale, t]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): Ctx {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error("useI18n outside I18nProvider");
  return ctx;
}
