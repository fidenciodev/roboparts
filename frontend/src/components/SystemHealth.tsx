import {
  AlertTriangle,
  Check,
  Database,
  Layers3,
  RefreshCw,
  Server,
  Waypoints,
} from "lucide-react";
import { useSystemStatus } from "../hooks/useSystemStatus";
import { StatusBadge } from "./StatusBadge";
import type { StatusTone } from "./StatusBadge";

interface SystemHealthProps {
  detailed?: boolean;
}

export function SystemHealth({ detailed = false }: SystemHealthProps) {
  const query = useSystemStatus();
  // Um erro de nova verificação invalida visualmente o sucesso anterior.
  const data = query.isError ? undefined : query.data;
  const healthy = data?.status === "UP" && data.database.status === "UP";
  const tone: StatusTone =
    query.isError || (data && !healthy)
      ? "error"
      : healthy
        ? "success"
        : "neutral";
  const stateText = query.isPending
    ? "Verificando conexão"
    : query.isError
      ? "Conexão indisponível"
      : healthy
        ? "Serviços conectados"
        : "Atenção aos serviços";
  const rows = [
    {
      name: "API",
      detail: "Spring Boot · Java 21",
      Icon: Server,
      value:
        data?.status === "UP"
          ? "Online"
          : data?.status === "DOWN"
            ? "Indisponível"
            : "Não verificado",
      tone:
        data?.status === "UP"
          ? "success"
          : data?.status === "DOWN"
            ? "error"
            : "neutral",
    },
    {
      name: "Banco de dados",
      detail: data
        ? `PostgreSQL ${data.database.version}`
        : "PostgreSQL · roboparts",
      Icon: Database,
      value:
        data?.database.status === "UP"
          ? "Conectado"
          : data?.database.status === "DOWN"
            ? "Indisponível"
            : "Não verificado",
      tone:
        data?.database.status === "UP"
          ? "success"
          : data?.database.status === "DOWN"
            ? "error"
            : "neutral",
    },
    {
      name: "Migrações",
      detail: data
        ? data.migrations.description
        : "Estrutura versionada com Flyway",
      Icon: Layers3,
      value: data ? `Versão ${data.migrations.version}` : "Não verificado",
      tone: data ? "success" : "neutral",
    },
  ] as const;

  return (
    <section
      className="panel health-panel"
      aria-labelledby="health-title"
      aria-busy={query.isFetching}
    >
      <div className="panel-heading">
        <div>
          <div className="eyebrow">VERIFICAÇÃO EM TEMPO REAL</div>
          <h2 id="health-title">Conexão entre as camadas</h2>
          <p>
            Uma consulta à API verifica o backend, o banco e a migração atual.
          </p>
        </div>
        <button
          type="button"
          className="secondary-button"
          onClick={() => void query.refetch()}
          disabled={query.isFetching}
        >
          <RefreshCw
            size={15}
            className={query.isFetching ? "animate-spin" : ""}
            aria-hidden="true"
          />
          {query.isFetching ? "Verificando…" : "Verificar agora"}
        </button>
      </div>
      <div className="health-summary" aria-live="polite">
        <span className={`summary-icon summary-icon--${tone}`}>
          {tone === "error" ? (
            <AlertTriangle size={20} aria-hidden="true" />
          ) : tone === "success" ? (
            <Check size={20} aria-hidden="true" />
          ) : (
            <Waypoints size={20} aria-hidden="true" />
          )}
        </span>
        <div>
          <strong>{stateText}</strong>
          <p>
            {query.isPending
              ? "Aguardando a resposta do ambiente local."
              : query.isError
                ? "A verificação não confirmou a disponibilidade do ambiente."
                : healthy
                  ? "O RoboParts recebeu uma resposta válida do ambiente."
                  : "A API respondeu, mas um serviço informou indisponibilidade."}
          </p>
        </div>
        <StatusBadge tone={tone}>
          {query.isPending
            ? "Em consulta"
            : healthy && !query.isError
              ? "Operacional"
              : "Verificar"}
        </StatusBadge>
      </div>
      {query.isError && (
        <div className="error-message" role="alert">
          <AlertTriangle size={18} aria-hidden="true" />
          <div>
            <strong>Não foi possível concluir a verificação.</strong>
            <p>{query.error.message}</p>
          </div>
        </div>
      )}
      <div className="service-grid">
        {rows.map(({ name, detail, Icon, value, tone: rowTone }) => (
          <div className="service-item" key={name}>
            <div className="service-icon">
              <Icon size={20} aria-hidden="true" />
            </div>
            <h3>{name}</h3>
            <p>{detail}</p>
            <StatusBadge tone={rowTone}>{value}</StatusBadge>
          </div>
        ))}
      </div>
      <div className="health-footnote">
        <span
          className={`connection-dot ${healthy && !query.isError ? "connection-dot--up" : ""}`}
        />
        <span>
          {query.isError
            ? "Última tentativa sem sucesso"
            : query.dataUpdatedAt
              ? `Última resposta: ${new Intl.DateTimeFormat("pt-BR", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(query.dataUpdatedAt)}`
              : "Nenhuma verificação concluída"}
          <span className="footnote-separator">·</span>
          <code>GET /api/system/status</code>
        </span>
      </div>
      {detailed && (
        <div className="api-details">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3>Resposta da última verificação</h3>
            <span className="eyebrow">CONTRATO DA API</span>
          </div>
          {data ? (
            <pre aria-label="Resposta JSON da API">
              {JSON.stringify(data, null, 2)}
            </pre>
          ) : (
            <p className="muted-text">
              Uma resposta válida será exibida aqui após a conexão com o
              backend.
            </p>
          )}
        </div>
      )}
    </section>
  );
}
