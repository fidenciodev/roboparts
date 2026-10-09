import { describe, expect, it, vi } from "vitest";
import { getSystemStatus } from "./systemService";

const validStatus = {
  application: "RoboParts",
  status: "UP",
  database: { status: "UP", version: "18.3" },
  migrations: { version: "1", description: "Initial foundation" },
};

describe("Consulta de status real", () => {
  it("consulta a rota de integração e aceita um contrato válido", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify(validStatus), { status: 200 }),
      );
    vi.stubGlobal("fetch", fetchMock);
    expect(await getSystemStatus()).toEqual(validStatus);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/system/status",
      expect.objectContaining({
        headers: { Accept: "application/json" },
        cache: "no-store",
        signal: expect.any(AbortSignal),
      }),
    );
  });

  it("preserva a indisponibilidade informada pela API", async () => {
    const status = {
      ...validStatus,
      status: "DOWN",
      database: { ...validStatus.database, status: "DOWN" },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify(status))),
    );
    expect(await getSystemStatus()).toEqual(status);
  });

  it("rejeita um erro HTTP sem expor detalhes internos da resposta", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response("internal secret", { status: 503 })),
    );
    await expect(getSystemStatus()).rejects.toMatchObject({
      status: 503,
      message: expect.stringContaining("HTTP 503"),
    });
  });

  it("informa a indisponibilidade quando a rede falha", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    await expect(getSystemStatus()).rejects.toThrow(
      "Não foi possível acessar o backend",
    );
  });

  it.each([
    { ...validStatus, database: null },
    { ...validStatus, application: "Other application" },
    {
      ...validStatus,
      migrations: { version: 1, description: "Initial foundation" },
    },
  ])(
    "não trata uma resposta incompatível como conexão válida (%j)",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(new Response(JSON.stringify(status))),
      );
      await expect(getSystemStatus()).rejects.toThrow(
        "não corresponde ao status esperado",
      );
    },
  );

  it("rejeita uma resposta HTML em vez de JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("<html>proxy error</html>")),
    );
    await expect(getSystemStatus()).rejects.toThrow("resposta inválida");
  });
});
