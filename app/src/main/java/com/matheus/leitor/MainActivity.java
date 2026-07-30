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
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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

    @Override protected void onDestroy() {
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

    // ================= SpeechRecognizer (transcrição contínua) =================
    private volatile boolean listening = false;
    private Handler recHandler;
    private int errStreak = 0;
    private String lastPartial = "";

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
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle p) {}
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float r) {}
                    @Override public void onBufferReceived(byte[] b) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onPartialResults(Bundle partial) {
                        errStreak = 0;
                        String t = firstResult(partial);
                        if (t != null && !t.isEmpty()) lastPartial = t;
                        emitText(t, false);
                    }
                    @Override public void onResults(Bundle results) {
                        errStreak = 0;
                        lastPartial = "";
                        emitText(firstResult(results), true);
                        restartSoon(120);   // continua ouvindo, sem parar
                    }
                    @Override public void onError(int error) {
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            listening = false;
                            js("window.__srError && window.__srError('permissao')");
                            js("window.__srEnd && window.__srEnd()");
                            return;
                        }
                        // sessão terminou sem final: commita o que já foi falado (não apaga)
                        if (lastPartial != null && !lastPartial.isEmpty()) {
                            emitText(lastPartial, true);
                            lastPartial = "";
                        }
                        errStreak++;
                        if (errStreak > 30) { // ~3 min de silêncio contínuo: encerra sozinho
                            listening = false;
                            js("window.__srError && window.__srError('semvoz')");
                            js("window.__srEnd && window.__srEnd()");
                            return;
                        }
                        restartSoon(300);
                    }
                    @Override public void onEvent(int e, Bundle p) {}
                });
            }
            startSession();
        } catch (Exception e) {
            js("window.__srError && window.__srError('erro')");
        }
    }

    private void startSession() {
        if (!listening || recognizer == null) return;
        try {
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang);
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            // tolera pausas maiores antes de encerrar a sessão (ditado contínuo)
            it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000);
            it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000);
            it.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60000);
            recognizer.startListening(it);
        } catch (Exception e) {
            restartSoon(500);
        }
    }

    private void restartSoon(long delayMs) {
        if (!listening || recHandler == null) return;
        recHandler.postDelayed(() -> {
            if (listening && recognizer != null) startSession();
        }, delayMs);
    }

    private void stopListening() {
        listening = false;
        try { if (recHandler != null) recHandler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) {}
        js("window.__srEnd && window.__srEnd()");
    }

    private String firstResult(Bundle b) {
        try {
            ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) return list.get(0);
        } catch (Exception ignored) {}
        return null;
    }
    private void emitText(String t, boolean isFinal) {
        if (t == null) return;
        js("window.__srResult && window.__srResult(" + JSONObject.quote(t) + "," + (isFinal ? "true" : "false") + ")");
    }
}
