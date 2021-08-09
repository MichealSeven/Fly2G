package com.v2ray.ang.ui

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.lifecycle.ViewModelProviders
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import com.google.android.material.navigation.NavigationView
import androidx.core.view.GravityCompat
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.tbruyelle.rxpermissions.RxPermissions
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.fly.BuildConfig
import com.v2ray.ang.fly.R
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_sub_edit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import libv2ray.Libv2ray
import me.drakeet.support.toast.ToastCompat
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val REQUEST_CODE_VPN_PREPARE = 0
        private const val REQUEST_SCAN = 1
        private const val REQUEST_FILE_CHOOSER = 2
        private const val REQUEST_SCAN_URL = 3
        private const val REQUEST_CODE_TESTALL_VPN_PREPARE = 4
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val mainStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE) }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by lazy { ViewModelProviders.of(this).get(MainViewModel::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = getString(R.string.title_services)
        setSupportActionBar(toolbar)

        fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                Utils.stopVService(this)
            } else if (settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN" == "VPN") {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    startActivityForResult(intent, REQUEST_CODE_VPN_PREPARE)
                }
            } else {
                startV2Ray()
            }
        }
        layout_test.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                tv_test_state.text = getString(R.string.connection_test_testing)
                mainViewModel.testCurrentServerRealPing()
            } else {
                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        mainViewModel.mainActivity = this
        mainViewModel.mainAdapter = adapter

        recycler_view.setHasFixedSize(true)
        recycler_view.layoutManager = LinearLayoutManager(this)
        recycler_view.adapter = adapter

        val callback = SimpleItemTouchHelperCallback(adapter)
        mItemTouchHelper = ItemTouchHelper(callback)
        mItemTouchHelper?.attachToRecyclerView(recycler_view)


        val toggle = ActionBarDrawerToggle(
                this, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer_layout.addDrawerListener(toggle)
        toggle.syncState()
        nav_view.setNavigationItemSelectedListener(this)
        version.text = "v${BuildConfig.VERSION_NAME} (${Libv2ray.checkVersionX()})"

        setupViewModelObserver()
        migrateLegacy()
        checkSubSetting()
    }

    private fun setupViewModelObserver() {
        mainViewModel.updateListAction.observe(this, {
            val index = it ?: return@observe
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        })
        mainViewModel.updateTestResultAction.observe(this, { tv_test_state.text = it })
        mainViewModel.isRunning.observe(this, {
            val isRunning = it ?: return@observe
            adapter.isRunning = isRunning
            if (isRunning) {
                fab.setImageResource(R.drawable.ic_v)
                tv_test_state.text = getString(R.string.connection_connected)
                layout_test.isFocusable = true
            } else {
                fab.setImageResource(R.drawable.ic_v_idle)
                tv_test_state.text = getString(R.string.connection_not_connected)
                layout_test.isFocusable = false
            }
            hideCircle()
        })
        mainViewModel.startListenBroadcast()
    }

    private fun migrateLegacy() {
        GlobalScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.migrateLegacyConfig(this@MainActivity)
            if (result != null) {
                launch(Dispatchers.Main) {
                    if (result) {
                        toast(getString(R.string.migration_success))
                        mainViewModel.reloadServerList()
                    } else {
                        toast(getString(R.string.migration_fail))
                    }
                }
            }
        }
    }

    private fun checkSubSetting(){
//        val subStorage = MMKV.mmkvWithID(MmkvManager.ID_SUB, MMKV.MULTI_PROCESS_MODE)
//        if (subStorage?.count() == 0L) {
//            val subItem: SubscriptionItem = SubscriptionItem()
//            subItem.remarks = "netlify"
//            subItem.url = "https://jiang.netlify.com/"
//            subStorage?.encode(Utils.getUuid(), Gson().toJson(subItem))
//        }
        if(MmkvManager.decodeServerList().isEmpty()){
            AlertDialog.Builder(this).setMessage(R.string.update_free_servers)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val url = AppConfig.freeSubDomain
                    if (Utils.isValidUrl(url)) {
                        GlobalScope.launch(Dispatchers.IO) {
                            val configText = try {
                                URL(url).readText()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                return@launch
                            }
                            launch(Dispatchers.Main) {
                                importBatchConfig(Utils.decode(configText), "free")
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel){_, _ ->

                }
                .show()

        }
    }

    fun startV2Ray() {
        if (mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).isNullOrEmpty()) {
            return
        }
        showCircle()
        V2RayServiceManager.startV2Ray(this)
        hideCircle()
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_VPN_PREPARE ->
                if (resultCode == RESULT_OK) {
                    startV2Ray()
                }
            REQUEST_CODE_TESTALL_VPN_PREPARE->
                if (resultCode == RESULT_OK) {
                    mainViewModel.testAllRealPing()
                }
            REQUEST_SCAN ->
                if (resultCode == RESULT_OK) {
                    importBatchConfig(data?.getStringExtra("SCAN_RESULT"))
                }
            REQUEST_FILE_CHOOSER -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    readContentFromUri(uri)
                }
            }
            REQUEST_SCAN_URL ->
                if (resultCode == RESULT_OK) {
                    importConfigCustomUrl(data?.getStringExtra("SCAN_RESULT"))
                }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode(REQUEST_SCAN)
            true
        }
        R.id.import_clipboard -> {
            importClipboard()
            true
        }
        R.id.import_manually_vmess -> {
            startActivity(Intent().putExtra("createConfigType", EConfigType.VMESS.value).
            setClass(this, ServerActivity::class.java))
            true
        }
        R.id.import_manually_ss -> {
            startActivity(Intent().putExtra("createConfigType", EConfigType.SHADOWSOCKS.value).
            setClass(this, ServerActivity::class.java))
            true
        }
        R.id.import_manually_socks -> {
            startActivity(Intent().putExtra("createConfigType", EConfigType.SOCKS.value).
            setClass(this, ServerActivity::class.java))
            true
        }
        R.id.import_config_custom_clipboard -> {
            importConfigCustomClipboard()
            true
        }
        R.id.import_config_custom_local -> {
            importConfigCustomLocal()
            true
        }
        R.id.import_config_custom_url -> {
            importConfigCustomUrlClipboard()
            true
        }
        R.id.import_config_custom_url_scan -> {
            importQRcode(REQUEST_SCAN_URL)
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.export_all -> {
            if (AngConfigManager.shareNonCustomConfigsToClipboard(this, mainViewModel.serverList) == 0) {
                toast(R.string.toast_success)
            } else {
                toast(R.string.toast_failure)
            }
            true
        }

        R.id.ping_all -> {
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            if (mainViewModel.isRunning.value == true) {
                Utils.stopVService(this)
            }
            if (settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN" == "VPN") {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    mainViewModel.testAllRealPing()
                } else {
                    startActivityForResult(intent, REQUEST_CODE_TESTALL_VPN_PREPARE)
                }
            } else {
                mainViewModel.testAllRealPing()
            }
            true
        }

        R.id.remove_invalid_servers -> {
            mainViewModel.removeInvalidServers()
            true
        }
        R.id.sort_servers_by_speed -> {
            mainViewModel.sortServerList()
            true
        }

        R.id.open_gtv -> {
            launchUrl(AppConfig.gGtvUrl)
            true
        }

        R.id.open_gnews -> {
            launchUrl(AppConfig.gGnewsUrl)
            true
        }

        R.id.open_gettr -> {
            launchUrl(AppConfig.gGettrUrl)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }


    /**
     * import config from qrcode
     */
    fun importQRcode(requestCode: Int): Boolean {
        RxPermissions(this)
                .request(Manifest.permission.CAMERA)
                .subscribe {
                    if (it)
                        startActivityForResult(Intent(this, ScannerActivity::class.java), requestCode)
                    else
                        toast(R.string.toast_permission_denied)
                }
        return true
    }

    /**
     * import config from clipboard
     */
    fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importBatchConfig(server: String?, subid: String = "") {
        var count = AngConfigManager.importBatchConfig(server, subid)
        if (count <= 0) {
            count = AngConfigManager.importBatchConfig(Utils.decode(server!!), subid)
        }
        if (count > 0) {
            toast(R.string.toast_success)
            mainViewModel.reloadServerList()
        } else {
            toast(R.string.toast_failure)
        }
    }

    fun importConfigCustomClipboard()
            : Boolean {
        try {
            val configText = Utils.getClipboard(this)
            if (TextUtils.isEmpty(configText)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from local config file
     */
    fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importConfigCustomUrlClipboard()
            : Boolean {
        try {
            val url = Utils.getClipboard(this)
            if (TextUtils.isEmpty(url)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * import config from url
     */
    fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            GlobalScope.launch(Dispatchers.IO) {
                val configText = try {
                    URL(url).readText()
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                launch(Dispatchers.Main) {
                    importCustomizeConfig(configText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    fun importConfigViaSub()
            : Boolean {
        try {
            if (MmkvManager.decodeSubscriptions().isEmpty()){
//                val url = AppConfig.freeSubDomain
//                if (!Utils.isValidUrl(url)) {
//                    return false
//                }
//                GlobalScope.launch(Dispatchers.IO) {
//                    val configText = try {
//                        URL(url).readText()
//                    } catch (e: Exception) {
//                        e.printStackTrace()
//                        return@launch
//                    }
//                    launch(Dispatchers.Main) {
//                        importBatchConfig(Utils.decode(configText), "free")
//                    }
//                }
                checkSubSetting()
                return true
            }
            toast(R.string.title_sub_update)
            MmkvManager.decodeSubscriptions().forEach {
                if (TextUtils.isEmpty(it.first)
                        || TextUtils.isEmpty(it.second.remarks)
                        || TextUtils.isEmpty(it.second.url)
                ) {
                    return@forEach
                }
                val url = it.second.url
                if (!Utils.isValidUrl(url)) {
                    return@forEach
                }
                Log.d(ANG_PACKAGE, url)
                GlobalScope.launch(Dispatchers.IO) {
                    val configText = try {
                        URL(url).readText()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@launch
                    }
                    launch(Dispatchers.Main) {
                        importBatchConfig(Utils.decode(configText), it.first)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.title_file_chooser)),
                    REQUEST_FILE_CHOOSER)
        } catch (ex: android.content.ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        RxPermissions(this)
                .request(Manifest.permission.READ_EXTERNAL_STORAGE)
                .subscribe {
                    if (it) {
                        try {
                            contentResolver.openInputStream(uri).use { input ->
                                importCustomizeConfig(input?.bufferedReader()?.readText())
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else
                        toast(R.string.toast_permission_denied)
                }
    }

    /**
     * import customize config
     */
    fun importCustomizeConfig(server: String?) {
        try {
            if (server == null || TextUtils.isEmpty(server)) {
                toast(R.string.toast_none_data)
                return
            }
            mainViewModel.appendCustomConfigServer(server)
            toast(R.string.toast_success)
            adapter.notifyItemInserted(mainViewModel.serverList.lastIndex)
        } catch (e: Exception) {
            ToastCompat.makeText(this, "${getString(R.string.toast_malformed_josn)} ${e.cause?.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            return
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun showCircle() {
        fabProgressCircle?.show()
    }

    fun hideCircle() {
        try {
            Observable.timer(300, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe {
                        if (fabProgressCircle.isShown) {
                            fabProgressCircle?.hide()
                        }
                    }
        } catch (e: Exception) {
        }
    }

    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            //R.id.server_profile -> activityClass = MainActivity::class.java
            R.id.sub_setting -> {
                startActivity(Intent(this, SubSettingActivity::class.java))
            }
            R.id.settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)
                        .putExtra("isRunning", mainViewModel.isRunning.value == true))
            }
            R.id.feedback -> {
                //Utils.openUri(this, AppConfig.v2rayNGIssues)
                launchUrl(AppConfig.v2rayNGIssues)
            }
//            R.id.open_about -> {
//                //launchUrl("android.resource://"+getPackageName()+"/"+ R.raw.about)
//            }
//            R.id.donate -> {
////                startActivity<InappBuyActivity>()
//            }
            R.id.open_glist -> {
                launchUrl(AppConfig.gListUrl)
            }
        }
        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }

    lateinit var snackbar: CoordinatorLayout private set
    fun snackbar(text: CharSequence = "") = Snackbar.make(snackbar, text, Snackbar.LENGTH_LONG).apply {
        anchorView = fab
    }
    private val customTabsIntent by lazy {
        CustomTabsIntent.Builder().apply {
            setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
            }.build())
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
            }.build())
        }.build()
    }
    fun launchUrl(uri: String) = try {
        customTabsIntent.launchUrl(this, Uri.parse(uri))
    } catch (_: ActivityNotFoundException) {
        snackbar(uri).show()
    }
}
