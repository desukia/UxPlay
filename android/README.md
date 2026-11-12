# Apple TV AirPlay Receiver para Google TV (UxPlay Android)

Adaptação do **UxPlay** para **Google TV** e **Android TV**, transformando sua TV em uma verdadeira experiência **Apple TV 4K** com a interface idêntica ao tvOS, protetor de tela aéreo cinemático, diálogo de código PIN em vidro fosco (*frosted glass*), tela de reprodução musical e decodificação acelerada por hardware de baixíssima latência.

---

## 🌟 Principais Recursos

### 1. 🎨 Design e Experiência Idênticos à Apple TV (tvOS)
* **Protetor de Tela Aéreo (Aerial Screensavers)**: Vídeos aéreos em alta definição (San Francisco, Havaí, Londres, Terra vista do espaço) com efeito suave Ken Burns de movimento panorâmico e zoom.
* **Barra de Status tvOS**: Relógio digital elegante no canto superior, indicador de rede Wi-Fi e badge oficial Apple TV.
* **Cartão de Conexão em Vidro Fosco (*Frosted Glass*)**: Cartão translúcido com instruções passo a passo para conectar iPhone, iPad e Mac pela Central de Controle.
* **Código PIN de 4 Dígitos Apple TV**: Quando a segurança estiver ativada, uma janela modal flutuante exibe os 4 dígitos em caixas de vidro individuais com tipografia nítida e brilho suave.
* **Tela "Em Reprodução" (Apple TV Music)**: Capa do álbum em alta resolução com bordas arredondadas, iluminação ambiente dinâmica sincronizada com as cores do álbum, título, artista, nome do álbum e barra de progresso.
* **HUD Flutuante de Volume**: Notificação rápida no canto superior direito ao alterar o volume ou conectar um dispositivo.

### 2. ⚡ Performance e Decodificação Nativa no Google TV
* **Zero-Latency Hardware Decoding**: O stream de vídeo H.264/H.265 é enviado diretamente ao `MediaCodec` do Android e renderizado em `SurfaceView`, garantindo 60 quadros por segundo fluidos com consumo mínimo de CPU e sem superaquecer o Google TV.
* **Áudio de Alta Fidelidade**: Reprodução estéreo 44.1kHz 16-bit via `AudioTrack` com latência ultrabaixa e controle de atenuação em decibéis.
* **Serviço em Segundo Plano Persistente**: Executa como `ForegroundService` no Android, mantendo a descoberta mDNS/Bonjour ativa mesmo se você sair para a tela inicial do Google TV ou estiver usando outro aplicativo.
* **Início Automático no Boot**: Opção para iniciar o receptor AirPlay automaticamente assim que o Google TV ligar.

---

## 📱 Telas do Aplicativo

| Estado | Descrição |
| :--- | :--- |
| **Ambiente / Standby** | Protetor aéreo, relógio, nome do dispositivo na rede e instruções da Central de Controle |
| **Código de Segurança** | Modal tvOS de 4 dígitos para autorização rápida de novos dispositivos |
| **Espelhamento de Vídeo** | Tela cheia fluida 1080p ou 4K @ 60fps com HUD de volume flutuante |
| **Em Reprodução (Áudio)** | Visualizador Apple TV Music com capa do álbum, reflexo colorido dinâmico e barra de progresso |
| **Ajustes** | Menu tvOS em duas colunas para configurar nome, PIN, resolução e inicialização automática |

---

## 🛠️ Como Compilar o Projeto

### Pré-requisitos
* Android Studio Iguana / Jellyfish ou superior (ou Android SDK + NDK 25+)
* JDK 17 ou superior
* CMake 3.22.1+

### Compilando via Android Studio:
1. Abra a pasta `android/` no Android Studio.
2. Aguarde a sincronização do Gradle.
3. Vá no menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. O APK gerado estará em `android/app/build/outputs/apk/release/app-release.apk` (ou `debug/app-debug.apk`).

### Compilando via Linha de Comando:
No Windows (PowerShell):
```powershell
cd android
.\gradlew assembleRelease
```

No macOS / Linux:
```bash
cd android
chmod +x gradlew
./gradlew assembleRelease
```

---

## 📺 Como Instalar no Google TV

### Método 1: Via ADB (Rede Wi-Fi) — *Recomendado*
1. No seu Google TV, vá em **Configurações > Sistema > Sobre > Informações do SO** e clique 7 vezes em **Número da versão** para ativar o Modo Desenvolvedor.
2. Em **Configurações > Sistema > Opções do desenvolvedor**, ative a **Depuração USB / Depuração de Rede**.
3. No seu computador (conectado à mesma rede Wi-Fi da TV), instale o APK:
```bash
adb connect <IP-DO-SEU-GOOGLE-TV>:5555
adb install -r app-release.apk
```

### Método 2: Aplicativo "Send Files to TV"
1. Instale o app gratuito **Send Files to TV** na Google Play Store da TV e no seu celular.
2. Transfira o arquivo `app-release.apk` do seu computador/celular para o Google TV.
3. Use um gerenciador de arquivos (como o *AnExplorer* ou *X-plore*) na TV para abrir e instalar o APK.

### Método 3: Pen Drive / USB
1. Copie o APK para um pen drive formatado em FAT32 ou NTFS.
2. Conecte na porta USB do Google TV (ou hub USB-C).
3. Instale usando um gerenciador de arquivos.

---

## 🚀 Como Usar no iPhone, iPad ou Mac

1. Abra o aplicativo **Apple TV AirPlay** no seu Google TV (ele também pode iniciar sozinho ao ligar a TV).
2. Certifique-se de que seu iPhone, iPad ou Mac está conectado à **mesma rede Wi-Fi** da TV.
3. No seu iPhone ou iPad:
   * Deslize para baixo do canto superior direito para abrir a **Central de Controle**.
   * Toque no ícone de **Espelhar a Tela** (dois retângulos).
   * Selecione **Apple TV** (ou o nome que você definiu nos Ajustes).
4. Se o código de segurança estiver ativado, a TV mostrará o código PIN de 4 dígitos no estilo Apple TV. Digite o código no seu iPhone para emparelhar.
5. Pronto! Sua tela e seu áudio serão transmitidos imediatamente.
