package app.olauncher.data

import java.text.Collator

/** Key that ties an app or a pinned shortcut to its folder. For apps it equals the hidden-apps key. */
val AppModel.folderKey: String?
    get() = when (this) {
        is AppModel.App -> "$appPackage|$user"
        is AppModel.PinnedShortcut -> identity
        is AppModel.PrivateSpaceHeader -> null
    }

/** What the app drawer shows when the search is empty: the folder list, or the contents of one entry. */
sealed class FolderView {
    data class Named(val name: String) : FolderView()
    data object Uncategorised : FolderView()
    data object PrivateSpace : FolderView()
}

data class FolderRow(val label: String, val view: FolderView)

/**
 * Folders hold apps in the drawer. An app sits in one folder at most; an app in none, or in a
 * folder that no longer exists, counts as uncategorised.
 */
object Folders {

    data class ImportResult(val assigned: Int, val notFound: Int)

    fun sorted(prefs: Prefs): List<String> {
        val collator = Collator.getInstance()
        return prefs.folders.sortedWith(compareBy(collator) { it })
    }

    /** Reads the stored folders once, for lookups over a whole app list. */
    class Assignments(prefs: Prefs) {
        private val folders = prefs.folders
        private val appFolders = prefs.appFolders

        fun folderOf(appModel: AppModel): String? =
            appModel.folderKey?.let { appFolders[it] }?.takeIf { it in folders }
    }

    /** Returns the folder's stored name, the existing one if a folder differs only in case, or null for a blank name. */
    fun create(prefs: Prefs, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        prefs.folders.firstOrNull { it.equals(trimmed, true) }?.let { return it }
        prefs.folders = prefs.folders + trimmed
        return trimmed
    }

    fun assign(prefs: Prefs, appModel: AppModel, folder: String?) {
        val key = appModel.folderKey ?: return
        prefs.appFolders = if (folder == null) prefs.appFolders - key else prefs.appFolders + (key to folder)
    }

    /** Returns false when the new name is blank or taken by another folder. */
    fun rename(prefs: Prefs, oldName: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        if (prefs.folders.any { it != oldName && it.equals(trimmed, true) }) return false
        prefs.folders = prefs.folders - oldName + trimmed
        prefs.appFolders = prefs.appFolders.mapValues { (_, folder) -> if (folder == oldName) trimmed else folder }
        return true
    }

    /** Deletes the folder; its apps become uncategorised. */
    fun delete(prefs: Prefs, name: String) {
        prefs.folders = prefs.folders - name
        prefs.appFolders = prefs.appFolders.filterValues { it != name }
    }

    /**
     * Reads rows of app name and folder name, matches names against app labels ignoring case, and
     * files every match. A header row naming "name" and "category" columns picks them; without one
     * the first two columns are used. Rows filed under "Uncategorised" stay out of every folder.
     */
    fun importCsv(prefs: Prefs, csv: String, apps: List<AppModel>): ImportResult {
        val rows = csv.lines().filter { it.isNotBlank() }.map { parseCsvLine(it) }
        if (rows.isEmpty()) return ImportResult(0, 0)

        val header = rows.first().map { it.trim().lowercase() }
        val nameColumn = header.indexOf("name")
        val folderColumn = header.indexOf("category")
        val hasHeader = nameColumn >= 0 && folderColumn >= 0
        val dataRows = if (hasHeader) rows.drop(1) else rows
        val nameIndex = if (hasHeader) nameColumn else 0
        val folderIndex = if (hasHeader) folderColumn else 1

        val appsByLabel = apps.filter { it.folderKey != null }.groupBy { it.appLabel.trim().lowercase() }
        val folders = prefs.folders.toMutableSet()
        val appFolders = prefs.appFolders.toMutableMap()
        var assigned = 0
        var notFound = 0

        for (row in dataRows) {
            val name = row.getOrNull(nameIndex)?.trim().orEmpty()
            val folderName = row.getOrNull(folderIndex)?.trim().orEmpty()
            if (name.isEmpty()) continue
            val matches = appsByLabel[name.lowercase()]
            if (matches.isNullOrEmpty()) {
                notFound++
                continue
            }
            val isUncategorised = folderName.isEmpty() ||
                folderName.equals("uncategorised", true) || folderName.equals("uncategorized", true)
            val folder = if (isUncategorised) null
            else folders.firstOrNull { it.equals(folderName, true) } ?: folderName.also { folders.add(it) }
            for (app in matches) {
                val key = app.folderKey ?: continue
                if (folder == null) appFolders.remove(key) else appFolders[key] = folder
                assigned++
            }
        }

        prefs.folders = folders
        prefs.appFolders = appFolders
        return ImportResult(assigned, notFound)
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }

                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    fields.add(field.toString())
                    field.clear()
                }

                else -> field.append(c)
            }
            i++
        }
        fields.add(field.toString())
        return fields
    }
}
