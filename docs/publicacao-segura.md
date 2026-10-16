# Publicação segura

O perfil `prod` prepara o backend para publicação. Ele não instala certificados, não cria uma hospedagem e não altera o servidor local em execução. A escolha de hospedagem e domínio ainda está pendente.

## Configuração preparada

- Requisições HTTP são redirecionadas para HTTPS antes de processar autenticação.
- O cookie de sessão usa `Secure`, `HttpOnly` e `SameSite=Lax`; o prazo padrão de inatividade é de 30 minutos.
- Swagger e os documentos da API ficam desativados e bloqueados, inclusive para funcionários conectados.
- Logs de depuração, detalhes de requisições e depuração de SMTP ficam desativados.
- Origens de frontend precisam ser informadas explicitamente com HTTPS.
- HSTS, proteção contra inclusão em frames e uma política de conteúdo são enviados pelo backend. A configuração do frontend para Vercel também contém esses cabeçalhos.
- O backend recusa parâmetros inseguros antes de criar o contexto, conectar ao banco ou aplicar migrações. O erro informa somente o nome da opção a corrigir.

O modo local continua usando HTTP em `127.0.0.1`, com a documentação acessível para desenvolvimento. Não ative `prod` para testar o endereço local HTTP atual.

## Quando a hospedagem for escolhida

1. Configure o domínio e um certificado TLS válido na hospedagem ou no proxy de entrada. Redirecione HTTP para HTTPS na entrada, antes que o usuário envie sua senha.
2. Configure `SPRING_PROFILES_ACTIVE=prod` e as variáveis reais tomando `.env.production.example` como referência. Esses arquivos são exemplos, não são lidos automaticamente. Não copie as opções locais `SESSION_COOKIE_SECURE=false` ou `API_DOCS_ENABLED=true` para a produção.
3. Publique preferencialmente frontend e API no mesmo domínio, encaminhando `/api` ao backend. A configuração de conteúdo do frontend permite conexões somente à própria origem. Se forem usados domínios diferentes, ajuste `connect-src` para o endereço HTTPS exato da API, além das origens CORS.
4. Mantenha a porta 8080 e o PostgreSQL inacessíveis pela internet. O perfil pressupõe um proxy TLS na mesma máquina e o backend escutando em `127.0.0.1`.
5. O proxy precisa remover os cabeçalhos `Forwarded` e `X-Forwarded-*` recebidos do cliente e preencher seus próprios valores. Somente endereços de proxies conhecidos devem ser confiáveis. O padrão aceita apenas loopback; adapte `server.tomcat.remoteip.internal-proxies` aos proxies reais da hospedagem. Não use uma expressão vazia ou que aceite todos os endereços.
6. Se o TLS terminar diretamente no Java, configure o certificado com o mecanismo de segredos da hospedagem, `server.ssl.enabled=true` e `server.forward-headers-strategy=none`.
7. Confira HTTPS, cookie seguro, CORS, login, confirmação de e-mail e recuperação de senha no domínio real antes de liberar acesso à equipe.

Não marque o serviço como publicado apenas porque o perfil inicia. O certificado e a proteção de rede dependem da hospedagem escolhida.

## Validação no projeto

Os testes de publicação cobrem o redirecionamento antes da criação de sessão, os cabeçalhos HTTPS, o bloqueio da documentação e a recuperação sem login com CSRF. Testes de configuração verificam cookies, origens, logs, proxies, uso de conta restrita, convites obrigatórios, chave persistente e falhas antes da inicialização do contexto. As demais proteções e sua ativação local estão em [Segurança e backups](seguranca-e-backups.md).

Referências oficiais: [HTTPS no Spring Security](https://docs.spring.io/spring-security/reference/servlet/exploits/http.html), [proxies no Spring Boot](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server) e [proteção de cookies na OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#secure-attribute).
