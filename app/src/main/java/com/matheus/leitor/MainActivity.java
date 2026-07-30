package com.matheus.leitor;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * Leitor — app WebView 100% offline.
 *
 * O WebView do Android não implementa as APIs Web de voz (speechSynthesis) nem de
 * reconhecimento (webkitSpeechRecognition). Por isso este app expõe uma ponte
 * ("AndroidNative") para o TextToSpeech e o SpeechRecognizer nativos, e o
 * leitor.html injeta um "shim" que emula essas APIs Web sobre a ponte — assim o
 * HTML continua funcionando sem alterar nenhuma das suas funções.
 */
public class MainActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private String recLang = "pt-BR";

    private static final int REQ_MIC = 42;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage: notas e comentários
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDefaultTextEncodingName("utf-8");

        web.addJavascriptInterface(new Bridge(), "AndroidNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false; // mantém tudo dentro do WebView
            }
        });

        // Inicializa o TextToSpeech nativo
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                try { tts.setLanguage(new Locale("pt", "BR")); } catch (Exception ignored) {}
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) {
                        js("window.__ttsEnd && window.__ttsEnd(" + q(id) + ")");
                    }
                    @Override public void onError(String id) {
                        js("window.__ttsError && window.__ttsError(" + q(id) + ")");
                    }
                    @Override public void onStop(String id, boolean interrupted) {
                        // Pausa/cancelar: não avança a leitura (o HTML controla isso).
                    }
                });
                // Assim que as vozes estiverem prontas, envia para o JS
                pushVoices();
            }
        });

        // Pede a permissão do microfone logo no início (usada só na transcrição)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        web.loadUrl("file:///android_asset/leitor.html");
    }

    // ---- utilidades ----

    private void js(final String code) {
        if (web == null) return;
        web.post(() -> {
            try { web.evaluateJavascript(code, null); } catch (Exception ignored) {}
        });
    }

    /** Escapa uma string para uso seguro como literal JS. */
    private static String q(String s) {
        if (s == null) return "''";
        return JSONObject.quote(s);
    }

    private Locale localeFromTag(String tag) {
        try {
            if (tag == null || tag.isEmpty()) return new Locale("pt", "BR");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return Locale.forLanguageTag(tag);
            }
        } catch (Exception ignored) {}
        return new Locale("pt", "BR");
    }

    private void pushVoices() {
        if (!ttsReady) return;
        JSONArray arr = new JSONArray();
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice v : voices) {
                    try {
                        if (v == null || v.isNetworkConnectionRequired()) continue; // só offline
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

    @Override
    protected void onDestroy() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) {}
        try { if (recognizer != null) recognizer.destroy(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ================= PONTE JS =================
    public class Bridge {

        // ---- Voz (TextToSpeech) ----
        @JavascriptInterface
        public void speak(final String text, final float rate, final float pitch,
                          final String voiceName, final String lang, final String id) {
            runOnUiThread(() -> {
                if (!ttsReady) { js("window.__ttsError && window.__ttsError(" + q(id) + ")"); return; }
                try {
                    tts.setSpeechRate(rate > 0 ? rate : 1f);
                    tts.setPitch(pitch > 0 ? pitch : 1f);
                    boolean voiceSet = false;
                    if (voiceName != null && !voiceName.isEmpty()) {
                        Set<Voice> vs = tts.getVoices();
                        if (vs != null) {
                            for (Voice v : vs) {
                                if (v != null && voiceName.equals(v.getName())) {
                                    tts.setVoice(v); voiceSet = true; break;
                                }
                            }
                        }
                    }
                    if (!voiceSet) tts.setLanguage(localeFromTag(lang));
                    tts.speak(text == null ? "" : text, TextToSpeech.QUEUE_FLUSH, null, id);
                } catch (Exception e) {
                    js("window.__ttsError && window.__ttsError(" + q(id) + ")");
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> { try { if (ttsReady) tts.stop(); } catch (Exception ignored) {} });
        }

        @JavascriptInterface
        public void requestVoices() {
            runOnUiThread(MainActivity.this::pushVoices);
        }

        // ---- Copiar (clipboard) ----
        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("leitor", text == null ? "" : text));
                } catch (Exception ignored) {}
            });
        }

        // ---- Reconhecimento de voz (transcrição) ----
        @JavascriptInterface
        public boolean srAvailable() {
            try { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); }
            catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void startRecognition(final String lang) {
            recLang = (lang == null || lang.isEmpty()) ? "pt-BR" : lang;
            runOnUiThread(MainActivity.this::startListening);
        }

        @JavascriptInterface
        public void stopRecognition() {
            runOnUiThread(MainActivity.this::stopListening);
        }
    }

    // ================= SpeechRecognizer =================
    private void startListening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                js("window.__srError && window.__srError('nao-autorizado')");
                return;
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                js("window.__srError && window.__srError('indisponivel')");
                return;
            }
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) { }
                    @Override public void onBeginningOfSpeech() { }
                    @Override public void onRmsChanged(float rmsdB) { }
                    @Override public void onBufferReceived(byte[] buffer) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onPartialResults(Bundle partial) {
                        emit(partial, false);
                    }
                    @Override public void onResults(Bundle results) {
                        emit(results, true);
                        js("window.__srEnd && window.__srEnd()");
                    }
                    @Override public void onError(int error) {
                        js("window.__srEnd && window.__srEnd()");
                    }
                    @Override public void onEvent(int eventType, Bundle params) { }
                });
            }
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang);
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                it.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            }
            listening = true;
            recognizer.startListening(it);
        } catch (Exception e) {
            js("window.__srError && window.__srError('erro')");
        }
    }

    private void stopListening() {
        listening = false;
        try { if (recognizer != null) recognizer.stopListening(); } catch (Exception ignored) {}
    }

    private void emit(Bundle b, boolean isFinal) {
        try {
            ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) {
                String t = list.get(0);
                js("window.__srResult && window.__srResult(" +
                        JSONObject.quote(t == null ? "" : t) + "," + (isFinal ? "true" : "false") + ")");
            }
        } catch (Exception ignored) {}
    }
}
