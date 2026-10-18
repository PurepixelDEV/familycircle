import { render, screen } from "@testing-library/react";
import type { MapMember } from "../api/client";
import { I18nProvider } from "../i18n";
import { MemberRow } from "./MemberList";

const now = Date.parse("2026-10-18T12:00:00Z");

function member(over: Partial<MapMember>): MapMember {
  return { user_id: "b", display_name: "Bob", color: "#e0534a", has_avatar: false, is_self: false, location: null, ...over };
}

function renderRow(m: MapMember) {
  return render(
    <I18nProvider initial="de">
      <MemberRow m={m} now={now} selected={false} onSelect={() => undefined} />
    </I18nProvider>,
  );
}

describe("MemberRow", () => {
  it("marks an old position as outdated and never as live", () => {
    renderRow(
      member({
        location: {
          lat: 47, lon: 8, accuracy_m: 12, motion: "walking", freshness: "live",
          recorded_at: new Date(now - 3 * 3_600_000).toISOString(), received_at: new Date(now).toISOString(),
        },
      }),
    );
    expect(screen.getByText("Veraltet")).toBeInTheDocument();
    expect(screen.getByText("Letzte bekannte Position – nicht aktuell")).toBeInTheDocument();
    expect(screen.queryByText("Live")).not.toBeInTheDocument();
    expect(screen.queryByText("zu Fuss")).not.toBeInTheDocument(); // motion is meaningless for stale data
  });

  it("shows live details including battery", () => {
    renderRow(
      member({
        location: {
          lat: 47, lon: 8, accuracy_m: 12.4, motion: "walking", freshness: "live", battery_pct: 64, battery_charging: true,
          recorded_at: new Date(now - 20_000).toISOString(), received_at: new Date(now).toISOString(),
        },
      }),
    );
    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.getByText("Genauigkeit ± 12 m")).toBeInTheDocument();
    expect(screen.getByText("Akku 64 %, lädt")).toBeInTheDocument();
  });

  it("explains a missing location without guessing why", () => {
    renderRow(member({ location: null }));
    expect(screen.getByText("Kein Standort für dich sichtbar")).toBeInTheDocument();
    expect(screen.getByRole("button")).toBeDisabled();
  });
});
