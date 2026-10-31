# Dados e privacidade

O projeto não opera backend próprio ou telemetria. Vagas, perfil profissional, notas, preferências e rascunho de currículo ficam no armazenamento privado do aplicativo. O texto bruto/PDF importado não é guardado pelo importador. PDFs exportados ficam no local escolhido pelo usuário e não são apagados ao limpar o app.

Conexões externas são necessárias para consultar as fontes ativadas. Elas recebem IP, termos de busca e metadados de conexão; cargos e habilidades confirmadas podem integrar a consulta HTML, sem envio do PDF/histórico. URLs de logos podem provocar conexão com o host da imagem. Cada site tem suas próprias regras de tratamento de dados.

A chave pessoal Freehire é guardada separadamente, criptografada com AES-GCM/Android Keystore. É enviada como header Bearer apenas às chamadas Freehire configuradas no código. Não há chave compartilhada do mantenedor, upload do currículo à API ou serviço pago contratado pelo app.

Dorks Google usam uma sessão WebView local, com cookies e armazenamento de sites dentro do app. Não são copiadas credenciais ou cookies para o cliente HTTP. Resolver CAPTCHA é uma ação humana, sem garantia de liberação do serviço.

“Apagar todos os dados locais” remove banco, perfil, preferências, cache HTML, sessão WebView e a credencial Freehire. Remover a credencial no app não revoga a chave no provedor. Desinstalar o app elimina seu armazenamento local; arquivos exportados pelo seletor Android ficam fora desse armazenamento.

Backup e transferência automáticos estão desativados. O banco/perfil não recebe criptografia adicional deste app além da proteção do armazenamento Android; a criptografia específica descrita para a chave não se aplica ao banco inteiro. Consulte [SECURITY.md](../SECURITY.md) para limites.

Este documento descreve o fonte distribuído. Uma publicação em loja precisa de política pública e identificação/canal de contato do responsável pela distribuição; este arquivo não é uma declaração de aprovação Google Play nem de dispensa geral da LGPD.
