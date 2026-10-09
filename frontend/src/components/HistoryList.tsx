import { Link } from "react-router";
import type { HistoryEntry } from "../types/workspace";
import { actionLabels, formatDate } from "./WorkspaceUi";
export function HistoryList({entries}:{entries:HistoryEntry[]}) {
 if(!entries.length)return <p className="empty-state">Nenhuma atividade nesta página.</p>;
 return <ul className="history-list">{entries.map(a=><li key={a.id}><span className="history-marker"/><div><strong>{a.userName}</strong><span className="history-action">{actionLabels[a.action]??"Atividade registrada"}</span><p>{a.details||actionLabels[a.action]||"Atividade registrada"}</p>{a.resourceId&&a.resourceType==="ROBOT"&&<Link to={"/robos/"+a.resourceId}>Abrir robô →</Link>}{a.resourceId&&a.resourceType==="CHECKLIST"&&<Link to={"/checklists/"+a.resourceId}>Abrir retirada →</Link>}</div><time dateTime={a.createdAt}>{formatDate(a.createdAt)}</time></li>)}</ul>;
}
