package app.olauncher.helper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import app.olauncher.data.Prefs
import java.text.Collator

/**
 * Widgets on the home screen, above the home apps. Prefs.widgetRows holds their ids in rows; the
 * host is shared by the whole app and listens while MainActivity is started.
 */
object Widgets {

    private const val HOST_ID = 1024

    @Volatile
    private var host: AppWidgetHost? = null

    fun host(context: Context): AppWidgetHost =
        host ?: synchronized(this) {
            host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }
        }

    /** A new widget gets a row of its own at the bottom. */
    fun place(prefs: Prefs, appWidgetId: Int) {
        prefs.widgetRows = prefs.widgetRows + listOf(listOf(appWidgetId))
    }

    fun remove(context: Context, prefs: Prefs, appWidgetId: Int) {
        host(context).deleteAppWidgetId(appWidgetId)
        prefs.widgetRows = prefs.widgetRows.map { it - appWidgetId }.filter { it.isNotEmpty() }
    }

    fun rowOf(rows: List<List<Int>>, appWidgetId: Int): Int = rows.indexOfFirst { appWidgetId in it }

    /** Moves the widget to the end of the row above it. */
    fun joinRowAbove(prefs: Prefs, appWidgetId: Int) {
        val rows = prefs.widgetRows.map { it.toMutableList() }.toMutableList()
        val row = rowOf(rows, appWidgetId)
        if (row <= 0) return
        rows[row].remove(appWidgetId)
        rows[row - 1].add(appWidgetId)
        prefs.widgetRows = rows.filter { it.isNotEmpty() }
    }

    /** Takes the widget out of a shared row into a row of its own right below it. */
    fun splitToOwnRow(prefs: Prefs, appWidgetId: Int) {
        val rows = prefs.widgetRows.map { it.toMutableList() }.toMutableList()
        val row = rowOf(rows, appWidgetId)
        if (row < 0 || rows[row].size < 2) return
        rows[row].remove(appWidgetId)
        rows.add(row + 1, mutableListOf(appWidgetId))
        prefs.widgetRows = rows
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
