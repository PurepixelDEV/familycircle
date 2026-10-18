import { useState } from "react";
import { api, ApiError, type RemoteRequest } from "../api/client";
import { useI18n } from "../i18n";
import { navigate } from "../lib/router";
import { useApp } from "../state";
import { CheckInButton } from "./CheckIn";
import { useConfirm, useErrorText } from "./ui";

/**
 * Remote actions for a family member: update location, ring, history – or, for myself, ring my
 * phones ("find my phone"), my history and the check-in. Whether another person allows ring/locate
 * is only known to the server; a 403 is explained instead of guessed in advance.
 */
export function MemberActions({ userId, name, isSelf }: { userId: string; name: string; isSelf: boolean }) {
  const { t, locale } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const { ask, element: confirmDialog } = useConfirm();
  const [busy, setBusy] = useState<"ring" | "locate" | null>(null);
  const [result, setResult] = useState<{ text: string; error: boolean } | null>(null);

  const canHistory = isSelf ? (app.settings?.history_enabled ?? false) : (app.shares?.incoming.some((s) => s.user_id === userId && s.history) ?? false);
  const time = (iso: string) => new Date(iso).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });

  const remote = async (kind: "ring" | "locate") => {
    if (kind === "ring" && !(await ask(isSelf ? t("actions.ringSelfConfirm") : t("actions.ringConfirm", { name }), t("actions.ringAction"), false))) return;
    setBusy(kind);
    setResult(null);
    try {
      const r: RemoteRequest = kind === "ring" ? await api.ring(userId) : await api.locate(userId);
      const vars = { name, time: time(r.expires_at) };
      const text =
        kind === "ring"
          ? isSelf
            ? t(r.online ? "actions.ringSelfOnline" : "actions.ringSelfQueued", vars)
            : t(r.online ? "actions.ringOnline" : "actions.ringQueued", vars)
          : t(r.online ? "actions.locateOnline" : "actions.locateQueued", vars);
      setResult({ text, error: false });
    } catch (e) {
      let text = errorText(e);
      if (e instanceof ApiError && e.status === 403) text = t(kind === "ring" ? "actions.ringForbidden" : "actions.locateForbidden", { name });
      else if (e instanceof ApiError && e.status === 404) text = t("actions.notFound", { name });
      setResult({ text, error: true });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="member-actions" role="group" aria-label={t("actions.group", { name })}>
      <div className="row">
        {isSelf && <CheckInButton />}
        {!isSelf && (
          <button className="btn small secondary" type="button" disabled={busy !== null} onClick={() => void remote("locate")}>
            {t("actions.locate")}
          </button>
        )}
        <button className="btn small secondary" type="button" disabled={busy !== null} onClick={() => void remote("ring")}>
          {isSelf ? t("actions.ringSelf") : t("actions.ring")}
        </button>
        {canHistory && (
          <button className="btn small secondary" type="button" onClick={() => navigate(`/history/${userId}`)}>
            {isSelf ? t("actions.historySelf") : t("actions.history")}
          </button>
        )}
      </div>
      <div aria-live="polite">
        {result && <p className={"notice small" + (result.error ? " error" : "")}>{result.text}</p>}
      </div>
      {confirmDialog}
    </div>
  );
}
