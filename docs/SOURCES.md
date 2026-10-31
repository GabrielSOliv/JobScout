# Fontes da edição pública

- WWR RSS: feed público com atribuição e links de volta: https://weworkremotely.com/remote-job-rss-feed.
- Python.org HTML/RSS: conteúdo de terceiros segundo https://www.python.org/about/legal/; preservar origem.
- Freehire: opcional e desativado até configurar fonte/chave pessoal. Autenticação Bearer e GET /api/v1/jobs/search: https://freehire.me/docs/api. Chave própria não equivale a plano ou licença comercial. Termos: https://freehire.me/terms.
- Greenhouse/Lever: quadros públicos configuráveis, documentação https://docs.greenhouse.io/job-board.html e https://github.com/lever/postings-api. Permissão de um quadro não é licença de todos os empregadores.
- Vagas/Empregos e páginas de carreiras: coletores disponíveis, mas não ativados por padrão quando a base de permissão para distribuição não está comprovada. HTTP 200 e robots não constituem autorização jurídica.
- Google dorks: habilitados por padrão na 0.10.1 a pedido do titular, como na edição pessoal; podem ser desligados em Vagas → engrenagem. A atualização da 0.10.0 restaura o padrão uma vez, preservando escolhas salvas depois. App aberto, consultas limitadas, CAPTCHA humano e pausa após recusa. Não há permissão expressa do Google atestada por este projeto: https://developers.google.com/search/docs/essentials/spam-policies#machine-generated-traffic.
- DuckDuckGo legado: opcional, desativado. Sem autorização irrestrita inferida.

O app processa currículo/perfil localmente. Fontes recebem IP, consultas e dados de conexão; a chave Freehire é enviada somente ao endpoint Freehire via HTTPS. Não são enviados currículo nem histórico para esse endpoint.

Mocks e HTML sintético ficam exclusivamente em testes. Não distribuir uma base capturada de vagas junto do código. Publicação do adaptador não transfere direitos sobre os dados nem autoriza operar um serviço para terceiros.
