# Segurança

Esta edição processa dados no aparelho, mas recebe conteúdo externo. Uso individual não elimina falhas de parser, vazamento de chave ou risco de arquivos maliciosos.

## Medidas implementadas

- Chave Freehire inserida pelo usuário, sem valor embutido; AES-256-GCM com Android Keystore. IV novo a cada gravação e autenticação do ciphertext; sem fallback para texto puro.
- Credencial separada de preferências/perfil, campo mascarado e sem saved instance state. Remoção local e exclusão de dados também removem a chave criptográfica. Dispositivo comprometido ou processo em execução comprometido ainda pode expor o token em uso.
- Bearer restrito à chamada Retrofit Freehire, sem header global, redirecionamento automático ou cópia de cookies do WebView para o cliente HTTP.
- HTTPS, validação TLS padrão, recusa de erro SSL no WebView, sem JavaScript bridge nativa, sem acesso WebView a arquivos locais.
- Limites de leitura HTML/RSS, tamanho/páginas do PDF e cache; paginação com detecção de repetição; recusa de entidades externas em RSS; atualização de Jsoup e alinhamento das três bibliotecas Bouncy Castle.
- Sem upload do currículo ou telemetria própria; backup/transferência de dados desativados no manifest.

## Limites

- As APIs JSON (Greenhouse, Lever e Freehire) são convertidas em memória pelo Retrofit/Gson sem teto de bytes por resposta. Respostas excessivas podem esgotar a memória; o limite do leitor HTML/RSS não cobre esse caminho. A paginação também pode acumular muitos registros. Isso é um risco de disponibilidade identificado por inspeção, sem exploração reproduzida nesta revisão.
- O limite de 40 páginas do PDF é verificado depois de carregar o documento. O teto de 10 MB de entrada não limita o tamanho descomprimido nem o tempo total do parser; PDFs especialmente construídos podem consumir CPU/memória excessivas.

- PDFBox-Android é um port antigo, sem equivalência automática entre seus metadados e todos os avisos do PDFBox upstream. Use PDFs de origem conhecida; limites de tamanho/páginas não provam resistência a todo arquivo malicioso. Não há sandbox de parser em processo separado.
- Criptografia das credenciais não significa criptografia adicional do banco/perfil inteiro; esses dados usam o armazenamento privado e a proteção do Android.
- Scanners consultam bases e padrões conhecidos; saída sem achados não é certificado de segurança. Não há teste de exploração de todos os caminhos de bibliotecas, bateria prolongada, Android antigo ou aprovação Play Store.
- Dorks e outros coletores dependem de permissão e regras dos provedores; presença no código não atesta autorização.

As versões resolvidas das dependências estão em [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Nenhuma chave autenticada real foi necessária para os testes; requisições de autenticação são testadas com MockWebServer e tokens ficcionais.

Para relatar uma falha, não inclua chaves, currículos ou dados pessoais em issue pública. O mantenedor deve disponibilizar um canal privado ou habilitar o recurso de reporte privado do GitHub antes de solicitar dados sensíveis.
