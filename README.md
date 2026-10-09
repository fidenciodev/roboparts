# RoboParts

Sistema interno para controlar a retirada de robôs e seus componentes. As funcionalidades das seis etapas estão implementadas: autenticação restrita, robôs e árvore de componentes editável, checklists com snapshot, retiradas e devoluções, histórico, dashboard, interface responsiva, temas e preparação para PWA. A conexão com o PostgreSQL existente foi confirmada; a conclusão das migrações e a execução integrada ainda precisam ser verificadas. Os resultados de validação abaixo distinguem os testes locais da integração real.

## Estrutura

```text
roboparts/
├── backend/
│   ├── .mvn/wrapper/
│   ├── mvnw.cmd
│   ├── pom.xml
│   └── src/
│       ├── main/java/br/com/roboparts/
│       │   ├── config/ controller/ dto/ entity/
│       │   ├── exception/ repository/ security/ service/ tools/
│       │   └── RoboPartsApplication.java
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/
│       │       ├── V1__initial_foundation.sql
│       │       ├── V2__employee_authentication.sql
│       │       └── V3__robots_checklists_and_history.sql
│       └── test/
├── frontend/
│   ├── package.json
│   ├── package-lock.json
│   └── src/
├── scripts/
├── .vscode/
├── .env.example
└── README.md
```

O backend usa Java 21, Spring Boot, Spring Security, JPA, Bean Validation, Flyway e OpenAPI. O frontend usa React, TypeScript, Vite, Tailwind CSS, React Router, TanStack Query e Lucide. As versões exatas estão em `backend/pom.xml`, `frontend/package.json` e no arquivo de dependências travadas `frontend/package-lock.json`.

A lista completa está em [docs/arquivos-projeto.txt](docs/arquivos-projeto.txt). As listas das entregas anteriores foram preservadas. O [guia de uso e modelo](docs/uso-e-modelo.md) explica as telas, o ciclo de retirada, os snapshots e os indicadores.

## Preparar o ambiente no Windows

Instale Java 21, Node.js **24.15.0 ou superior** (`engines`: `>=24.15.0`), PostgreSQL 18, Git e VS Code. O Maven Wrapper baixa a versão Maven configurada na primeira execução; a instalação local do Maven também é aceita pelos scripts como alternativa. Nenhuma etapa usa Docker.

Abra a pasta `roboparts` no VS Code. No terminal PowerShell, confira as ferramentas:

```powershell
java -version
node --version
npm.cmd --version
git --version
```

Use `npm.cmd` nos comandos do Windows. Se `npm.ps1` estiver bloqueado pela política do PowerShell, `npm.cmd` funciona sem alterar essa política.

Instale as dependências e compile o backend:

```powershell
Set-Location .\frontend
npm.cmd ci
Set-Location ..\backend
.\mvnw.cmd package
Set-Location ..
```

A primeira execução precisa de internet para baixar dependências. Os builds e testes unitários não exigem senha nem acesso ao PostgreSQL.

## Banco e variáveis de ambiente

Use o banco **já criado** no pgAdmin: `localhost:5432`, database `roboparts`, usuário inicial `postgres`. Não crie o banco novamente. Confirme que o serviço PostgreSQL está iniciado e que a versão é 18.

O arquivo `.env.example` documenta a configuração. Ele **não é carregado automaticamente** pelo Spring Boot nem pelos scripts. Configure as variáveis no processo que executará a aplicação; nunca coloque a senha em arquivos versionados. Por exemplo, estas configurações sem segredos podem ser definidas no terminal:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/roboparts'
$env:DB_USERNAME = 'postgres'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8080'
```

Os scripts reutilizam `DB_PASSWORD` se ela estiver definida no processo. Caso contrário, importam a variável do escopo do usuário, se houver. Com `-PromptForPassword`, solicitam a senha com entrada oculta e mantêm a credencial somente no processo em execução; o valor temporário é removido ao terminar. Não é necessário salvar a senha em arquivo ou digitá-la em um comando que ficará no histórico.

Antes da primeira inicialização, faça a inspeção de leitura:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Database.ps1 -PromptForPassword
```

