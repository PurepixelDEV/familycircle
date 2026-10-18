import { useCallback, useEffect, useState } from "react";
import { api, type Place } from "../api/client";
import { subscribeLive } from "../api/live";

const POLL_MS = 60_000;

/**
 * Places of a family, refetched when the server announces a change (`places_changed`), when someone
 * arrives or leaves (presence changed), after a reconnect and once a minute as a safety net.
 */
export function usePlaces(familyId: string | null) {
  const [state, setState] = useState<{ familyId: string | null; places: Place[] | null; error: unknown }>({ familyId: null, places: null, error: null });
  const [token, setToken] = useState(0);
  const reload = useCallback(() => setToken((n) => n + 1), []);

  useEffect(() => {
    if (!familyId) return;
    let cancelled = false;
    api.places(familyId).then(
      ({ places }) => {
        if (!cancelled) setState({ familyId, places, error: null });
      },
      (error: unknown) => {
        if (!cancelled) setState((s) => (s.familyId === familyId ? { ...s, error } : { familyId, places: null, error }));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [familyId, token]);

  useEffect(() => {
    if (!familyId) return;
    const unsubscribe = subscribeLive({
      onOpen: reload,
      onEvent: (ev) => {
        if (ev.type === "places_changed" && ev.family_id === familyId) reload();
        if (ev.type === "alert" && (ev.alert.kind === "arrival" || ev.alert.kind === "departure")) reload();
      },
    });
    const id = setInterval(reload, POLL_MS);
    return () => {
      unsubscribe();
      clearInterval(id);
    };
  }, [familyId, reload]);

  const current = state.familyId === familyId ? state : { places: null, error: null };
  return { places: current.places, error: current.error, reload };
}
