package br.com.roboparts.controller;

import br.com.roboparts.dto.WorkspaceDtos.*;
import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.security.SessionUser;
import br.com.roboparts.service.WorkspaceService;
import br.com.roboparts.support.BackendHttpTest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspaceHttpTest extends BackendHttpTest {
 @Autowired WorkspaceService workspace;
 @Autowired JdbcTemplate jdbc;
 SessionUser employee;
 @BeforeEach void employee() {
  employee=new SessionUser(UUID.randomUUID(),"Funcionário teste","worker-"+UUID.randomUUID()+"@example.com");
  jdbc.update("INSERT INTO roboparts.users(id,name,email,password_hash,created_at) VALUES (?,?,?,?,?)",
    employee.id(),employee.name(),employee.email(),"test-fixture-not-for-login",Instant.now());
 }
 RobotView robot() { return workspace.createRobot(new RobotInput("Robô teste","Descrição"),employee); }
 RobotView node(RobotView r,String kind,String name,UUID parent,int quantity,boolean required) {
  return workspace.addNode(r.id(),new NodeInput(kind,name,"Detalhes",parent,quantity,required,r.version()),employee);
 }
 NodeUpdate update(NodeView n,UUID parent,boolean archived,long version,String name,int quantity) {
  return new NodeUpdate(name,n.description(),parent,quantity,n.required(),n.position(),archived,version);
 }
 ChecklistView start(RobotView r) { return workspace.start(r.id(),new StartInput(UUID.randomUUID(),r.version()),employee); }
 UUID component(ChecklistView c) { return c.items().stream().filter(i->i.kind().equals("COMPONENT")).findFirst().orElseThrow().id(); }
 @ParameterizedTest
 @ValueSource(strings={"IN_PROGRESS","COMPLETED","RETURNING"})
 void archiveRejectsAnyOutstandingWithdrawalAndSucceedsAfterCompleteReturn(String phase) throws Exception {
  RobotView r=node(robot(),"COMPONENT","Peça em uso",null,2,true);
  ChecklistView c=start(r);UUID item=component(c);
  c=workspace.move(c.id(),item,new MovementInput(phase.equals("IN_PROGRESS")?1:2,UUID.randomUUID(),c.version()),employee);
  if(!phase.equals("IN_PROGRESS"))c=workspace.finalizeChecklist(c.id(),new VersionInput(c.version()),employee);
  if(phase.equals("RETURNING"))c=workspace.move(c.id(),item,new MovementInput(1,UUID.randomUUID(),c.version()),employee);
  assertThat(c.status()).isEqualTo(phase);
  var authenticated=UsernamePasswordAuthenticationToken.authenticated(employee,null,List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
  mvc.perform(post("/api/robots/"+r.id()).with(authentication(authenticated)).with(csrf())
    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nome não permitido\",\"description\":\"\",\"archived\":true,\"expectedVersion\":"+r.version()+"}"))
    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("robot_in_use"));
  RobotView unchanged=workspace.robot(r.id());
  assertThat(unchanged.archived()).isFalse();assertThat(unchanged.name()).isEqualTo(r.name());
  assertThat(unchanged.version()).isEqualTo(r.version());
  assertThat(workspace.history(employee.id(),0,100)).extracting(HistoryView::action).doesNotContain("ROBOT_UPDATED");
  workspace.move(c.id(),item,new MovementInput(0,UUID.randomUUID(),c.version()),employee);
  assertThat(workspace.updateRobot(r.id(),new RobotUpdate(r.name(),r.description(),true,r.version()),employee).archived()).isTrue();
 }
 @Test void archivingChecksAllRetirementsWhileAllowingOtherRobotEdits() {
  RobotView r=node(robot(),"COMPONENT","Peça compartilhada",null,1,true);
  ChecklistView first=start(r),second=start(r);
  first=workspace.move(first.id(),component(first),new MovementInput(1,UUID.randomUUID(),first.version()),employee);
  second=workspace.move(second.id(),component(second),new MovementInput(1,UUID.randomUUID(),second.version()),employee);
  workspace.move(first.id(),component(first),new MovementInput(0,UUID.randomUUID(),first.version()),employee);
  r=workspace.updateRobot(r.id(),new RobotUpdate("Nome permitido","Descrição permitida",false,r.version()),employee);
  RobotView current=r;
  assertThatThrownBy(()->workspace.updateRobot(current.id(),new RobotUpdate(current.name(),current.description(),true,current.version()),employee))
    .isInstanceOfSatisfying(ApiRequestException.class,e->assertThat(e.getCode()).isEqualTo("robot_in_use"));
  workspace.move(second.id(),component(second),new MovementInput(0,UUID.randomUUID(),second.version()),employee);
  assertThat(workspace.updateRobot(r.id(),new RobotUpdate(r.name(),r.description(),true,r.version()),employee).archived()).isTrue();
 }
 @Test void concurrentArchivingAndWithdrawalCannotLeaveAnArchivedRobotInUse() throws Exception {
  RobotView r=node(robot(),"COMPONENT","Peça simultânea",null,1,true);ChecklistView c=start(r);
  CountDownLatch ready=new CountDownLatch(1);
  try(var workers=Executors.newFixedThreadPool(2)) {
   Future<Boolean> archive=workers.submit(()->{ready.await();try {
    workspace.updateRobot(r.id(),new RobotUpdate(r.name(),r.description(),true,r.version()),employee);return true;
   }catch(ApiRequestException e){assertThat(e.getCode()).isEqualTo("robot_in_use");return false;}});
   Future<Boolean> withdraw=workers.submit(()->{ready.await();try {
    workspace.move(c.id(),component(c),new MovementInput(1,UUID.randomUUID(),c.version()),employee);return true;
   }catch(ApiRequestException e){assertThat(e.getCode()).isEqualTo("robot_archived");return false;}});
   ready.countDown();
   assertThat(archive.get(20,TimeUnit.SECONDS)).isNotEqualTo(withdraw.get(20,TimeUnit.SECONDS));
   boolean archived=workspace.robot(r.id()).archived();
   assertThat(workspace.checklist(c.id()).items().getFirst().takenQuantity()).isEqualTo(archived?0:1);
  }
 }
 @Test void snapshotAndLifecyclePreserveNamesQuantitiesAndActors() {
  RobotView r=node(robot(),"COMPONENT","Bateria original",null,2,true);
  ChecklistView c=start(r);UUID item=component(c);
  assertThat(c.total()).isEqualTo(1);assertThat(c.pending()).isEqualTo(1);
  assertThat(c.items().stream().filter(i->i.kind().equals("COMPONENT")).findFirst().orElseThrow().parentId()).isNull();
  var initial=c;
  assertThatThrownBy(()->workspace.finalizeChecklist(initial.id(),new VersionInput(initial.version()),employee))
    .isInstanceOf(ApiRequestException.class).hasMessageContaining("obrigatórios");
  NodeView old=r.nodes().stream().filter(n->n.kind().equals("COMPONENT")).findFirst().orElseThrow();
  r=workspace.updateNode(r.id(),old.id(),update(old,null,false,r.version(),"Bateria nova",3),employee);
  r=workspace.updateRobot(r.id(),new RobotUpdate("Robô renomeado","Nova descrição",false,r.version()),employee);
  ChecklistView saved=workspace.checklist(c.id());
  assertThat(saved.robotName()).isEqualTo("Robô teste");
  assertThat(saved.items().stream().filter(i->i.id().equals(item)).findFirst().orElseThrow().name()).isEqualTo("Bateria original");
  assertThat(saved.items().stream().filter(i->i.id().equals(item)).findFirst().orElseThrow().quantity()).isEqualTo(2);
  c=workspace.move(c.id(),item,new MovementInput(1,UUID.randomUUID(),c.version()),employee);
  assertThat(c.status()).isEqualTo("IN_PROGRESS");assertThat(c.pending()).isEqualTo(1);
  c=workspace.move(c.id(),item,new MovementInput(2,UUID.randomUUID(),c.version()),employee);
  assertThat(c.completed()).isEqualTo(1);
  assertThat(c.items().stream().filter(i->i.id().equals(item)).findFirst().orElseThrow().checkedByName()).isEqualTo(employee.name());
  c=workspace.finalizeChecklist(c.id(),new VersionInput(c.version()),employee);
  assertThat(c.status()).isEqualTo("COMPLETED");assertThat(c.finalizedAt()).isNotNull();
  c=workspace.move(c.id(),item,new MovementInput(1,UUID.randomUUID(),c.version()),employee);
  assertThat(c.status()).isEqualTo("RETURNING");
  c=workspace.move(c.id(),item,new MovementInput(0,UUID.randomUUID(),c.version()),employee);
  assertThat(c.status()).isEqualTo("RETURNED");assertThat(c.finalizedAt()).isNotNull();
  assertThat(c.movements()).extracting(MovementView::delta).containsExactly(1,1,-1,-1);
  ChecklistView next=start(r);
  assertThat(next.id()).isNotEqualTo(c.id());assertThat(next.robotName()).isEqualTo("Robô renomeado");
  assertThat(next.items().stream().filter(i->i.kind().equals("COMPONENT")).findFirst().orElseThrow().name()).isEqualTo("Bateria nova");
  assertThat(workspace.history(employee.id(),0,100)).extracting(HistoryView::action)
    .contains("ROBOT_CREATED","NODE_UPDATED","CHECKLIST_STARTED","COMPONENT_WITHDRAWN","COMPONENT_RETURNED","CHECKLIST_FINALIZED");
 }
 @Test void rejectsCategoriesAndParentAssignmentsWithoutChangingComponents() throws Exception {
  RobotView r=node(robot(),"COMPONENT","Componente raiz",null,1,true);
  NodeView part=r.nodes().getFirst();
  assertThatThrownBy(()->workspace.addNode(r.id(),new NodeInput("CATEGORY","Não permitido","",null,1,true,r.version()),employee)).isInstanceOf(ApiRequestException.class);
  assertThatThrownBy(()->workspace.addNode(r.id(),new NodeInput("COMPONENT","Peça inválida","",part.id(),1,true,r.version()),employee)).isInstanceOf(ApiRequestException.class);
  assertThatThrownBy(()->workspace.updateNode(r.id(),part.id(),update(part,part.id(),false,r.version(),part.name(),1),employee)).isInstanceOf(ApiRequestException.class);
  var authenticated=UsernamePasswordAuthenticationToken.authenticated(employee,null,List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
  mvc.perform(post("/api/robots/"+r.id()+"/nodes").with(authentication(authenticated)).with(csrf())
    .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"CATEGORY\",\"name\":\"Não permitido\",\"description\":\"\",\"quantity\":1,\"required\":true,\"expectedVersion\":"+r.version()+"}"))
    .andExpect(status().isBadRequest());
  mvc.perform(post("/api/robots/"+r.id()+"/nodes").with(authentication(authenticated)).with(csrf())
    .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"COMPONENT\",\"parentId\":\""+part.id()+"\",\"name\":\"Não permitido\",\"description\":\"\",\"quantity\":1,\"required\":true,\"expectedVersion\":"+r.version()+"}"))
    .andExpect(status().isBadRequest());
  assertThat(workspace.robot(r.id()).version()).isEqualTo(r.version());
  assertThat(workspace.robot(r.id()).nodes()).containsExactly(part);
 }
 @Test void reorderingComponentsPersistsOneFlatList() {
  RobotView r=node(robot(),"COMPONENT","Peça um",null,1,true);r=node(r,"COMPONENT","Peça dois",null,4,false);
  NodeView part=r.nodes().getLast();
  r=workspace.updateNode(r.id(),part.id(),new NodeUpdate("Peça editada","Descrição editada",null,5,true,0,false,r.version()),employee);
  assertThat(r.nodes()).extracting(NodeView::name).containsExactly("Peça editada","Peça um");
  assertThat(r.nodes()).extracting(NodeView::position).containsExactly(0,1);
  NodeView saved=r.nodes().getFirst();assertThat(saved.parentId()).isNull();assertThat(saved.quantity()).isEqualTo(5);
  assertThat(saved.description()).isEqualTo("Descrição editada");
  assertThat(workspace.history(employee.id(),0,1).getFirst().details())
    .contains("quantidade 4 → 5","posição 2 → 1","obrigatório false → true","descrição alterada");
 }
 @Test void archivedComponentsAreExcludedFromNewSnapshotsAndOldOnesRemain() {
  RobotView r=node(robot(),"COMPONENT","Peça antiga",null,1,true);r=node(r,"COMPONENT","Peça ativa",null,1,true);
  ChecklistView old=start(r);NodeView part=r.nodes().getFirst();
  r=workspace.updateNode(r.id(),part.id(),update(part,null,true,r.version(),part.name(),1),employee);
  assertThat(start(r).items()).extracting(ItemView::name).containsExactly("Peça ativa");
  assertThat(workspace.checklist(old.id()).items()).extracting(ItemView::name).containsExactly("Peça antiga","Peça ativa");
 }
 UUID legacyNode(RobotView r,String kind,String name,UUID parent,int position,boolean archived) {
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO roboparts.component_nodes(id,robot_id,parent_id,kind,name,description,quantity,required,position,archived) VALUES (?,?,?,?,?,?,?,?,?,?)",
   id,r.id(),parent,kind,name,"Descrição preservada",2,true,position,archived);return id;
 }
 @Test void legacyNestedComponentsKeepOrderDetailsAndArchivedStateInTheFlatList() {
  RobotView r=robot();UUID group=legacyNode(r,"CATEGORY","Grupo antigo",null,0,false);
  UUID first=legacyNode(r,"COMPONENT","Peça interna",group,0,false);
  UUID nested=legacyNode(r,"CATEGORY","Subgrupo antigo",group,1,false);
  legacyNode(r,"COMPONENT","Peça profunda",nested,0,false);
  UUID archivedGroup=legacyNode(r,"CATEGORY","Grupo arquivado",null,1,true);
  UUID archivedPart=legacyNode(r,"COMPONENT","Peça arquivada",archivedGroup,0,false);
  legacyNode(r,"COMPONENT","Peça raiz",null,2,false);
  RobotView flat=workspace.robot(r.id());
  assertThat(flat.nodes()).extracting(NodeView::name).containsExactly("Peça interna","Peça profunda","Peça arquivada","Peça raiz");
  assertThat(flat.nodes()).allSatisfy(n->{assertThat(n.kind()).isEqualTo("COMPONENT");assertThat(n.parentId()).isNull();assertThat(n.description()).isEqualTo("Descrição preservada");assertThat(n.quantity()).isEqualTo(2);});
  assertThat(flat.nodes().get(2).archived()).isTrue();
  ChecklistView started=start(flat);assertThat(started.items()).extracting(ItemView::name).containsExactly("Peça interna","Peça profunda","Peça raiz");
  assertThat(started.items()).allSatisfy(i->assertThat(i.parentId()).isNull());
  NodeView part=flat.nodes().getFirst();
  RobotView edited=workspace.updateNode(r.id(),first,update(part,null,false,r.version(),"Peça atualizada",3),employee);
  assertThat(edited.nodes()).extracting(NodeView::name).containsExactly("Peça atualizada","Peça profunda","Peça arquivada","Peça raiz");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.component_nodes WHERE robot_id=? AND kind='COMPONENT' AND parent_id IS NOT NULL",Integer.class,r.id())).isZero();
  NodeView restore=edited.nodes().get(2);
  RobotView restored=workspace.updateNode(r.id(),archivedPart,update(restore,null,false,edited.version(),restore.name(),2),employee);
  assertThat(restored.nodes().get(2).archived()).isFalse();
  assertThat(workspace.checklist(started.id()).items().getFirst().name()).isEqualTo("Peça interna");
 }
 @Test void legacyChecklistComponentsCanBeReturnedWithoutChangingTheirStoredSnapshot() {
  RobotView r=robot();UUID group=legacyNode(r,"CATEGORY","Grupo anterior",null,0,false);
  UUID source=legacyNode(r,"COMPONENT","Peça anterior",group,0,false);
  UUID checkId=UUID.randomUUID(),groupItem=UUID.randomUUID(),partItem=UUID.randomUUID();
  jdbc.update("INSERT INTO roboparts.checklists(id,robot_id,request_id,robot_name,robot_description,started_by,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
   checkId,r.id(),UUID.randomUUID(),r.name(),r.description(),employee.id(),"IN_PROGRESS",0,Instant.now(),Instant.now());
  jdbc.update("INSERT INTO roboparts.checklist_items(id,checklist_id,source_node_id,parent_id,kind,name,description,quantity,required,position,taken_quantity) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
   groupItem,checkId,group,null,"CATEGORY","Grupo anterior","",1,true,0,0);
  jdbc.update("INSERT INTO roboparts.checklist_items(id,checklist_id,source_node_id,parent_id,kind,name,description,quantity,required,position,taken_quantity,checked_by,checked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
   partItem,checkId,source,groupItem,"COMPONENT","Peça anterior","Snapshot preservado",2,true,0,1,employee.id(),Instant.now());
  ChecklistView flat=workspace.checklist(checkId);assertThat(flat.items()).hasSize(1);
  assertThat(flat.items().getFirst().parentId()).isNull();assertThat(flat.items().getFirst().takenQuantity()).isEqualTo(1);
  assertThat(flat.items().getFirst().checkedByName()).isEqualTo(employee.name());
  ChecklistView returned=workspace.move(checkId,partItem,new MovementInput(0,UUID.randomUUID(),flat.version()),employee);
  assertThat(returned.items().getFirst().takenQuantity()).isZero();assertThat(returned.movements().getFirst().delta()).isEqualTo(-1);
  assertThat(jdbc.queryForObject("SELECT parent_id FROM roboparts.checklist_items WHERE id=?",UUID.class,partItem)).isEqualTo(groupItem);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.checklist_items WHERE checklist_id=?",Integer.class,checkId)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT description FROM roboparts.checklist_items WHERE id=?",String.class,partItem)).isEqualTo("Snapshot preservado");
 }
 @Test void staleVersionsAndInvalidQuantitiesCannotOverwriteData() {
  RobotView r=node(robot(),"COMPONENT","Peça limitada",null,2,true);ChecklistView c=start(r);UUID item=component(c);
  assertThatThrownBy(()->workspace.move(c.id(),item,new MovementInput(3,UUID.randomUUID(),c.version()),employee)).isInstanceOf(ApiRequestException.class);
  ChecklistView changed=workspace.move(c.id(),item,new MovementInput(1,UUID.randomUUID(),c.version()),employee);
  assertThat(changed.version()).isGreaterThan(c.version());
  assertThatThrownBy(()->workspace.move(c.id(),item,new MovementInput(2,UUID.randomUUID(),c.version()),employee))
    .isInstanceOf(ApiRequestException.class).hasMessageContaining("Atualize");
  assertThat(workspace.checklist(c.id()).movements()).hasSize(1);
 }
 @Test void repeatedMovementAndStartRequestsAreIdempotentAndPayloadReuseIsRejected() {
  RobotView r=node(robot(),"COMPONENT","Peça única",null,2,true);
  StartInput start=new StartInput(UUID.randomUUID(),r.version());ChecklistView c=workspace.start(r.id(),start,employee);
  assertThat(workspace.start(r.id(),start,employee).id()).isEqualTo(c.id());
  MovementInput movement=new MovementInput(1,UUID.randomUUID(),c.version());UUID item=component(c);
  workspace.move(c.id(),item,movement,employee);
  assertThat(workspace.move(c.id(),item,movement,employee).movements()).hasSize(1);
  assertThatThrownBy(()->workspace.move(c.id(),item,new MovementInput(2,movement.requestId(),c.version()),employee)).isInstanceOf(ApiRequestException.class);
 }
 @Test void concurrentEmployeesCannotBothOverwriteTheSameRevision() throws Exception {
  RobotView r=node(robot(),"COMPONENT","Peça concorrente",null,2,true);ChecklistView c=start(r);UUID item=component(c);
  CountDownLatch start=new CountDownLatch(1);
  try(var workers=Executors.newFixedThreadPool(2)) {
   List<Future<Boolean>> results=new ArrayList<>();
   for(int quantity:List.of(1,2))results.add(workers.submit(()->{
    start.await();try {workspace.move(c.id(),item,new MovementInput(quantity,UUID.randomUUID(),c.version()),employee);return true;}
    catch(ApiRequestException e) {assertThat(e.getCode()).isEqualTo("concurrent_update");return false;}
   }));
   start.countDown();int succeeded=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))succeeded++;
   assertThat(succeeded).isEqualTo(1);assertThat(workspace.checklist(c.id()).movements()).hasSize(1);
  }
 }
 @Test void cancelRequiresAllComponentsReturnedAndClosedChecklistsRejectFurtherMovements() {
  RobotView r=node(robot(),"COMPONENT","Peça cancelável",null,1,true);ChecklistView c=start(r);UUID item=component(c);
  c=workspace.move(c.id(),item,new MovementInput(1,UUID.randomUUID(),c.version()),employee);ChecklistView taken=c;
  assertThatThrownBy(()->workspace.cancel(taken.id(),new VersionInput(taken.version()),employee)).isInstanceOf(ApiRequestException.class);
  c=workspace.move(c.id(),item,new MovementInput(0,UUID.randomUUID(),c.version()),employee);
  c=workspace.cancel(c.id(),new VersionInput(c.version()),employee);ChecklistView cancelled=c;
  assertThat(c.status()).isEqualTo("CANCELLED");
  assertThatThrownBy(()->workspace.move(cancelled.id(),item,new MovementInput(1,UUID.randomUUID(),cancelled.version()),employee)).isInstanceOf(ApiRequestException.class);
 }
 @Test void dashboardReflectsPersistedRetirementsAndPendingRequiredItems() {
  DashboardView before=workspace.dashboard();RobotView r=node(robot(),"COMPONENT","Peça indicador",null,2,true);
  ChecklistView c=start(r);c=workspace.move(c.id(),component(c),new MovementInput(1,UUID.randomUUID(),c.version()),employee);
  DashboardView after=workspace.dashboard();assertThat(after.robots()).isEqualTo(before.robots()+1);
  assertThat(after.robotsInUse()).isEqualTo(before.robotsInUse()+1);assertThat(after.inProgress()).isEqualTo(before.inProgress()+1);
  assertThat(after.pendingComponents()).isEqualTo(before.pendingComponents()+1);
 }
 @Test void apiRequiresAuthenticationCsrfAndValidBodiesAndDerivesActorFromSession() throws Exception {
  mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
  var authenticated=UsernamePasswordAuthenticationToken.authenticated(employee,null,List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
  mvc.perform(post("/api/robots").with(authentication(authenticated)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"name\":\"Robô HTTP\",\"description\":\"\"}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/robots").with(authentication(authenticated)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
    .content("{\"name\":\" \",\"description\":\"\"}")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/robots").with(authentication(authenticated)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
    .content("{\"name\":\"Robô HTTP\",\"description\":\"Persistido\",\"userId\":\""+UUID.randomUUID()+"\"}"))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Robô HTTP"));
  assertThat(workspace.history(employee.id(),0,100)).extracting(HistoryView::userId).containsOnly(employee.id());
  mvc.perform(get("/api/history?limit=101").with(authentication(authenticated))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/history").with(authentication(authenticated)).with(csrf())).andExpect(status().isForbidden());
 }
 @Test void httpChecklistOperationsPersistTheSnapshotAndRejectFractionalQuantities() throws Exception {
  RobotView r=node(robot(),"COMPONENT","Peça HTTP",null,2,true);
  var authenticated=UsernamePasswordAuthenticationToken.authenticated(employee,null,List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
  String started=mvc.perform(post("/api/robots/"+r.id()+"/checklists").with(authentication(authenticated)).with(csrf())
    .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":"+r.version()+"}"))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.robotName").value("Robô teste")).andReturn().getResponse().getContentAsString();
  String id=com.jayway.jsonpath.JsonPath.read(started,"$.id");
  String item=com.jayway.jsonpath.JsonPath.read(started,"$.items[0].id");
  mvc.perform(post("/api/checklists/"+id+"/items/"+item+"/movements").with(authentication(authenticated)).with(csrf())
    .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1.5,\"requestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":0}"))
    .andExpect(status().isBadRequest());
  String moved=mvc.perform(post("/api/checklists/"+id+"/items/"+item+"/movements").with(authentication(authenticated)).with(csrf())
    .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2,\"requestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":0}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].takenQuantity").value(2))
    .andExpect(jsonPath("$.movements[0].userName").value(employee.name())).andReturn().getResponse().getContentAsString();
  Number revision=com.jayway.jsonpath.JsonPath.read(moved,"$.version");
  mvc.perform(post("/api/checklists/"+id+"/finalize").with(authentication(authenticated)).with(csrf())
    .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+revision.longValue()+"}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
  mvc.perform(get("/api/checklists/"+id).with(authentication(authenticated))).andExpect(status().isOk())
    .andExpect(jsonPath("$.finalizedByName").value(employee.name()));
 }
 @Test void openApiDocumentsProtectedWritesAndTheirSchemas() throws Exception {
  mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
    .andExpect(jsonPath("$.paths['/api/robots'].post.security[0].csrfToken").isArray())
    .andExpect(jsonPath("$.paths['/api/robots'].post.security[0].employeeSession").isArray())
    .andExpect(jsonPath("$.paths['/api/auth/login'].post.security[0].csrfToken").isArray())
    .andExpect(jsonPath("$.paths['/api/auth/login'].post.security[0].employeeSession").doesNotExist())
    .andExpect(jsonPath("$.components.schemas.MovementInput.properties.expectedVersion").exists());
 }
}
