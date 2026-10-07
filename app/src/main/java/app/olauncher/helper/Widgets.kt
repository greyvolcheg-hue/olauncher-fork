package app.olauncher.helper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import java.text.Collator

/**
 * Widgets on the home screen, below the home apps. Their ids live in Prefs.widgetIds in display
 * order; the host is shared by the whole app and listens while MainActivity is started.
 */
object Widgets {

    private const val HOST_ID = 1024

    @Volatile
    private var host: AppWidgetHost? = null

    fun host(context: Context): AppWidgetHost =
        host ?: synchronized(this) {
            host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }
        }

    /** "App: widget" for a placed widget, or null when its provider is gone. */
    fun label(context: Context, appWidgetId: Int): String? =
        AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.let { label(context, it) }

    fun label(context: Context, info: AppWidgetProviderInfo): String {
        val packageManager = context.packageManager
        val widgetLabel = info.loadLabel(packageManager)
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(info.provider.packageName, 0)
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            info.provider.packageName
        }
        return if (widgetLabel.isNullOrBlank() || widgetLabel == appLabel) appLabel else "$appLabel: $widgetLabel"
    }

    /** Every widget the user can place, sorted by label. */
    fun providers(context: Context): List<Pair<String, AppWidgetProviderInfo>> {
        val collator = Collator.getInstance()
        return AppWidgetManager.getInstance(context).installedProviders
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .map { label(context, it) to it }
            .sortedWith(compareBy(collator) { it.first })
    }
}
