import { useQuery } from "@tanstack/react-query";
import { History, RefreshCw } from "lucide-react";
import { useAuth } from "../hooks/useAuth";
import { getActivities } from "../services/authService";
import { actionLabels } from "./WorkspaceUi";

const labels: Record<string, string> = {
  USER_REGISTERED: "Conta cadastrada",
  USER_LOGGED_IN: "Entrada no sistema",
  USER_LOGGED_OUT: "Saída do sistema",
};
const dateFormat = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short" });

export function UserActivities() {
  const { user } = useAuth();
  const query = useQuery({
    queryKey: ["auth", "activities", user?.id],
    queryFn: ({ signal }) => getActivities(signal),
    enabled: Boolean(user), retry: false, staleTime: 30_000, refetchOnWindowFocus: false,
  });
  return <section className="panel user-activities" aria-labelledby="my-activities-title" aria-busy={query.isFetching}>
    <div className="panel-heading">
      <div><p className="eyebrow">SEU ACESSO</p><h2 id="my-activities-title">Minha atividade</h2><p>Últimos registros da sua conta de funcionário.</p></div>
      <button className="secondary-button" type="button" disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={15} className={query.isFetching ? "animate-spin" : ""} aria-hidden="true" />{query.isFetching ? "Atualizando…" : "Atualizar atividade"}</button>
    </div>
    {query.isPending ? <p className="activity-placeholder" role="status">Carregando suas atividades…</p>
      : query.isError ? <p className="activity-placeholder field-error" role="alert">{query.error.message}</p>
      : query.data.length === 0 ? <p className="activity-placeholder">Nenhuma atividade registrada para sua conta.</p>
      : <ul className="activity-list">{query.data.map((activity) => <li key={activity.id}>
        <span className="activity-icon"><History size={16} aria-hidden="true" /></span>
        <span>{labels[activity.action] ?? actionLabels[activity.action] ?? "Atividade da conta registrada"}</span>
        <time dateTime={activity.createdAt}>{dateFormat.format(new Date(activity.createdAt))}</time>
      </li>)}</ul>}
  </section>;
}
