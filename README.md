# RoboParts

## Configuração atual de segurança

Depois de compilar o backend, abra **Configurar-Seguranca.cmd** para preparar o responsável pelos acessos, contas restritas do banco, Gmail e backups criptografados. Digite as senhas somente nessa janela local. Depois encerre o backend antigo e use **Iniciar-RoboParts.cmd**. O guia completo está em [Segurança e backups](docs/seguranca-e-backups.md). A página **Segurança** do site permite configurar o autenticador; o responsável também pode convidar e bloquear funcionários.

As instruções manuais de banco abaixo são referências para manutenção. O iniciador antigo encaminha para a configuração privada quando ela existe. Hospedagem, domínio HTTPS e cópia externa dos backups ainda precisam ser definidos; os arquivos de CI e Dependabot precisam ser enviados ao GitHub para ativar as rotinas remotas.

Sistema interno para controlar a retirada de robôs e seus componentes. As funcionalidades das seis etapas estão implementadas: autenticação restrita, robôs e lista de componentes editável, checklists com snapshot, retiradas e devoluções, histórico, dashboard, interface responsiva, temas e preparação para PWA. A conexão com o PostgreSQL existente e as três migrações foram confirmadas. Os resultados de validação abaixo distinguem os testes locais das verificações no ambiente real.

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

A migração V1 cria a tabela `roboparts.application_metadata`; a V2 acrescenta funcionários e auditoria de autenticação; a V3 acrescenta robôs, componentes, checklists, itens de snapshot e movimentações, além dos detalhes da auditoria. A V4 acrescenta a confirmação de e-mail e os desafios de código; a V5 acrescenta recuperação de senha e revogação de sessões antigas. Nenhuma dessas alterações apaga contas ou históricos. As migrações anteriores permanecem intactas. O Flyway gerencia seu histórico no mesmo schema; o Hibernate apenas valida a estrutura.

## Código interno e autenticação

A preparação para publicação está em [Publicação segura](docs/publicacao-segura.md), com perfil `prod`, HTTPS, cookies seguros, documentação bloqueada e validação antes de acessar o banco. O uso local continua separado; domínio, hospedagem e certificado ainda precisam ser escolhidos e configurados.

Configure `REGISTRATION_ACCESS_CODE` no ambiente do backend com um código secreto de 24 a 256 caracteres, compartilhado apenas com os funcionários autorizados a criar contas. O script de inicialização solicita esse valor com entrada oculta se ele ainda não estiver configurado. Deixar a entrada vazia mantém novos cadastros desabilitados. O login de contas existentes continua disponível. Trocar o código afeta os próximos cadastros.

O código fica exclusivamente no servidor. Não o coloque em variáveis `VITE_*`, arquivos versionados ou mensagens de log. O `.env.example` tem o campo vazio e serve apenas como referência. Para iniciar com novos cadastros desabilitados explicitamente, use `Start-Backend.ps1 -DisableRegistration`.

Abra `/cadastro`, informe nome, e-mail, senha e o código interno. A tela seguinte pede o código de 6 dígitos enviado ao e-mail. Após confirmá-lo, entre em `/entrar` com a sua senha. A confirmação não cria uma sessão automaticamente. A senha deve ter entre 12 e 128 caracteres. O backend normaliza o e-mail, impede duplicidade no banco e armazena somente hashes PBKDF2 com salt aleatório para senhas e códigos. Todos os funcionários têm as mesmas permissões.

O código de e-mail vale por 10 minutos, pode ser usado uma vez e permite até 5 tentativas. Reenviar exige um intervalo de 60 segundos e invalida o código anterior; o limite é de 5 envios por conta por hora. Nem o código nem o hash aparecem na resposta da API ou nos logs. Uma falha de envio desfaz o cadastro pendente ou mantém o código anterior no caso de reenvio. As contas já existentes também precisam confirmar o e-mail no próximo login; a V4 não marca e-mails antigos como verificados sem prova de acesso. A senha correta é exigida antes de iniciar essa confirmação pelo login.

### Configurar envio pelo Gmail

