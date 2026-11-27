package com.camasirsarayi.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.camasirsarayi.app.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    internal lateinit var ui: ActivityMainBinding
    internal val model: LaundryViewModel by viewModels()
    internal lateinit var repo: LaundryRepository
    internal val demo get() = repo.auth == null
    internal val state get() = model.state
    internal val member get() = state.optJSONObject("member") ?: JSONObject()
    internal val admin get() = member.optString("role") == "ADMIN"
    internal val operationsStaff get() = admin && member.optString("staffScope") == "OPERATIONS"
    internal val machines get() = state.items("machines")
    internal val rooms get() = state.items("rooms")
    internal val mine get() = machines.firstOrNull { it.optBoolean("mine") }
    internal val selected get() = machines.firstOrNull { it.optString("id") == model.machineId }
    internal val now get() = state.optLong("now") + (SystemClock.elapsedRealtime() - model.receivedAt)
    internal val settings by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }

    private var operation = 0
    private var feedbackBar: Snackbar? = null

    internal fun actionFeedback(message: String, pending: Boolean = false) {
        feedbackBar?.dismiss()
        feedbackBar = Snackbar.make(ui.root, message, if (pending) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG)
            .apply {
                if (ui.navCard.isVisible) anchorView = ui.navCard
                show()
            }
    }
    private var refreshing = false
    private val timer = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (repo.dormId.isNotEmpty() && model.page in signedInPages && !model.busy) refreshStatus()
            timer.postDelayed(this, if (BuildConfig.LAUNDRY_WORKER_URL.isBlank()) 5000 else 20000)
        }
    }
    private val signedInPages = setOf("home", "machines", "detail", "queue", "wash", "duration", "history",
        "notifications", "profile", "report", "faults", "members", "reports", "settings", "joinCode", "machineForm", "pending")
    internal val formPages = setOf("duration", "report", "machineForm", "settings")
    internal var countdown: (() -> Unit)? = null
    private val ticker = object : Runnable {
        override fun run() {
            countdown?.invoke()
            timer.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(settings.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        super.onCreate(savedInstanceState)
        repo = LaundryRepository(applicationContext)
        if (savedInstanceState != null) {
            model.adminSignIn = savedInstanceState.getBoolean("adminSignIn")
            model.appleDeletion = savedInstanceState.getBoolean("appleDeletion")
        }
        ui = ActivityMainBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(ui.root)
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(system.left, system.top, system.right, maxOf(system.bottom, keyboard.bottom))
            insets
        }
        val light = resources.configuration.uiMode and 48 != 32
        WindowCompat.getInsetsController(window, ui.root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        ui.back.setOnClickListener { back() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        val resumeSession = model.page in setOf("welcome", "resumeSession") && repo.auth?.currentUser != null
        if (resumeSession) model.page = "resumeSession"
        render()
        if (resumeSession) resumeAccount()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("adminSignIn", model.adminSignIn)
        outState.putBoolean("appleDeletion", model.appleDeletion)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        timer.post(refresh)
        timer.post(ticker)
    }

    override fun onStop() {
        timer.removeCallbacks(refresh)
        timer.removeCallbacks(ticker)
        super.onStop()
    }

    internal fun route(page: String, replace: Boolean = false) {
        if (page != model.page && feedbackBar?.duration == Snackbar.LENGTH_INDEFINITE) feedbackBar?.dismiss()
        if (page == "enroll") repo.studentDraftDorm = model.dorm?.optString("id").orEmpty()
        if (page == "pending" || page == "home" && !admin) repo.studentDraftDorm = ""
        if (page != model.page && !replace) model.back.add(model.page)
        if (replace) model.back.clear()
        model.page = page
        model.filter = ""
        render()
        ui.scroll.scrollTo(0, 0)
    }

    private fun back() {
        if (feedbackBar?.duration == Snackbar.LENGTH_INDEFINITE) feedbackBar?.dismiss()
        val previous = model.back.removeLastOrNull()
        when {
            previous != null -> {
                model.page = previous
                model.filter = ""
                render()
                ui.scroll.scrollTo(0, 0)
            }
            model.page in signedInPages && model.page != "home" && member.optString("status") == "ACTIVE" -> route("home", true)
            model.page != "welcome" && model.page !in signedInPages -> {
                if (repo.auth?.currentUser != null) finish() else route("welcome", true)
            }
            else -> finish()
        }
    }

    internal fun request(action: String, args: Map<String, Any> = emptyMap(), after: ((JSONObject) -> Unit)? = null) {
        if (model.busy) return
        if (action == "machine") actionFeedback("Makine kaydediliyor…", pending = true)
        model.busy = true
        val currentOperation = ++operation
        repo.call(action, args) { result ->
            if (currentOperation != operation) return@call
            model.busy = false
            if (isDestroyed) return@call
            result.onSuccess { value ->
                if (value.has("member")) accept(value)
                if (after != null) after(value) else render()
                if (action == "machine") actionFeedback("Makine kaydedildi.")
            }.onFailure {
                if (it is com.google.firebase.auth.FirebaseAuthInvalidUserException ||
                    it.laundryErrorCode() == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                    val staff = model.adminSignIn
                    signOut()
                    route(if (staff) "adminLogin" else "login", true)
                    if (action == "machine") actionFeedback("Oturumun sona erdi. Yeniden giriş yap.")
                    else message("Oturumun sona erdi. Yeniden giriş yap.")
                    return@call
                }
                val code = it.laundryErrorCode()
                val errorMessage = when {
                    action == "machine" && (it is java.io.IOException ||
                        code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE) ->
                        "Makine kaydının sonucunu doğrulayamadık. Listeyi kontrol edip gerekirse tekrar dene."
                    action == "managerAccess" && code != com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND ->
                        "Personel yetkisi kontrol edilemedi. Tekrar dene."
                    code != com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> null
                    action == "managerAccess" -> "Yönetici hizmeti henüz yayında değil."
                    action == "enroll" -> "Yurt katılımı henüz açılmadı. Yurt listesi üzerinden yurtları inceleyebilirsin."
                    else -> null
                }
                val detail = errorMessage ?: it.localizedMessage ?: "İşlem tamamlanamadı."
                if (action == "machine") actionFeedback(detail) else message(detail)
            }
        }
    }

    private fun accept(value: JSONObject) {
        model.state = value
        model.receivedAt = SystemClock.elapsedRealtime()
        model.lastUpdate = System.currentTimeMillis()
        model.offline = false
    }

    private fun refreshStatus() {
        if (refreshing) return
        refreshing = true
        val currentOperation = operation
        val dorm = repo.dormId
        val account = repo.auth?.currentUser?.uid
        repo.call("status", emptyMap()) { result ->
            refreshing = false
            if (isDestroyed || currentOperation != operation || model.busy || model.page !in signedInPages || dorm != repo.dormId || account != repo.auth?.currentUser?.uid) return@call
            result.onSuccess { value ->
                val before = JSONObject(state.toString()).apply { remove("now") }.toString()
                val after = JSONObject(value.toString()).apply { remove("now") }.toString()
                val wasOffline = model.offline
                accept(value)
                val status = member.optString("status")
                if (status != "ACTIVE" && model.page != "pending") route("pending", true)
                else if (status == "ACTIVE" && model.page == "pending") route("home", true)
                else if (model.page in formPages || model.editing) updateBanner()
                else if (wasOffline || before != after) renderKeepingScroll()
            }.onFailure {
                val code = it.laundryErrorCode()
                if (it is com.google.firebase.auth.FirebaseAuthInvalidUserException ||
                    code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                    val staff = model.adminSignIn
                    signOut()
                    route(if (staff) "adminLogin" else "login", true)
                } else if (code == com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED ||
                    code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND) {
                    model.state = JSONObject()
                    repo.dormId = ""
                    resumeAccount()
                } else if (!model.offline) {
                    model.offline = true
                    if (model.page in formPages || model.editing) updateBanner() else renderKeepingScroll()
                }
            }
        }
    }

    private fun renderKeepingScroll() {
        val position = ui.scroll.scrollY
        render()
        ui.scroll.post { ui.scroll.scrollTo(0, position) }
    }

    internal fun signOut() {
        feedbackBar?.dismiss()
        operation++
        model.busy = false
        repo.auth?.signOut()
        clearProviderSession()
        model.adminSignIn = false
        model.appleDeletion = false
        model.dorm = null
        model.catalog = emptyList()
        model.managerDorms = emptyList()
        model.managerEmailUnverified = null
        model.staffStatus = ""
        model.kykStaffStatus = false
        model.publicDorms = null
        model.dormSearch = ""
        model.pendingInvite = ""
        model.kykInvitations = emptyList()
        model.staffReviewDorm = ""
        model.staffApplications = emptyList()
        model.roomId = ""
        model.name = ""
        model.email = ""
        model.state = JSONObject()
        model.offline = false
        route("welcome", true)
    }

    internal fun refreshMembership() = request("status") {
        route(if (member.optString("status") == "ACTIVE") "home" else "pending", true)
    }

    private fun updateBanner() {
        ui.banner.isVisible = model.offline || demo && model.page != "welcome"
        ui.banner.text = if (model.offline) "Çevrimdışısın • Son güncelleme: ${clock(model.lastUpdate)}" else getString(R.string.demo_notice)
    }

    internal fun render() {
        countdown = null
        model.editing = false
        val page = model.page
        ui.content.removeAllViews()
        ui.content.gravity = if (page == "welcome") android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.TOP
        ui.dormBody.removeAllViews()
        ui.dormBody.isVisible = page == "dorms"
        ui.scroll.isVisible = page != "dorms"
        ui.content.setPadding(if (page == "home" || page == "welcome") 0 else dp(20), 0,
            if (page == "home" || page == "welcome") 0 else dp(20), dp(16))
        ui.toolbar.isVisible = page != "home" && page != "welcome"
        ui.title.text = screenTitle(page)
        updateBanner()
        navigation(page)
        when (page) {
            "welcome" -> welcome()
            "resumeSession" -> {
                notice("Oturum ve yurt yetkisi kontrol ediliyor.")
                button("Yeniden Dene") { resumeAccount() }
                accountLink("Çıkış Yap") { signOut() }
            }
            "login", "adminLogin" -> login(page == "adminLogin")
            "register", "adminRegister" -> register(page == "adminRegister")
            "staffStatus" -> staffStatus()
            "verifyEmail" -> verifyEmail()
            "kykRegister" -> kykRegister()
            "kykInviteAccept" -> kykInviteAccept()
            "kykInvitations" -> kykInvitations()
            "staffReviews" -> staffReviews()
            "adminAccess" -> managerAccess()
            "dorms" -> dorms()
            "enroll" -> enroll()
            "pending" -> pending()
            "home" -> home()
            "machines" -> machineList()
            "detail" -> machineDetail()
            "queue" -> if (admin) adminQueue() else studentQueue()
            "wash" -> activeWash()
            "duration" -> duration()
            "history" -> history()
            "report" -> reportFault()
            "notifications" -> notifications()
            "profile" -> profile()
            "machineForm" -> machineForm()
            "faults" -> faultList()
            "members" -> members()
            "settings" -> dormSettings()
            "joinCode" -> manageJoinCode()
            "reports" -> reports()
        }
    }

    private var navigationIsAdmin: Boolean? = null

    private fun navigation(page: String) {
        val routes = listOf("home", "machines", "queue", if (admin) "faults" else "history", "profile")
        ui.navCard.isVisible = page in routes || page == "wash" || page == "reports"
        if (!ui.navCard.isVisible) return
        if (navigationIsAdmin != admin) {
            navigationIsAdmin = admin
            val titles = listOf(if (admin) R.string.panel else R.string.home, R.string.machines, R.string.queue,
                if (admin) R.string.faults else R.string.history, R.string.profile)
            val icons = listOf(R.drawable.ic_home, R.drawable.ic_machine, R.drawable.ic_queue,
                if (admin) R.drawable.ic_fault else R.drawable.ic_history, R.drawable.ic_profile)
            ui.nav.menu.clear()
            routes.forEachIndexed { index, _ -> ui.nav.menu.add(0, index + 1, index, titles[index]).setIcon(icons[index]) }
            ui.nav.setOnItemSelectedListener {
                route(routes[it.itemId - 1], true)
                true
            }
        }
        val selectedId = (routes.indexOf(page).takeIf { it >= 0 } ?: 0) + 1
        ui.nav.menu.findItem(3).isVisible = !operationsStaff
        ui.nav.menu.findItem(selectedId).isChecked = true
    }

    internal fun setNightPreference(mode: Int) {
        settings.edit().putInt("theme", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
