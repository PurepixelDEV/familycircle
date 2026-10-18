import { lazy, Suspense, useState } from "react";
import { api, ApiError, type Family, type Place } from "../api/client";
import { usePlaces } from "../hooks/usePlaces";
import { useI18n } from "../i18n";
import { useApp } from "../state";
import { Switch, toast, useConfirm, useErrorText } from "./ui";

// The editor contains a MapLibre map; load it only when someone adds or edits a place.
const PlaceEditor = lazy(() => import("./PlaceEditor").then((m) => ({ default: m.PlaceEditor })));

/** Places of the family: who is there, alerts on/off per place, add/edit/delete. */
export function PlacesSection({ family }: { family: Family }) {
  const { t } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const { ask, element: confirmDialog } = useConfirm();
  const { places, error, reload } = usePlaces(family.id);
  const [editing, setEditing] = useState<Place | "new" | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const names = new Map(family.members.map((m) => [m.user_id, m.user_id === app.me.id ? t("common.you") : m.display_name]));
  const canEdit = (p: Place) => family.role === "admin" || p.created_by === app.me.id;

  const act = async (key: string, fn: () => Promise<unknown>, done?: string) => {
    setBusy(key);
    try {
      await fn();
      if (done) toast(done);
    } catch (e) {
      toast(e instanceof ApiError && e.status === 403 ? t("places.forbidden") : errorText(e));
    } finally {
      setBusy(null);
      reload();
    }
  };

  return (
    <section className="section" aria-labelledby="places-h">
      <h2 id="places-h">{t("places.title")}</h2>
      <p className="small muted">{t("places.intro")}</p>
      {places && places.length > 0 && (
        <div className="panel">
          {places.map((p) => {
            const present = p.present.map((id) => names.get(id)).filter((n): n is string => !!n);
            return (
              <div className="place-row" key={p.id}>
                <div className="list-row">
                  <span className="place-dot" aria-hidden="true" />
                  <div className="grow">
                    <div className="name">{p.name}</div>
                    <div className="small muted">
                      {t("places.radius", { m: p.radius_m })} · {present.length > 0 ? t("places.present", { names: present.join(", ") }) : t("places.nobody")}
                    </div>
                  </div>
                </div>
                <Switch
                  title={t("places.mute")}
                  help={t("places.muteHelp")}
                  checked={!p.muted}
                  disabled={busy === "mute:" + p.id}
                  onChange={(on) => void act("mute:" + p.id, () => api.setPlaceMuted(p.id, !on))}
                />
                {canEdit(p) && (
                  <div className="row-actions">
                    <button className="btn small secondary" type="button" onClick={() => setEditing(p)}>
                      {t("places.edit")}
                    </button>
                    <button
                      className="btn small danger"
                      type="button"
                      disabled={busy === "del:" + p.id}
                      onClick={async () => {
                        if (await ask(t("places.deleteConfirm", { name: p.name }), t("places.delete")))
                          void act("del:" + p.id, () => api.deletePlace(p.id), t("places.deleted"));
                      }}
                    >
                      {t("places.delete")}
                    </button>
                  </div>
                )}
                {editing !== null && editing !== "new" && editing.id === p.id && (
                  <Suspense fallback={<p className="muted">{t("common.loading")}</p>}>
                    <PlaceEditor
                      familyId={family.id}
                      place={p}
                      onDone={(saved) => {
                        setEditing(null);
                        if (saved) reload();
                      }}
                    />
                  </Suspense>
                )}
              </div>
            );
          })}
        </div>
      )}
      {places && places.length === 0 && <p className="muted">{t("places.empty")}</p>}
      {!places && error != null && <p className="notice error">{errorText(error)}</p>}
      {editing === "new" ? (
        <Suspense fallback={<p className="muted">{t("common.loading")}</p>}>
          <PlaceEditor
            familyId={family.id}
            place={null}
            onDone={(saved) => {
              setEditing(null);
              if (saved) reload();
            }}
          />
        </Suspense>
      ) : (
        <button className="btn secondary" type="button" style={{ justifySelf: "start" }} onClick={() => setEditing("new")}>
          + {t("places.add")}
        </button>
      )}
      {confirmDialog}
    </section>
  );
}
