# Keep class names for reflection (qualifiedName)
-keepnames class eu.kanade.tachiyomi.extension.all.pururin.Pururin

# Keep the parse method used by Komikku's delegated source
-keepclassmembers class eu.kanade.tachiyomi.extension.all.pururin.Pururin {
    public java.util.List chapterListParse(okhttp3.Response);
}
