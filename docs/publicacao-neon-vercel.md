# Publicação da demonstração: Neon e Vercel

## 1. Criar o banco no Neon

Crie um projeto no plano Free, com PostgreSQL **18**, banco **roboparts** e
usuário inicial **roboparts_owner**. O RoboParts já possui autenticação própria;
não é necessário ativar Neon Auth ou Data API.

## 2. Copiar os dados locais

Esta é uma cópia em um momento específico. Alterações posteriores no computador
e no Neon serão independentes, sem sincronização automática.

1. No Neon, abra **Connect**, selecione `roboparts` / `roboparts_owner` e
   **desligue Connection pooling** para obter a conexão direta.
2. Use **Copy snippet** para copiar somente a URL `postgresql://...`.
3. No computador que contém o banco local e a configuração privada do RoboParts,
   abra **Copiar-Banco-Neon.cmd** na pasta do projeto.
4. Cole a URL no campo oculto e pressione Enter. A senha não aparece na tela,
   não é colocada na linha de comando nem salva em arquivo de configuração.
5. Aguarde **COPIA CONCLUIDA E VERIFICADA**. O programa recusa um destino ocupado
   e PostgreSQL diferente da versão 18. Não apague objetos para contornar essa
   proteção; inspecione o destino antes de qualquer nova tentativa.

O script acessa a origem com o usuário somente para leitura `roboparts_backup`.
Exporta apenas o schema `roboparts`, em snapshot consistente, e restaura sem
copiar donos ou permissões locais. A conexão ao Neon exige TLS `verify-full`,
certificado e hostname válidos e channel binding SCRAM. O certificado público
ISRG Root X1 é obtido por HTTPS do site oficial Let's Encrypt, conforme a
documentação do Neon.

A verificação de destino vazio, a criação das tabelas, os dados, as constraints
e a conferência das contagens de todas as tabelas da exportação compartilham uma
única transação. Um erro reverte essa transação. Se a conexão cair durante o
commit, confira o resultado no Neon: o cliente pode não ter recebido a resposta.
Uma segunda execução jamais substitui a instalação existente.

Arquivos temporários que contêm dados ficam em uma pasta privada exclusiva em
`%LOCALAPPDATA%\RoboParts\neon-copy-...`, fora do repositório. O script remove
somente os arquivos que criou, ao terminar. Se o processo for encerrado à força,
a pasta pode permanecer nesse local privado. A senha do Neon não é persistida.

## 3. Preparar o acesso do aplicativo

Gere o pacote separado para nuvem, sem substituir o JAR usado no computador:

```powershell
cd backend
.\mvnw.cmd -Pcloud verify
```

Abra **Preparar-Neon-Vercel.cmd** e cole a mesma conexão **direta** do Neon no
campo oculto. O assistente exige o destino registrado pela cópia anterior,
valida a identidade do RoboParts e o histórico Flyway, e então:

- Instala a infraestrutura de sessões e limites compartilhados em transação.
  O controle `cloud_state` identifica essa infraestrutura separadamente: as sete
  migrações do sistema local permanecem inalteradas. O servidor web só valida
  essas tabelas; não possui DDL nem executa instalação automática.
- Cria `roboparts_web` por SQL, sem conceder privilégios de administração ou
  participação em outros roles. Uma conta existente privilegiada é recusada.
  Auditoria e movimentações continuam sem UPDATE/DELETE; metadados são somente
  para leitura. O backend verifica essas restrições antes de iniciar.
- Define uma senha aleatória exclusiva usando SCRAM, verifica o acesso direto e
  pelo pooler, e armazena a senha do aplicativo com DPAPI na pasta privada.
- Preserva uma cópia protegida da chave de criptografia original, para que a
  autenticação em duas etapas dos dados copiados continue funcionando.

A senha de `roboparts_owner` não é salva. O assistente recusa uma segunda
configuração já registrada, para não trocar inadvertidamente uma senha em uso.
Instalar o acesso do Neon não muda o banco utilizado pelo sistema local.

## 4. Configurar a Vercel

