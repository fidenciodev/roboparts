import type { SystemStatus } from "../types/system";
import { apiGet, ApiError } from "./api";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isServiceState(value: unknown): boolean {
  return value === "UP" || value === "DOWN";
}

export function isSystemStatus(value: unknown): value is SystemStatus {
  return (
    isRecord(value) &&
    value.application === "RoboParts" &&
    isServiceState(value.status) &&
    isRecord(value.database) &&
    isServiceState(value.database.status) &&
    typeof value.database.version === "string" &&
    value.database.version.length > 0 &&
    isRecord(value.migrations) &&
    typeof value.migrations.version === "string" &&
    value.migrations.version.length > 0 &&
    typeof value.migrations.description === "string"
  );
}

export async function getSystemStatus(
  signal?: AbortSignal,
): Promise<SystemStatus> {
  const data = await apiGet("/api/system/status", signal);
  if (!isSystemStatus(data)) {
    throw new ApiError(
      "A resposta da API não corresponde ao status esperado do RoboParts.",
    );
  }
  return data;
}
