# ASSINADOR — Design

Data: 2026-09-24

## Objetivo

Aplicativo desktop para Linux e Windows destinado à assinatura de PDFs com certificados A1 ICP-Brasil. O fluxo deve ser semelhante ao Assinador Serpro, permitir vários certificados cadastrados, assinatura de arquivo único e assinatura em lote.

## Decisões aprovadas

- Java 21 + JavaFX + AtlantaFX.
- DSS como motor PAdES.
- Somente PDF nesta primeira versão.
- Certificados A1 PKCS#12 (`.pfx`/`.p12`).
- Vários certificados no cofre local, selecionáveis por assinatura.
- Arquivo único: pré-visualização, escolha da página e posicionamento do carimbo visual.
- Lote: carimbo na última página, em posição predefinida.
- Arquivo de saída com sufixo `_assinado`, na mesma pasta do original e sem sobrescrevê-lo.
- PAdES ICP-Brasil AD-RB como padrão.
- TSA opcional; quando configurada, assinatura com referência de tempo AD-RT.
- Sem servidor e sem telemetria.

## Segurança

- O PKCS#12 é armazenado somente no computador do usuário.
- O cofre local usa AES-256-GCM.
- A chave do cofre é derivada da senha mestra por PBKDF2-HMAC-SHA-256.
- Senhas de certificados não são persistidas.
- As credenciais de TSA, quando usadas, são armazenadas cifradas no mesmo cofre.
- O app não registra senhas, chaves privadas nem conteúdo de documentos em logs.

## Fluxo principal

1. Desbloquear ou criar o cofre local.
2. Importar um ou mais certificados A1.
3. Escolher um certificado.
4. Adicionar um ou vários PDFs.
5. Para arquivo único, escolher página e posicionar o carimbo.
6. Para lote, escolher a posição do carimbo na última página.
7. Informar a senha do certificado.
8. Assinar e salvar de forma atômica.
9. Exibir resumo de sucesso e falhas.

## Critérios de aceite

- Abre no Linux e Windows.
- Importa e lista múltiplos certificados A1.
- Assina PDF único com carimbo visual posicionável.
- Assina vários PDFs em sequência com o carimbo na última página.
- Preserva assinaturas PDF anteriores por atualização incremental sempre que o motor DSS permitir.
- Suporta TSA configurável sem tornar a TSA obrigatória.
- Erro em um item de lote não interrompe os demais.
- Testes cobrem cofre, nomenclatura de saída e cálculo de posicionamento.
- A aceitação ICP-Brasil final deve ser verificada com certificado real no Verificador do ITI e em leitor PDF de referência.
