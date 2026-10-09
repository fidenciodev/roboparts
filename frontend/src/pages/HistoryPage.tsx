import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getHistory } from "../services/workspaceService";
import { HistoryList } from "../components/HistoryList";
import { Heading, Pagination, QueryState } from "../components/WorkspaceUi";
export function HistoryPage() {
 const [page,setPage]=useState(0);const [mine,setMine]=useState(false);
 const query=useQuery({queryKey:["workspace","history",page,mine],queryFn:({signal})=>getHistory(page,mine,signal),retry:false});
 return <><Heading title="Histórico da equipe" description="Alterações, retiradas e devoluções com data e responsável."/>
  <div className="workspace-toolbar"><label className="check-label"><input type="checkbox" checked={mine} onChange={e=>{setMine(e.target.checked);setPage(0);}}/> Apenas minhas atividades</label><button className="secondary-button" disabled={query.isFetching} onClick={()=>void query.refetch()}>Atualizar histórico</button></div>
  <section className="panel history-panel"><QueryState pending={query.isPending} error={query.isError?query.error:null} retry={()=>void query.refetch()}/>{query.data&&!query.isError&&<HistoryList entries={query.data}/>}</section>
  <Pagination page={page} setPage={setPage} hasNext={query.data?.length===20}/>
 </>;
}
