import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { ApiError, avatarUrl } from "../api/client";
import { useI18n } from "../i18n";

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "")).toUpperCase();
}

export function Avatar(props: { userId: string; name: string; color: string; hasAvatar: boolean; version?: number | null; stale?: boolean; size?: number }) {
  const [broken, setBroken] = useState(false);
  const size = props.size ?? 44;
  return (
    <span
      className={"avatar" + (props.stale ? " stale" : "")}
      style={{ ["--ring" as string]: props.color, width: size, height: size, fontSize: size * 0.38 }}
      aria-hidden="true"
    >
      {props.hasAvatar && !broken ? (
        <img src={avatarUrl(props.userId, props.version)} alt="" onError={() => setBroken(true)} />
      ) : (
        initials(props.name)
      )}
    </span>
  );
}

export function Field(props: { label: string; help?: string; error?: string; children: (id: string, describedBy?: string) => ReactNode }) {
  const id = useId();
  const helpId = props.help || props.error ? id + "-help" : undefined;
  return (
    <div className="field">
      <label htmlFor={id}>{props.label}</label>
      {props.children(id, helpId)}
      {props.error ? (
        <span id={helpId} className="error" role="alert">
          {props.error}
        </span>
      ) : props.help ? (
        <span id={helpId} className="help">
          {props.help}
        </span>
      ) : null}
    </div>
  );
}

/** Password field with a show/hide toggle (instead of a second "repeat password" field, NIST SP 800-63B). */
export function PasswordInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  const [visible, setVisible] = useState(false);
  const { t } = useI18n();
  return (
    <div className="pw-wrap">
      <input {...props} type={visible ? "text" : "password"} />
      <button
        type="button"
        className="pw-toggle"
        aria-label={visible ? t("auth.hidePassword") : t("auth.showPassword")}
        aria-pressed={visible}
        onClick={() => setVisible((v) => !v)}
      >
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
          <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
          <circle cx="12" cy="12" r="3" />
          {visible && <path d="M3 3l18 18" />}
        </svg>
      </button>
    </div>
  );
}

export function Switch(props: { title: string; help?: string; checked: boolean; disabled?: boolean; onChange: (v: boolean) => void }) {
  const id = useId();
  return (
    <div className="switch-row">
      <span className="title" id={id}>
        {props.title}
      </span>
      <button
        type="button"
        role="switch"
        className="switch"
        aria-checked={props.checked}
        aria-labelledby={id}
        aria-describedby={props.help ? id + "-h" : undefined}
        disabled={props.disabled}
        onClick={() => props.onChange(!props.checked)}
      />
      {props.help && (
        <span className="help" id={id + "-h"}>
          {props.help}
        </span>
      )}
    </div>
  );
}

/** Native <dialog> based confirmation. Resolves the promise returned by `ask`. */
export function useConfirm() {
  const [state, setState] = useState<{ text: string; action: string; danger?: boolean; resolve: (ok: boolean) => void } | null>(null);
  const ref = useRef<HTMLDialogElement>(null);
  const { t } = useI18n();
  useEffect(() => {
    if (state) ref.current?.showModal();
  }, [state]);
  const close = (ok: boolean) => {
    ref.current?.close();
    state?.resolve(ok);
    setState(null);
  };
  const ask = (text: string, action: string, danger = true) =>
    new Promise<boolean>((resolve) => setState({ text, action, danger, resolve }));
  const element = state ? (
    <dialog ref={ref} className="confirm" onCancel={() => close(false)}>
      <p>{state.text}</p>
      <div className="actions">
        <button className="btn secondary" type="button" onClick={() => close(false)} autoFocus>
          {t("common.cancel")}
        </button>
        <button className={"btn" + (state.danger ? " danger" : "")} type="button" onClick={() => close(true)}>
          {state.action}
        </button>
      </div>
    </dialog>
  ) : null;
  return { ask, element };
}

let toastSetter: ((msg: string | null) => void) | undefined;

export function toast(msg: string) {
  toastSetter?.(msg);
}

export function ToastHost() {
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => {
    toastSetter = setMsg;
    return () => {
      toastSetter = undefined;
    };
  }, []);
  useEffect(() => {
    if (!msg) return;
    const id = setTimeout(() => setMsg(null), 3500);
    return () => clearTimeout(id);
  }, [msg]);
  return msg ? (
    <div className="toast" role="status" aria-live="polite">
      {msg}
    </div>
  ) : null;
}

/** Maps an API error to a user-facing message. */
export function useErrorText() {
  const { t } = useI18n();
  return useCallback((err: unknown): string => {
    if (err instanceof ApiError) {
      if (err.code === "network") return t("common.errorNetwork");
      if (err.status === 429) return t("common.tooMany");
      if (err.code === "invalid_credentials") return t("auth.wrong");
      if (err.code === "invite_invalid") return t("auth.inviteInvalid");
      if (err.code === "email_taken") return t("auth.emailTaken");
      if (err.code === "invalid_setup_token") return t("setup.codeWrong");
      if (err.code === "last_admin") return t("family.lastAdmin");
    }
    return t("common.error");
  }, [t]);
}

export function Logo({ size = 28 }: { size?: number }) {
  // Three overlapping circles: people sharing a common centre.
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <circle cx="16" cy="11" r="7" fill="none" stroke="var(--brand)" strokeWidth="2.5" />
      <circle cx="10.5" cy="20" r="7" fill="none" stroke="var(--brand)" strokeWidth="2.5" opacity="0.75" />
      <circle cx="21.5" cy="20" r="7" fill="none" stroke="var(--brand)" strokeWidth="2.5" opacity="0.5" />
    </svg>
  );
}
