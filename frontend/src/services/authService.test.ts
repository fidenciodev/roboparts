import { beforeEach, describe, expect, it, vi } from "vitest";
import { EmailVerificationRequired, getActivities, getCurrentUser, login, logout, register, resendVerification, verifyEmail, requestPasswordReset, resetPassword } from "./authService";
import { invalidateCsrf } from "./api";

const user = { id: "ab70c719-b047-4f31-97c0-d85280cda031", name: "Ana", email: "ana@example.com" };
const challenge = { verificationId: "8f5d4544-2e9a-44ec-bf5e-ab415da134ad", email: user.email, expiresInSeconds: 600, retryAfterSeconds: 60 };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const token = (value = "masked-token") => json({ token: value, headerName: "X-CSRF-TOKEN" });
beforeEach(() => invalidateCsrf());

describe("Contrato de autenticação", () => {
  it("recupera com CSRF, preserva zeros no código e não envia confirmação nem inicia sessão", async () => {
    const mock = vi.fn().mockResolvedValueOnce(token())
      .mockResolvedValueOnce(json({ message: "generic" }, 202))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", mock);
    await requestPasswordReset(user.email);
    await resetPassword(user.email, "012345", "new-valid-password-123");
    expect(mock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/csrf", "/api/auth/forgot-password", "/api/auth/reset-password"]);
    expect(JSON.parse(mock.mock.calls[1][1].body)).toEqual({ email: user.email });
    expect(JSON.parse(mock.mock.calls[2][1].body)).toEqual({ email: user.email, code: "012345", password: "new-valid-password-123" });
    expect(mock.mock.calls[2][1].headers["X-CSRF-TOKEN"]).toBe("masked-token");
    expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
  });
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
    const fetchMock = vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json(challenge, 201));
    vi.stubGlobal("fetch", fetchMock);
    const input = { name: user.name, email: user.email, password: "valid-password-123", accessCode: "team-code" };
    expect(await register(input)).toEqual(challenge);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/auth/csrf", "/api/auth/register"]);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(input);
    expect(localStorage.length).toBe(0);
  });

  it("um login pendente não autentica nem renova o CSRF como se tivesse entrado", async () => {
    const mock=vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json(challenge,202));vi.stubGlobal("fetch",mock);
    await expect(login(user.email,"valid-password-123")).rejects.toBeInstanceOf(EmailVerificationRequired);
    expect(mock.mock.calls.map(([path])=>path)).toEqual(["/api/auth/csrf","/api/auth/login"]);
    expect(localStorage.length).toBe(0);expect(sessionStorage.length).toBe(0);
  });

  it("confirma com o código e reenvia usando apenas o identificador do desafio", async () => {
    const mock=vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json(user)).mockResolvedValueOnce(json(challenge));
    vi.stubGlobal("fetch",mock);
    await expect(verifyEmail(challenge.verificationId,"012345")).resolves.toEqual(user);
    await expect(resendVerification(challenge.verificationId)).resolves.toEqual(challenge);
    expect(JSON.parse(mock.mock.calls[1][1].body)).toEqual({verificationId:challenge.verificationId,code:"012345"});
    expect(JSON.parse(mock.mock.calls[2][1].body)).toEqual({verificationId:challenge.verificationId});
  });

  it("rejeita respostas de confirmação sem prazo ou identificador válidos", async () => {
    vi.stubGlobal("fetch",vi.fn().mockResolvedValueOnce(token()).mockResolvedValueOnce(json({...challenge,expiresInSeconds:-1})));
    await expect(register({name:user.name,email:user.email,password:"valid-password-123",accessCode:"team-code"})).rejects.toThrow("confirmação do e-mail");
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
