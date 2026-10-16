import { apiRequest, ApiError, refreshCsrf } from "./api";
import type { EmailVerification, RegistrationInput, User, UserActivity } from "../types/auth";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function readUser(value: unknown): User {
  if (!isRecord(value) || typeof value.id !== "string" || !value.id ||
    typeof value.name !== "string" || !value.name || typeof value.email !== "string" || !value.email) {
    throw new ApiError("A resposta da API não corresponde à identificação esperada do funcionário.");
  }
  return { id: value.id, name: value.name, email: value.email };
}

export function isEmailVerification(value: unknown): value is EmailVerification {
  return isRecord(value) && typeof value.verificationId === "string" && Boolean(value.verificationId) &&
    typeof value.email === "string" && Boolean(value.email) &&
    typeof value.expiresInSeconds === "number" && Number.isSafeInteger(value.expiresInSeconds) && value.expiresInSeconds >= 0 && value.expiresInSeconds <= 600 &&
    typeof value.retryAfterSeconds === "number" && Number.isSafeInteger(value.retryAfterSeconds) && value.retryAfterSeconds >= 0 && value.retryAfterSeconds <= 60;
}

function readVerification(value: unknown): EmailVerification {
  if (!isEmailVerification(value)) throw new ApiError("Não foi possível iniciar a confirmação do e-mail. Tente novamente.");
  return value;
}

export class EmailVerificationRequired extends Error {
  constructor(readonly verification: EmailVerification) { super("Confirme seu e-mail para entrar."); }
}
export class MfaRequired extends Error {
  constructor() { super("Confirme o código do seu aplicativo autenticador."); }
}
export async function loginMfa(code: string): Promise<User> {
  const user = readUser(await apiRequest("/api/auth/mfa-login", { method: "POST", body: { code }, notifyUnauthorized: false }));
  await refreshCsrf(); return user;
}

export async function getCurrentUser(signal?: AbortSignal): Promise<User> {
  return readUser(await apiRequest("/api/auth/me", { signal, notifyUnauthorized: false }));
}

export async function requestPasswordReset(email: string): Promise<void> {
  await apiRequest("/api/auth/forgot-password", {
    method: "POST", body: { email }, notifyUnauthorized: false,
  });
}

export async function resetPassword(email: string, code: string, password: string): Promise<void> {
  await apiRequest("/api/auth/reset-password", {
    method: "POST", body: { email, code, password }, notifyUnauthorized: false,
  });
}

export async function login(email: string, password: string): Promise<User> {
  const result = await apiRequest("/api/auth/login", {
    method: "POST", body: { email, password }, notifyUnauthorized: false, signal: AbortSignal.timeout(20000),
  });
  if (isEmailVerification(result)) throw new EmailVerificationRequired(result);
  if (isRecord(result) && result.mfaRequired === true && result.expiresInSeconds === 300) throw new MfaRequired();
  const user = readUser(result);
  await refreshCsrf();
  return user;
}

export async function register(input: RegistrationInput): Promise<EmailVerification> {
  return readVerification(await apiRequest("/api/auth/register", {
    method: "POST", body: input, notifyUnauthorized: false, signal: AbortSignal.timeout(20000),
  }));
}

export async function verifyEmail(verificationId: string, code: string): Promise<User> {
  return readUser(await apiRequest("/api/auth/verify-email", {
    method: "POST", body: { verificationId, code }, notifyUnauthorized: false,
  }));
}

export async function resendVerification(verificationId: string): Promise<EmailVerification> {
  return readVerification(await apiRequest("/api/auth/resend-verification", {
    method: "POST", body: { verificationId }, notifyUnauthorized: false, signal: AbortSignal.timeout(20000),
  }));
}

export async function logout(): Promise<"loggedOut" | "expired"> {
  let outcome: "loggedOut" | "expired" = "loggedOut";
  try {
    await apiRequest("/api/auth/logout", { method: "POST", notifyUnauthorized: false });
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 401) {
      outcome = "expired";
    } else if (cause instanceof ApiError && cause.status === 503) {
      // O servidor invalida a sessão mesmo se o registro da saída estiver indisponível.
      // Confirme a identidade com um GET; nunca repita a operação de saída.
      try { await getCurrentUser(); }
      catch (verificationError) {
        if (!(verificationError instanceof ApiError && verificationError.status === 401)) throw cause;
        await refreshCsrf();
        return outcome;
      }
      throw cause;
    } else {
      throw cause;
    }
  }
  await refreshCsrf();
  return outcome;
}

export async function getActivities(signal?: AbortSignal): Promise<UserActivity[]> {
  const data = await apiRequest("/api/auth/activities?limit=20", { signal });
  if (!Array.isArray(data) || !data.every((item) => isRecord(item) &&
    typeof item.id === "string" && typeof item.action === "string" &&
    typeof item.createdAt === "string" && Number.isFinite(Date.parse(item.createdAt)))) {
    throw new ApiError("A resposta da API não corresponde ao histórico de atividades esperado.");
  }
  return data.map((item) => ({ id: item.id, action: item.action, createdAt: item.createdAt }));
}
