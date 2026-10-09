import { createContext, useCallback, useEffect, useState } from "react";
import type { PropsWithChildren } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError, invalidateCsrf, onSessionExpired } from "../services/api";
import * as authService from "../services/authService";
import type { User } from "../types/auth";

export interface AuthContextValue {
  user: User | null;
  status: "loading" | "authenticated" | "anonymous" | "unavailable";
  error: string | null;
  sessionExpired: boolean;
  retrySession: () => void;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const queryClient = useQueryClient();
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<AuthContextValue["status"]>("loading");
  const [error, setError] = useState<string | null>(null);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [sessionAttempt, setSessionAttempt] = useState(0);

  useEffect(() => onSessionExpired(() => {
    setUser(null);
    setStatus("anonymous");
    setError(null);
    setSessionExpired(true);
    queryClient.clear();
  }), [queryClient]);

  useEffect(() => {
    const controller = new AbortController();
    setStatus("loading");
    setError(null);
    void authService.getCurrentUser(controller.signal).then((currentUser) => {
      if (controller.signal.aborted) return;
      setUser(currentUser);
      setStatus("authenticated");
    }).catch((cause: unknown) => {
      if (controller.signal.aborted) return;
      setUser(null);
      if (cause instanceof ApiError && cause.status === 401) {
        invalidateCsrf();
        setStatus("anonymous");
      } else {
        setStatus("unavailable");
        setError(cause instanceof ApiError ? cause.message : "Não foi possível verificar sua sessão. Tente novamente.");
      }
    });
    return () => controller.abort();
  }, [sessionAttempt]);

  const login = useCallback(async (email: string, password: string) => {
    const currentUser = await authService.login(email, password);
    queryClient.clear();
    setUser(currentUser);
    setError(null);
    setSessionExpired(false);
    setStatus("authenticated");
  }, [queryClient]);

  const logout = useCallback(async () => {
    const outcome = await authService.logout();
    queryClient.clear();
    setUser(null);
    setError(null);
    setSessionExpired(outcome === "expired");
    setStatus("anonymous");
  }, [queryClient]);

  return <AuthContext.Provider value={{ user, status, error, sessionExpired, login, logout,
    retrySession: () => setSessionAttempt((attempt) => attempt + 1) }}>{children}</AuthContext.Provider>;
}
