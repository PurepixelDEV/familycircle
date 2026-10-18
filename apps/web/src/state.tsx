import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, type FamilySummary, type Instance, type Me, type Settings, type Shares } from "./api/client";

type AppState = {
  instance: Instance;
  me: Me;
  setMe: (me: Me) => void;
  families: FamilySummary[];
  familyId: string | null;
  setFamilyId: (id: string) => void;
  reloadFamilies: () => void;
  settings: Settings | null;
  setSettings: (s: Settings) => void;
  shares: Shares | null;
  reloadShares: () => void;
  signOut: () => void;
};

const AppContext = createContext<AppState | null>(null);

function storedFamily(): string | null {
  try {
    return localStorage.getItem("fc.family");
  } catch {
    return null;
  }
}

export function AppProvider(props: { instance: Instance; me: Me; setMe: (me: Me) => void; onSignedOut: () => void; children: ReactNode }) {
  const [families, setFamilies] = useState<FamilySummary[]>([]);
  const [familyId, setFamilyIdState] = useState<string | null>(storedFamily());
  const [settings, setSettings] = useState<Settings | null>(null);
  const [shares, setShares] = useState<Shares | null>(null);

  const reloadFamilies = useCallback(() => {
    api.families().then(({ families }) => {
      setFamilies(families);
      setFamilyIdState((cur) => (cur && families.some((f) => f.id === cur) ? cur : (families[0]?.id ?? null)));
    }, () => undefined);
  }, []);
  const reloadShares = useCallback(() => {
    api.shares().then(setShares, () => undefined);
  }, []);

  useEffect(() => {
    reloadFamilies();
    reloadShares();
    api.settings().then(setSettings, () => undefined);
  }, [reloadFamilies, reloadShares]);

  const setFamilyId = useCallback((id: string) => {
    setFamilyIdState(id);
    try {
      localStorage.setItem("fc.family", id);
    } catch {
      // ignore
    }
  }, []);

  const { onSignedOut } = props;
  const signOut = useCallback(() => {
    api.logout().finally(onSignedOut);
  }, [onSignedOut]);

  const value = useMemo<AppState>(
    () => ({
      instance: props.instance, me: props.me, setMe: props.setMe,
      families, familyId, setFamilyId, reloadFamilies,
      settings, setSettings, shares, reloadShares, signOut,
    }),
    [props.instance, props.me, props.setMe, families, familyId, setFamilyId, reloadFamilies, settings, shares, reloadShares, signOut],
  );
  return <AppContext.Provider value={value}>{props.children}</AppContext.Provider>;
}

export function useApp(): AppState {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp outside AppProvider");
  return ctx;
}
