import { beforeEach, describe, expect, it, vi } from "vitest";
import { getActivities, getCurrentUser, login, logout, register } from "./authService";
import { invalidateCsrf } from "./api";

const user = { id: "ab70c719-b047-4f31-97c0-d85280cda031", name: "Ana", email: "ana@example.com" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const token = (value = "masked-token") => json({ token: value, headerName: "X-CSRF-TOKEN" });
beforeEach(() => invalidateCsrf());

describe("Contrato de autenticação", () => {
  it("envia o login e renova o CSRF após a troca de sessão", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(token("anonymous"))
      .mockResolvedValueOnce(json({ ...user, passwordHash: "never-keep-this" }))
      .mockResolvedValueOnce(token("authenticated"));
    vi.stubGlobal("fetch", fetchMock);
    expect(await login(user.email, "valid-password-123")).toEqual(user);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/csrf", "/api/auth/login", "/api/auth/csrf"]);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ email: user.email, password: "valid-password-123" });
    expect(localStorage.length).toBe(0);
  });

  it("não desfaz um login confirmado quando a renovação do CSRF falha", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json(user))
      .mockRejectedValueOnce(new TypeError("offline")));
    expect(await login(user.email, "valid-password-123")).toEqual(user);
  });

  it("cadastra sem fazer login e nunca envia a confirmação de senha à API", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json(user, 201));
    vi.stubGlobal("fetch", fetchMock);
    const input = { name: user.name, email: user.email, password: "valid-password-123", accessCode: "team-code" };
    expect(await register(input)).toEqual(user);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/csrf", "/api/auth/register"]);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(input);
    expect(localStorage.length).toBe(0);
  });

  it("renova o token depois do logout confirmado", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(token("anonymous"));
    vi.stubGlobal("fetch", fetchMock);
    await expect(logout()).resolves.toBe("loggedOut");
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/csrf", "/api/auth/logout", "/api/auth/csrf"]);
  });

  it("considera um logout 401 uma sessão já expirada", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json({ code: "unauthenticated" }, 401))
      .mockResolvedValueOnce(token("anonymous")));
    await expect(logout()).resolves.toBe("expired");
  });

  it("confirma com GET que um logout 503 invalidou a sessão e não repete o POST", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json({ code: "service_unavailable" }, 503))
      .mockResolvedValueOnce(json({ code: "unauthenticated" }, 401)).mockResolvedValueOnce(token("anonymous"));
    vi.stubGlobal("fetch", fetchMock);
    await expect(logout()).resolves.toBe("loggedOut");
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/csrf", "/api/auth/logout", "/api/auth/me", "/api/auth/csrf"]);
  });

  it.each(["authenticated", "offline"])("preserva o erro de logout se não confirmar sessão encerrada (%s)", async (verification) => {
    const fetchMock = vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json({ code: "service_unavailable" }, 503));
    if (verification === "authenticated") fetchMock.mockResolvedValueOnce(json(user));
    else fetchMock.mockRejectedValueOnce(new TypeError("offline"));
    vi.stubGlobal("fetch", fetchMock);
    await expect(logout()).rejects.toMatchObject({ status: 503, code: "service_unavailable" });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("rejeita dados de usuário incompatíveis e atividades com data inválida", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(json({ ...user, id: null }))
      .mockResolvedValueOnce(json([{ id: "id", action: "USER_LOGGED_IN", createdAt: "not-a-date" }])));
    await expect(getCurrentUser()).rejects.toThrow("identificação esperada");
    await expect(getActivities()).rejects.toThrow("histórico de atividades esperado");
  });
});