Esse comando compila a ferramenta `InspectDatabase` e consulta a identidade, a versão e os objetos existentes. Ele não inicia Spring Boot, não executa Flyway e não modifica o banco. Sem senha disponível e sem `-PromptForPassword`, termina com código 2. Uma falha de conexão ou de validação termina com código 1.

Ao iniciar o backend, uma segunda inspeção acontece **antes de qualquer migração**. Ela exige o banco `roboparts`, PostgreSQL 18 e um estado de schema reconhecido. Objetos existentes em outros schemas são preservados. Um schema `roboparts` ocupado sem histórico Flyway exige conciliação antes de continuar; não há baseline automático. O Flyway valida o histórico antes de migrar, e sua operação de limpeza está desabilitada.

Na primeira execução, o Flyway cria o schema da aplicação dentro do banco existente. A estratégia chama `migrate()` com [`validate-on-migrate=true`](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-validate-on-migrate-setting), que mantém a validação do histórico e aceita migrações pendentes. Uma chamada isolada a `validate()` antes disso falhava quando o schema ainda não existia.

A migração V1 cria a tabela `roboparts.application_metadata`; a V2 acrescenta funcionários e auditoria de autenticação; a V3 acrescenta robôs, elementos da árvore, checklists, itens de snapshot e movimentações, além dos detalhes da auditoria. V1 e V2 permanecem intactas. A V3 não remove dados existentes. O Flyway gerencia seu histórico no mesmo schema; o Hibernate apenas valida a estrutura.

## Código interno e autenticação

Configure `REGISTRATION_ACCESS_CODE` no ambiente do backend com um código secreto de 24 a 256 caracteres, compartilhado apenas com os funcionários autorizados a criar contas. O script de inicialização solicita esse valor com entrada oculta se ele ainda não estiver configurado. Deixar a entrada vazia mantém novos cadastros desabilitados. O login de contas existentes continua disponível. Trocar o código afeta os próximos cadastros.

O código fica exclusivamente no servidor. Não o coloque em variáveis `VITE_*`, arquivos versionados ou mensagens de log. O `.env.example` tem o campo vazio e serve apenas como referência. Para iniciar com novos cadastros desabilitados explicitamente, use `Start-Backend.ps1 -DisableRegistration`.

Abra `/cadastro`, informe nome, e-mail, senha e o código interno. Depois do cadastro, entre em `/entrar`. A senha deve ter entre 12 e 128 caracteres. O backend normaliza o e-mail, impede duplicidade no banco e armazena somente o hash PBKDF2 com salt aleatório. Todos os funcionários têm as mesmas permissões.

A autenticação é mantida no servidor e identificada por cookie HttpOnly. A interface restaura a sessão ao recarregar a página; não salva credenciais ou tokens de autenticação no armazenamento local do navegador. A sessão expira após o período de inatividade configurado em `SESSION_TIMEOUT` (padrão: 8 horas). Reiniciar o backend encerra as sessões em memória. O botão **Sair** invalida a sessão no servidor.

As requisições que alteram estado precisam de CSRF: o cliente chama `GET /api/auth/csrf`, envia o token retornado no cabeçalho `X-CSRF-TOKEN` e obtém um novo token depois do login ou logout. A identidade registrada nas atividades vem da autenticação no backend. Não existe operação pública para editar essas atividades. A migração PostgreSQL também acrescenta gatilhos contra atualização, exclusão e truncamento dos registros.

O limite padrão de cadastro e login é de 20 requisições por minuto por endereço de origem, com capacidade para 4096 endereços na memória do processo. `AUTH_RATE_LIMIT_REQUESTS`, `AUTH_RATE_LIMIT_WINDOW_SECONDS` e `AUTH_RATE_LIMIT_CAPACITY` permitem ajustá-lo. O limite usa o endereço da conexão e não confia em cabeçalhos enviados pelo cliente; tentativas excessivas recebem HTTP 429 com `Retry-After`. Em uma implantação com múltiplas instâncias, sessão e limites compartilhados precisarão ser configurados.

