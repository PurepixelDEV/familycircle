import { useState, type FormEvent } from "react";
import { api, ApiError, type Instance, type Me } from "../api/client";
import { useI18n } from "../i18n";
import { navigate, queryParam } from "../lib/router";
import { Field, Logo, PasswordInput, useErrorText } from "../components/ui";
import { AppHandoff } from "../components/AppHandoff";
import { inviteUrl } from "../components/QrCode";

function AuthFrame(props: { title: string; intro: string; insecure: boolean; children: React.ReactNode }) {
  const { t } = useI18n();
  return (
    <div className="auth">
      <div className="auth-card">
        <div className="auth-mark">
          <Logo size={44} /> {t("app.name")}
        </div>
        <h1>{props.title}</h1>
        <p className="muted">{props.intro}</p>
        {props.insecure && <p className="notice">{t("auth.insecure")}</p>}
        {props.children}
      </div>
    </div>
  );
}

function useSubmit(onDone: (me: Me) => void) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [fields, setFields] = useState<Record<string, string>>({});
  const errorText = useErrorText();
  const run = async (fn: () => Promise<Me>) => {
    setBusy(true);
    setError(undefined);
    setFields({});
    try {
      onDone(await fn());
    } catch (err) {
      if (err instanceof ApiError && err.code === "invalid_input" && Object.keys(err.fields).length) setFields(err.fields);
      else setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };
  return { busy, error, fields, run };
}

export function SetupView({ instance, onDone }: { instance: Instance; onDone: (me: Me) => void }) {
  const { t, locale } = useI18n();
  const { busy, error, fields, run } = useSubmit(onDone);
  const [v, setV] = useState({ setup_token: "", display_name: "", email: "", password: "", family_name: "" });
  const set = (k: keyof typeof v) => (e: React.ChangeEvent<HTMLInputElement>) => setV({ ...v, [k]: e.target.value });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(() => api.setup({ ...v, locale }));
  };
  return (
    <AuthFrame title={t("setup.title")} intro={t("setup.intro")} insecure={!instance.secure_context && location.protocol !== "https:"}>
      <form onSubmit={submit} noValidate>
        <Field label={t("setup.code")} help={t("setup.codeHelp")} error={fields.setup_token}>
          {(id, d) => <input id={id} aria-describedby={d} className="input code" autoComplete="off" required value={v.setup_token} onChange={set("setup_token")} />}
        </Field>
        <Field label={t("auth.name")} error={fields.display_name}>
          {(id, d) => <input id={id} aria-describedby={d} className="input" autoComplete="name" required value={v.display_name} onChange={set("display_name")} />}
        </Field>
        <Field label={t("auth.email")} error={fields.email}>
          {(id, d) => <input id={id} aria-describedby={d} className="input" type="email" autoComplete="email" required value={v.email} onChange={set("email")} />}
        </Field>
        <Field label={t("auth.password")} help={t("auth.passwordHelp")} error={fields.password}>
          {(id, d) => <PasswordInput id={id} aria-describedby={d} className="input" autoComplete="new-password" required minLength={10} value={v.password} onChange={set("password")} />}
        </Field>
        <Field label={t("setup.family")} error={fields.family_name}>
          {(id, d) => <input id={id} aria-describedby={d} className="input" placeholder={t("setup.familyPlaceholder")} required value={v.family_name} onChange={set("family_name")} />}
        </Field>
        {error && <p className="notice error" role="alert">{error}</p>}
        <button className="btn block" disabled={busy}>
          {t("setup.submit")}
        </button>
      </form>
    </AuthFrame>
  );
}

export function LoginView({ instance, onDone }: { instance: Instance; onDone: (me: Me) => void }) {
  const { t } = useI18n();
  const { busy, error, run } = useSubmit(onDone);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(() => api.login(email, password));
  };
  return (
    <AuthFrame title={t("auth.loginTitle")} intro={t("auth.loginIntro")} insecure={!instance.secure_context && location.protocol !== "https:"}>
      <form onSubmit={submit}>
        <Field label={t("auth.email")}>
          {(id) => <input id={id} className="input" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />}
        </Field>
        <Field label={t("auth.password")}>
          {(id) => <PasswordInput id={id} className="input" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}
        </Field>
        {error && <p className="notice error" role="alert">{error}</p>}
        <button className="btn block" disabled={busy}>
          {t("auth.loginSubmit")}
        </button>
      </form>
      <button className="btn ghost" type="button" onClick={() => navigate("/register")}>
        {t("auth.toRegister")}
      </button>
    </AuthFrame>
  );
}

export function RegisterView({ instance, onDone }: { instance: Instance; onDone: (me: Me) => void }) {
  const { t, locale } = useI18n();
  const { busy, error, fields, run } = useSubmit(onDone);
  const linkCode = queryParam("code");
  const [v, setV] = useState({ invite_code: linkCode ?? "", display_name: "", email: "", password: "" });
  const set = (k: keyof typeof v) => (e: React.ChangeEvent<HTMLInputElement>) => setV({ ...v, [k]: e.target.value });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(() => api.register({ ...v, locale }));
  };
  return (
    <AuthFrame title={t("auth.registerTitle")} intro={t("auth.registerIntro")} insecure={!instance.secure_context && location.protocol !== "https:"}>
      {linkCode && (
        <>
          <AppHandoff url={inviteUrl(location.origin, linkCode)} version={instance.version} />
          <p className="muted">{t("applink.web")}</p>
        </>
      )}
      <form onSubmit={submit} noValidate>
        <Field label={t("auth.inviteCode")} error={fields.invite_code}>
          {(id, d) => <input id={id} aria-describedby={d} className="input code" autoComplete="off" required value={v.invite_code} onChange={set("invite_code")} />}
        </Field>
        <Field label={t("auth.name")} error={fields.display_name}>
          {(id, d) => <input id={id} aria-describedby={d} className="input" autoComplete="name" required value={v.display_name} onChange={set("display_name")} />}
        </Field>
        <Field label={t("auth.email")} error={fields.email}>
          {(id, d) => <input id={id} aria-describedby={d} className="input" type="email" autoComplete="email" required value={v.email} onChange={set("email")} />}
        </Field>
        <Field label={t("auth.password")} help={t("auth.passwordHelp")} error={fields.password}>
          {(id, d) => <PasswordInput id={id} aria-describedby={d} className="input" autoComplete="new-password" required value={v.password} onChange={set("password")} />}
        </Field>
        {error && <p className="notice error" role="alert">{error}</p>}
        <button className="btn block" disabled={busy}>
          {t("auth.registerSubmit")}
        </button>
      </form>
      <button className="btn ghost" type="button" onClick={() => navigate("/login")}>
        {t("auth.toLogin")}
      </button>
    </AuthFrame>
  );
}
