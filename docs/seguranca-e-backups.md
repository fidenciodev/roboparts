# Segurança e backups

## Ativar no Windows

As proteções estão implementadas no projeto. Elas só passam a atender o site depois de configurar e reiniciar o backend com a versão nova. A configuração solicita senhas em uma janela local, com entrada oculta.

1. Gere o backend com `backend\mvnw.cmd -f backend\pom.xml verify` a partir da raiz do projeto.
2. Abra `Configurar-Seguranca.cmd`. Informe a senha atual do PostgreSQL, o Gmail remetente e sua senha de app. Informe o e-mail cadastrado que será responsável pelos acessos. Esse e-mail precisa estar confirmado no RoboParts.
3. Crie uma frase de proteção dos backups, de 24 a 256 caracteres, e guarde uma cópia em um gerenciador de senhas ou outro local fora deste computador. Sem essa frase não será possível recuperar um backup em outra máquina.
4. Depois da mensagem de conclusão, encerre a janela do backend antigo e abra `Iniciar-RoboParts.cmd`. Mantenha o frontend aberto como antes.
5. Entre no site e abra **Segurança**. O responsável pode gerar convites e bloquear ou reativar funcionários. Cada pessoa pode configurar seu próprio autenticador.

Não coloque credenciais no GitHub. Os arquivos `.env.example` são referências e não são carregados automaticamente. O configurador mantém preferências e credenciais em `%LOCALAPPDATA%\RoboParts`, com acesso restrito ao usuário do Windows. Senhas são protegidas pelo DPAPI, vinculado a esse usuário e computador. A senha administrativa do PostgreSQL não é salva. Essa configuração é para desenvolvimento local; produção exige o perfil e a infraestrutura descritos em [publicação segura](publicacao-segura.md).

O configurador aplica migrações aditivas e preserva os registros existentes. Ele não apaga bancos ou tabelas. Atualizações futuras com novas migrações precisam ser aplicadas pelo configurador, usando a conta administrativa somente nesse processo separado. A aplicação usa `roboparts_app`, sem criação de tabelas, administração do banco ou edição/exclusão da auditoria e movimentações. O backup usa a conta de leitura `roboparts_backup`.

## Acesso, convites e duas etapas

- O limite por conta permite cinco tentativas de senha em 15 minutos, além do limite por origem. Trocar de sessão ou endereço de rede não reinicia o contador da conta. A recuperação de senha continua disponível. Os contadores são locais ao processo; várias instâncias exigem armazenamento compartilhado desses limites antes de escalar.
- Novos funcionários precisam de um convite individual vinculado ao e-mail, válido por três dias e usado uma vez. Convites são armazenados apenas como hash. O código inicial compartilhado fica restrito ao cadastro do e-mail responsável configurado, que ainda precisa provar acesso à caixa postal.
- Bloquear uma conta revoga as sessões existentes. Reativar exige novo login; não recupera uma sessão antiga. O responsável não pode bloquear a própria conta.
- Na página Segurança, confirme a senha atual para configurar um aplicativo compatível com TOTP. Adicione manualmente a chave exibida, com códigos baseados em tempo. Confirme o código de seis números em até dez minutos.
- Ao ativar duas etapas, guarde os oito códigos de recuperação mostrados uma única vez. Cada código funciona uma vez. A chave do autenticador fica criptografada no banco e os códigos de recuperação ficam como hashes. Outras sessões são revogadas ao ativar ou desativar duas etapas.
- O login com senha correta só abre uma sessão após confirmar o segundo fator. O desafio dura cinco minutos; códigos já usados não podem ser repetidos. Cinco códigos incorretos bloqueiam a verificação por cinco minutos. A recuperação de senha por e-mail não remove o autenticador.
- Perder o celular e todos os códigos de recuperação exige um procedimento administrativo separado com verificação da identidade; não há um atalho público que remova o segundo fator.

## Onde guardar os backups

O padrão é `%LOCALAPPDATA%\RoboParts\Backups`, fora do código e do GitHub. Use também uma cópia em um HD externo ou armazenamento em nuvem com acesso restrito e histórico de versões. Uma cópia somente neste computador não protege contra perda do computador. Nenhuma conta de nuvem é configurada automaticamente.

Para escolher outro destino, execute localmente:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Configure-Security.ps1 -BackupDirectory "E:\Backups\RoboParts"
```

O configurador só agenda após gerar e verificar o primeiro backup. A tarefa `RoboParts-DailyBackup` executa diariamente às 02:00, com o usuário do Windows conectado. Se perder o horário, tenta na próxima oportunidade. Ela não funciona enquanto o computador estiver desligado ou o usuário desconectado; uma hospedagem precisa do agendador próprio do servidor. Não há exclusão automática dos arquivos antigos. Acompanhe espaço e capacidade do destino.

Cada `.rpb` inclui um dump PostgreSQL do schema RoboParts, a chave de criptografia das duas etapas e um manifesto com hashes. O pacote usa AES-256-GCM, chave derivada por PBKDF2-HMAC-SHA256 com sal aleatório e 600 mil iterações. O script verifica a decifragem antes de informar sucesso. O utilitário atual aceita pacotes de até 256 MiB; instalações maiores precisam de outra solução de backup. Os temporários em texto claro ficam na pasta privada durante a execução e são removidos ao terminar.

Para executar um backup manual:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Backup-Database.ps1
```

Confira `%LOCALAPPDATA%\RoboParts\backup-status.txt` e o histórico da tarefa no Agendador do Windows. Uma mensagem `FAILURE` precisa de investigação. A verificação criptográfica confirma a integridade do arquivo; a restauração abaixo também confirma que o banco pode ser recuperado.

## Testar a restauração sem substituir o banco atual

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-RestoreBackup.ps1 -BackupFile "E:\Backups\RoboParts\arquivo.rpb" -TargetDatabase "roboparts_restore_test_20261016"
```

Informe a frase original do backup e a senha administrativa localmente. O script autentica o pacote, verifica os hashes e restaura em um banco **novo**, com prefixo `roboparts_restore_test_`. Se o destino já existir, a operação falha; ele não é apagado ou substituído. O banco `roboparts` e a chave em uso são preservados. Inspecione o banco de teste no pgAdmin. Faça um teste periódico e depois de mudanças relevantes. A recuperação definitiva em outra máquina exige promover o banco e a chave originais com o servidor parado, em uma operação administrativa planejada.

## Monitoramento e atualizações

A página Segurança do responsável reúne falhas de login e do autenticador, totais das últimas 24 horas e os cem alertas mais recentes. Os identificadores de conta são derivados por HMAC, sem expor e-mails nos logs de alerta. Eventos de segurança são retidos por 30 dias, com limpeza durante o registro de novos eventos. Convites e bloqueios também geram auditoria.

O projeto inclui verificações do backend e frontend no GitHub Actions, auditoria das dependências de produção do npm e atualizações semanais do Dependabot para Maven, npm e Actions. As ações estão fixadas por hash de commit. Esses arquivos só ativam as rotinas remotas quando enviados ao GitHub. Revise e teste as propostas antes de mesclar. Alertas externos, disponibilidade da hospedagem, armazenamento remoto e notificações operacionais dependem do provedor que será escolhido.

Referências: [TOTP — RFC 6238](https://www.rfc-editor.org/rfc/rfc6238), [pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html), [configuração do Dependabot](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference).