O fluxo segue a documentação oficial do Spring Security para [sessões](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) e [CSRF em aplicações JavaScript](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

## Executar backend e frontend

Na raiz `roboparts`, execute o backend em um terminal:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Backend.ps1
```

O script solicita a senha do PostgreSQL e o código interno de cadastro de forma oculta se eles ainda não estiverem disponíveis. A saída do Maven também é salva em `backend/target/roboparts-startup.log`, substituindo ocorrências literais dessas credenciais por `[segredo oculto]`. O arquivo é sobrescrito a cada inicialização e fica fora do versionamento. Se a inicialização falhar, procure nesse log as linhas `Caused by:` anteriores ao resumo de erro do Maven.

Para iniciar manualmente quando `DB_PASSWORD` e, se houver cadastro habilitado, `REGISTRATION_ACCESS_CODE` já estiverem definidos no terminal:

```powershell
Set-Location .\backend
.\mvnw.cmd spring-boot:run
```

Em outro terminal, na raiz `roboparts`, execute o frontend:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Frontend.ps1
```

Ou execute os comandos manuais:

```powershell
Set-Location .\frontend
npm.cmd run dev
```

Abra [http://127.0.0.1:5173](http://127.0.0.1:5173). O Vite encaminha `/api` para o backend local na porta 8080. As páginas privadas exigem login; depois de entrar, a página de ambiente consulta o estado real da integração. Pare cada processo com `Ctrl+C`.

As tarefas do VS Code estão em **Terminal → Executar Tarefa**: inspecionar PostgreSQL, iniciar backend e iniciar frontend. O parâmetro `ExecutionPolicy Bypass` vale apenas para o processo que executa o script; nenhuma política persistente do Windows é alterada.

Depois que o backend iniciar com sucesso, os endpoints locais são:

| Endereço | Finalidade |
| --- | --- |
| `http://127.0.0.1:8080/api/system/status` | Estado da aplicação, PostgreSQL e migrações; requer sessão |
| `http://127.0.0.1:8080/actuator/health` | Verificação de saúde sem detalhes sensíveis |
| `http://127.0.0.1:8080/swagger-ui/index.html` | Documentação interativa da API |
| `http://127.0.0.1:8080/v3/api-docs` | Especificação OpenAPI |

As operações de autenticação são:

| Método e rota | Finalidade |
| --- | --- |
| `GET /api/auth/csrf` | Obter token CSRF antes de um POST |
| `POST /api/auth/register` | Cadastrar funcionário com código interno; não faz login automático |
| `POST /api/auth/login` | Autenticar por e-mail e senha |
| `GET /api/auth/me` | Consultar o funcionário da sessão atual |
| `POST /api/auth/logout` | Invalidar a sessão |
| `GET /api/auth/activities?limit=20` | Consultar atividades do próprio funcionário; limite máximo 50 |

Sem sessão válida, as rotas privadas retornam 401. Todos os funcionários autenticados podem operar os robôs e checklists; cada ação registra a identidade da sessão no backend.

## Robôs, checklists e histórico

Abra **Robôs e componentes** para cadastrar um robô. Na estrutura, adicione categorias, subcategorias e componentes. Clique em qualquer nome para edição inline: Enter salva e Escape cancela. Em **Editar detalhes**, altere descrição, quantidade, obrigatoriedade, categoria e posição. Categorias só podem conter elementos do mesmo robô; ciclos são rejeitados. Arquivar uma categoria arquiva seus descendentes. Para restaurar um elemento sob uma categoria arquivada, restaure primeiro os ancestrais.

Clique em **Iniciar retirada** para criar um checklist independente. Ele copia os nomes, descrições, quantidades, ordem e hierarquia atuais; mudanças posteriores na estrutura não alteram essa cópia. Marque o componente para retirar a quantidade completa, ou informe uma quantidade parcial e use **Registrar**. Desmarcar ou reduzir a quantidade registra uma devolução.

**Finalizar conferência** exige todos os componentes obrigatórios retirados e ao menos uma retirada registrada. Depois da finalização, apenas devoluções são permitidas. Um checklist vazio ainda não finalizado pode ser cancelado sem apagar seu histórico. Consulte retiradas anteriores em **Checklists de retirada** e alterações em **Histórico da equipe**.

As escritas usam transações, bloqueios por robô ou checklist e uma versão esperada. Se outro funcionário salvar antes, a API responde 409: atualize, confira e salve novamente. Criação de checklist e movimentações recebem um UUID de operação; repetir a mesma requisição não repete a retirada. A interface não repete POST automaticamente.

As estruturas têm limite de 1000 elementos por robô e 32 níveis; quantidades variam de 1 a 1.000.000. As listas e o histórico têm paginação. Busca e filtro de status na interface se aplicam à página exibida.

| Método e rota | Operação |
| --- | --- |
| `GET/POST /api/robots` | Listar / cadastrar robôs |
| `GET/POST /api/robots/{id}` | Consultar / atualizar robô |
| `POST /api/robots/{id}/nodes` | Criar categoria ou componente |
| `POST /api/robots/{id}/nodes/{nodeId}` | Renomear, editar, mover, reordenar ou arquivar elemento |
| `POST /api/robots/{id}/checklists` | Criar snapshot e iniciar retirada |
| `GET /api/checklists?robotId=...&page=0&limit=20` | Consultar retiradas anteriores |
| `GET /api/checklists/{id}` | Snapshot, progresso e movimentações |
| `POST /api/checklists/{id}/items/{itemId}/movements` | Definir quantidade retirada; registrar delta e responsável |
| `POST /api/checklists/{id}/finalize` | Finalizar a conferência |
| `POST /api/checklists/{id}/cancel` | Cancelar checklist vazio |
| `GET /api/history?mine=false&page=0&limit=20` | Auditoria da equipe ou do funcionário da sessão |
| `GET /api/dashboard` | Indicadores e atividades recentes |

Os contratos completos estão no Swagger. Registros de auditoria e movimentação não possuem operações de edição ou exclusão. A migração PostgreSQL também impede essas alterações por gatilhos e protege os campos de estrutura dos snapshots.

## Builds e testes

Na raiz `roboparts`, execute os testes automatizados do backend:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1
```

Para o frontend e para gerar os artefatos de ambos:

```powershell
Set-Location .\frontend
npm.cmd test
npm.cmd run build
Set-Location ..\backend
.\mvnw.cmd verify
Set-Location ..
```

O frontend é gerado em `frontend/dist`; o backend, em `backend/target`. Os testes verificam a fundação, a API e o fluxo de autenticação. Os testes locais de persistência usam H2 exclusivamente no escopo de testes, com um schema próprio, sem acessar ou alterar o PostgreSQL existente. Eles não substituem a validação das migrações e dos recursos específicos do PostgreSQL 18.

Depois de iniciar o backend pelo menos uma vez e concluir a migração, os testes opcionais de integração podem verificar o PostgreSQL existente:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Postgres -PromptForPassword
```

Esse script habilita temporariamente `ROBOPARTS_POSTGRES_IT=true` e executa `mvnw.cmd -Ppostgres-it verify`. Os testes leem os metadados já persistidos e validam o caminho HTTP → serviço → banco. Eles não criam banco, não executam migrações e não alteram dados. Sem as duas variáveis `ROBOPARTS_POSTGRES_IT=true` e `DB_PASSWORD`, os testes PostgreSQL são ignorados; isso não confirma uma conexão real.

## Verificação desta entrega

| Verificação | Resultado |
| --- | --- |
| Backend: `mvnw.cmd -B -ntp verify` | 79 testes aprovados; build e JAR executável gerados |
| Inicialização com schema ausente e migrações pendentes | Regressão coberta com Flyway real em H2; criação inicial, atualização e rejeição de checksum alterado verificadas |
| Inicialização do backend sem `DB_PASSWORD` | Proteção confirmada: saída 1 antes de migrar; nenhuma migração executada |
| `Test-Database.ps1` sem senha disponível | Saída 2 confirmada; nenhuma conexão ou alteração realizada |
| Frontend: build de produção | TypeScript e Vite aprovados; arquivos gerados em `frontend/dist` |
| Auditoria de dependências de produção do frontend | Zero vulnerabilidades reportadas |
| Frontend: `npm.cmd test` | 64 testes aprovados em 6 arquivos; cache PWA e estados dos botões do checklist incluídos |
| Porta local 5432 | Respondeu à verificação TCP |
| Conexão JDBC e versão real do PostgreSQL | Confirmadas pelo log de inicialização: banco `roboparts`, PostgreSQL 18.6, zero objetos existentes e nenhum histórico Flyway |
| Migrações V1/V2/V3 no PostgreSQL | Confirmadas pelo log local: três migrações aplicadas, schema na versão 3 e backend iniciado |
| Inspeção visual no navegador | Não realizada: nenhum navegador disponível na ferramenta de inspeção |

A resposta TCP confirma apenas que há um serviço escutando na porta; o log posterior confirmou a conexão JDBC. A suíte de backend usa persistência real em H2 e HTTP MockMvc; inclui concorrência, snapshots, idempotência, ciclos, quantidades, auditoria e autenticação. Os testes de frontend simulam a API. Essas suítes não validam a execução dos gatilhos PostgreSQL. A validação em navegador conectado ao banco permanece pendente.

## PWA

O build inclui manifest, ícones e service worker, registrado apenas em produção. O cache contém somente o HTML público e os arquivos estáticos da interface; não contém respostas de API, sessões, dados de funcionários, checklists ou filas de escrita. Operações do sistema exigem conexão com o backend. A instalação depende do suporte do navegador e não foi conferida visualmente neste ambiente.

Para verificar o build local, execute `npm.cmd run build` e `npm.cmd run preview` no frontend e abra `http://127.0.0.1:4173`. Antes de iniciar o backend para esse endereço, acrescente essa origem a `APP_CORS_ALLOWED_ORIGINS`, por exemplo `http://127.0.0.1:5173,http://127.0.0.1:4173`. O preview usa o proxy da API configurado no Vite.

## Preparação para hospedagem

A configuração local mantém o backend em `127.0.0.1`. Em um serviço de hospedagem Spring Boot sem Docker, configure `SERVER_ADDRESS`, `SERVER_PORT`, `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` no painel de segredos do provedor. Use a conexão com TLS recomendada pelo PostgreSQL gerenciado, por exemplo `sslmode=verify-full` com certificado confiável, e confirme a compatibilidade com PostgreSQL 18. O banco gerenciado também deve se chamar `roboparts`, pois a proteção atual verifica esse nome antes de permitir migrações.

No frontend da Vercel, defina `VITE_API_BASE_URL` com a origem pública da API conforme a configuração do cliente. Variáveis `VITE_*` são incluídas no bundle e não devem conter segredos. Configure `APP_CORS_ALLOWED_ORIGINS` com as origens exatas do frontend e HTTPS; use `SESSION_COOKIE_SECURE=true`. O padrão `SESSION_COOKIE_SAME_SITE=lax` atende a execução local. Em origens de sites diferentes, a topologia de cookies precisa ser validada; se necessário, `none` exige HTTPS e cookies seguros, e alguns navegadores ainda bloqueiam cookies de terceiros. Uma origem comum com proxy evita essa dependência. `API_DOCS_ENABLED=false` desativa Swagger/OpenAPI quando necessário. Configure também o código interno no painel de segredos do backend.

O arquivo `frontend/vercel.json` prepara as rotas do React e cabeçalhos para hospedagem do frontend. Nenhum serviço foi publicado nem validado em hospedagem. O repositório Git local está na branch `main`; remoto, credenciais de provedores e publicação ainda precisam ser configurados.
