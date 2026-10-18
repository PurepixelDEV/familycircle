import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, type Device, type Session, type Settings } from "../api/client";
import { useApp } from "../state";
import { useI18n, type Locale } from "../i18n";
import { applyTheme, loadTheme, type ThemePref } from "../lib/theme";
import { relativeTime } from "../lib/time";
import { useNow } from "../lib/useNow";
import { Avatar, Field, Switch, toast, useConfirm, useErrorText } from "../components/ui";
import { ConnectPhone } from "./ConnectPhone";

const RETENTION_OPTIONS = [7, 30, 90, 365];

export function PrivacyView() {
  const { t } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const { ask, element: confirmDialog } = useConfirm();
  const s = app.settings;

  const update = async (patch: Partial<Settings>) => {
    try {
      app.setSettings(await api.updateSettings(patch));
    } catch (e) {
      toast(errorText(e));
    }
  };

  return (
    <div className="page">
      <div className="page-inner">
        <h1>{t("privacy.title")}</h1>

        {s && (
          <section className="section">
            <div className="panel">
              <Switch title={t("privacy.pause")} help={t("privacy.pauseHelp")} checked={s.sharing_paused} onChange={(v) => void update({ sharing_paused: v })} />
              <Switch
                title={t("privacy.history")}
                help={t("privacy.historyHelp")}
                checked={s.history_enabled}
                onChange={async (v) => {
                  if (!v && !(await ask(t("privacy.historyOffConfirm"), t("common.save")))) return;
                  void update({ history_enabled: v });
                }}
              />
              {s.history_enabled && (
                <div style={{ padding: "4px 0 14px" }}>
                  <Field label={t("privacy.retention")}>
                    {(id) => (
                      <select id={id} className="input" value={s.history_retention_days} onChange={(e) => void update({ history_retention_days: Number(e.target.value) })}>
                        {[...new Set([...RETENTION_OPTIONS, s.history_retention_days])].sort((a, b) => a - b).map((n) => (
                          <option key={n} value={n}>
                            {t("privacy.days", { n })}
                          </option>
                        ))}
                      </select>
                    )}
                  </Field>
                </div>
              )}
              <Switch title={t("privacy.battery")} help={t("privacy.batteryHelp")} checked={s.share_battery} onChange={(v) => void update({ share_battery: v })} />
            </div>
          </section>
        )}

        <ConnectPhone />
        <ProfileSection />
        <SessionsSection ask={ask} />
        <PasswordSection />

        <section className="section">
          <h2>{t("privacy.data")}</h2>
          <div className="panel pad">
            <a className="btn secondary" href="/api/v1/me/export" download>
              {t("privacy.export")}
            </a>
            <button
              className="btn danger"
              type="button"
              onClick={async () => {
                if (!(await ask(t("privacy.deleteLocationsConfirm"), t("privacy.deleteLocations")))) return;
                try {
                  await api.deleteMyLocations();
                  toast(t("privacy.deleted"));
                } catch (e) {
                  toast(errorText(e));
                }
              }}
            >
              {t("privacy.deleteLocations")}
            </button>
          </div>
        </section>

        <DeleteAccountSection />

        <button className="btn secondary" type="button" onClick={app.signOut}>
          {t("privacy.logout")}
        </button>
      </div>
      {confirmDialog}
    </div>
  );
}

function ProfileSection() {
  const { t, locale, setLocale } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const [name, setName] = useState(app.me.display_name);
  const [theme, setTheme] = useState<ThemePref>(loadTheme());
  const fileRef = useRef<HTMLInputElement>(null);

  const saveName = async (e: FormEvent) => {
    e.preventDefault();
    try {
      app.setMe(await api.updateMe({ display_name: name }));
      toast(t("privacy.saved"));
    } catch (err) {
      toast(errorText(err));
    }
  };
  const changeLocale = async (l: Locale) => {
    setLocale(l);
    try {
      app.setMe(await api.updateMe({ locale: l }));
    } catch {
      // the UI language still changes locally
    }
  };
  const upload = async (file: File) => {
    try {
      await api.putAvatar(file);
      app.setMe(await api.me());
    } catch (err) {
      toast(errorText(err));
    }
  };

  return (
    <section className="section">
      <h2>{t("privacy.profile")}</h2>
      <div className="panel pad">
        <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
          <Avatar userId={app.me.id} name={app.me.display_name} color="var(--brand)" hasAvatar={app.me.has_avatar} version={app.me.avatar_version} size={64} />
          <div style={{ display: "grid", gap: 6 }}>
            <button className="btn small secondary" type="button" onClick={() => fileRef.current?.click()}>
              {t("privacy.photo")}
            </button>
            {app.me.has_avatar && (
              <button
                className="btn small ghost"
                type="button"
                onClick={async () => {
                  await api.deleteAvatar().catch(() => undefined);
                  app.setMe(await api.me());
                }}
              >
                {t("privacy.photoRemove")}
              </button>
            )}
          </div>
          <input
            ref={fileRef}
            type="file"
            accept="image/jpeg,image/png"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) void upload(f);
              e.target.value = "";
            }}
          />
        </div>
        <p className="small muted">{t("privacy.photoHelp")}</p>
        <form onSubmit={saveName} style={{ display: "grid", gap: 10 }}>
          <Field label={t("auth.name")}>
            {(id) => <input id={id} className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={64} required />}
          </Field>
          <button className="btn secondary" disabled={name.trim() === app.me.display_name || !name.trim()}>
            {t("common.save")}
          </button>
        </form>
        <Field label={t("privacy.language")}>
          {(id) => (
            <select id={id} className="input" value={locale} onChange={(e) => void changeLocale(e.target.value as Locale)}>
              <option value="de">Deutsch</option>
              <option value="en">English</option>
            </select>
          )}
        </Field>
        <Field label={t("privacy.theme")}>
          {(id) => (
            <select
              id={id}
              className="input"
              value={theme}
              onChange={(e) => {
                const v = e.target.value as ThemePref;
                setTheme(v);
                applyTheme(v);
              }}
            >
              <option value="system">{t("privacy.themeSystem")}</option>
              <option value="light">{t("privacy.themeLight")}</option>
              <option value="dark">{t("privacy.themeDark")}</option>
            </select>
          )}
        </Field>
      </div>
    </section>
  );
}

