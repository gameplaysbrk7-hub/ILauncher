# iOS Style Launcher (Android)

Projeto inicial de launcher Android inspirado na organização visual da tela inicial do iOS. Não é um produto da Apple e não contém código, ícones proprietários ou serviços do iOS.

## O que já funciona
- Pode ser escolhido como aplicativo de tela inicial padrão do Android (HOME).
- Lista apps instalados que aparecem no launcher do Android.
- Abre apps ao tocar nos ícones.
- Grade de aplicativos, dock visual, páginas iniciais e indicador de páginas.
- Biblioteca de apps com busca.
- Tela demonstrativa de widgets.
- Painel de controle demonstrativo com botões e controles visuais.
- Workflow do GitHub Actions para compilar e guardar o APK como artefato.

## Limitações importantes
Este é um protótipo inicial, não uma réplica completa do iOS. O Android impede que um launcher comum replique ou controle algumas funções do sistema da Apple. Os botões de Wi‑Fi, Bluetooth, lanterna, brilho e volume neste protótipo são visuais e não alteram configurações reais. Notificações, widgets reais, pastas editáveis, edição/arraste de ícones, gestos de sistema e configurações avançadas ainda precisam ser implementados. O visual também pode variar conforme fabricante e versão do Android.

## Compilar pelo GitHub
1. Crie um repositório no GitHub.
2. Extraia este ZIP e envie todos os arquivos e pastas para a raiz do repositório.
3. Abra **Actions** e habilite GitHub Actions, se solicitado.
4. Faça um `push` para `main`/`master`, ou execute manualmente **Build Android APK**.
5. Quando o workflow terminar, abra a execução e baixe o artefato `ios-style-launcher-debug`.
6. Dentro do artefato estará `app-debug.apk`.

## Instalar e definir como padrão
- Instale o APK no Android (pode ser necessário permitir instalação da fonte usada).
- Pressione o botão Home e selecione **iOS Style Launcher** como aplicativo de tela inicial.
- Para voltar, acesse Configurações do Android > Aplicativos padrão > App de início (o nome varia por fabricante).

## Requisitos de build
- JDK 17
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Android SDK 35

## Próximas melhorias recomendadas
- Persistência da posição dos apps e pastas com DataStore.
- Arrastar e soltar ícones e criar pastas.
- Widgets reais usando AppWidgetHost.
- Badges de notificações (com permissões e limitações do Android).
- Personalização de papel de parede, tamanho de ícones e grade.
- Implementar gestos e busca de apps/contatos com permissões adequadas.
