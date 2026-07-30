# Leitor — app Android (offline)

Aplicativo Android que roda o seu leitor de voz dentro de um WebView, carregando o
`leitor.html` a partir de `assets` (sem internet). Todas as funções foram mantidas:
leitura em voz, filtro de ruído, markdown com destaque, comentários, transcrição por
voz, notas e atalhos.

Como o WebView do Android não implementa as APIs de voz do navegador, o app usa uma
**ponte nativa** (classe `Bridge` em `MainActivity.java`) para o `TextToSpeech` e o
`SpeechRecognizer` do Android, e o `leitor.html` traz um pequeno "shim" no topo que liga
essas APIs à ponte. Você não precisa mexer em nada disso.

## Gerar o APK no Android Studio (recomendado)

1. Abra o **Android Studio** → *File* → *Open* → selecione a pasta `LeitorApp`.
2. Aguarde o Gradle sincronizar (na primeira vez ele baixa o Gradle 8.2, o Android
   Gradle Plugin e o SDK necessário — precisa de internet só nessa etapa).
   - Se pedir para instalar o **Android SDK Platform 34**, aceite.
3. Menu **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**.
4. Quando terminar, clique em **locate** no aviso que aparece — o arquivo é:
   `app/build/outputs/apk/debug/app-debug.apk`

Esse `app-debug.apk` já instala direto no tablet (é um APK de depuração, assinado
automaticamente com a chave de debug).

## Alternativa por linha de comando

Com o Android SDK e o JDK 17 instalados (variável `ANDROID_HOME` apontando para o SDK):

```
cd LeitorApp
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar no tablet

1. Copie o `app-debug.apk` para o tablet (cabo USB, Google Drive, e-mail, etc.).
2. No tablet, abra o arquivo. Se pedir, permita **"instalar apps de fontes
   desconhecidas"** para o app que está abrindo o APK.
3. Instale e abra. Na primeira vez o app pede permissão de **microfone** (usada só na
   transcrição dos comentários — pode negar se não for usar).

## Qualidade da voz (offline)

A voz vem do próprio Android. Para uma voz pt-BR mais natural e offline:
*Configurações → Acessibilidade → Saída de conversão de texto em voz → Mecanismo do
Google → Instalar dados de voz → Português (Brasil)*. Depois reabra o app.

## Transcrição por voz offline

A transcrição usa o reconhecimento do Android. Para funcionar **offline**, instale o
pacote de digitação por voz offline em pt-BR (no app **Gboard**: *Configurações → Voz →
Reconhecimento de voz offline → baixar Português (Brasil)*). Sem esse pacote, a
transcrição só funciona com internet.

## Personalizar

- Nome do app: `app/src/main/res/values/strings.xml` (`app_name`).
- Ícone: arquivos `ic_launcher.png` em `app/src/main/res/mipmap-*`.
- Pacote/ID: `com.matheus.leitor` (em `app/build.gradle` e no pacote do `MainActivity.java`).
- Atualizar o leitor: basta substituir `app/src/main/assets/leitor.html` (mantendo o
  bloco do shim no topo) e recompilar.