Ative a [verificação em duas etapas e crie uma senha de app](https://support.google.com/accounts/answer/185833) na conta Gmail que enviará os códigos. O SMTP utiliza `smtp.gmail.com`, porta 587 e STARTTLS obrigatório, conforme as [configurações oficiais do Gmail](https://support.google.com/mail/answer/7104828). A senha de app do Gmail é diferente da senha do PostgreSQL, do código interno de cadastro e do código de confirmação recebido por e-mail.

Na pasta `CHECKUP-ROBOS`, inicie o backend com:

Para abrir a configuração sem copiar comandos, entre na pasta `roboparts` e dê dois cliques em **Configurar-Gmail.cmd**. O arquivo abre a página de senhas de app do Google e inicia os prompts de configuração no terminal. A criação da senha de app e a digitação dos dados ficam com você.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\roboparts\scripts\Start-Backend.ps1 -ConfigureEmail
```

O script pede a senha do PostgreSQL e o código interno como antes; depois solicita o Gmail remetente e a senha de app com entrada oculta. A configuração vale apenas para esse processo e é restaurada ao sair. Use `-ConfigureEmail` novamente nas próximas inicializações, ou forneça `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` e `MAIL_FROM` por um gerenciador de segredos no ambiente do servidor. Nenhuma senha deve ser colocada em arquivos versionados. Sem envio configurado, a API informa indisponibilidade ao solicitar um código; não existe confirmação automática nem impressão do código como alternativa.

### Esqueci minha senha

Na tela de login, **Esqueci minha senha** abre `/esqueci-senha`. Informe o e-mail cadastrado, digite o código de recuperação recebido e escolha a nova senha duas vezes (12 a 128 caracteres). Após a troca, entre normalmente com a nova senha. O código também comprova o acesso ao e-mail de contas ainda não confirmadas.

Os códigos de recuperação são separados dos códigos de cadastro, armazenados apenas como hash, válidos por 10 minutos e limitados a 5 tentativas. Reenviar exige 60 segundos e respeita 5 envios por conta por hora; o código anterior perde a validade. O pedido sempre retorna a mesma mensagem, inclusive para e-mails desconhecidos ou dentro do intervalo de reenvio. A busca da conta e o envio acontecem em uma fila limitada no servidor para não expor a existência de contas pelo tempo de envio do Gmail. Falhas desse envio aparecem apenas como aviso genérico no log; nenhum código é impresso.

A migração V5 acrescenta a tabela de recuperação e uma versão de senha, sem alterar senhas existentes. A troca consome o código uma única vez, registra auditoria e revoga as sessões antigas na próxima requisição. Um aviso de senha alterada é enviado após a confirmação da transação; uma falha nesse aviso não desfaz a troca. O fluxo segue as [orientações de recuperação de senha da OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).

Depois de atualizar o projeto, reinicie o backend para aplicar a V5 e carregar as novas rotas. A configuração do Gmail continua necessária para os dois tipos de código.

A autenticação é mantida no servidor e identificada por cookie HttpOnly. A interface restaura a sessão ao recarregar a página; não salva credenciais ou tokens de autenticação no armazenamento local do navegador. A sessão expira após o período de inatividade configurado em `SESSION_TIMEOUT` (padrão: 8 horas). Reiniciar o backend encerra as sessões em memória. O botão **Sair** invalida a sessão no servidor.

As requisições que alteram estado precisam de CSRF: o cliente chama `GET /api/auth/csrf`, envia o token retornado no cabeçalho `X-CSRF-TOKEN` e obtém um novo token depois do login ou logout. A identidade registrada nas atividades vem da autenticação no backend. Não existe operação pública para editar essas atividades. A migração PostgreSQL também acrescenta gatilhos contra atualização, exclusão e truncamento dos registros.

O limite padrão de cadastro, login, confirmação e reenvio é de 20 requisições por minuto por endereço de origem, com capacidade para 4096 endereços na memória do processo. `AUTH_RATE_LIMIT_REQUESTS`, `AUTH_RATE_LIMIT_WINDOW_SECONDS` e `AUTH_RATE_LIMIT_CAPACITY` permitem ajustá-lo. O limite usa o endereço da conexão e não confia em cabeçalhos enviados pelo cliente; tentativas excessivas recebem HTTP 429 com `Retry-After`. Em uma implantação com múltiplas instâncias, sessão e limites compartilhados precisarão ser configurados.

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
| `POST /api/auth/register` | Cadastrar conta pendente com código interno e enviar confirmação; retorna o desafio, sem fazer login |
| `POST /api/auth/login` | Autenticar por e-mail e senha; HTTP 202 com desafio se o e-mail ainda precisar de confirmação |
| `POST /api/auth/verify-email` | Confirmar o código de 6 dígitos com seu `verificationId`; não inicia uma sessão |
| `POST /api/auth/resend-verification` | Reenviar código usando o `verificationId`; respeita intervalo e limite de envios |
| `POST /api/auth/forgot-password` | Solicitar/reenviar recuperação; aceita `email` e retorna HTTP 202 com mensagem genérica |
| `POST /api/auth/reset-password` | Enviar `email`, `code` e `password`; troca a senha com código válido e retorna HTTP 204, sem iniciar sessão |
| `GET /api/auth/me` | Consultar o funcionário da sessão atual |
| `POST /api/auth/logout` | Invalidar a sessão |
| `GET /api/auth/activities?limit=20` | Consultar atividades do próprio funcionário; limite máximo 50 |

Sem sessão válida, as rotas privadas retornam 401. Todos os funcionários autenticados podem operar os robôs e checklists; cada ação registra a identidade da sessão no backend.

## Robôs, checklists e histórico

Abra **Robôs e componentes** para cadastrar um robô. Use **Adicionar componente** para incluir as peças diretamente na lista do robô. Clique no nome para renomear: Enter salva e Escape cancela. Nos detalhes do componente, altere descrição, quantidade, obrigatoriedade e posição na lista. **Mostrar arquivados** permite restaurar componentes individualmente. Os componentes de estruturas antigas aparecem na mesma lista, mantendo seus dados e estados de arquivamento.

Um robô em uso não pode ser arquivado: enquanto houver qualquer quantidade retirada em qualquer checklist, a tentativa é recusada com uma mensagem orientando a devolução completa. A conferência finalizada e a devolução parcial continuam contando como uso. Após devolver todos os componentes, o arquivamento fica disponível. A regra vale para todos os funcionários e o backend também impede que uma retirada e um arquivamento simultâneos deixem um robô arquivado em uso.

Clique em **Iniciar retirada** para criar um checklist independente. Ele copia os nomes, descrições, quantidades e ordem atuais; mudanças posteriores na estrutura não alteram essa cópia. Marque o componente para retirar a quantidade completa, ou informe uma quantidade parcial e use **Registrar**. Desmarcar ou reduzir a quantidade registra uma devolução.

**Confirmar retirada**, seguido de **Confirmar**, exige todos os componentes obrigatórios retirados e ao menos uma retirada registrada. O status exibido passa a **Em uso** e a tela muda automaticamente para **Checklist de devolução**. Marcar uma peça devolve todas as unidades restantes; para uma devolução parcial, informe a quantidade que está devolvendo e clique em **Devolver**. O progresso considera todas as peças retiradas, incluindo as opcionais. Após devolver tudo, o status passa a **Devolvido**. Um checklist vazio ainda não confirmado pode ser cancelado sem apagar seu histórico. Consulte retiradas anteriores em **Retiradas e devoluções** e alterações em **Histórico da equipe**. O código interno `COMPLETED` continua identificando a retirada confirmada para manter compatibilidade com os dados existentes.

As escritas usam transações, bloqueios por robô ou checklist e uma versão esperada. Se outro funcionário salvar antes, a API responde 409: atualize, confira e salve novamente. Criação de checklist e movimentações recebem um UUID de operação; repetir a mesma requisição não repete a retirada. A interface não repete POST automaticamente.

Cada robô aceita até 1000 componentes; quantidades variam de 1 a 1.000.000. As listas e o histórico têm paginação. Busca e filtro de status na interface se aplicam à página exibida.

| Método e rota | Operação |
| --- | --- |
| `GET/POST /api/robots` | Listar / cadastrar robôs |
| `GET/POST /api/robots/{id}` | Consultar / atualizar robô |
| `POST /api/robots/{id}/nodes` | Criar componente diretamente no robô |
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
| Backend: `mvnw.cmd -B verify` | 172 testes na suíte completa e mais 7 cenários de configuração aprovados depois; inclui convites, bloqueio, segundo fator, recuperação, limites por conta, criptografia de backups e rotas de trabalho |
| PostgreSQL 18 temporário, porta 55438 | Sete migrações aplicadas; permissões restritas e conta de backup somente leitura verificadas; dump criptografado restaurado em banco separado; autenticação SCRAM das contas testada com senha correta e incorreta |
| JAR com conta restrita, porta 58082 | Inicialização, validação do histórico sem DDL, saúde e CSRF confirmados no banco temporário; processos de teste encerrados. A ativação no banco do usuário depende do configurador local |
| Inicialização com schema ausente e migrações pendentes | Regressão coberta com Flyway real em H2; criação inicial, atualização e rejeição de checksum alterado verificadas |
| Inicialização do backend sem `DB_PASSWORD` | Proteção confirmada: saída 1 antes de migrar; nenhuma migração executada |
| `Test-Database.ps1` sem senha disponível | Saída 2 confirmada; nenhuma conexão ou alteração realizada |
| Frontend: build de produção | TypeScript e Vite aprovados; arquivos gerados em `frontend/dist` |
| Auditoria de dependências de produção do frontend | Zero vulnerabilidades reportadas |
| Frontend: `npm.cmd test` | 86 testes aprovados em 6 arquivos; segundo fator, códigos de recuperação, gestão de acessos e convites, recuperação e confirmação de e-mail, cadastro/login, cache PWA e retirada/devolução cobertos |
| Gmail: scripts de configuração | Sintaxe PowerShell, configuração apenas no processo, ocultação de senha e restauração do ambiente aprovadas com valores simulados |
| Confirmação de e-mail: V4 e entrega real | V4 e fluxos HTTP verificados em H2 com remetente simulado; V4 aplicada no PostgreSQL local, sem apagar contas; entrega pelo Gmail ainda requer teste real |
| Recuperação de senha: V5 e envio real | Migração e fluxos HTTP verificados em H2 com remetente simulado; aplicar V5 reiniciando o backend; envio real deve ser testado pela tela de recuperação |
| Porta local 5432 | Respondeu à verificação TCP |
| Conexão JDBC e versão real do PostgreSQL | Confirmadas pelo log de inicialização: banco `roboparts`, PostgreSQL 18.6, zero objetos existentes e nenhum histórico Flyway |
| Migrações V1/V2/V3 no PostgreSQL | Confirmadas pelo log local: três migrações aplicadas, schema na versão 3 e backend iniciado |
| Inspeção visual no navegador | Não realizada: nenhum navegador disponível na ferramenta de inspeção |

A resposta TCP confirma apenas que há um serviço escutando na porta; o log posterior confirmou a conexão JDBC. A suíte de backend usa persistência real em H2 e HTTP MockMvc; inclui concorrência, snapshots, idempotência, ciclos, quantidades, auditoria e autenticação. Os testes de frontend simulam a API. Essas suítes não validam a execução dos gatilhos PostgreSQL. A validação em navegador conectado ao banco permanece pendente.

## PWA

O build inclui manifest, ícones e service worker, registrado apenas em produção. O cache contém somente o HTML público e os arquivos estáticos da interface; não contém respostas de API, sessões, dados de funcionários, checklists ou filas de escrita. Operações do sistema exigem conexão com o backend. A instalação depende do suporte do navegador e não foi conferida visualmente neste ambiente.

Para verificar o build local, execute `npm.cmd run build` e `npm.cmd run preview` no frontend e abra `http://127.0.0.1:4173`. Antes de iniciar o backend para esse endereço, acrescente essa origem a `APP_CORS_ALLOWED_ORIGINS`, por exemplo `http://127.0.0.1:5173,http://127.0.0.1:4173`. O preview usa o proxy da API configurado no Vite.

## Preparação para hospedagem

O [guia de publicação com Neon e Vercel](docs/publicacao-neon-vercel.md) descreve a cópia protegida dos dados locais, a criação do usuário restrito `roboparts_web` e a configuração dos segredos. O banco gerenciado deve usar PostgreSQL 18 e se chamar `roboparts`.

O `vercel.json` da raiz reúne o frontend Vite e o backend Java em Services, com `/api` no mesmo domínio. O perfil `prod,vercel` mantém sessões e limites de tentativas no PostgreSQL, cookies seguros e origem HTTPS exata. As tabelas de infraestrutura da nuvem são instaladas por um comando offline separado; o servidor valida o schema e as permissões, sem DDL. O sistema local continua com seu próprio banco e as sete migrações existentes.

Gere o pacote separado com `.\mvnw.cmd -Pcloud verify`, executado na pasta `backend`. Os assistentes `Copiar-Banco-Neon.cmd` e `Preparar-Neon-Vercel.cmd` recebem a conexão administrativa em campo oculto. O exportador gera o arquivo de variáveis fora do projeto, na pasta privada do Windows; importe-o somente em Production. Nenhuma senha, arquivo de dados ou chave deve ser enviada ao GitHub. Variáveis `VITE_*` são públicas e não devem conter segredos.

O runtime de containers e Services da Vercel está em beta. Os arquivos preparados não equivalem a uma publicação concluída: build Docker, entrega de e-mails e fluxos do site precisam ser confirmados na hospedagem. O arquivo `frontend/vercel.json` permanece disponível para hospedagem apenas da interface; na publicação conjunta, importe a raiz `./` do repositório.
