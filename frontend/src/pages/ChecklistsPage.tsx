import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ClipboardCheck } from "lucide-react";
import { Link, useSearchParams } from "react-router";
import { getChecklists } from "../services/workspaceService";
import { formatDate, Heading, Pagination, Progress, QueryState, statusLabels } from "../components/WorkspaceUi";
export function ChecklistsPage() {
 const [page,setPage]=useState(0);const [params]=useSearchParams();const robotId=params.get("robotId")??"";
 const [filter,setFilter]=useState("ALL");
 const query=useQuery({queryKey:["workspace","checklists",page,robotId],queryFn:({signal})=>getChecklists(page,robotId,signal),retry:false});
 const visible=query.data?.filter(c=>filter==="ALL"||c.status===filter)??[];
 return <><Heading title="Checklists de retirada" description="Cada utilização tem seu próprio checklist e a estrutura preservada."><Link className="primary-button" to="/robos">Iniciar pelo robô →</Link></Heading>
  <div className="workspace-toolbar"><label>Status nesta página<select value={filter} onChange={e=>setFilter(e.target.value)}><option value="ALL">Todos</option>{Object.entries(statusLabels).map(([v,label])=><option value={v} key={v}>{label}</option>)}</select></label>{robotId&&<Link to="/checklists">Ver todos os robôs</Link>}<button className="secondary-button" disabled={query.isFetching} onClick={()=>void query.refetch()}>Atualizar</button></div>
  <QueryState pending={query.isPending} error={query.isError?query.error:null} retry={()=>void query.refetch()}/>
  {!query.isPending&&!query.isError&&(visible.length?<div className="checklist-grid">{visible.map(c=><Link key={c.id} className="panel checklist-card" to={"/checklists/"+c.id}><div><ClipboardCheck size={24}/><span className={"state-pill state-"+c.status.toLowerCase()}>{statusLabels[c.status]}</span></div><h2>{c.robotName}</h2><p className="checklist-reference">Retirada #{c.id.slice(0,8)}</p><p>{formatDate(c.createdAt)} · {c.startedByName}</p><Progress completed={c.completed} total={c.total} label="Componentes obrigatórios"/><span className="card-link">Conferir e consultar histórico →</span></Link>)}</div>:<div className="panel empty-state"><ClipboardCheck size={32}/><h2>Nenhum checklist nesta seleção.</h2><p>Abra um robô e use “Iniciar retirada”.</p></div>)}
  <Pagination page={page} setPage={setPage} hasNext={query.data?.length===20}/>
 </>;
}
