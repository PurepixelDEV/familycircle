import { api, ApiError, setUnauthorizedHandler } from "./client";

function mockFetch(status: number, body?: unknown) {
  const fn = vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status }));
  vi.stubGlobal("fetch", fn);
  return fn;
}

afterEach(() => vi.unstubAllGlobals());

describe("api client", () => {
  it("sends the CSRF header on state-changing requests only", async () => {
    const f = mockFetch(200, { families: [] });
    await api.families();
    const getInit = (f.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect((getInit.headers as Record<string, string>)["X-FC-CSRF"]).toBeUndefined();

    const f2 = mockFetch(204);
    await api.revokeShare("u1");
    const [url, init] = f2.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/v1/shares/u1");
    expect(init.method).toBe("DELETE");
    expect((init.headers as Record<string, string>)["X-FC-CSRF"]).toBe("1");
  });

  it("maps API errors with code and field messages", async () => {
    mockFetch(400, { error: { code: "invalid_input", message: "invalid input", fields: { email: "invalid" } } });
    const err = await api.register({ invite_code: "x", email: "x", password: "y", display_name: "z" }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe("invalid_input");
    expect(err.fields.email).toBe("invalid");
  });

  it("signs out on an invalid session but not on a wrong password", async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    mockFetch(401, { error: { code: "invalid_credentials", message: "wrong" } });
    await api.deleteMe("wrong").catch(() => undefined);
    expect(handler).not.toHaveBeenCalled();

    mockFetch(401, { error: { code: "unauthorized", message: "expired" } });
    await api.me().catch(() => undefined);
    expect(handler).toHaveBeenCalledOnce();
  });

  it("reports network failures with a dedicated code", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new TypeError("offline"))));
    const err = await api.instance().catch((e) => e);
    expect(err.code).toBe("network");
  });
});
