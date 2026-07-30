# Mantém a interface JavaScript exposta ao WebView (não pode ser ofuscada/removida).
-keepclassmembers class com.matheus.leitor.MainActivity$Bridge {
   public *;
}
-keepattributes JavascriptInterface
