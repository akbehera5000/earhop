# EarHop's own classes are started by name (app_process runs RootBt and ClipHelper; the manifest names the services,
# receivers and providers) and use reflection, so they are kept whole. R8 still shrinks and optimizes the libraries.
-keep class com.earhop.logger.** { *; }

# Hidden Android classes that ClipHelper and RootBt look up by name at run time.
-dontwarn android.content.IClipboard**
-dontwarn android.content.IOnPrimaryClipChangedListener**
-dontwarn android.app.ActivityThread
