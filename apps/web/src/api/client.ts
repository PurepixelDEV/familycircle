import type { components } from "./schema";

export type Schemas = components["schemas"];
export type Me = Schemas["Me"];
export type Instance = Schemas["Instance"];
export type Settings = Schemas["Settings"];
export type FamilySummary = Schemas["FamilySummary"];
export type Family = Schemas["Family"];
export type Member = Schemas["Member"];
export type FamilyMap = Schemas["FamilyMap"];
export type MapMember = Schemas["MapMember"];
export type Location = Schemas["Location"];
export type Invite = Schemas["Invite"];
export type InviteCreated = Schemas["InviteCreated"];
export type Shares = Schemas["Shares"];
export type Session = Schemas["Session"];
export type Device = Schemas["Device"];

/** Error returned by the API, with the stable machine-readable code from the Error schema. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fields: Record<string, string> = {},
  ) {
    super(message);
  }
}

type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

let onUnauthorized: (() => void) | undefined;

/** Registers a callback for 401 responses on authenticated calls (session expired or revoked). */
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

async function request<T>(method: Method, path: string, body?: unknown, opts: { auth?: boolean; raw?: BodyInit; contentType?: string } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET") headers["X-FC-CSRF"] = "1";
  let payload: BodyInit | undefined;
  if (opts.raw !== undefined) {
    payload = opts.raw;
    if (opts.contentType) headers["Content-Type"] = opts.contentType;
  } else if (body !== undefined) {
    payload = JSON.stringify(body);
    headers["Content-Type"] = "application/json";
  }
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, { method, headers, body: payload, credentials: "same-origin" });
  } catch {
    throw new ApiError(0, "network", "network error");
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const data = text ? safeJSON(text) : undefined;
  if (!res.ok) {
    const err = (data as { error?: { code?: string; message?: string; fields?: Record<string, string> } } | undefined)?.error;
    // Only an invalid session signs the user out – not e.g. a wrong password on re-authentication.
    if (res.status === 401 && err?.code === "unauthorized" && opts.auth !== false) onUnauthorized?.();
    throw new ApiError(res.status, err?.code ?? "http_" + res.status, err?.message ?? res.statusText, err?.fields ?? {});
  }
  return data as T;
}

function safeJSON(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export const api = {
  instance: () => request<Instance>("GET", "/instance", undefined, { auth: false }),
  setup: (b: Schemas["SetupRequest"]) => request<Me>("POST", "/setup", b, { auth: false }),
  register: (b: Schemas["RegisterRequest"]) => request<Me>("POST", "/auth/register", b, { auth: false }),
  login: (email: string, password: string) => request<Me>("POST", "/auth/session", { email, password }, { auth: false }),
  logout: () => request<void>("DELETE", "/auth/session"),

  me: () => request<Me>("GET", "/me"),
  updateMe: (b: { display_name?: string; locale?: "de" | "en" }) => request<Me>("PATCH", "/me", b),
  deleteMe: (password: string) => request<void>("DELETE", "/me", { password }),
  changePassword: (current_password: string, new_password: string) =>
    request<void>("PUT", "/me/password", { current_password, new_password }),
  settings: () => request<Settings>("GET", "/me/settings"),
  updateSettings: (b: Schemas["SettingsUpdate"]) => request<Settings>("PATCH", "/me/settings", b),
  putAvatar: (file: Blob) => request<void>("PUT", "/me/avatar", undefined, { raw: file, contentType: file.type }),
  deleteAvatar: () => request<void>("DELETE", "/me/avatar"),
  sessions: () => request<{ sessions: Session[] }>("GET", "/me/sessions"),
  revokeSession: (id: string) => request<void>("DELETE", `/me/sessions/${id}`),
  devices: () => request<{ devices: Device[] }>("GET", "/me/devices"),
  deleteDevice: (id: string) => request<void>("DELETE", `/me/devices/${id}`),
  deleteMyLocations: () => request<void>("DELETE", "/me/locations"),

  families: () => request<{ families: FamilySummary[] }>("GET", "/families"),
  createFamily: (name: string) => request<Family>("POST", "/families", { name }),
  family: (id: string) => request<Family>("GET", `/families/${id}`),
  renameFamily: (id: string, name: string) => request<Family>("PATCH", `/families/${id}`, { name }),
  leaveFamily: (id: string) => request<void>("POST", `/families/${id}/leave`),
  setRole: (familyId: string, userId: string, role: "admin" | "member") =>
    request<void>("PATCH", `/families/${familyId}/members/${userId}`, { role }),
  removeMember: (familyId: string, userId: string) => request<void>("DELETE", `/families/${familyId}/members/${userId}`),
  familyMap: (id: string) => request<FamilyMap>("GET", `/families/${id}/map`),
  invites: (familyId: string) => request<{ invites: Invite[] }>("GET", `/families/${familyId}/invites`),
  createInvite: (familyId: string, b: { expires_in_hours?: number; max_uses?: number }) =>
    request<InviteCreated>("POST", `/families/${familyId}/invites`, b),
  revokeInvite: (familyId: string, inviteId: string) => request<void>("DELETE", `/families/${familyId}/invites/${inviteId}`),
  acceptInvite: (code: string) => request<FamilySummary>("POST", "/invites/accept", { code }),

  shares: () => request<Shares>("GET", "/shares"),
  grantShare: (userId: string) => request<void>("PUT", `/shares/${userId}`),
  revokeShare: (userId: string) => request<void>("DELETE", `/shares/${userId}`),
};

/** URL of a user's avatar; the version busts the browser cache when the picture changes. */
export function avatarUrl(userId: string, version?: number | null): string {
  return `/api/v1/users/${userId}/avatar${version ? `?v=${version}` : ""}`;
}
