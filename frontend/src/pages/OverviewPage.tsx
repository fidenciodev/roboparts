import { useQuery } from "@tanstack/react-query";
import { Bot, ClipboardCheck, Layers3, PackageCheck, RefreshCw } from "lucide-react";
import { Link } from "react-router";
import { SystemHealth } from "../components/SystemHealth";
import { UserActivities } from "../components/UserActivities";
import { HistoryList } from "../components/HistoryList";
import { QueryState } from "../components/WorkspaceUi";
import { useAuth } from "../hooks/useAuth";
import { getDashboard } from "../services/workspaceService";
export function OverviewPage() {
 const {user}=useAuth();
 const query=useQuery({queryKey:["workspace","dashboard"],queryFn:({signal})=>getDashboard(signal),retry:false,refetchInterval:60_000});
 const d=query.data;
 return <>
  <div className="page-heading workspace-heading"><div><p className="eyebrow">SEU ESPAÇO DE TRABALHO</p><h1>Bem-vindo, {user?.name}.</h1><p>Confira os robôs, acompanhe retiradas e veja o que ainda está pendente.</p></div><Link className="primary-button" to="/robos">Ver robôs <Bot size={18}/></Link></div>
  <section className="welcome-banner dashboard-banner"><div className="welcome-copy"><span className="banner-label"><Layers3 size={14}/> CONFERIR. RETIRAR. REGISTRAR.</span><h2>Cada componente conta.<br/>Cada retirada, registrada.</h2><p>Um checklist por utilização, com as quantidades e os responsáveis sempre à mão.</p><Link className="banner-link" to="/checklists">Acompanhar retiradas →</Link></div><PackageCheck className="dashboard-illustration" size={125} strokeWidth={1}/></section>
  <QueryState pending={query.isPending} error={query.isError?query.error:null} retry={()=>void query.refetch()}/>
  {d&&!query.isError&&<><section className="metric-grid" aria-label="Indicadores de retiradas">
   {[["Robôs cadastrados",d.robots,Bot],["Robôs em uso",d.robotsInUse,PackageCheck],["Checklists em andamento",d.inProgress,ClipboardCheck],["Conferências completas",d.completed,ClipboardCheck],["Componentes pendentes",d.pendingComponents,Layers3]].map(([label,value,Icon])=>{const Symbol=Icon as typeof Bot;return <article className="panel metric-card" key={String(label)}><Symbol size={21}/><strong>{String(value)}</strong><span>{String(label)}</span></article>;})}
  </section><section className="panel history-panel"><div className="panel-heading"><div><p className="eyebrow">ATIVIDADE DA EQUIPE</p><h2>Últimos registros</h2><p>Indicadores de componentes consideram itens obrigatórios de retiradas abertas.</p></div><button className="secondary-button" disabled={query.isFetching} onClick={()=>void query.refetch()}><RefreshCw size={16}/> Atualizar indicadores</button></div><HistoryList entries={d.recent}/><Link className="panel-bottom-link" to="/historico">Consultar todo o histórico →</Link></section></>}
  <UserActivities/><SystemHealth/>
 </>;
}
