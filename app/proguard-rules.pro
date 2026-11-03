# إزالة كل استدعاءات Log من نسخة الإصدار حتى لا يتسرب أي محتوى إلى سجلات النظام.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
