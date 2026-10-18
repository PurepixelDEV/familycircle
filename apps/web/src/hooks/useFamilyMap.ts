import { useCallback, useEffect, useEffectEvent, useState } from "react";
import { api, type FamilyMap } from "../api/client";
import { subscribeLive, type LiveStatus } from "../api/live";
import { applyLiveEvent } from "../lib/mapState";
import { useNow } from "../lib/useNow";

const POLL_CONNECTED_MS = 60_000;
const POLL_DISCONNECTED_MS = 20_000;

type State = { familyId: string | null; map: FamilyMap | null; error: unknown };

/**
 * Loads the family map and keeps it current via WebSocket, with polling as a safety net.
 * `now` ticks so that ages and freshness are re-evaluated while the page stays open.
 */
export function useFamilyMap(familyId: string | null, onFamilyChanged?: () => void) {
  const [state, setState] = useState<State>({ familyId: null, map: null, error: null });
  const [status, setStatus] = useState<LiveStatus>("connecting");
  const [reloadToken, setReloadToken] = useState(0);
  const now = useNow(15_000);
  const reload = useCallback(() => setReloadToken((n) => n + 1), []);
  const familyChanged = useEffectEvent(() => onFamilyChanged?.());

  useEffect(() => {
    if (!familyId) return;
    let cancelled = false;
    api.familyMap(familyId).then(
      (map) => {
        if (!cancelled) setState({ familyId, map, error: null });
      },
      (error: unknown) => {
        if (!cancelled) setState((s) => (s.familyId === familyId ? { ...s, error } : { familyId, map: null, error }));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [familyId, reloadToken]);

  useEffect(() => {
    if (!familyId) return;
    return subscribeLive({
      onStatus: setStatus,
      onOpen: reload, // refetch whatever we missed while disconnected
      onEvent: (ev) => {
        if (ev.type === "family_changed") familyChanged();
        setState((s) => {
          if (!s.map || s.familyId !== familyId) return s;
          const { map, effect } = applyLiveEvent(s.map, ev);
          if (effect === "refetch") queueMicrotask(reload);
          return map === s.map ? s : { ...s, map };
        });
      },
    });
  }, [familyId, reload]);

  useEffect(() => {
    const id = setInterval(reload, status === "open" ? POLL_CONNECTED_MS : POLL_DISCONNECTED_MS);
    return () => clearInterval(id);
  }, [status, reload]);

  const current = state.familyId === familyId ? state : { map: null, error: null };
  return { map: current.map, error: current.error, status, now, reload };
}
