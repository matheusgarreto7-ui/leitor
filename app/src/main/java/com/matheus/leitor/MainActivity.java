package com.matheus.leitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.TypedValue;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Leitor — WebView + ponte nativa de voz.
 *
 * Novidade: botão "Voz" que deixa o usuário escolher, DENTRO do app, qual
 * mecanismo (TextToSpeech) e qual voz usar. A escolha fica salva e sempre é
 * usada na leitura — não depende mais da configuração do sistema.
 */
public class MainActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String currentEngine = null; // pacote do mecanismo ligado agora

    private SpeechRecognizer recognizer;
    private String recLang = "pt-BR";
    private static final int REQ_MIC = 42;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("leitor", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Botão flutuante "Voz" (canto superior direito)
        Button btnVoz = new Button(this);
        btnVoz.setText("🔊 Voz");
        btnVoz.setAllCaps(false);
        btnVoz.setTextColor(Color.WHITE);
        btnVoz.setBackgroundColor(0xCC6C5CE7);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        int m = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        lp.setMargins(0, m, m, 0);
        root.addView(btnVoz, lp);
        btnVoz.setOnClickListener(v -> showEnginePicker());

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDefaultTextEncodingName("utf-8");
        web.addJavascriptInterface(new Bridge(), "AndroidNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return false; }
        });
        // Sem um WebChromeClient o WebView ignora alert/confirm/prompt: era por isso
        // que os botoes "limpar" das notas e dos comentarios nao faziam nada.
        web.setWebChromeClient(new WebChromeClient());

        initTts(prefs.getString("engine", null), null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        web.loadUrl("file:///android_asset/leitor.html");
    }

    // ---------- TTS init ----------
    private void initTts(final String enginePkg, final Runnable onReady) {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) {}
        ttsReady = false;
        final TextToSpeech.OnInitListener init = status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                currentEngine = (enginePkg != null) ? enginePkg : safeDefaultEngine();
                try { tts.setLanguage(new Locale("pt", "BR")); } catch (Exception ignored) {}
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) { js("window.__ttsEnd && window.__ttsEnd(" + q(id) + ")"); }
                    @Override public void onError(String id) { js("window.__ttsError && window.__ttsError(" + q(id) + ")"); }
                    @Override public void onStop(String id, boolean interrupted) {}
                });
                applySavedVoice();
                pushVoices();
                if (onReady != null) runOnUiThread(onReady);
            } else {
                runOnUiThread(() -> {
                    if (enginePkg != null) {
                        Toast.makeText(MainActivity.this,
                                "Não consegui carregar esse mecanismo. Voltando ao padrão.",
                                Toast.LENGTH_LONG).show();
                        prefs.edit().remove("engine").remove("voice").apply();
                        initTts(null, onReady);
                    }
                });
            }
        };
        if (enginePkg != null && !enginePkg.isEmpty()) {
            tts = new TextToSpeech(this, init, enginePkg);
        } else {
            tts = new TextToSpeech(this, init);
        }
    }

    private String safeDefaultEngine() {
        try { return tts.getDefaultEngine(); } catch (Exception e) { return null; }
    }

    private void applySavedVoice() {
        try {
            String vn = prefs.getString("voice", null);
            if (vn == null) return;
            Set<Voice> vs = tts.getVoices();
            if (vs == null) return;
            for (Voice v : vs) {
                if (v != null && vn.equals(v.getName())) { tts.setVoice(v); return; }
            }
        } catch (Exception ignored) {}
    }

    // ---------- Seletor de mecanismo + voz ----------
    private void showEnginePicker() {
        if (tts == null) return;
        final List<TextToSpeech.EngineInfo> engines;
        try { engines = tts.getEngines(); } catch (Exception e) { return; }
        if (engines == null || engines.isEmpty()) return;
        final String[] labels = new String[engines.size()];
        int sel = -1;
        for (int i = 0; i < engines.size(); i++) {
            labels[i] = engines.get(i).label;
            if (engines.get(i).name.equals(currentEngine)) sel = i;
        }
        new AlertDialog.Builder(this)
            .setTitle("Mecanismo de voz")
            .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                final String pkg = engines.get(which).name;
                dialog.dismiss();
                initTts(pkg, () -> {
                    prefs.edit().putString("engine", pkg).remove("voice").apply();
                    showVoiceList();
                });
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void showVoiceList() {
        if (!ttsReady) return;
        final List<Voice> pt = new ArrayList<>();
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs != null) {
                for (Voice v : vs) {
                    if (v == null) continue;
                    String lang = (v.getLocale() != null) ? v.getLocale().toString().toLowerCase() : "";
                    if (lang.contains("pt")) pt.add(v);
                }
            }
        } catch (Exception ignored) {}
        if (pt.isEmpty()) {
            Toast.makeText(this, "Este mecanismo não tem vozes em português.", Toast.LENGTH_LONG).show();
            return;
        }
        final String[] names = new String[pt.size()];
        String savedVoice = prefs.getString("voice", null);
        int sel = -1;
        for (int i = 0; i < pt.size(); i++) {
            names[i] = pt.get(i).getName();
            if (names[i].equals(savedVoice)) sel = i;
        }
        final int[] chosen = { sel };
        new AlertDialog.Builder(this)
            .setTitle("Escolha a voz (toque para ouvir)")
            .setSingleChoiceItems(names, sel, (dialog, which) -> {
                chosen[0] = which;
                try {
                    tts.setVoice(pt.get(which));
                    tts.setSpeechRate(1.0f);
                    tts.speak("Olá, esta é a voz selecionada para o leitor.",
                              TextToSpeech.QUEUE_FLUSH, null, "preview");
                } catch (Exception ignored) {}
            })
            .setPositiveButton("Usar esta voz", (dialog, w) -> {
                if (chosen[0] >= 0) {
                    Voice v = pt.get(chosen[0]);
                    prefs.edit().putString("voice", v.getName()).apply();
                    try { tts.setVoice(v); } catch (Exception ignored) {}
                    Toast.makeText(this, "Voz salva. ✔", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ---------- utilidades ----------
    private void js(final String code) {
        if (web == null) return;
        web.post(() -> { try { web.evaluateJavascript(code, null); } catch (Exception ignored) {} });
    }
    private static String q(String s) { return (s == null) ? "''" : JSONObject.quote(s); }
    private Locale localeFromTag(String tag) {
        try {
            if (tag == null || tag.isEmpty()) return new Locale("pt", "BR");
            return Locale.forLanguageTag(tag);
        } catch (Exception e) { return new Locale("pt", "BR"); }
    }
    private void pushVoices() {
        if (!ttsReady) return;
        JSONArray arr = new JSONArray();
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice v : voices) {
                    try {
                        if (v == null) continue;
                        Locale l = v.getLocale();
                        JSONObject o = new JSONObject();
                        o.put("name", v.getName());
                        o.put("lang", l != null ? l.toLanguageTag() : "pt-BR");
                        arr.put(o);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        js("window.__ttsVoices && window.__ttsVoices(" + q(arr.toString()) + ")");
    }

    @Override protected void onPause() {
        // trava de seguranca: o app nunca pode sair de cena deixando o som mudo
        muteBeeps(false);
        super.onPause();
    }
    @Override protected void onDestroy() {
        muteBeeps(false);
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) {}
        try { if (recognizer != null) recognizer.destroy(); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    // ================= PONTE JS =================
    public class Bridge {
        @JavascriptInterface
        public void speak(final String text, final float rate, final float pitch,
                          final String voiceName, final String lang, final String id) {
            runOnUiThread(() -> {
                muteBeeps(false); // garante que a leitura em voz nunca saia muda
                if (!ttsReady) { js("window.__ttsError && window.__ttsError(" + q(id) + ")"); return; }
                try {
                    tts.setSpeechRate(rate > 0 ? rate : 1f);
                    tts.setPitch(pitch > 0 ? pitch : 1f);
                    String savedVoice = prefs.getString("voice", null);
                    String target = (savedVoice != null) ? savedVoice : voiceName;
                    boolean set = false;
                    if (target != null && !target.isEmpty()) {
                        Set<Voice> vs = tts.getVoices();
                        if (vs != null) {
                            for (Voice v : vs) {
                                if (v != null && target.equals(v.getName())) { tts.setVoice(v); set = true; break; }
                            }
                        }
                    }
                    if (!set) tts.setLanguage(localeFromTag(lang));
                    tts.speak(text == null ? "" : text, TextToSpeech.QUEUE_FLUSH, null, id);
                } catch (Exception e) {
                    js("window.__ttsError && window.__ttsError(" + q(id) + ")");
                }
            });
        }
        @JavascriptInterface public void stop() {
            runOnUiThread(() -> { try { if (ttsReady) tts.stop(); } catch (Exception ignored) {} });
        }
        @JavascriptInterface public void requestVoices() { runOnUiThread(MainActivity.this::pushVoices); }
        /** Guarda preferencias da tela (ex.: velocidade) fora do WebView, para nao se perderem. */
        @JavascriptInterface public void setPref(final String key, final String value) {
            try { prefs.edit().putString("web_" + key, value == null ? "" : value).apply(); } catch (Exception ignored) {}
        }
        @JavascriptInterface public String getPref(final String key, final String def) {
            String fallback = (def == null) ? "" : def;
            try { return prefs.getString("web_" + key, fallback); } catch (Exception e) { return fallback; }
        }
        @JavascriptInterface public void copy(final String text) {
            runOnUiThread(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("leitor", text == null ? "" : text));
                } catch (Exception ignored) {}
            });
        }
        @JavascriptInterface public boolean srAvailable() {
            try { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void startRecognition(final String lang) {
            recLang = (lang == null || lang.isEmpty()) ? "pt-BR" : lang;
            runOnUiThread(MainActivity.this::startListening);
        }
        @JavascriptInterface public void stopRecognition() { runOnUiThread(MainActivity.this::stopListening); }
        @JavascriptInterface public void pickVoice() { runOnUiThread(MainActivity.this::showEnginePicker); }
        @JavascriptInterface public void pasteInto() {
            runOnUiThread(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData cd = cm.getPrimaryClip();
                    String t = "";
                    if (cd != null && cd.getItemCount() > 0) {
                        CharSequence c = cd.getItemAt(0).coerceToText(MainActivity.this);
                        t = (c == null) ? "" : c.toString();
                    }
                    js("window.__pasteText && window.__pasteText(" + q(t) + ")");
                } catch (Exception ignored) {}
            });
        }
    }

    // ================= SpeechRecognizer (ditado contínuo) =================
    //
    // v7 — quem acumula o texto agora é o JAVA, não o HTML.
    //
    // O Java é o único que sabe COM CERTEZA onde uma frase termina (onResults).
    // Antes ele mandava texto solto e o HTML tentava adivinhar as fronteiras por
    // tamanho/prefixo — era esse chute que fazia a frase nova apagar a anterior.
    //
    //   committed = tudo que já foi confirmado.  NUNCA é apagado.
    //   partial   = hipótese da frase em andamento. Só ela muda na tela.
    //   tela      = committed + partial
    //
    // Com isso, apagar virou impossível por construção: nenhum caminho do código
    // escreve por cima de committed.
    private volatile boolean listening = false;
    private Handler recHandler;
    private String committed = "";
    private String partial = "";
    private String prevPartial = "";
    private long ultimaMudanca = 0;
    private final List<Integer> pausas = new ArrayList<>();
    private int errStreak = 0;
    private boolean onDevice = false;
    private boolean onDeviceGaveUp = false;

    // ---- silenciar os bipes do reconhecedor ----
    // Cada startListening() toca o tom de início/fim do Google. Reiniciando a cada
    // frase, isso vira o "pulsar" que se ouvia. Silenciamos só enquanto o mic está
    // ligado e restauramos em qualquer saída (parar, pausar, fechar, erro).
    private AudioManager audioMgr;
    private boolean beepsMuted = false;
    private static final int[] BEEP_STREAMS = {
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_RING
    };

    private void muteBeeps(boolean mute) {
        try {
            if (audioMgr == null) audioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioMgr == null || mute == beepsMuted) return;
            for (int st : BEEP_STREAMS) {
                try {
                    audioMgr.adjustStreamVolume(st,
                        mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
                } catch (Exception ignored) {}
            }
            beepsMuted = mute;
        } catch (Exception ignored) {}
    }

    // ================= Pontuação automática do ditado =================
    private static final LinkedHashMap<String, String> COMANDOS_VOZ = new LinkedHashMap<>();
    static {
        COMANDOS_VOZ.put("ponto de interrogacao", "?");
        COMANDOS_VOZ.put("ponto de exclamacao", "!");
        COMANDOS_VOZ.put("ponto e virgula", ";");
        COMANDOS_VOZ.put("abre parenteses", "(");
        COMANDOS_VOZ.put("fecha parenteses", ")");
        COMANDOS_VOZ.put("abre parentese", "(");
        COMANDOS_VOZ.put("fecha parentese", ")");
        COMANDOS_VOZ.put("novo paragrafo", "\n\n");
        COMANDOS_VOZ.put("quebra de linha", "\n");
        COMANDOS_VOZ.put("nova linha", "\n");
        COMANDOS_VOZ.put("abre aspas", "\u201C");
        COMANDOS_VOZ.put("fecha aspas", "\u201D");
        COMANDOS_VOZ.put("dois pontos", ":");
        COMANDOS_VOZ.put("ponto final", ".");
        COMANDOS_VOZ.put("reticencias", "...");
        COMANDOS_VOZ.put("interrogacao", "?");
        COMANDOS_VOZ.put("exclamacao", "!");
        COMANDOS_VOZ.put("paragrafo", "\n\n");
        COMANDOS_VOZ.put("virgula", ",");
        COMANDOS_VOZ.put("travessao", "\u2014");
        COMANDOS_VOZ.put("ponto", ".");
    }

    /** minusculo, sem acento e sem pontuacao: "Vírgula," -> "virgula" */
    private static String simples(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static void anexar(StringBuilder sb, String pedaco, boolean pontuacao) {
        if (pedaco == null || pedaco.isEmpty()) return;
        if (sb.length() == 0) { sb.append(pedaco); return; }
        boolean abertura = pedaco.equals("(") || pedaco.equals("\u201C");
        if (pontuacao && !abertura) {
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') sb.setLength(sb.length() - 1);
            sb.append(pedaco);
            return;
        }
        char ult = sb.charAt(sb.length() - 1);
        if (ult != '\n' && ult != ' ' && ult != '(' && ult != '\u201C') sb.append(' ');
        sb.append(pedaco);
    }

    /** Troca as palavras faladas pelos sinais: "virgula" -> "," */
    private static String aplicarComandosDeVoz(String texto) {
        if (texto == null || texto.trim().isEmpty()) return "";
        String[] tk = texto.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < tk.length) {
            String simbolo = null; int usados = 0;
            for (int n = 3; n >= 1 && simbolo == null; n--) {
                if (i + n > tk.length) continue;
                StringBuilder chave = new StringBuilder();
                for (int k = 0; k < n; k++) { if (k > 0) chave.append(' '); chave.append(simples(tk[i + k])); }
                String c = chave.toString();
                // "ponto de vista"/"ponto de partida": aqui "ponto" e palavra normal
                if (c.equals("ponto") && i + 1 < tk.length && simples(tk[i + 1]).equals("de")) continue;
                String v = COMANDOS_VOZ.get(c);
                if (v != null) { simbolo = v; usados = n; }
            }
            if (simbolo != null) { anexar(sb, simbolo, true); i += usados; }
            else { anexar(sb, tk[i], false); i++; }
        }
        return sb.toString();
    }

    /** Vírgulas nas pausas curtas. So insere onde ainda ha um espaco: se o motor
     *  revisou a frase e a posicao nao bate mais, simplesmente nao mexe. */
    private static String inserirVirgulas(String texto, List<Integer> posicoes) {
        if (texto == null || texto.isEmpty() || posicoes == null || posicoes.isEmpty()) return texto;
        StringBuilder sb = new StringBuilder(texto);
        TreeSet<Integer> ord = new TreeSet<>(posicoes);
        Iterator<Integer> it = ord.descendingIterator();
        while (it.hasNext()) {
            int p = it.next();
            if (p <= 0 || p >= sb.length()) continue;
            if (sb.charAt(p) != ' ') continue;
            char ant = sb.charAt(p - 1);
            if (",.;:!?".indexOf(ant) >= 0) continue;
            sb.insert(p, ',');
        }
        return sb.toString();
    }

    private static String limparEspacos(String t) {
        t = t.replaceAll("[ \\t]+", " ");
        t = t.replaceAll("[ \\t]+([,.;:!?])", "$1");
        // a pausa e a palavra "virgula" podem cair no mesmo lugar: nao pode dobrar
        t = t.replaceAll("([,;:])(?:[ \\t]*[,;:])+", "$1");
        t = t.replaceAll("[,;:]+[ \\t]*([.!?])", "$1");
        t = t.replaceAll("([,;:])(?=[^\\s])", "$1 ");
        t = t.replaceAll("([.!?])(?=[\\p{L}])", "$1 ");
        t = t.replaceAll("[ \\t]*\n[ \\t]*", "\n");
        // apara so espacos: um trim() comum comeria a quebra de paragrafo do inicio
        return t.replaceAll("^[ \\t]+", "").replaceAll("[ \\t]+$", "");
    }

    private static String maiusculaInicial(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) return t.substring(0, i) + Character.toUpperCase(c) + t.substring(i + 1);
            if (!Character.isWhitespace(c) && c != '\u201C' && c != '(') break;
        }
        return t;
    }

    private static boolean comecoDeFrase(String c) {
        if (c == null || c.trim().isEmpty()) return true;
        String t = c.trim();
        return ".!?".indexOf(t.charAt(t.length() - 1)) >= 0 || c.endsWith("\n");
    }

    /** Fecha a frase: arruma espacos, maiuscula no comeco e ponto no fim. */
    private static String finalizarFrase(String t, boolean comeco) {
        t = limparEspacos(t);
        if (t.isEmpty()) return "";
        if (comeco) t = maiusculaInicial(t);
        char ult = t.charAt(t.length() - 1);
        if (".!?,;:".indexOf(ult) < 0 && !t.endsWith("\n")) t = t + ".";
        return t;
    }

    // ---- montagem do texto ----
    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return (b == null) ? "" : b;
        if (b == null || b.isEmpty()) return a;
        if (a.endsWith("\n") || b.startsWith("\n")) return a.replaceAll("[ \\t]+$", "") + b;
        return a.replaceAll("[ \\t]+$", "") + " " + b;
    }

    private void emitState() {
        // na tela, a frase em andamento ja aparece com os sinais falados trocados
        String mostrar = partial.isEmpty() ? "" : aplicarComandosDeVoz(partial);
        js("window.__srText && window.__srText(" + q(join(committed, mostrar)) + ")");
    }

    /**
     * A frase nova ainda e a mesma de antes (o motor so revisou o palpite), ou o
     * motor recomecou do zero? Crescer, ou manter o mesmo comeco, e revisao.
     */
    private static boolean isContinuation(String prev, String next) {
        if (next.length() >= prev.length()) return true;      // cresceu: mesma frase
        String a = prev.toLowerCase(), b = next.toLowerCase();
        if (a.startsWith(b) || b.startsWith(a)) return true;  // revisao da mesma frase
        int n = Math.min(6, Math.min(a.length(), b.length()));
        return n > 0 && a.regionMatches(0, b, 0, n);          // mesmo comeco: mesma frase
    }

    /** Fecha a frase atual: pontua e vira texto definitivo. */
    private void commitPartial() {
        String p = (partial == null) ? "" : partial.trim();
        if (!p.isEmpty()) {
            p = inserirVirgulas(p, pausas);          // pausas curtas viram virgula
            p = aplicarComandosDeVoz(p);             // "virgula", "ponto", "paragrafo"...
            p = finalizarFrase(p, comecoDeFrase(committed));
            committed = join(committed, p);
        }
        partial = "";
        pausas.clear();
    }

    private final RecognitionListener recListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) {}
        @Override public void onBeginningOfSpeech() { errStreak = 0; }
        @Override public void onRmsChanged(float r) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}

        @Override public void onPartialResults(Bundle b) {
            String t = firstResult(b);
            if (t == null || t.trim().isEmpty()) return;
            errStreak = 0;
            t = t.trim();
            // pausa curta no meio da fala -> marca o ponto para virar virgula no fim
            long agora = System.currentTimeMillis();
            if (!prevPartial.isEmpty() && t.startsWith(prevPartial) && t.length() > prevPartial.length()) {
                long gap = agora - ultimaMudanca;
                if (gap >= 700 && gap <= 2600) pausas.add(prevPartial.length());
            }
            ultimaMudanca = agora;
            // Rede de seguranca: em alguns aparelhos o motor recomeca a frase do
            // zero DENTRO da mesma sessao, sem mandar onResults. Se isso acontecer,
            // fechamos a frase anterior em vez de deixar a nova escrever por cima.
            if (!prevPartial.isEmpty() && !isContinuation(prevPartial, t)) {
                partial = prevPartial;
                commitPartial();
            }
            prevPartial = t;
            partial = t;
            emitState();
        }

        @Override public void onResults(Bundle b) {
            String t = firstResult(b);
            if (t != null && !t.trim().isEmpty()) partial = t.trim();
            commitPartial();
            prevPartial = "";
            emitState();
            errStreak = 0;
            restartSoon(onDevice ? 120 : 250);
        }

        @Override public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                listening = false;
                muteBeeps(false);
                js("window.__srError && window.__srError('permissao')");
                js("window.__srEnd && window.__srEnd()");
                return;
            }
            // Serviço ainda ocupado: apenas espera mais um pouco. Não conta como
            // falha e não mexe no texto — era aqui que a v6 entrava em loop.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { restartSoon(450); return; }

            // Motor on-device sem o idioma baixado: cai para o reconhecedor normal.
            if (onDevice && (error == 12 || error == 13 || error == SpeechRecognizer.ERROR_SERVER)) {
                onDeviceGaveUp = true;
                try { if (recognizer != null) recognizer.destroy(); } catch (Exception ignored) {}
                recognizer = buildRecognizer();
                restartSoon(250);
                return;
            }

            // Qualquer outro fim de sessão: salva o que já foi dito e recomeça.
            commitPartial();
            prevPartial = "";
            emitState();
            errStreak++;
            if (errStreak > 90) { // silêncio longo de verdade: encerra sozinho
                listening = false;
                muteBeeps(false);
                js("window.__srError && window.__srError('semvoz')");
                js("window.__srEnd && window.__srEnd()");
                return;
            }
            restartSoon(onDevice ? 150 : 300);
        }
        @Override public void onEvent(int e, Bundle p) {}
    };

    /**
     * Prefere o reconhecedor ON-DEVICE (Android 12+): ele não toca os bipes do
     * Google, reinicia muito mais rápido e funciona sem internet. Se não existir
     * ou não tiver português baixado, usa o reconhecedor normal.
     */
    private SpeechRecognizer buildRecognizer() {
        SpeechRecognizer r = null;
        if (!onDeviceGaveUp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                    onDevice = true;
                }
            } catch (Throwable ignored) { r = null; }
        }
        if (r == null) {
            r = SpeechRecognizer.createSpeechRecognizer(this);
            onDevice = false;
        }
        r.setRecognitionListener(recListener);
        return r;
    }

    private void startListening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                js("window.__srError && window.__srError('permissao')");
                return;
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                js("window.__srError && window.__srError('indisponivel')");
                return;
            }
            if (recHandler == null) recHandler = new Handler(Looper.getMainLooper());
            listening = true;
            errStreak = 0;
            committed = "";
            partial = "";
            prevPartial = "";
            pausas.clear();
            ultimaMudanca = System.currentTimeMillis();
            if (recognizer == null) recognizer = buildRecognizer();
            muteBeeps(true);
            startSession();
        } catch (Exception e) {
            muteBeeps(false);
            js("window.__srError && window.__srError('erro')");
        }
    }

    private void startSession() {
        if (!listening || recognizer == null) return;
        try {
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recLang);
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            it.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            if (onDevice) it.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            // Pausa natural NÃO encerra a frase.
            // A v6 usava 1000/800ms: fechava a frase a cada respiro, reiniciava o
            // motor toda hora (o "pulsar") e cada reinício comia um pedaço da fala.
            it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000);
            it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500);
            recognizer.startListening(it);
        } catch (Exception e) {
            restartSoon(600);
        }
    }

    private void restartSoon(long delayMs) {
        if (!listening || recHandler == null) return;
        recHandler.removeCallbacksAndMessages(null); // nunca empilha reinícios
        recHandler.postDelayed(() -> {
            if (listening && recognizer != null) startSession();
        }, delayMs);
    }

    private void stopListening() {
        listening = false;
        commitPartial();   // a última frase falada nunca se perde
        emitState();
        try { if (recHandler != null) recHandler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) {}
        muteBeeps(false);
        js("window.__srEnd && window.__srEnd()");
    }

    private String firstResult(Bundle b) {
        try {
            ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) return list.get(0);
        } catch (Exception ignored) {}
        return null;
    }
}
