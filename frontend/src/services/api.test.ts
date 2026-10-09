import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiGet, apiRequest, invalidateCsrf, onSessionExpired } from "./api";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const token = (value = "masked-token") => json({ token: value, headerName: "X-CSRF-TOKEN" });

beforeEach(() => invalidateCsrf());

describe("Solicitações autenticadas e proteção CSRF", () => {
  it("inclui cookies em GET e prepara o CSRF antes de um POST", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ready: true }))
      .mockResolvedValueOnce(token())
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await apiGet("/api/system/status");
    await apiRequest("/api/auth/logout", { method: "POST" });
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      "/api/system/status", "/api/auth/csrf", "/api/auth/logout",
    ]);
    for (const [, options] of fetchMock.mock.calls) {
      expect(options).toMatchObject({ credentials: "include", cache: "no-store" });
    }
    expect(fetchMock.mock.calls[2][1]).toMatchObject({
      method: "POST", headers: { "X-CSRF-TOKEN": "masked-token" },
    });
  });

  it("compartilha a consulta de CSRF entre operações simultâneas", async () => {
    let resolveToken!: (response: Response) => void;
    const fetchMock = vi.fn().mockImplementation((path: string) => path === "/api/auth/csrf"
      ? new Promise<Response>((resolve) => { resolveToken = resolve; })
      : Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal("fetch", fetchMock);

    const first = apiRequest("/api/first", { method: "POST" });
    const second = apiRequest("/api/second", { method: "POST" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    resolveToken(token());
    await Promise.all([first, second]);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/csrf", "/api/first", "/api/second"]);
  });

  it("não envia a operação se o servidor devolver um CSRF inválido", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ token: "unsafe", headerName: "Authorization" }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(apiRequest("/api/auth/login", { method: "POST" })).rejects.toThrow("solicitação segura");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("não repete automaticamente um POST recusado e renova o token na próxima tentativa manual", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(token("old-token"))
      .mockResolvedValueOnce(json({ code: "csrf_invalid" }, 403))
      .mockResolvedValueOnce(token("new-token"))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(apiRequest("/api/auth/logout", { method: "POST" })).rejects.toMatchObject({ code: "csrf_invalid" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await apiRequest("/api/auth/logout", { method: "POST" });
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(fetchMock.mock.calls[3][1].headers).toMatchObject({ "X-CSRF-TOKEN": "new-token" });
  });

  it("informa sessão expirada em uma API privada e permite silenciar o 401 de login", async () => {
    const expired = vi.fn();
    const unsubscribe = onSessionExpired(expired);
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(json({ code: "unauthenticated" }, 401))));
    try {
      await expect(apiGet("/api/auth/activities")).rejects.toMatchObject({ status: 401 });
      expect(expired).toHaveBeenCalledTimes(1);
      await expect(apiRequest("/api/auth/me", { notifyUnauthorized: false })).rejects.toMatchObject({ status: 401 });
      expect(expired).toHaveBeenCalledTimes(1);
    } finally { unsubscribe(); }
  });

  it("traduz problemas de validação sem expor detalhes internos ou campos desconhecidos", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      code: "validation_error", detail: "secret-database-details",
      errors: { name: "secret-name-details", password: "secret-password-details", internal: "secret" },
    }, 400)));
    const error = await apiGet("/api/example").catch((cause: unknown) => cause);
    expect(error).toMatchObject({ code: "validation_error", message: "Confira os campos indicados e tente novamente." });
    expect(error).toHaveProperty("fields", {
      name: "Informe um nome válido, com 2 a 100 caracteres.", password: "Use uma senha de 12 a 128 caracteres.",
    });
  });

  it("não exibe conteúdo HTML nem mensagens de rede internas", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response("<html>internal secret</html>", { status: 502 }))
      .mockRejectedValueOnce(new TypeError("secret-network-details"));
    vi.stubGlobal("fetch", fetchMock);
    await expect(apiGet("/api/example")).rejects.toMatchObject({ status: 502, message: expect.stringContaining("HTTP 502") });
    await expect(apiGet("/api/example")).rejects.toThrow("Não foi possível acessar o backend");
  });

  it("cancelar uma operação enquanto o CSRF carrega não cancela outra operação nem envia o POST cancelado", async () => {
    let resolveToken!: (response: Response) => void;
    const fetchMock = vi.fn().mockImplementation((path: string) => path === "/api/auth/csrf"
      ? new Promise<Response>((resolve) => { resolveToken = resolve; })
      : Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();
    const cancelled = apiRequest("/api/cancelled", { method: "POST", signal: controller.signal });
    const pending = apiRequest("/api/active", { method: "POST" });
    const assertion = expect(cancelled).rejects.toMatchObject({ name: "AbortError" });
    controller.abort();
    await assertion;
    resolveToken(token());
    await pending;
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/csrf", "/api/active"]);
  });

  it("ignora um 401 tardio de uma consulta cancelada para preservar a sessão atual", async () => {
    let resolveResponse!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => new Promise<Response>((resolve) => { resolveResponse = resolve; })));
    const controller = new AbortController();
    const expired = vi.fn();
    const unsubscribe = onSessionExpired(expired);
    try {
      const request = apiGet("/api/auth/activities", controller.signal);
      const assertion = expect(request).rejects.toMatchObject({ name: "AbortError" });
      controller.abort();
      resolveResponse(json({ code: "unauthenticated" }, 401));
      await assertion;
      expect(expired).not.toHaveBeenCalled();
    } finally { unsubscribe(); }
  });
});
