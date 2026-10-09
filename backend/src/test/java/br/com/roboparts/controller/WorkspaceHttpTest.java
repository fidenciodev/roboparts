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
 @Test void snapshotAndLifecyclePreserveNamesQuantitiesHierarchyAndActors() {
  RobotView r=robot();r=node(r,"CATEGORY","Sistema elétrico",null,1,true);
  UUID parent=r.nodes().getFirst().id();r=node(r,"COMPONENT","Bateria original",parent,2,true);
  ChecklistView c=start(r);UUID item=component(c);
  assertThat(c.total()).isEqualTo(1);assertThat(c.pending()).isEqualTo(1);
  assertThat(c.items().stream().filter(i->i.kind().equals("COMPONENT")).findFirst().orElseThrow().parentId()).isNotNull();
  var initial=c;
  assertThatThrownBy(()->workspace.finalizeChecklist(initial.id(),new VersionInput(initial.version()),employee))
    .isInstanceOf(ApiRequestException.class).hasMessageContaining("obrigatórios");
  NodeView old=r.nodes().stream().filter(n->n.kind().equals("COMPONENT")).findFirst().orElseThrow();
  r=workspace.updateNode(r.id(),old.id(),update(old,parent,false,r.version(),"Bateria nova",3),employee);
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
 @Test void rejectsCrossRobotParentsComponentsAsParentsAndCyclesWithoutChangingTheTree() {
  RobotView r=robot();r=node(r,"CATEGORY","Categoria A",null,1,true);UUID a=r.nodes().getFirst().id();
  r=node(r,"CATEGORY","Categoria B",a,1,true);NodeView b=r.nodes().stream().filter(n->n.name().equals("Categoria B")).findFirst().orElseThrow();
  NodeView first=r.nodes().stream().filter(n->n.id().equals(a)).findFirst().orElseThrow();
  RobotView current=r;
  assertThatThrownBy(()->workspace.updateNode(current.id(),a,update(first,b.id(),false,current.version(),first.name(),1),employee))
    .isInstanceOf(ApiRequestException.class);
  assertThat(workspace.robot(r.id()).version()).isEqualTo(r.version());
  RobotView other=node(robot(),"CATEGORY","Outra categoria",null,1,true);
  UUID foreign=other.nodes().getFirst().id();
  assertThatThrownBy(()->workspace.addNode(current.id(),new NodeInput("COMPONENT","Peça inválida","",foreign,1,true,current.version()),employee)).isInstanceOf(ApiRequestException.class);
  r=node(r,"COMPONENT","Componente raiz",null,1,true);
  UUID part=r.nodes().stream().filter(n->n.kind().equals("COMPONENT")).findFirst().orElseThrow().id();RobotView latest=r;
  assertThatThrownBy(()->workspace.addNode(latest.id(),new NodeInput("CATEGORY","Não permitido","",part,1,true,latest.version()),employee)).isInstanceOf(ApiRequestException.class);
 }
 @Test void movingAndReorderingNodesPersistsAnEditableTree() {
  RobotView r=node(robot(),"CATEGORY","Primeira categoria",null,1,true);
  UUID cat=r.nodes().getFirst().id();r=node(r,"COMPONENT","Peça um",null,1,true);r=node(r,"COMPONENT","Peça dois",null,4,false);
  NodeView part=r.nodes().stream().filter(n->n.name().equals("Peça dois")).findFirst().orElseThrow();
  r=workspace.updateNode(r.id(),part.id(),new NodeUpdate("Peça editada","Descrição editada",cat,5,true,0,false,r.version()),employee);
  NodeView saved=workspace.robot(r.id()).nodes().stream().filter(n->n.id().equals(part.id())).findFirst().orElseThrow();
  assertThat(saved.parentId()).isEqualTo(cat);assertThat(saved.quantity()).isEqualTo(5);assertThat(saved.description()).isEqualTo("Descrição editada");
  assertThat(workspace.history(employee.id(),0,1).getFirst().details())
    .contains("quantidade 4 → 5","categoria raiz → Primeira categoria","obrigatório false → true","descrição alterada");
 }
 @Test void archivedSubtreesAreExcludedFromNewSnapshotsAndOldOnesRemain() {
  RobotView r=node(robot(),"CATEGORY","Grupo arquivável",null,1,true);UUID cat=r.nodes().getFirst().id();
  r=node(r,"COMPONENT","Peça antiga",cat,1,true);r=node(r,"COMPONENT","Peça ativa",null,1,true);
  ChecklistView old=start(r);NodeView category=r.nodes().stream().filter(n->n.id().equals(cat)).findFirst().orElseThrow();
  r=workspace.updateNode(r.id(),cat,update(category,null,true,r.version(),category.name(),1),employee);
  assertThat(start(r).items()).extracting(ItemView::name).containsExactly("Peça ativa");
  assertThat(workspace.checklist(old.id()).items()).extracting(ItemView::name).contains("Peça antiga","Grupo arquivável");
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