function SessionsSection({ ask }: { ask: (text: string, action: string) => Promise<boolean> }) {
  const { t, locale } = useI18n();
  const errorText = useErrorText();
  const [sessions, setSessions] = useState<Session[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [reloadToken, setReloadToken] = useState(0);
  const now = useNow(30_000);
  const load = () => setReloadToken((n) => n + 1);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.sessions(), api.devices()]).then(
      ([s, d]) => {
        if (cancelled) return;
        setSessions(s.sessions);
        setDevices(d.devices);
      },
      (e: unknown) => {
        if (!cancelled) toast(errorText(e));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [reloadToken, errorText]);

  const webSessions = sessions.filter((s) => s.kind === "web");
  return (
    <section className="section">
      <h2>{t("privacy.security")}</h2>
      <div className="panel">
        {devices.map((d) => (
          <div className="list-row" key={d.id}>
            <div className="grow">
              <div className="name">{d.name}</div>
              <div className="small muted">{d.last_seen_at ? t("privacy.lastUsed", { when: relativeTime(d.last_seen_at, now, locale, t("time.justNow")) }) : ""}</div>
            </div>
            <button
              className="btn small danger"
              type="button"
              onClick={async () => {
                if (!(await ask(t("privacy.removeDeviceConfirm", { name: d.name }), t("privacy.removeDevice")))) return;
                await api.deleteDevice(d.id).catch((e) => toast(errorText(e)));
                load();
              }}
            >
              {t("privacy.removeDevice")}
            </button>
          </div>
        ))}
        {webSessions.map((s) => (
          <div className="list-row" key={s.id}>
            <div className="grow">
              <div className="name">{s.current ? t("privacy.thisBrowser") : t("privacy.browser")}</div>
              <div className="small muted" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {t("privacy.lastUsed", { when: relativeTime(s.last_used_at, now, locale, t("time.justNow")) })}
                {s.user_agent ? ` – ${s.user_agent}` : ""}
              </div>
            </div>
            {!s.current && (
              <button
                className="btn small secondary"
                type="button"
                onClick={async () => {
                  await api.revokeSession(s.id).catch((e) => toast(errorText(e)));
                  load();
                }}
              >
                {t("privacy.signOutSession")}
              </button>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}

function PasswordSection() {
  const { t } = useI18n();
  const errorText = useErrorText();
  const [cur, setCur] = useState("");
  const [next, setNext] = useState("");
  const [error, setError] = useState<string>();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(undefined);
    try {
      await api.changePassword(cur, next);
      setCur("");
      setNext("");
      toast(t("privacy.passwordChanged"));
    } catch (err) {
      setError(errorText(err));
    }
  };
  return (
    <section className="section">
      <h2>{t("privacy.password")}</h2>
      <form className="panel pad" onSubmit={submit}>
        <UsernameHint />
        <Field label={t("privacy.currentPassword")}>
          {(id) => <input id={id} className="input" type="password" autoComplete="current-password" value={cur} onChange={(e) => setCur(e.target.value)} required />}
        </Field>
        <Field label={t("privacy.newPassword")} help={t("auth.passwordHelp")} error={error}>
          {(id, d) => <input id={id} aria-describedby={d} className="input" type="password" autoComplete="new-password" minLength={10} value={next} onChange={(e) => setNext(e.target.value)} required />}
        </Field>
        <button className="btn secondary" disabled={!cur || next.length < 10}>
          {t("privacy.password")}
        </button>
      </form>
    </section>
  );
}

function DeleteAccountSection() {
  const { t } = useI18n();
  const errorText = useErrorText();
  const [pw, setPw] = useState("");
  const [error, setError] = useState<string>();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await api.deleteMe(pw);
      location.assign("/login");
    } catch (err) {
      setError(errorText(err));
    }
  };
  return (
    <section className="section">
      <h2>{t("privacy.deleteAccount")}</h2>
      <form className="panel pad" onSubmit={submit}>
        <p className="small muted">{t("privacy.deleteAccountHelp")}</p>
        <UsernameHint />
        <Field label={t("auth.password")} error={error}>
          {(id, d) => <input id={id} aria-describedby={d} className="input" type="password" autoComplete="current-password" value={pw} onChange={(e) => setPw(e.target.value)} required />}
        </Field>
        <button className="btn danger" disabled={!pw}>
          {t("privacy.deleteAccount")}
        </button>
      </form>
    </section>
  );
}

/** Hidden username so password managers know which account a password form belongs to. */
function UsernameHint() {
  const { me } = useApp();
  return <input type="email" name="username" autoComplete="username" value={me.email} readOnly hidden />;
}
