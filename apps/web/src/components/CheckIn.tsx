import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { api } from "../api/client";
import { useAlerts } from "../hooks/useAlerts";
import { useI18n } from "../i18n";
import { currentPosition, GeoError } from "../lib/geo";
import { CheckPinIcon } from "./icons";
import { toast, useErrorText } from "./ui";

export const CHECK_IN_MAX = 140;

/** "Bin angekommen" button that opens the check-in dialog. */
export function CheckInButton({ className = "btn small secondary" }: { className?: string }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className={className} type="button" onClick={() => setOpen(true)}>
        <CheckPinIcon />
        {t("checkin.button")}
      </button>
      {open && <CheckInDialog onClose={() => setOpen(false)} />}
    </>
  );
}

/** Check-in with the browser's current position (required) and an optional short message. */
export function CheckInDialog({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const alerts = useAlerts();
  const errorText = useErrorText();
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  const [message, setMessage] = useState("");
  const [phase, setPhase] = useState<"idle" | "locating" | "sending">("idle");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal?.();
    return () => d?.close?.();
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setPhase("locating");
    let pos;
    try {
      pos = await currentPosition({ timeoutMs: 15_000, maxAgeMs: 30_000, hardTimeoutMs: 30_000 });
    } catch (err) {
      setError(t(`geo.${err instanceof GeoError ? err.code : "unavailable"}`));
      setPhase("idle");
      return;
    }
    setPhase("sending");
    try {
      const text = message.trim();
      const res = await api.checkIn({ ...pos, ...(text ? { message: text } : {}) });
      alerts.add(res.alert);
      toast(t("checkin.sent", { n: res.recipients }));
      onClose();
    } catch (err) {
      setError(errorText(err));
      setPhase("idle");
    }
  };

  return (
    <dialog ref={ref} className="confirm sheet-dialog" aria-labelledby={id + "-t"} onCancel={onClose}>
      <form onSubmit={submit} style={{ display: "grid", gap: 14 }}>
        <h2 id={id + "-t"}>{t("checkin.title")}</h2>
        <p className="muted small">{t("checkin.intro")}</p>
        <div className="field">
          <label htmlFor={id + "-m"}>{t("checkin.message")}</label>
          <textarea
            id={id + "-m"}
            className="input"
            rows={3}
            maxLength={CHECK_IN_MAX}
            value={message}
            placeholder={t("checkin.placeholder")}
            aria-describedby={id + "-c"}
            onChange={(e) => setMessage(e.target.value)}
          />
          <span id={id + "-c"} className="help" style={{ justifySelf: "end" }}>
            {t("checkin.counter", { n: message.length, max: CHECK_IN_MAX })}
          </span>
        </div>
        {error && (
          <p className="notice error" role="alert">
            {error}
          </p>
        )}
        {phase !== "idle" && (
          <p className="muted small" role="status">
            {phase === "locating" ? t("checkin.locating") : t("common.sending")}
          </p>
        )}
        <div className="actions">
          <button className="btn secondary" type="button" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button className="btn" disabled={phase !== "idle"}>
            {t("checkin.send")}
          </button>
        </div>
      </form>
    </dialog>
  );
}