Depois de preparar o acesso, gere o arquivo privado de variáveis:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Export-VercelEnvironment.ps1
```

O resultado fica em `%LOCALAPPDATA%\RoboParts\vercel-production.env`, fora do
GitHub, com permissões restritas ao usuário do Windows. O arquivo contém
segredos; importe-o pelo seletor de arquivos da Vercel e não o compartilhe.

Na Vercel, importe o repositório com **Root Directory `./`**, sem trocar a raiz
para `frontend`. A configuração `vercel.json` na raiz define dois Services:
frontend Vite estático e backend Java usando `backend/Dockerfile.vercel`.
`/api` e `/actuator/health` vão para o backend; as outras rotas vão para o
frontend. As páginas e a API usam o mesmo domínio, sem cookies entre sites.

Configure as variáveis somente em **Production**, incluindo `PORT=8080`,
`SPRING_PROFILES_ACTIVE=prod,vercel`, usuário `roboparts_web`, senha, chave
persistente, responsáveis e Gmail. O arquivo exportado reúne esses valores
usando os segredos já protegidos neste computador. O cadastro do responsável
por código inicial fica desabilitado online; use a conta copiada e os convites.

Mantenha **Automatically expose System Environment Variables** habilitado. A
origem CORS usa exclusivamente o domínio canônico
`VERCEL_PROJECT_PRODUCTION_URL` fornecido pela plataforma, com HTTPS. Não usa
headers enviados pelo visitante nem a URL transitória de cada deploy. Para
definir uma origem manual, passe `-PublicOrigin https://seu-dominio` ao exportar.
O perfil de nuvem recusa `VERCEL_ENV=preview`; previews precisam de ambiente e
banco próprios antes de serem habilitados.

Em **Settings → Functions → Function Regions**, prefira São Paulo (`gru1`)
quando o banco também estiver em São Paulo. Ao alterar variáveis ou região,
faça um novo deploy.

O perfil `vercel` compartilha sessões e limites de tentativas pelo PostgreSQL,
mantém cookies Secure/HttpOnly/SameSite=Lax e usa um pool de até três conexões
por instância, sem conexões ociosas mínimas. As identidades das janelas de limite
são hashes; a reserva persiste mesmo se a transação de negócio falhar. O limite
de capacidade e as reservas concorrentes são coordenados entre instâncias.

Ter os dados e os arquivos preparados **ainda não publica nem valida o site**.
Após o deploy, teste login, recuperação por Gmail, convites, retirada e devolução.
A entrega de e-mail deve ser conferida na hospedagem, incluindo o ciclo de vida
do envio assíncrono. Defina também os backups do banco online: a tarefa agendada
atual cobre somente o banco local.

O runtime de containers e Services da Vercel está em beta. A configuração deve
ser validada na conta antes de tratar a hospedagem como concluída. O plano Hobby
destina-se a uso pessoal não comercial; a demonstração deve respeitar seus limites.

## Verificação do assistente de cópia

Sem acessar banco ou credenciais reais:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-NeonMigration.ps1
```

Para testar também exportação, restauração atômica, recusa de destino ocupado e
rollback após erro de verificação, use `-Postgres`. Essa opção cria um cluster
**descartável** na porta `55439`, somente em loopback, com dados fictícios sob
`backend/target`, e o encerra no final. Requer ferramentas PostgreSQL 18 e o JAR
do backend já gerado. Não altera o PostgreSQL que atende o sistema local.

Com `-Postgres -CloudRuntime`, o mesmo teste também prepara a nuvem como um
proprietário **sem superusuário**, confere a repetição da instalação e o login
SCRAM do usuário restrito, recusa a senha errada e operações administrativas, e
inicia o JAR `target/roboparts-vercel.jar` com `prod,vercel` na porta `58083`.
Confere HTTPS atrás do proxy e a persistência da sessão CSRF no PostgreSQL, e
encerra ambos os servidores de teste no final. Requer `mvnw.cmd -Pcloud verify`.
O build Docker e o comportamento da plataforma ainda precisam ser verificados
na Vercel quando Docker não está instalado no computador.

Referências:

- [Neon: pg_dump / pg_restore](https://neon.com/docs/import/migrate-from-postgres)
- [Neon: conexão segura](https://neon.com/docs/connect/connect-securely)
- [Vercel: Container Runtime](https://vercel.com/docs/functions/runtimes/container)
- [Vercel: Hobby](https://vercel.com/docs/plans/hobby)
- [Vercel: Services](https://vercel.com/docs/services)
- [Vercel: variáveis de sistema](https://vercel.com/docs/environment-variables/system-environment-variables)
