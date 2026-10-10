# Assinador ICP-Brasil

Aplicativo desktop, livre e de código aberto, para assinar PDFs com certificados digitais **A1 ICP-Brasil** (`.pfx`/`.p12`) no **Linux** e no **Windows**.

## Funcionalidades

- Vários certificados A1 cadastrados, com escolha do certificado a cada assinatura.
- Assinatura de arquivo único com pré-visualização, escolha da página e posicionamento do carimbo visual.
- Assinatura em lote (o processamento continua se um arquivo falhar).
- PAdES ICP-Brasil **AD-RB** por padrão; **AD-RT** quando um servidor de carimbo do tempo (TSA) é configurado.
- Preserva assinaturas existentes (assinatura incremental).
- Gera `arquivo_assinado.pdf` na mesma pasta, sem sobrescrever o original.
- Valida validade, KeyUsage e cadeia contra as raízes ICP-Brasil embarcadas, completando intermediárias via AIA quando o A1 não as traz.
- Sem servidor e sem telemetria: tudo roda no seu computador.

## Download

Baixe a versão mais recente em [Releases](../../releases):

- **Linux:** `Assinador-ICP-Brasil-<versão>-x86_64.AppImage` — dê permissão de execução (`chmod +x`) e abra.
- **Windows:** `Assinador-ICP-Brasil-<versão>-windows-x64.zip` — extraia e execute `ASSINADOR.exe`.

Os dois pacotes já incluem o Java; não é preciso instalar nada.

## Segurança e privacidade

- Os certificados ficam somente no seu computador, em `~/.assinador` (Windows: `%USERPROFILE%\.assinador`), num cofre cifrado com AES-256-GCM.
- O cofre abre sem senha ao iniciar: a chave fica em `.assinador/auto-unlock.key`, legível só pelo seu usuário (permissão 600 no Linux). Assim, a proteção dos certificados depende da segurança da sua conta no computador — use senha de login e criptografia de disco.
- Cofres antigos, criados com senha mestra (PBKDF2-HMAC-SHA-256, 600.000 iterações), pedem a senha uma única vez para migrar para o desbloqueio automático.
- O app não registra senhas, chaves privadas nem conteúdo de documentos em logs.

## Compilar a partir do código

Requisitos: JDK 21 e Maven.

```bash
mvn package            # compila e roda os testes
mvn javafx:run         # executa o app
```

Os pacotes nativos são gerados com `jpackage` (JDK 21), usando `br.com.vida.assinador.Launcher` como classe principal.

## Tecnologias

Java 21, JavaFX, [AtlantaFX](https://github.com/mkpaz/atlantafx), [DSS](https://github.com/esig/dss) (LGPL-2.1), PDFBox e Bouncy Castle.

## Licença

[MIT](LICENSE). As bibliotecas de terceiros mantêm suas próprias licenças.
