import type { MapMember } from "../api/client";
import { useI18n } from "../i18n";
import { freshnessOf, relativeTime, type Freshness } from "../lib/time";
import { Avatar } from "./ui";

export function FreshnessBadge({ recordedAt, server, now }: { recordedAt: string; server?: Freshness; now: number }) {
  const { t } = useI18n();
  const f = freshnessOf(recordedAt, now, server);
  const label = f === "live" ? t("map.live") : f === "recent" ? t("map.recent") : t("map.stale");
  return <span className={"fresh " + f}>{label}</span>;
}

export function MemberRow({ m, now, selected, onSelect }: { m: MapMember; now: number; selected: boolean; onSelect: () => void }) {
  const { t, locale } = useI18n();
  const loc = m.location;
  const fresh = loc ? freshnessOf(loc.recorded_at, now, loc.freshness) : null;
  const name = m.is_self ? `${m.display_name} (${t("common.you")})` : m.display_name;
  return (
    <button className="member" type="button" aria-pressed={selected} onClick={onSelect} disabled={!loc} aria-label={loc ? t("map.focus", { name: m.display_name }) : undefined}>
      <Avatar userId={m.user_id} name={m.display_name} color={m.color} hasAvatar={m.has_avatar} version={m.avatar_version} stale={fresh === "stale"} />
      <span className="grow" style={{ minWidth: 0, flex: 1 }}>
        <span className="name" style={{ display: "block" }}>
          {name}
        </span>
        {loc ? (
          <span className="meta">
            <FreshnessBadge recordedAt={loc.recorded_at} server={loc.freshness} now={now} />
            <span>{relativeTime(loc.recorded_at, now, locale, t("time.justNow"))}</span>
            <span>{t("map.accuracy", { m: Math.round(loc.accuracy_m) })}</span>
            {loc.battery_pct != null && (
              <span>
                {t("map.battery", { p: loc.battery_pct })}
                {loc.battery_charging ? `, ${t("map.charging")}` : ""}
              </span>
            )}
            {loc.motion !== "unknown" && fresh !== "stale" && <span>{t(`map.motion.${loc.motion}`)}</span>}
            {fresh === "stale" && <span>{t("map.staleHint")}</span>}
          </span>
        ) : (
          <span className="meta">{m.is_self ? t("map.noFixSelf") : t("map.hidden")}</span>
        )}
      </span>
    </button>
  );
}
