import { lazy, Suspense, useCallback, useEffect, useState } from "react";
import { api, setUnauthorizedHandler, type Instance, type Me } from "./api/client";
import { useI18n } from "./i18n";
import { navigate, usePath } from "./lib/router";
import { AppProvider } from "./state";
import { LoginView, RegisterView, SetupView } from "./views/Auth";
import { FamilyView } from "./views/FamilyView";
import { PrivacyView } from "./views/PrivacyView";
import { FamilyIcon, MapIcon, ShieldIcon } from "./components/icons";
import { ToastHost, useErrorText } from "./components/ui";

// MapLibre is large; load it only once the user is signed in.
const MapView = lazy(() => import("./views/MapView").then((m) => ({ default: m.MapView })));

type Boot = { state: "loading" } | { state: "error"; error: unknown } | { state: "ready"; instance: Instance; me: Me | null };

export function App() {
  const [boot, setBoot] = useState<Boot>({ state: "loading" });
  const { t, setLocale } = useI18n();
  const errorText = useErrorText();
  const path = usePath();

  const [attempt, setAttempt] = useState(0);
  const retry = () => {
    setBoot({ state: "loading" });
    setAttempt((n) => n + 1);
  };

  useEffect(() => {
    let cancelled = false;
    api
      .instance()
      .then(async (instance) => {
        const me = instance.setup_required ? null : await api.me().catch(() => null);
        if (cancelled) return;
        if (me) setLocale(me.locale);
        setBoot({ state: "ready", instance, me });
      })
      .catch((error: unknown) => {
        if (!cancelled) setBoot({ state: "error", error });
      });
    return () => {
      cancelled = true;
    };
  }, [attempt, setLocale]);

  const signedOut = useCallback(() => {
    setBoot((b) => (b.state === "ready" ? { ...b, me: null } : b));
    navigate("/login", true);
  }, []);

  useEffect(() => setUnauthorizedHandler(signedOut), [signedOut]);

  const signedIn = (me: Me) => {
    setLocale(me.locale);
    setBoot((b) => (b.state === "ready" ? { ...b, me, instance: { ...b.instance, setup_required: false } } : b));
    navigate("/", true);
  };

  if (boot.state === "loading") {
    return <p className="auth muted">{t("common.loading")}</p>;
  }
  if (boot.state === "error") {
    return (
      <div className="auth">
        <div className="auth-card">
          <p className="notice error">{errorText(boot.error)}</p>
          <button className="btn" onClick={retry}>
            {t("common.retry")}
          </button>
        </div>
      </div>
    );
  }

  const { instance, me } = boot;
  if (instance.setup_required) return <SetupView instance={instance} onDone={signedIn} />;
  if (!me) {
    return path === "/register" ? <RegisterView instance={instance} onDone={signedIn} /> : <LoginView instance={instance} onDone={signedIn} />;
  }

  return (
    <AppProvider instance={instance} me={me} setMe={(m) => setBoot({ ...boot, me: m })} onSignedOut={signedOut}>
      <Shell path={path} />
      <ToastHost />
    </AppProvider>
  );
}

function Shell({ path }: { path: string }) {
  const { t } = useI18n();
  const tabs = [
    { href: "/", label: t("nav.map"), icon: <MapIcon /> },
    { href: "/family", label: t("nav.family"), icon: <FamilyIcon /> },
    { href: "/privacy", label: t("nav.privacy"), icon: <ShieldIcon /> },
  ];
  const view = path === "/family" ? <FamilyView /> : path === "/privacy" ? <PrivacyView /> : <MapView />;
  return (
    <div className="shell">
      <main>
        <Suspense fallback={<p className="page muted">{t("common.loading")}</p>}>{view}</Suspense>
      </main>
      <nav className="tabbar" aria-label="FamilyCircle">
        {tabs.map((tab) => (
          <a
            key={tab.href}
            href={tab.href}
            aria-current={(tab.href === "/" ? !["/family", "/privacy"].includes(path) : path === tab.href) ? "page" : undefined}
            onClick={(e) => {
              e.preventDefault();
              navigate(tab.href);
            }}
          >
            {tab.icon}
            {tab.label}
          </a>
        ))}
      </nav>
    </div>
  );
}
