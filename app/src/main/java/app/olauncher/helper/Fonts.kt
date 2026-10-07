package app.olauncher.helper

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import app.olauncher.R
import app.olauncher.data.Prefs
import java.io.File

/**
 * The app-wide font. The default keeps the theme's own family (sans-serif-light, or sans-serif
 * with the bold option); any other choice is applied to every TextView as it is inflated.
 */
object Fonts {

    const val DEFAULT = ""
    const val FROM_FILE = "file"

    /** Built-in families, in the order the settings list shows them. */
    val presets: List<Pair<String, Int>> = listOf(
        DEFAULT to R.string.font_default,
        "sans-serif" to R.string.font_sans,
        "sans-serif-condensed" to R.string.font_condensed,
        "serif" to R.string.font_serif,
        "monospace" to R.string.font_monospace,
    )

    @StringRes
    fun presetLabel(family: String): Int? = presets.firstOrNull { it.first == family }?.second

    private fun fontFile(context: Context) = File(context.filesDir, "custom_font")

    fun hasImportedFile(context: Context) = fontFile(context).exists()

    /** The typeface to force on every TextView, or null to leave the theme's font alone. */
    fun typeface(context: Context, prefs: Prefs): Typeface? = when (prefs.fontFamily) {
        DEFAULT -> null
        FROM_FILE -> load(fontFile(context))
        else -> Typeface.create(prefs.fontFamily, Typeface.NORMAL)
    }

    private fun load(file: File): Typeface? {
        if (!file.exists()) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Typeface.Builder(file).build()
            else Typeface.createFromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Copies the picked font into app storage and checks that it loads. Returns its display name,
     * or null when the file is not a usable font; the previous font stays in place then.
     */
    fun import(context: Context, uri: Uri): String? {
        val incoming = File(context.filesDir, "custom_font.new")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                incoming.outputStream().use { input.copyTo(it) }
            } ?: return null
            if (load(incoming) == null) return null
            if (!incoming.renameTo(fontFile(context))) return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            incoming.delete()
        }
        return displayName(context, uri) ?: context.getString(R.string.font_from_file)
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    /**
     * Inflater factory that lets AppCompat create its views, then sets [typeface] on every
     * TextView. TextView subclasses AppCompat does not map (TextClock, SearchView's text field)
     * are created here, so they get the font too.
     */
    class Factory(
        private val delegate: AppCompatDelegate,
        private val typeface: Typeface,
        private val bold: Boolean,
    ) : LayoutInflater.Factory2 {

        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
            val view = delegate.createView(parent, name, context, attrs) ?: createTextView(name, context, attrs)
            if (view is TextView) {
                val style = if (bold) Typeface.BOLD else view.typeface?.style ?: Typeface.NORMAL
                view.setTypeface(typeface, style)
            }
            return view
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)

        private fun createTextView(name: String, context: Context, attrs: AttributeSet): View? {
            val className = if (name.contains('.')) name else "android.widget.$name"
            return try {
                val viewClass = Class.forName(className, false, context.classLoader)
                if (!TextView::class.java.isAssignableFrom(viewClass)) return null
                viewClass.getConstructor(Context::class.java, AttributeSet::class.java)
                    .newInstance(context, attrs) as View
            } catch (_: Exception) {
                null
            }
        }
    }
}
