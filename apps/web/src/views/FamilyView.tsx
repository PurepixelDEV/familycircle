import { useEffect, useState, type FormEvent } from "react";
import { api, type Family, type Invite, type InviteCreated, type Member } from "../api/client";
import { useApp } from "../state";
import { useI18n } from "../i18n";
import { Avatar, Field, Switch, toast, useConfirm, useErrorText } from "../components/ui";

const INVITE_HOURS = 72;

export function FamilyView() {
  const { t, locale } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const { ask, element: confirmDialog } = useConfirm();
  // Data is tagged with the family it belongs to, so switching families never shows stale data.
  const [data, setData] = useState<{ familyId: string; family: Family; invites: Invite[] } | null>(null);
  const [created, setCreated] = useState<{ familyId: string; invite: InviteCreated } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    const id = app.familyId;
    if (!id) return;
    let cancelled = false;
    api
      .family(id)
      .then((family) =>
        family.role === "admin" ? api.invites(id).then((r) => ({ family, invites: r.invites })) : { family, invites: [] as Invite[] },
      )
      .then(
        ({ family, invites }) => {
          if (!cancelled) setData({ familyId: id, family, invites });
        },
        (e: unknown) => {
          if (!cancelled) toast(errorText(e));
        },
      );
    return () => {
      cancelled = true;
    };
  }, [app.familyId, reloadToken, errorText]);

  const family = data?.familyId === app.familyId ? data.family : null;
  const invites = data?.familyId === app.familyId ? data.invites : [];
  const newInvite = created?.familyId === app.familyId ? created.invite : null;

  const act = async (key: string, fn: () => Promise<unknown>, done?: string) => {
    setBusy(key);
    try {
      await fn();
      if (done) toast(done);
      setReloadToken((n) => n + 1);
      app.reloadShares();
    } catch (e) {
      toast(errorText(e));
    } finally {
      setBusy(null);
    }
  };

  const toggleShare = (m: Member, on: boolean) =>
    act("share:" + m.user_id, () => (on ? api.grantShare(m.user_id) : api.revokeShare(m.user_id)));

  const isAdmin = family?.role === "admin";
  const others = family?.members.filter((m) => m.user_id !== app.me.id) ?? [];

  return (
    <div className="page">
      <div className="page-inner">
        <header className="section">
          <h1>{family?.name ?? t("family.title")}</h1>
          {app.families.length > 1 && (
            <Field label={t("family.switch")}>
              {(id) => (
                <select id={id} className="input" value={app.familyId ?? ""} onChange={(e) => app.setFamilyId(e.target.value)}>
                  {app.families.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.name}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          )}
        </header>

        <section className="section" aria-labelledby="members-h">
          <h2 id="members-h">{t("family.members")}</h2>
          <div className="panel">
            {others.map((m) => (
              <div key={m.user_id} className="list-row" style={{ flexWrap: "wrap" }}>
                <Avatar userId={m.user_id} name={m.display_name} color={m.color} hasAvatar={m.has_avatar} version={m.avatar_version} />
                <div className="grow">
                  <div className="name">{m.display_name}</div>
                  <div className="muted small">
                    {m.role === "admin" ? t("family.admin") : t("family.member")} · {m.shares_with_me ? t("family.sharesWithMe") : t("family.notSharingWithMe")}
                  </div>
                </div>
                <div style={{ flexBasis: "100%" }}>
                  <Switch
                    title={t("family.shareToggle")}
                    help={m.i_share_with ? t("family.shareToggleHelp", { name: m.display_name }) : undefined}
                    checked={m.i_share_with}
                    disabled={busy === "share:" + m.user_id}
                    onChange={(on) => void toggleShare(m, on)}
                  />
                  {isAdmin && (
                    <div style={{ display: "flex", gap: 8, flexWrap: "wrap", paddingBottom: 6 }}>
                      <button
                        className="btn small secondary"
                        type="button"
                        onClick={() => void act("role", () => api.setRole(family!.id, m.user_id, m.role === "admin" ? "member" : "admin"))}
                      >
                        {m.role === "admin" ? t("family.makeMember") : t("family.makeAdmin")}
                      </button>
                      <button
                        className="btn small danger"
                        type="button"
                        onClick={async () => {
                          if (await ask(t("family.removeConfirm", { name: m.display_name }), t("family.remove")))
                            void act("remove", () => api.removeMember(family!.id, m.user_id));
                        }}
                      >
                        {t("family.remove")}
                      </button>
                    </div>
                  )}
                </div>
              </div>
            ))}
            {family && others.length === 0 && <p className="muted" style={{ padding: "14px 0" }}>{t("map.empty")}</p>}
          </div>
        </section>

        {isAdmin && family && (
          <section className="section" aria-labelledby="invite-h">
            <h2 id="invite-h">{t("family.invite")}</h2>
            <div className="panel pad">
              <p className="muted">{t("family.inviteHelp", { hours: INVITE_HOURS })}</p>
              {newInvite && (
                <div className="section">
                  <div className="code-display" aria-live="polite">
                    {newInvite.code}
                  </div>
                  <p className="small muted">{t("family.inviteShown")}</p>
                  <button
                    className="btn secondary"
                    type="button"
                    onClick={() => {
                      const link = `${location.origin}/register?code=${encodeURIComponent(newInvite.code)}`;
                      navigator.clipboard?.writeText(`${newInvite.code}\n${link}`).then(() => toast(t("common.copied")), () => undefined);
                    }}
                  >
                    {t("common.copy")}
                  </button>
                </div>
              )}
              <button
                className="btn"
                type="button"
                disabled={busy === "invite"}
                onClick={() =>
                  void act("invite", async () => setCreated({ familyId: family.id, invite: await api.createInvite(family.id, { expires_in_hours: INVITE_HOURS, max_uses: 1 }) }))
                }
              >
                {t("family.inviteCreate")}
              </button>
              {invites.length > 0 && (
                <div>
                  <h3 style={{ marginTop: 6 }}>{t("family.invitesOpen")}</h3>
                  {invites.map((inv) => (
                    <div className="list-row" key={inv.id}>
                      <span className="grow small">
                        {t("family.inviteEnding", {
                          hint: inv.code_hint,
                          date: new Date(inv.expires_at).toLocaleString(locale, { dateStyle: "medium", timeStyle: "short" }),
                        })}
                      </span>
                      <button className="btn small secondary" type="button" onClick={() => void act("revoke", () => api.revokeInvite(family.id, inv.id))}>
                        {t("family.inviteRevoke")}
                      </button>
                    </div>
                  ))}
                </div>
              )}
              <p className="small muted">{t("family.adminNote")}</p>
            </div>
          </section>
        )}

        <JoinOrCreate />

        {family && (
          <section className="section">
            <button
              className="btn danger"
              type="button"
              onClick={async () => {
                if (await ask(t("family.leaveConfirm", { family: family.name }), t("family.leave"))) {
                  await act("leave", () => api.leaveFamily(family.id));
                  app.reloadFamilies();
                }
              }}
            >
              {t("family.leave")}
            </button>
          </section>
        )}
      </div>
      {confirmDialog}
    </div>
  );
}

function JoinOrCreate() {
  const { t } = useI18n();
  const app = useApp();
  const errorText = useErrorText();
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string>();

  const join = async (e: FormEvent) => {
    e.preventDefault();
    setError(undefined);
    try {
      const f = await api.acceptInvite(code);
      setCode("");
      toast(t("family.joined", { family: f.name }));
      app.reloadFamilies();
      app.setFamilyId(f.id);
    } catch (err) {
      setError(errorText(err));
    }
  };
  const create = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const f = await api.createFamily(name);
      setName("");
      app.reloadFamilies();
      app.setFamilyId(f.id);
    } catch (err) {
      toast(errorText(err));
    }
  };

  return (
    <section className="section">
      <form className="panel pad" onSubmit={join}>
        <Field label={t("family.join")} error={error}>
          {(id, d) => <input id={id} aria-describedby={d} className="input code" autoComplete="off" value={code} onChange={(e) => setCode(e.target.value)} required />}
        </Field>
        <button className="btn secondary" disabled={!code.trim()}>
          {t("family.join")}
        </button>
      </form>
      <form className="panel pad" onSubmit={create}>
        <Field label={t("family.createName")}>
          {(id) => <input id={id} className="input" value={name} onChange={(e) => setName(e.target.value)} required maxLength={64} />}
        </Field>
        <button className="btn secondary" disabled={!name.trim()}>
          {t("family.create")}
        </button>
      </form>
    </section>
  );
}
