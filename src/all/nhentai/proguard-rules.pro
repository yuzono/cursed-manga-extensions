# Keep class names for reflection (qualifiedName)
-keepnames class eu.kanade.tachiyomi.extension.all.nhentai.NHentai

# Keep the request and parse methods used by Komikku's delegated source
-keepclassmembers class eu.kanade.tachiyomi.extension.all.nhentai.NHentai {
    public okhttp3.Request mangaDetailsRequest(eu.kanade.tachiyomi.source.model.SManga);
    public okhttp3.Request chapterListRequest(eu.kanade.tachiyomi.source.model.SManga);
    public java.util.List chapterListParse(okhttp3.Response);
}
