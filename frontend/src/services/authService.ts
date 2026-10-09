import { apiRequest, ApiError, refreshCsrf } from "./api";
import type { RegistrationInput, User, UserActivity } from "../types/auth";

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

export async function getCurrentUser(signal?: AbortSignal): Promise<User> {
  return readUser(await apiRequest("/api/auth/me", { signal, notifyUnauthorized: false }));
}

export async function login(email: string, password: string): Promise<User> {
  const user = readUser(await apiRequest("/api/auth/login", {
    method: "POST", body: { email, password }, notifyUnauthorized: false,
  }));
  await refreshCsrf();
  return user;
}

export async function register(input: RegistrationInput): Promise<User> {
  return readUser(await apiRequest("/api/auth/register", {
    method: "POST", body: input, notifyUnauthorized: false,
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
