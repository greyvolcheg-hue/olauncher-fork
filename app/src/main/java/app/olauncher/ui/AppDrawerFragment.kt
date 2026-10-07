package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.FolderRow
import app.olauncher.data.FolderView
import app.olauncher.data.Folders
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogFolderNameBinding
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.createDialog
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall

class AppDrawerFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var folderBackCallback: OnBackPressedCallback
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false

    // Folders: with an empty search the drawer shows the folder list, or the open folder's apps.
    // Typing searches all apps; clearing the search returns to where it was.
    private val foldersEnabled get() = flag == Constants.FLAG_LAUNCH_APP
    private var openFolder: FolderView? = null
    private var adapterHoldsAllApps = false
    private var defaultQueryHint: CharSequence? = null
    private var animateLists = false

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
        }

        folderBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeFolder()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, folderBackCallback)

        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP)
            binding.search.queryHint = "Please select an app"
        defaultQueryHint = binding.search.queryHint
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.gravity = prefs.appLabelAlignment
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (foldersEnabled) initFolderNavigation()
    }

    // In the main drawer the empty search field's text works as back: out of an open folder,
    // or out of the drawer from the folder list. The magnifier starts a search.
    @SuppressLint("ClickableViewAccessibility")
    private fun initFolderNavigation() {
        binding.searchButton.visibility = View.VISIBLE
        binding.searchButton.setOnClickListener { binding.search.showKeyboard() }
        searchTextView?.setOnTouchListener { _, event ->
            if (!binding.search.query.isNullOrEmpty()) return@setOnTouchListener false
            if (event.action == MotionEvent.ACTION_UP) {
                if (openFolder != null) closeFolder()
                else findNavController().popBackStack()
            }
            true
        }
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (foldersEnabled && query.isNullOrBlank())
                    Unit // nothing to launch from a folder view
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    adapter.allowAutoLaunch = !isSearchComposing()
                    if (foldersEnabled && newText.isBlank()) {
                        currentAppList?.let { showFolderView(it) }
                    } else {
                        if (foldersEnabled && !adapterHoldsAllApps)
                            currentAppList?.let { showAllApps(it) }
                        adapter.filter.filter(newText)
                    }
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        if (start !in 0 until end) return false
        return isCjkKeyboard()
    }

    private fun isCjkKeyboard(): Boolean {
        cachedIsCjkKeyboard?.let { return it }
        val result = try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val subtype = imm.currentInputMethodSubtype
            val language = when {
                subtype == null -> ""
                subtype.languageTag.isNotEmpty() -> subtype.languageTag // e.g. "zh-CN", "ja-JP", "en-US"
                else -> subtype.locale // deprecated fallback, e.g. "zh_CN"
            }
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
        } catch (e: Exception) {
            false
        }
        cachedIsCjkKeyboard = result
        return result
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                viewModel.selectedApp(appModel, flag)
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    findNavController().popBackStack(R.id.mainFragment, false)
                else
                    findNavController().popBackStack()
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    findNavController().popBackStack()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    findNavController().navigate(R.id.action_appListFragment_to_settingsFragment2)
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appFolderListener = { appModel, anchor -> showAssignFolderMenu(appModel, anchor) }
        )

        folderAdapter = FolderAdapter(
            prefs.appLabelAlignment,
            folderClickListener = { row -> openFolder(row.view) },
            folderLongClickListener = { row, anchor -> showFolderMenu(row, anchor) }
        )

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        else if (requireContext().isSystemAnimationsDisabled().not()) {
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
            animateLists = true
        }
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        if (foldersEnabled && binding.search.query.isNullOrBlank()) {
            showFolderView(apps)
            return
        }
        showAllApps(apps)
        adapter.filter.filter(binding.search.query)
    }

    private fun showAllApps(apps: List<AppModel>) {
        val combined = apps.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        attachAdapter(adapter)
        adapter.setAppList(combined)
        adapterHoldsAllApps = true
    }

    private fun showFolderView(apps: List<AppModel>) {
        val folder = openFolder
        adapterHoldsAllApps = false
        folderBackCallback.isEnabled = folder != null
        binding.search.queryHint = folder?.label() ?: defaultQueryHint
        if (folder == null) {
            folderAdapter.submitList(folderRows(apps))
            attachAdapter(folderAdapter)
        } else {
            attachAdapter(adapter)
            adapter.setAppList(appsIn(folder, apps))
        }
    }

    private fun folderRows(apps: List<AppModel>): List<FolderRow> {
        val assignments = Folders.Assignments(prefs)
        val rows = Folders.sorted(prefs).map { FolderRow(it, FolderView.Named(it)) }.toMutableList()
        if (apps.any { assignments.folderOf(it) == null })
            rows.add(FolderRow(FolderView.Uncategorised.label(), FolderView.Uncategorised))
        if (currentPrivateSpaceAvailable)
            rows.add(FolderRow(FolderView.PrivateSpace.label(), FolderView.PrivateSpace))
        return rows
    }

    private fun appsIn(folder: FolderView, apps: List<AppModel>): MutableList<AppModel> {
        val assignments = Folders.Assignments(prefs)
        return when (folder) {
            is FolderView.Named -> apps.filter { assignments.folderOf(it) == folder.name }.toMutableList()
            FolderView.Uncategorised -> apps.filter { assignments.folderOf(it) == null }.toMutableList()
            FolderView.PrivateSpace -> mutableListOf<AppModel>(
                AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked)
            ).apply {
                if (!currentPrivateSpaceLocked) currentPrivateSpaceApps?.let { addAll(it) }
            }
        }
    }

    private fun FolderView.label(): String = when (this) {
        is FolderView.Named -> name
        FolderView.Uncategorised -> getString(R.string.uncategorised)
        FolderView.PrivateSpace -> getString(R.string.private_space)
    }

    private fun attachAdapter(listAdapter: RecyclerView.Adapter<*>) {
        if (binding.recyclerView.adapter === listAdapter) return
        binding.recyclerView.adapter = listAdapter
        if (animateLists) binding.recyclerView.scheduleLayoutAnimation()
    }

    private fun openFolder(folder: FolderView) {
        openFolder = folder
        updateCombinedAppList()
        binding.recyclerView.scrollToPosition(0)
    }

    private fun closeFolder() {
        openFolder = null
        if (binding.search.query.isNullOrEmpty()) updateCombinedAppList()
        else binding.search.setQuery("", false)
    }

    private fun showAssignFolderMenu(appModel: AppModel, anchor: View) {
        val folders = Folders.sorted(prefs)
        val current = Folders.Assignments(prefs).folderOf(appModel)
        anchor.showPopupMenu(configure = { menu ->
            folders.forEachIndexed { index, name ->
                menu.add(0, index, index, name).apply {
                    isCheckable = true
                    isChecked = name == current
                }
            }
            menu.add(0, MENU_NEW_FOLDER, folders.size, R.string.new_folder)
            if (current != null) menu.add(0, MENU_NO_FOLDER, folders.size + 1, R.string.no_folder)
        }) { item ->
            when (item.itemId) {
                MENU_NEW_FOLDER -> showFolderNameDialog(R.string.new_folder, R.string.create, "") { name ->
                    Folders.create(prefs, name)?.let { Folders.assign(prefs, appModel, it) }
                    updateCombinedAppList()
                }

                MENU_NO_FOLDER -> {
                    Folders.assign(prefs, appModel, null)
                    updateCombinedAppList()
                }

                else -> folders.getOrNull(item.itemId)?.let {
                    Folders.assign(prefs, appModel, it)
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun showFolderMenu(row: FolderRow, anchor: View) {
        val folder = (row.view as? FolderView.Named)?.name ?: return
        anchor.showPopupMenu(configure = { menu ->
            menu.add(0, MENU_RENAME_FOLDER, 0, R.string.rename)
            menu.add(0, MENU_DELETE_FOLDER, 1, R.string.delete_folder)
        }) { item ->
            when (item.itemId) {
                MENU_RENAME_FOLDER -> showFolderNameDialog(R.string.rename_folder, R.string.rename, folder) { name ->
                    if (!Folders.rename(prefs, folder, name))
                        requireContext().showToast(R.string.folder_name_taken)
                    updateCombinedAppList()
                }

                MENU_DELETE_FOLDER -> requireContext().createDialog(
                    title = R.string.delete_folder,
                    action = R.string.delete_folder,
                    message = R.string.delete_folder_message,
                    onAction = {
                        Folders.delete(prefs, folder)
                        updateCombinedAppList()
                    }
                ).showRespectingStatusBar()
            }
        }
    }

    private fun showFolderNameDialog(
        @StringRes title: Int,
        @StringRes action: Int,
        initialName: String,
        onName: (String) -> Unit,
    ) {
        var input: DialogFolderNameBinding? = null
        val dialog = requireContext().createDialog(title, action, onAction = {
            input?.let { onName(it.etFolderName.text.toString()) }
        }) { container ->
            DialogFolderNameBinding.inflate(layoutInflater, container, false).also { input = it }.root
        }
        input?.etFolderName?.apply {
            setText(initialName)
            setSelection(initialName.length)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                onName(text.toString())
                dialog.dismiss()
                true
            }
        }
        // Without this the keyboard stays up over the list once the dialog closes
        dialog.setOnDismissListener { _binding?.search?.hideKeyboard() }
        dialog.showRespectingStatusBar()
        input?.etFolderName?.showKeyboard()
    }

    private fun initClickListeners() {
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            findNavController().popBackStack()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop && isRemoving.not())
                                binding.search.showKeyboard(prefs.autoShowKeyboard)
                    }
                }
            }
        }
    }

    private fun checkMessageAndExit() {
        findNavController().popBackStack()
        if (flag == Constants.FLAG_LAUNCH_APP)
            viewModel.checkForMessages.call()
    }

    override fun onStart() {
        super.onStart()
        cachedIsCjkKeyboard = null
        binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    override fun onStop() {
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchTextView = null
        _binding = null
    }

    companion object {
        private const val MENU_RENAME_FOLDER = 1
        private const val MENU_DELETE_FOLDER = 2
        private const val MENU_NEW_FOLDER = 100_000
        private const val MENU_NO_FOLDER = 100_001
    }
}
