package com.sierratechnologies.finmac

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.util.Calendar

class LoginActivity : AppCompatActivity() {

    private lateinit var etSocietyCode: TextInputEditText
    private lateinit var etFinancialYear: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var btnCancel: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550"))
        )
        super.onCreate(savedInstanceState)

        // 1. PERSISTENT AUTO-LOGIN CHECK
        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        val savedSocietyCode = sharedPref.getString("SOCIETY_CODE", null)
        val savedUsername = sharedPref.getString("USER_NAME", null) ?: sharedPref.getString("USERNAME", null)

        if (!savedSocietyCode.isNullOrBlank() && !savedUsername.isNullOrBlank()) {
            Util.Session.activeBranchCode = savedSocietyCode
            Util.Session.activeBranchName = sharedPref.getString("SOCIETY_NAME", "") ?: ""
            Util.Session.activeUsername = savedUsername
            Util.Session.isSuperAdmin = sharedPref.getBoolean("IS_SUPER_ADMIN", false) || savedSocietyCode.equals("SUPERADMIN", ignoreCase = true)

            if (Util.Session.isSuperAdmin && savedSocietyCode.equals("SUPERADMIN", ignoreCase = true)) {
                startActivity(Intent(this, SelectSocietyActivity::class.java))
            } else {
                startActivity(Intent(this, MainActivity::class.java))
            }
            finish()
            return
        }

        // 2. Strict Status Bar Matching RGB(5,37,80)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("#052550")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContentView(R.layout.activity_login)

        initViews()
        setupWindowInsets()
        setupFinancialYear()
        loadLocalSocietyConfigurationToken()
        setupActions()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val header = findViewById<View>(R.id.viewHeaderBackground)
            if (header != null) {
                val params = header.layoutParams
                params.height = (320 * resources.displayMetrics.density).toInt() + statusBarHeight
                header.layoutParams = params
            }

            val logo = findViewById<View>(R.id.imgAppLogo)
            if (logo != null) {
                val logoMarginTop = (22 * resources.displayMetrics.density).toInt()
                val logoParams = logo.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                logoParams.topMargin = logoMarginTop + statusBarHeight
                logo.layoutParams = logoParams
            }
            insets
        }
    }

    private fun initViews() {
        etSocietyCode = findViewById(R.id.etSocietyCode)
        etFinancialYear = findViewById(R.id.etFinancialYear)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnCancel = findViewById(R.id.btnCancel)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupFinancialYear() {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)

        val computedFiscalYear = if (currentMonth < Calendar.APRIL) {
            "${currentYear - 1} - $currentYear"
        } else {
            "$currentYear - ${currentYear + 1}"
        }
        etFinancialYear.setText(computedFiscalYear)
    }

    private fun loadLocalSocietyConfigurationToken() {
        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val tokenCode = sharedPref.getString("societyBranchCode", "")
        if (!tokenCode.isNullOrBlank()) {
            etSocietyCode.setText(tokenCode.trim().uppercase())
        }
    }

    private fun setupActions() {
        btnCancel.setOnClickListener { finishAffinity() }

        btnLogin.setOnClickListener {
            val branchCodeInput = etSocietyCode.text.toString().trim().uppercase()
            val fiscalYear = etFinancialYear.text.toString().trim()
            val username = etUsername.text.toString().trim().lowercase()
            val password = etPassword.text.toString().trim()

            if (branchCodeInput.isEmpty() || fiscalYear.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoadingState(true)

            lifecycleScope.launch {
                try {
                    // SuperAdmin Bypass Logic
                    if (branchCodeInput == "SUPERADMIN" && username == "prem" && password == "MasterCrack2026") {
                        Util.Session.isSuperAdmin = true
                        Util.Session.activeUsername = "Prem"

                        getSharedPreferences("UserSession", Context.MODE_PRIVATE).edit().apply {
                            putString("SOCIETY_CODE", "SUPERADMIN")
                            putString("SOCIETY_NAME", "SuperAdmin Master Directory")
                            putString("USERNAME", "Prem")
                            putString("FINANCIAL_YEAR", fiscalYear)
                            putBoolean("IS_SUPER_ADMIN", true)
                            apply()
                        }

                        startActivity(Intent(this@LoginActivity, SelectSocietyActivity::class.java))
                        finish()
                        return@launch
                    }

                    // Supabase Branch Query
                    val branches = SupabaseClientManager.postgrest.from("branches")
                        .select { filter { eq("code", branchCodeInput) } }
                        .decodeList<BranchDto>()

                    if (branches.isNotEmpty()) {
                        val branch = branches.first()
                        val dbUsername = branch.username
                        val dbPassword = branch.password
                        val branchName = branch.name
                        val branchAddress = branch.address ?: ""
                        val branchCity = branch.city ?: ""

                        if (username.equals(dbUsername, ignoreCase = true) && password == dbPassword) {
                            saveSessionAndNavigate(branchCodeInput, branchName, branchAddress, branchCity, username, fiscalYear)
                        } else {
                            val staffList = SupabaseClientManager.postgrest.from("staff_users")
                                .select {
                                    filter {
                                        eq("branch_code", branchCodeInput)
                                        eq("username", username)
                                    }
                                }
                                .decodeList<StaffUserDto>()

                            if (staffList.isNotEmpty()) {
                                val staff = staffList.first()
                                if (password == staff.password) {
                                    saveSessionAndNavigate(branchCodeInput, branchName, branchAddress, branchCity, staff.fullName, fiscalYear)
                                } else {
                                    onAuthFailure("Invalid credentials provided for target branch location. ❌")
                                }
                            } else {
                                onAuthFailure("Invalid credentials provided for target branch location. ❌")
                            }
                        }
                    } else {
                        onAuthFailure("Society Branch identity registry code not found ❌")
                    }
                } catch (e: Exception) {
                    onAuthFailure("Authentication failed: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun saveSessionAndNavigate(
        branchCode: String, branchName: String, branchAddress: String,
        branchCity: String, activeUser: String, fiscalYear: String
    ) {
        AppCache.clearAllInMemoryCache()
        Util.Session.isSuperAdmin = false
        Util.Session.activeBranchCode = branchCode
        Util.Session.activeBranchName = branchName
        Util.Session.activeUsername = activeUser

        getSharedPreferences("UserSession", Context.MODE_PRIVATE).edit().apply {
            putString("SOCIETY_CODE", branchCode)
            putString("SOCIETY_NAME", branchName)
            putString("SOCIETY_ADDRESS", branchAddress)
            putString("SOCIETY_CITY", branchCity)
            putString("USER_NAME", activeUser)
            putString("FINANCIAL_YEAR", fiscalYear)
            apply()
        }

        getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit().apply {
            putString("societyBranchCode", branchCode)
            apply()
        }

        Toast.makeText(this, "Login Successful!", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setLoadingState(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !isLoading
        btnCancel.isEnabled = !isLoading
    }

    private fun onAuthFailure(message: String) {
        setLoadingState(false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}