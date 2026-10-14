package com.sierratechnologies.finmac

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itextpdf.text.BaseColor
import com.itextpdf.text.Document
import com.itextpdf.text.Element
import com.itextpdf.text.Font
import com.itextpdf.text.PageSize
import com.itextpdf.text.Paragraph
import com.itextpdf.text.Rectangle
import com.itextpdf.text.pdf.PdfPCell
import com.itextpdf.text.pdf.PdfPTable
import com.itextpdf.text.pdf.PdfWriter
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class VoucherTransactionActivity : AppCompatActivity() {

    enum class VoucherMode { RECEIPT, PAYMENT, CONTRA }

    private lateinit var viewHeaderBackground: View
    private lateinit var btnBack: ImageView
    private lateinit var btnRecentTx: ImageView
    private lateinit var lblHeaderTitle: TextView
    private lateinit var lblInvoiceNum: TextView
    private lateinit var toggleModeGroup: MaterialButtonToggleGroup

    private lateinit var tilEntityCode: TextInputLayout
    private lateinit var txtEntityCode: EditText
    private lateinit var txtPartyName: EditText

    private lateinit var groupCategory: LinearLayout
    private lateinit var containerAccountGroup: LinearLayout
    private lateinit var spnCategory: Spinner
    private lateinit var spnAccountGroup: Spinner

    private lateinit var groupStandardMode: LinearLayout
    private lateinit var spnPaymentMode: Spinner
    private lateinit var bankSelectionGroup: LinearLayout
    private lateinit var spnBank: Spinner

    private lateinit var groupContraAccounts: LinearLayout
    private lateinit var spnSourceAccount: Spinner
    private lateinit var spnDestinationAccount: Spinner

    private lateinit var txtNarration: EditText
    private lateinit var txtBaseAmount: EditText
    private lateinit var txtFine: EditText
    private lateinit var tilFine: TextInputLayout
    private lateinit var lblFineVal: TextView
    private lateinit var lblTotalVal: TextView
    private lateinit var btnProcess: MaterialButton
    private lateinit var btnMakeNewEntry: MaterialButton
    private lateinit var btnDownloadPdf: MaterialButton
    private lateinit var loadingOverlay: FrameLayout

    private var currentMode = VoucherMode.RECEIPT
    private var activeBranchCode = ""
    private var activeUsername = "Admin"
    private var nextInvoiceSerial = 1L
    private var branchName = "Khetan Group / Society"
    private var branchAddress = "Head Office Administration"
    private val bankNameToAccMap = HashMap<String, String>()

    // Instant Local Payee Caching Maps (0ms Lookup)
    private val memberCodeToNameMap = HashMap<String, String>()
    private val vendorCodeToNameMap = HashMap<String, String>()

    // Dynamic Account Categories & Groups Master
    private val branchCustomCategories = ArrayList<AccountCategoryDto>()
    private val accountGroupsList = mutableListOf("LIABILITY", "ASSET", "INCOME", "EXPENSE", "+ Add New Group")

    private var loadedVoucherNo: String? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voucher_transaction)

        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        activeBranchCode = intent.getStringExtra("SOCIETY_CODE") ?: sharedPref.getString("SOCIETY_CODE", "")?.trim() ?: ""
        activeUsername = sharedPref.getString("USERNAME", "Admin") ?: "Admin"

        if (activeBranchCode.isBlank()) {
            Toast.makeText(this, "Session invalid. Please re-login.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initViews()
        setupWindowInsets()
        setupTextWatchers()

        btnBack.setOnClickListener { finish() }
        btnRecentTx.setOnClickListener { showRecentTransactionsSheet() }
        btnProcess.setOnClickListener { executeVoucherProcessingSequence() }
        btnMakeNewEntry.setOnClickListener { startBrandNewEntry() }
        btnDownloadPdf.setOnClickListener { generateProfessionalPdfArtifact() }

        toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                updateModeTabThemeColors(checkedId)

                if (loadedVoucherNo == null) {
                    when (checkedId) {
                        R.id.btnTabReceipt -> switchMode(VoucherMode.RECEIPT)
                        R.id.btnTabPayment -> switchMode(VoucherMode.PAYMENT)
                        R.id.btnTabContra -> switchMode(VoucherMode.CONTRA)
                    }
                }
            }
        }

        val initialModeStr = intent.getStringExtra("VOUCHER_MODE") ?: "RECEIPT"
        val targetButtonId = when (initialModeStr.uppercase(Locale.getDefault())) {
            "PAYMENT" -> R.id.btnTabPayment
            "CONTRA" -> R.id.btnTabContra
            else -> R.id.btnTabReceipt
        }

        toggleModeGroup.check(targetButtonId)
        updateModeTabThemeColors(toggleModeGroup.checkedButtonId.takeIf { it != View.NO_ID } ?: R.id.btnTabReceipt)

        switchMode(currentMode)
        loadDataPipeline()
    }

    private fun initViews() {
        viewHeaderBackground = findViewById(R.id.viewHeaderBackground)
        btnBack = findViewById(R.id.btnBack)
        btnRecentTx = findViewById(R.id.btnRecentTx)
        lblHeaderTitle = findViewById(R.id.lblHeaderTitle)
        lblInvoiceNum = findViewById(R.id.lblInvoiceNum)
        toggleModeGroup = findViewById(R.id.toggleModeGroup)

        tilEntityCode = findViewById(R.id.tilEntityCode)
        txtEntityCode = findViewById(R.id.txtEntityCode)
        txtPartyName = findViewById(R.id.txtPartyName)

        groupCategory = findViewById(R.id.groupCategory)
        containerAccountGroup = findViewById(R.id.containerAccountGroup)
        spnCategory = findViewById(R.id.spnCategory)
        spnAccountGroup = findViewById(R.id.spnAccountGroup)

        groupStandardMode = findViewById(R.id.groupStandardMode)
        spnPaymentMode = findViewById(R.id.spnPaymentMode)
        bankSelectionGroup = findViewById(R.id.bankSelectionGroup)
        spnBank = findViewById(R.id.spnBank)

        groupContraAccounts = findViewById(R.id.groupContraAccounts)
        spnSourceAccount = findViewById(R.id.spnSourceAccount)
        spnDestinationAccount = findViewById(R.id.spnDestinationAccount)

        txtNarration = findViewById(R.id.txtNarration)
        txtBaseAmount = findViewById(R.id.txtBaseAmount)
        txtFine = findViewById(R.id.txtFine)
        tilFine = findViewById(R.id.tilFine)
        lblFineVal = findViewById(R.id.lblFineVal)
        lblTotalVal = findViewById(R.id.lblTotalVal)
        btnProcess = findViewById(R.id.btnProcess)
        btnMakeNewEntry = findViewById(R.id.btnMakeNewEntry)
        btnDownloadPdf = findViewById(R.id.btnDownloadPdf)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        setupAccountGroupSpinner()

        spnPaymentMode.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf("Cash", "Bank Transfer / Check")).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spnPaymentMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                bankSelectionGroup.visibility = if (position == 1 && currentMode != VoucherMode.CONTRA) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnDownloadPdf.isEnabled = false
    }

    private fun setFormFieldsLocked(locked: Boolean) {
        val enabled = !locked
        val isSuperAdmin = Util.Session.isSuperAdmin || activeBranchCode.equalsIgnoreCase("SUPERADMIN")

        txtEntityCode.isEnabled = enabled
        spnCategory.isEnabled = enabled
        containerAccountGroup.visibility = if (isSuperAdmin) View.VISIBLE else View.GONE
        spnAccountGroup.isEnabled = enabled && isSuperAdmin
        spnPaymentMode.isEnabled = enabled
        spnBank.isEnabled = enabled
        spnSourceAccount.isEnabled = enabled
        spnDestinationAccount.isEnabled = enabled
        txtNarration.isEnabled = enabled
        txtBaseAmount.isEnabled = enabled
        txtFine.isEnabled = enabled
        btnProcess.isEnabled = enabled

        findViewById<MaterialButton>(R.id.btnTabReceipt).isEnabled = enabled
        findViewById<MaterialButton>(R.id.btnTabPayment).isEnabled = enabled
        findViewById<MaterialButton>(R.id.btnTabContra).isEnabled = enabled

        val alphaVal = if (locked) 0.6f else 1.0f
        findViewById<View>(R.id.tilEntityCode).alpha = alphaVal
        findViewById<View>(R.id.groupCategory).alpha = alphaVal
        findViewById<View>(R.id.groupStandardMode).alpha = alphaVal
        findViewById<View>(R.id.groupContraAccounts).alpha = alphaVal
        txtNarration.alpha = alphaVal
        txtBaseAmount.alpha = alphaVal
        txtFine.alpha = alphaVal
        btnProcess.alpha = alphaVal
    }

    private fun startBrandNewEntry() {
        setFormFieldsLocked(false)
        resetFormFields()
        btnDownloadPdf.isEnabled = false
        Toast.makeText(this, "Form unlocked for new voucher entry! 📝", Toast.LENGTH_SHORT).show()
    }

    private fun setupAccountGroupSpinner() {
        val isSuperAdmin = Util.Session.isSuperAdmin || activeBranchCode.equalsIgnoreCase("SUPERADMIN")
        containerAccountGroup.visibility = if (isSuperAdmin) View.VISIBLE else View.GONE
        val groupAdapter = ArrayAdapter(this, R.layout.spinner_item, accountGroupsList)
        groupAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spnAccountGroup.adapter = groupAdapter
        spnAccountGroup.isEnabled = isSuperAdmin

        spnAccountGroup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = spnAccountGroup.selectedItem?.toString() ?: ""
                if (selected == "+ Add New Group") {
                    showAddNewGroupDialog()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateModeTabThemeColors(checkedId: Int) {
        val activeColor = Color.parseColor("#052550")
        val inactiveTextColor = Color.parseColor("#64748B")
        val transparentColor = Color.TRANSPARENT

        val btnReceipt = findViewById<MaterialButton>(R.id.btnTabReceipt)
        val btnPayment = findViewById<MaterialButton>(R.id.btnTabPayment)
        val btnContra = findViewById<MaterialButton>(R.id.btnTabContra)

        val buttons = listOf(btnReceipt, btnPayment, btnContra)

        for (btn in buttons) {
            if (btn != null) {
                if (btn.id == checkedId) {
                    btn.setBackgroundColor(activeColor)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(transparentColor)
                    btn.setTextColor(inactiveTextColor)
                }
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            viewHeaderBackground.setPadding(0, statusBarHeight, 0, 0)
            loadingOverlay.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun switchMode(mode: VoucherMode, clearFields: Boolean = true) {
        currentMode = mode
        if (clearFields && txtEntityCode.text.isNullOrBlank() && loadedVoucherNo == null) {
            resetFormFields()
        }

        val categoryOptions = ArrayList<String>()

        when (mode) {
            VoucherMode.RECEIPT -> {
                lblHeaderTitle.text = "RECEIPT VOUCHER ENTRY"
                tilEntityCode.hint = "Flat / Unit Code *"
                groupCategory.visibility = View.VISIBLE
                groupStandardMode.visibility = View.VISIBLE
                groupContraAccounts.visibility = View.GONE
                tilFine.visibility = View.VISIBLE

                categoryOptions.addAll(
                    listOf(
                        "Bank Intrest", "Deposits Recieved", "Entry Fee", "Fixed Deposit Maturity",
                        "Maintainance Collection", "Monthly Contributions", "Other Charges",
                        "Parking Charges", "Reserve Funds", "Share Capital", "Sinking Fund"
                    )
                )
            }
            VoucherMode.PAYMENT -> {
                lblHeaderTitle.text = "PAYMENT DISBURSEMENT"
                tilEntityCode.hint = "Vendor Code *"
                groupCategory.visibility = View.VISIBLE
                groupStandardMode.visibility = View.VISIBLE
                groupContraAccounts.visibility = View.GONE
                tilFine.visibility = View.VISIBLE

                categoryOptions.addAll(
                    listOf(
                        "Audit Fees", "Bank Charges", "Clearing Expenses", "Deposit Refund",
                        "Diwali Bonus", "Electrical Repairs and Materials", "Maintainance Expenses",
                        "Miscellaneous Expenses", "Monthly Contributions", "P & L Accounts",
                        "Reserve Fund Deposit", "Salary Expenses", "Temporary Labour Charges"
                    )
                )
            }
            VoucherMode.CONTRA -> {
                lblHeaderTitle.text = "CONTRA VAULT TRANSFER"
                tilEntityCode.hint = "Associated Flat Code *"
                groupCategory.visibility = View.GONE
                groupStandardMode.visibility = View.GONE
                groupContraAccounts.visibility = View.VISIBLE
                tilFine.visibility = View.GONE
            }
        }

        // Add custom categories fetched from Supabase for this branch
        for (custom in branchCustomCategories) {
            if (custom.voucherType.equalsIgnoreCase("ALL") || custom.voucherType.equalsIgnoreCase(mode.name)) {
                if (!categoryOptions.contains(custom.categoryHead)) {
                    categoryOptions.add(custom.categoryHead)
                }
            }
        }

        categoryOptions.add("+ Add New Category")

        val catAdapter = ArrayAdapter(this, R.layout.spinner_item, categoryOptions)
        catAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spnCategory.adapter = catAdapter

        if (spnAccountGroup.selectedItem?.toString().isNullOrBlank()) {
            if (mode == VoucherMode.RECEIPT) {
                setSpinnerValue(spnAccountGroup, "INCOME")
            } else if (mode == VoucherMode.PAYMENT) {
                setSpinnerValue(spnAccountGroup, "EXPENSE")
            }
        }

        spnCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = spnCategory.selectedItem?.toString() ?: ""
                if (selected == "+ Add New Category") {
                    showAddNewCategoryDialog()
                } else {
                    val customMatch = branchCustomCategories.find { it.categoryHead.equalsIgnoreCase(selected) }
                    if (customMatch != null) {
                        setSpinnerValue(spnAccountGroup, customMatch.accountGroup)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        calculateFinancials()
    }

    private var isProgrammaticLoading = false

    private fun onUserFieldModified() {
        if (!isProgrammaticLoading && loadedVoucherNo != null) {
            btnDownloadPdf.isEnabled = false
        }
    }

    private var fetchEntityJob: kotlinx.coroutines.Job? = null

    private fun setupTextWatchers() {
        // Instant 0ms Local Resolution with Async Fallback
        txtEntityCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onUserFieldModified()
                if (loadedVoucherNo != null) return
                val input = s.toString().trim()
                fetchEntityJob?.cancel()

                if (input.isNotEmpty()) {
                    val key = input.lowercase(Locale.getDefault())
                    val cachedName = if (currentMode == VoucherMode.PAYMENT) {
                        vendorCodeToNameMap[key]
                    } else {
                        memberCodeToNameMap[key]
                    }

                    if (cachedName != null) {
                        txtPartyName.setText(cachedName) // 0ms Instant Auto-Fetch!
                    } else {
                        fetchEntityName(input)
                    }
                } else {
                    txtPartyName.setText("")
                }
            }
        })

        val calcWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                calculateFinancials()
                onUserFieldModified()
            }
        }
        txtBaseAmount.addTextChangedListener(calcWatcher)
        txtFine.addTextChangedListener(calcWatcher)

        val narrationWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onUserFieldModified()
            }
        }
        txtNarration.addTextChangedListener(narrationWatcher)
    }

    private fun fetchEntityName(code: String) {
        fetchEntityJob?.cancel()
        fetchEntityJob = lifecycleScope.launch {
            try {
                if (currentMode == VoucherMode.PAYMENT) {
                    val list = SupabaseClientManager.postgrest.from("vendors")
                        .select {
                            filter {
                                eq("branch_code", activeBranchCode)
                                ilike("vendor_code", code)
                            }
                        }
                        .decodeList<VendorDto>()

                    val currentCode = txtEntityCode.text.toString().trim()
                    if (currentCode.equalsIgnoreCase(code)) {
                        if (list.isNotEmpty()) {
                            val name = list.first().partyName
                            vendorCodeToNameMap[code.lowercase(Locale.getDefault())] = name
                            txtPartyName.setText(name)
                        } else {
                            txtPartyName.setText("")
                        }
                    }
                } else {
                    val list = SupabaseClientManager.postgrest.from("members")
                        .select {
                            filter {
                                eq("branch_code", activeBranchCode)
                                ilike("flat_no_id", code)
                            }
                        }
                        .decodeList<MemberDto>()

                    val currentCode = txtEntityCode.text.toString().trim()
                    if (currentCode.equalsIgnoreCase(code)) {
                        if (list.isNotEmpty()) {
                            val name = list.first().memberName
                            memberCodeToNameMap[code.lowercase(Locale.getDefault())] = name
                            txtPartyName.setText(name)
                        } else {
                            txtPartyName.setText("")
                        }
                    }
                }
            } catch (_: Exception) {
                val currentCode = txtEntityCode.text.toString().trim()
                if (currentCode.equalsIgnoreCase(code)) {
                    txtPartyName.setText("")
                }
            }
        }
    }

    private fun showAddNewCategoryDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }

        val tilName = TextInputLayout(this).apply { hint = "New Category Name *" }
        val etName = TextInputEditText(this)
        tilName.addView(etName)

        val lblGroup = TextView(this).apply {
            text = "Select Account Group:"
            setTextColor(Color.parseColor("#1F2937"))
            textSize = 12f
            setPadding(0, 16, 0, 4)
        }

        val availableGroups = accountGroupsList.filter { it != "+ Add New Group" }.toTypedArray()
        val spnGrp = Spinner(this).apply {
            adapter = ArrayAdapter(this@VoucherTransactionActivity, android.R.layout.simple_spinner_dropdown_item, availableGroups)
        }

        layout.addView(tilName)
        layout.addView(lblGroup)
        layout.addView(spnGrp)

        AlertDialog.Builder(this)
            .setTitle("➕ Add New Account Head Category")
            .setView(layout)
            .setPositiveButton("Save Category") { dialog, _ ->
                val newCat = etName.text.toString().trim()
                val selectedGroup = spnGrp.selectedItem?.toString() ?: "INCOME"

                if (newCat.isEmpty()) {
                    Toast.makeText(this, "Category name cannot be empty", Toast.LENGTH_SHORT).show()
                    switchMode(currentMode, clearFields = false)
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    try {
                        val catDto = AccountCategoryDto(
                            branchCode = activeBranchCode,
                            categoryHead = newCat,
                            accountGroup = selectedGroup,
                            voucherType = currentMode.name
                        )

                        SupabaseClientManager.postgrest.from("account_categories").upsert(catDto)
                        branchCustomCategories.add(catDto)
                        Toast.makeText(this@VoucherTransactionActivity, "Category '$newCat' added successfully! ✅", Toast.LENGTH_SHORT).show()

                        switchMode(currentMode, clearFields = false)
                        setSpinnerValue(spnCategory, newCat)
                        setSpinnerValue(spnAccountGroup, selectedGroup)
                    } catch (e: Exception) {
                        Toast.makeText(this@VoucherTransactionActivity, "Error saving category: ${e.message}", Toast.LENGTH_SHORT).show()
                        switchMode(currentMode, clearFields = false)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                switchMode(currentMode, clearFields = false)
                dialog.dismiss()
            }
            .show()
    }

    private fun showAddNewGroupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }

        val tilName = TextInputLayout(this).apply { hint = "New Account Group Name *" }
        val etName = TextInputEditText(this)
        tilName.addView(etName)

        layout.addView(tilName)

        AlertDialog.Builder(this)
            .setTitle("➕ Add New Account Group")
            .setView(layout)
            .setPositiveButton("Save Group") { dialog, _ ->
                val newGrp = etName.text.toString().trim().uppercase(Locale.getDefault())

                if (newGrp.isEmpty()) {
                    Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    try {
                        val catDto = AccountCategoryDto(
                            branchCode = activeBranchCode,
                            categoryHead = newGrp,
                            accountGroup = newGrp,
                            voucherType = "ALL"
                        )

                        SupabaseClientManager.postgrest.from("account_categories").upsert(catDto)
                        branchCustomCategories.add(catDto)

                        if (!accountGroupsList.contains(newGrp)) {
                            accountGroupsList.add(accountGroupsList.size - 1, newGrp)
                            val groupAdapter = ArrayAdapter(this@VoucherTransactionActivity, R.layout.spinner_item, accountGroupsList)
                            groupAdapter.setDropDownViewResource(R.layout.spinner_item)
                            spnAccountGroup.adapter = groupAdapter
                        }

                        setSpinnerValue(spnAccountGroup, newGrp)
                        Toast.makeText(this@VoucherTransactionActivity, "Account Group '$newGrp' saved to database! ✅", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@VoucherTransactionActivity, "Error saving group: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                setSpinnerValue(spnAccountGroup, "INCOME")
                dialog.dismiss()
            }
            .show()
    }

    private fun calculateFinancials() {
        val base = txtBaseAmount.text.toString().toDoubleOrNull() ?: 0.0
        val fineVal = if (currentMode == VoucherMode.CONTRA) 0.0 else (txtFine.text.toString().toDoubleOrNull() ?: 0.0)
        val total = base + fineVal

        lblFineVal.text = String.format("+₹%,.2f", fineVal)
        lblTotalVal.text = String.format("₹%,.2f", total)
    }

    private fun loadDataPipeline() {
        if (activeBranchCode.isBlank()) return

        // 1. Instant 0ms Load from AppCache Disk/Memory
        val cachedMembers = AppCache.getMembers(this, activeBranchCode)
        val cachedVendors = AppCache.getVendors(this, activeBranchCode)
        val cachedBanks = AppCache.getBankAccounts(this, activeBranchCode)

        var hasFullCache = false
        if (cachedMembers != null && cachedVendors != null && cachedBanks != null) {
            hasFullCache = true
            for (m in cachedMembers) {
                memberCodeToNameMap[m.flatNoId.lowercase(Locale.getDefault())] = m.memberName
            }
            for (v in cachedVendors) {
                vendorCodeToNameMap[v.vendorCode.lowercase(Locale.getDefault())] = v.partyName
            }

            val bankRosterLabels = ArrayList<String>()
            bankNameToAccMap.clear()
            val contraAccounts = mutableListOf("Cash in Hand Account")

            for (doc in cachedBanks) {
                val bName = doc.bankName
                val accNo = doc.accountNumber
                if (bName.isNotEmpty() && accNo.length >= 3) {
                    val label = "$bName xxx${accNo.takeLast(3)}"
                    bankRosterLabels.add(label)
                    bankNameToAccMap[label] = accNo
                    contraAccounts.add(label)
                }
            }

            spnBank.adapter = ArrayAdapter(this, R.layout.spinner_item, bankRosterLabels).apply { setDropDownViewResource(R.layout.spinner_item) }
            spnSourceAccount.adapter = ArrayAdapter(this, R.layout.spinner_item, contraAccounts).apply { setDropDownViewResource(R.layout.spinner_item) }
            spnDestinationAccount.adapter = ArrayAdapter(this, R.layout.spinner_item, contraAccounts).apply { setDropDownViewResource(R.layout.spinner_item) }
            if (contraAccounts.size > 1) spnDestinationAccount.setSelection(1)
        }

        if (!hasFullCache) {
            loadingOverlay.visibility = View.VISIBLE
        }

        // 2. Silent Background Sync from Supabase
        lifecycleScope.launch {
            try {
                val members = SupabaseClientManager.postgrest.from("members")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<MemberDto>()

                AppCache.saveMembers(this@VoucherTransactionActivity, activeBranchCode, members)
                for (m in members) {
                    memberCodeToNameMap[m.flatNoId.lowercase(Locale.getDefault())] = m.memberName
                }

                val vendors = SupabaseClientManager.postgrest.from("vendors")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<VendorDto>()

                AppCache.saveVendors(this@VoucherTransactionActivity, activeBranchCode, vendors)
                for (v in vendors) {
                    vendorCodeToNameMap[v.vendorCode.lowercase(Locale.getDefault())] = v.partyName
                }

                try {
                    val customCats = SupabaseClientManager.postgrest.from("account_categories")
                        .select { filter { eq("branch_code", activeBranchCode) } }
                        .decodeList<AccountCategoryDto>()

                    branchCustomCategories.clear()
                    branchCustomCategories.addAll(customCats)

                    for (cat in customCats) {
                        val grp = cat.accountGroup
                        if (grp.isNotBlank() && !accountGroupsList.contains(grp)) {
                            accountGroupsList.add(accountGroupsList.size - 1, grp)
                        }
                    }
                    val groupAdapter = ArrayAdapter(this@VoucherTransactionActivity, R.layout.spinner_item, accountGroupsList)
                    groupAdapter.setDropDownViewResource(R.layout.spinner_item)
                    spnAccountGroup.adapter = groupAdapter
                } catch (_: Exception) {}

                val branches = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", activeBranchCode) } }
                    .decodeList<BranchDto>()

                if (branches.isNotEmpty()) {
                    val branch = branches.first()
                    branchName = branch.name
                    branchAddress = branch.address ?: branchAddress
                }

                val bankSnap = SupabaseClientManager.postgrest.from("bank_accounts")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<BankAccountDto>()

                AppCache.saveBankAccounts(this@VoucherTransactionActivity, activeBranchCode, bankSnap)

                val bankRosterLabels = ArrayList<String>()
                bankNameToAccMap.clear()
                val contraAccounts = mutableListOf("Cash in Hand Account")

                for (doc in bankSnap) {
                    val bName = doc.bankName
                    val accNo = doc.accountNumber
                    if (bName.isNotEmpty() && accNo.length >= 3) {
                        val label = "$bName xxx${accNo.takeLast(3)}"
                        bankRosterLabels.add(label)
                        bankNameToAccMap[label] = accNo
                        contraAccounts.add(label)
                    }
                }

                spnBank.adapter = ArrayAdapter(this@VoucherTransactionActivity, R.layout.spinner_item, bankRosterLabels).apply { setDropDownViewResource(R.layout.spinner_item) }
                spnSourceAccount.adapter = ArrayAdapter(this@VoucherTransactionActivity, R.layout.spinner_item, contraAccounts).apply { setDropDownViewResource(R.layout.spinner_item) }
                spnDestinationAccount.adapter = ArrayAdapter(this@VoucherTransactionActivity, R.layout.spinner_item, contraAccounts).apply { setDropDownViewResource(R.layout.spinner_item) }
                if (contraAccounts.size > 1) spnDestinationAccount.setSelection(1)

                if (loadedVoucherNo == null) {
                    val count = when (currentMode) {
                        VoucherMode.RECEIPT -> SupabaseClientManager.postgrest.from("receipts").select { filter { eq("branch_code", activeBranchCode) } }.decodeList<ReceiptDto>().size
                        VoucherMode.PAYMENT -> SupabaseClientManager.postgrest.from("payments").select { filter { eq("branch_code", activeBranchCode) } }.decodeList<PaymentDto>().size
                        VoucherMode.CONTRA -> SupabaseClientManager.postgrest.from("contra_entries").select { filter { eq("branch_code", activeBranchCode) } }.decodeList<ContraEntryDto>().size
                    }

                    nextInvoiceSerial = (count + 1).toLong()

                    val prefix = when (currentMode) {
                        VoucherMode.RECEIPT -> "R"
                        VoucherMode.PAYMENT -> "P"
                        VoucherMode.CONTRA -> "C"
                    }
                    val year = SimpleDateFormat("yy", Locale.getDefault()).format(Date())
                    lblInvoiceNum.text = String.format("Invoice #%s%s-%04d", prefix, year, nextInvoiceSerial)
                }

                switchMode(currentMode, clearFields = false)
            } catch (e: Exception) {
                if (!hasFullCache) {
                    Toast.makeText(this@VoucherTransactionActivity, "Pipeline load: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun loadVoucherForEditing(targetInvoiceNo: String, targetMode: VoucherMode) {
        currentMode = targetMode
        loadedVoucherNo = targetInvoiceNo
        setFormFieldsLocked(false)
        btnProcess.text = "Update Existing Entry"
        btnDownloadPdf.isEnabled = true
        lblInvoiceNum.text = "Invoice #$targetInvoiceNo"

        when (targetMode) {
            VoucherMode.RECEIPT -> toggleModeGroup.check(R.id.btnTabReceipt)
            VoucherMode.PAYMENT -> toggleModeGroup.check(R.id.btnTabPayment)
            VoucherMode.CONTRA -> toggleModeGroup.check(R.id.btnTabContra)
        }
        updateModeTabThemeColors(toggleModeGroup.checkedButtonId)

        loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                isProgrammaticLoading = true
                when (targetMode) {
                    VoucherMode.RECEIPT -> {
                        val list = SupabaseClientManager.postgrest.from("receipts")
                            .select { filter { eq("branch_code", activeBranchCode); eq("invoice_no", targetInvoiceNo) } }
                            .decodeList<ReceiptDto>()

                        if (list.isNotEmpty()) {
                            val doc = list.first()
                            txtEntityCode.setText(doc.flatNo ?: "")
                            txtPartyName.setText(doc.partyName)
                            txtNarration.setText(doc.customNarration ?: "")
                            setSpinnerValue(spnCategory, doc.categoryHead)
                            setSpinnerValue(spnAccountGroup, doc.accountGroup ?: "INCOME")
                            setSpinnerValue(spnPaymentMode, doc.paymentMode ?: "Cash")
                            if (doc.paymentMode?.contains("Bank", ignoreCase = true) == true) {
                                bankSelectionGroup.visibility = View.VISIBLE
                                setSpinnerValue(spnBank, doc.selectedBank ?: "")
                            }
                            txtBaseAmount.setText(doc.baseAmount.toString())
                            txtFine.setText(doc.fineAmount.toString())
                        }
                    }
                    VoucherMode.PAYMENT -> {
                        val list = SupabaseClientManager.postgrest.from("payments")
                            .select { filter { eq("branch_code", activeBranchCode); eq("invoice_no", targetInvoiceNo) } }
                            .decodeList<PaymentDto>()

                        if (list.isNotEmpty()) {
                            val doc = list.first()
                            txtEntityCode.setText(doc.vendorCode ?: "")
                            txtPartyName.setText(doc.partyName)
                            txtNarration.setText(doc.customNarration ?: "")
                            setSpinnerValue(spnCategory, doc.categoryHead)
                            setSpinnerValue(spnAccountGroup, doc.accountGroup ?: "EXPENSE")
                            setSpinnerValue(spnPaymentMode, doc.paymentMode ?: "Cash")
                            if (doc.paymentMode?.contains("Bank", ignoreCase = true) == true) {
                                bankSelectionGroup.visibility = View.VISIBLE
                                setSpinnerValue(spnBank, doc.selectedBank ?: "")
                            }
                            txtBaseAmount.setText(doc.baseAmount.toString())
                            txtFine.setText(doc.fineAmount.toString())
                        }
                    }
                    VoucherMode.CONTRA -> {
                        val list = SupabaseClientManager.postgrest.from("contra_entries")
                            .select { filter { eq("branch_code", activeBranchCode); eq("voucher_no", targetInvoiceNo) } }
                            .decodeList<ContraEntryDto>()

                        if (list.isNotEmpty()) {
                            val doc = list.first()
                            txtEntityCode.setText(doc.flatNo ?: "")
                            txtPartyName.setText(doc.partyName)
                            txtNarration.setText(doc.narration ?: "")
                            setSpinnerValue(spnSourceAccount, doc.sourceAccount)
                            setSpinnerValue(spnDestinationAccount, doc.destinationAccount)
                            txtBaseAmount.setText(doc.amount.toString())
                        }
                    }
                }
                calculateFinancials()
            } catch (e: Exception) {
                Toast.makeText(this@VoucherTransactionActivity, "Failed to load record: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isProgrammaticLoading = false
                btnDownloadPdf.isEnabled = true
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun setSpinnerValue(spinner: Spinner, value: String) {
        val adapter = spinner.adapter ?: return
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString().equals(value, ignoreCase = true)) {
                spinner.setSelection(i)
                break
            }
        }
    }

    private fun executeVoucherProcessingSequence() {
        val code = txtEntityCode.text.toString().trim()
        val partyName = txtPartyName.text.toString().trim()
        val baseAmount = txtBaseAmount.text.toString().toDoubleOrNull() ?: 0.0

        if (code.isEmpty() || partyName.isEmpty() || baseAmount <= 0.0) {
            Toast.makeText(this, "Please populate required parameter inputs (*)", Toast.LENGTH_SHORT).show()
            return
        }

        if (loadedVoucherNo != null) {
            val inputLayout = TextInputLayout(this).apply {
                hint = "Modification Reason Note *"
                setPadding(32, 16, 32, 16)
            }
            val inputEdit = EditText(this).apply {
                isSingleLine = false
                setLines(3)
            }
            inputLayout.addView(inputEdit)

            AlertDialog.Builder(this)
                .setTitle("⚠️ Secure Audit Policy Intercept")
                .setMessage("Please enter the explicit reason note for modifying this saved voucher entry:")
                .setView(inputLayout)
                .setPositiveButton("Confirm Revision") { dialog, _ ->
                    val reason = inputEdit.text.toString().trim()
                    if (reason.isEmpty()) {
                        Toast.makeText(this, "Modification aborted. Reason note is strictly mandatory.", Toast.LENGTH_LONG).show()
                    } else {
                        commitValidatedVoucherPayload(reason)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } else {
            commitValidatedVoucherPayload("Initial Entry Token")
        }
    }

    private suspend fun computeLiveCashInHandBalance(): Double {
        var runningCash = 0.0

        val cal = Calendar.getInstance()
        val currentMonth = cal.get(Calendar.MONTH) + 1
        val currentYear = cal.get(Calendar.YEAR)
        val startYear = if (currentMonth >= 4) currentYear else currentYear - 1
        val activeFYStr = String.format("FY %d-%s", startYear, (startYear + 1).toString().takeLast(2))

        try {
            val openingList = SupabaseClientManager.postgrest.from("opening_balances")
                .select { filter { eq("branch_code", activeBranchCode); eq("financial_year", activeFYStr) } }
                .decodeList<OpeningBalanceDto>()
            if (openingList.isNotEmpty()) {
                runningCash += openingList.first().openingCashBalance
            }
        } catch (_: Exception) {}

        val cachedReceipts = AppCache.getReceipts(this, activeBranchCode)
            ?: try {
                val fetched = SupabaseClientManager.postgrest.from("receipts")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ReceiptDto>()
                AppCache.saveReceipts(this, activeBranchCode, fetched)
                fetched
            } catch (_: Exception) { emptyList() }

        for (r in cachedReceipts) {
            if (r.paymentMode?.contains("Cash", ignoreCase = true) == true) {
                runningCash += r.totalFinalAmount
            }
        }

        val cachedPayments = AppCache.getPayments(this, activeBranchCode)
            ?: try {
                val fetched = SupabaseClientManager.postgrest.from("payments")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<PaymentDto>()
                AppCache.savePayments(this, activeBranchCode, fetched)
                fetched
            } catch (_: Exception) { emptyList() }

        for (p in cachedPayments) {
            if (p.paymentMode?.contains("Cash", ignoreCase = true) == true) {
                runningCash -= p.totalFinalAmount
            }
        }

        val cachedContra = AppCache.getContra(this, activeBranchCode)
            ?: try {
                val fetched = SupabaseClientManager.postgrest.from("contra_entries")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ContraEntryDto>()
                AppCache.saveContra(this, activeBranchCode, fetched)
                fetched
            } catch (_: Exception) { emptyList() }

        for (c in cachedContra) {
            if (c.sourceAccount.contains("Cash", ignoreCase = true)) {
                runningCash -= c.amount
            }
            if (c.destinationAccount.contains("Cash", ignoreCase = true)) {
                runningCash += c.amount
            }
        }

        return runningCash
    }

    private fun showBalanceBlockAlert(title: String, message: String) {
        loadingOverlay.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Understood", null)
            .show()
    }

    private fun commitValidatedVoucherPayload(reasonNote: String) {
        val isEditing = loadedVoucherNo != null
        val year = SimpleDateFormat("yy", Locale.getDefault()).format(Date())
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val prefix = when (currentMode) {
            VoucherMode.RECEIPT -> "R"
            VoucherMode.PAYMENT -> "P"
            VoucherMode.CONTRA -> "C"
        }
        val targetVoucherID = loadedVoucherNo ?: String.format("%s%s-%04d", prefix, year, nextInvoiceSerial)

        loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val bankAccountsList = AppCache.getBankAccounts(this@VoucherTransactionActivity, activeBranchCode)
                    ?: try {
                        val fetched = SupabaseClientManager.postgrest.from("bank_accounts")
                            .select { filter { eq("branch_code", activeBranchCode) } }
                            .decodeList<BankAccountDto>()
                        AppCache.saveBankAccounts(this@VoucherTransactionActivity, activeBranchCode, fetched)
                        fetched
                    } catch (_: Exception) { emptyList() }

                if (currentMode == VoucherMode.CONTRA) {
                    val src = spnSourceAccount.selectedItem?.toString() ?: "Cash in Hand Account"
                    val dest = spnDestinationAccount.selectedItem?.toString() ?: "Default Bank"
                    val requiredAmount = txtBaseAmount.text.toString().toDoubleOrNull() ?: 0.0

                    if (src == dest) {
                        loadingOverlay.visibility = View.GONE
                        Toast.makeText(this@VoucherTransactionActivity, "Source and Destination cannot be identical", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // 1. Strict Balance Pre-Check for Contra Source
                    if (src.contains("Cash", ignoreCase = true)) {
                        val liveCash = computeLiveCashInHandBalance()
                        if (requiredAmount > liveCash) {
                            showBalanceBlockAlert(
                                "⚠️ Insufficient Cash Vault Funds",
                                String.format(Locale.getDefault(), "Transaction Aborted!\nAvailable Cash in Hand: ₹%,.2f\nRequired: ₹%,.2f", liveCash, requiredAmount)
                            )
                            return@launch
                        }
                    } else {
                        val srcAccNo = bankNameToAccMap[src] ?: ""
                        val srcBank = bankAccountsList.find { it.accountNumber == srcAccNo || src.contains(it.bankName, ignoreCase = true) }
                        val availableBankBal = srcBank?.currentBalance ?: 0.0

                        if (requiredAmount > availableBankBal) {
                            showBalanceBlockAlert(
                                "⚠️ Insufficient Bank Vault Funds",
                                String.format(Locale.getDefault(), "Transaction Aborted!\nAvailable in %s: ₹%,.2f\nRequired: ₹%,.2f", srcBank?.bankName ?: src, availableBankBal, requiredAmount)
                            )
                            return@launch
                        }
                    }

                    val contraDto = ContraEntryDto(
                        branchCode = activeBranchCode,
                        voucherNo = targetVoucherID,
                        date = currentDate,
                        flatNo = txtEntityCode.text.toString().trim(),
                        partyName = txtPartyName.text.toString().trim(),
                        sourceAccount = src,
                        destinationAccount = dest,
                        amount = requiredAmount,
                        narration = txtNarration.text.toString().trim(),
                        operatorIdentity = activeUsername,
                        rejectionOrChangeReason = if (isEditing) reasonNote else ""
                    )

                    SupabaseClientManager.postgrest.from("contra_entries").upsert(contraDto)

                    // Update Source / Destination Bank balances in Supabase
                    if (!src.contains("Cash", ignoreCase = true)) {
                        val srcAccNo = bankNameToAccMap[src] ?: ""
                        val srcBank = bankAccountsList.find { it.accountNumber == srcAccNo || src.contains(it.bankName, ignoreCase = true) }
                        if (srcBank != null) {
                            val newBal = srcBank.currentBalance - requiredAmount
                            val jsonPayload = buildJsonObject {
                                put("current_balance", newBal)
                            }
                            SupabaseClientManager.postgrest.from("bank_accounts").update(jsonPayload) {
                                filter { eq("branch_code", activeBranchCode); eq("account_number", srcBank.accountNumber) }
                            }
                        }
                    }

                    if (!dest.contains("Cash", ignoreCase = true)) {
                        val destAccNo = bankNameToAccMap[dest] ?: ""
                        val destBank = bankAccountsList.find { it.accountNumber == destAccNo || dest.contains(it.bankName, ignoreCase = true) }
                        if (destBank != null) {
                            val newBal = destBank.currentBalance + requiredAmount
                            val jsonPayload = buildJsonObject {
                                put("current_balance", newBal)
                            }
                            SupabaseClientManager.postgrest.from("bank_accounts").update(jsonPayload) {
                                filter { eq("branch_code", activeBranchCode); eq("account_number", destBank.accountNumber) }
                            }
                        }
                    }
                } else if (currentMode == VoucherMode.RECEIPT) {
                    val baseAmount = txtBaseAmount.text.toString().toDoubleOrNull() ?: 0.0
                    val fineVal = txtFine.text.toString().toDoubleOrNull() ?: 0.0
                    val totalAmount = baseAmount + fineVal
                    val selectedBankLabel = if (spnPaymentMode.selectedItemPosition == 1) spnBank.selectedItem?.toString() ?: "N/A" else "N/A"
                    val targetAccNo = bankNameToAccMap[selectedBankLabel] ?: "N/A"

                    val receiptDto = ReceiptDto(
                        branchCode = activeBranchCode,
                        invoiceNo = targetVoucherID,
                        date = currentDate,
                        flatNo = txtEntityCode.text.toString().trim(),
                        partyName = txtPartyName.text.toString().trim(),
                        categoryHead = (spnCategory.selectedItem?.toString() ?: "General"),
                        accountGroup = (spnAccountGroup.selectedItem?.toString() ?: "INCOME"),
                        customNarration = txtNarration.text.toString().trim(),
                        paymentMode = (spnPaymentMode.selectedItem?.toString() ?: "Cash"),
                        selectedBank = selectedBankLabel,
                        linkedAccountNo = targetAccNo,
                        baseAmount = baseAmount,
                        fineAmount = fineVal,
                        totalFinalAmount = totalAmount,
                        operatorAccountIdentity = activeUsername,
                        rejectionOrChangeReason = if (isEditing) reasonNote else ""
                    )

                    SupabaseClientManager.postgrest.from("receipts").upsert(receiptDto)

                    // Update Target Bank Balance for Bank Receipt
                    if (spnPaymentMode.selectedItemPosition == 1 && targetAccNo != "N/A") {
                        val targetBank = bankAccountsList.find { it.accountNumber == targetAccNo }
                        if (targetBank != null) {
                            val newBal = targetBank.currentBalance + totalAmount
                            val jsonPayload = buildJsonObject {
                                put("current_balance", newBal)
                            }
                            SupabaseClientManager.postgrest.from("bank_accounts").update(jsonPayload) {
                                filter { eq("branch_code", activeBranchCode); eq("account_number", targetAccNo) }
                            }
                        }
                    }
                } else {
                    // PAYMENT OUTFLOW VALIDATION
                    val baseAmount = txtBaseAmount.text.toString().toDoubleOrNull() ?: 0.0
                    val fineVal = txtFine.text.toString().toDoubleOrNull() ?: 0.0
                    val totalAmount = baseAmount + fineVal
                    val selectedBankLabel = if (spnPaymentMode.selectedItemPosition == 1) spnBank.selectedItem?.toString() ?: "N/A" else "N/A"
                    val targetAccNo = bankNameToAccMap[selectedBankLabel] ?: "N/A"

                    val isCashPayment = spnPaymentMode.selectedItemPosition == 0

                    // 2. Strict Balance Pre-Check for Payment
                    if (isCashPayment) {
                        val liveCash = computeLiveCashInHandBalance()
                        if (totalAmount > liveCash) {
                            showBalanceBlockAlert(
                                "⚠️ Insufficient Cash Funds",
                                String.format(Locale.getDefault(), "Disbursement Aborted!\nAvailable Cash in Hand: ₹%,.2f\nRequired: ₹%,.2f", liveCash, totalAmount)
                            )
                            return@launch
                        }
                    } else {
                        val targetBank = bankAccountsList.find { it.accountNumber == targetAccNo }
                        val availableBankBal = targetBank?.currentBalance ?: 0.0

                        if (totalAmount > availableBankBal) {
                            showBalanceBlockAlert(
                                "⚠️ Insufficient Bank Funds",
                                String.format(Locale.getDefault(), "Disbursement Aborted!\nAvailable in %s: ₹%,.2f\nRequired: ₹%,.2f", targetBank?.bankName ?: selectedBankLabel, availableBankBal, totalAmount)
                            )
                            return@launch
                        }
                    }

                    val paymentDto = PaymentDto(
                        branchCode = activeBranchCode,
                        invoiceNo = targetVoucherID,
                        date = currentDate,
                        vendorCode = txtEntityCode.text.toString().trim(),
                        partyName = txtPartyName.text.toString().trim(),
                        categoryHead = (spnCategory.selectedItem?.toString() ?: "General"),
                        accountGroup = (spnAccountGroup.selectedItem?.toString() ?: "EXPENSE"),
                        customNarration = txtNarration.text.toString().trim(),
                        paymentMode = (spnPaymentMode.selectedItem?.toString() ?: "Cash"),
                        selectedBank = selectedBankLabel,
                        linkedAccountNo = targetAccNo,
                        baseAmount = baseAmount,
                        fineAmount = fineVal,
                        totalFinalAmount = totalAmount,
                        operatorAccountIdentity = activeUsername,
                        rejectionOrChangeReason = if (isEditing) reasonNote else ""
                    )

                    SupabaseClientManager.postgrest.from("payments").upsert(paymentDto)

                    // Update Target Bank Balance for Bank Payment
                    if (!isCashPayment && targetAccNo != "N/A") {
                        val targetBank = bankAccountsList.find { it.accountNumber == targetAccNo }
                        if (targetBank != null) {
                            val newBal = targetBank.currentBalance - totalAmount
                            val jsonPayload = buildJsonObject {
                                put("current_balance", newBal)
                            }
                            SupabaseClientManager.postgrest.from("bank_accounts").update(jsonPayload) {
                                filter { eq("branch_code", activeBranchCode); eq("account_number", targetAccNo) }
                            }
                        }
                    }
                }

                if (isEditing) {
                    writeAuditLogEntry(targetVoucherID, reasonNote)
                } else {
                    finishCommitSuccess(targetVoucherID)
                }
            } catch (e: Exception) {
                loadingOverlay.visibility = View.GONE
                Toast.makeText(this@VoucherTransactionActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun writeAuditLogEntry(voucherID: String, reasonNote: String) {
        lifecycleScope.launch {
            try {
                val auditDto = VoucherAuditLogDto(
                    branchCode = activeBranchCode,
                    invoiceNo = voucherID,
                    modifiedBy = activeUsername,
                    timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    reasonNote = reasonNote
                )

                SupabaseClientManager.postgrest.from("voucher_audit_logs").insert(auditDto)
            } catch (_: Exception) {
            } finally {
                finishCommitSuccess(voucherID)
            }
        }
    }

    private fun finishCommitSuccess(voucherID: String) {
        loadingOverlay.visibility = View.GONE
        setFormFieldsLocked(true)
        btnDownloadPdf.isEnabled = true
        Toast.makeText(this, "Voucher #$voucherID saved cleanly! Fields locked for security. Ready to download PDF. ✅", Toast.LENGTH_LONG).show()
    }

    private fun resetFormFields() {
        txtEntityCode.setText("")
        txtPartyName.setText("")
        txtNarration.setText("")
        txtBaseAmount.setText("0.00")
        txtFine.setText("0.00")
        loadedVoucherNo = null
        btnProcess.text = "Process & Save Voucher"
        btnDownloadPdf.isEnabled = false
        calculateFinancials()
    }

    private fun String.equalsIgnoreCase(other: String?): Boolean {
        return this.equals(other, ignoreCase = true)
    }

    data class RecentRecord(val date: String, val id: String, val entity: String, val name: String, val amount: String, val mode: VoucherMode)

    private fun showRecentTransactionsSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.layout_recent_transactions_sheet, null)
        dialog.setContentView(sheetView)

        val txtSearch = sheetView.findViewById<EditText>(R.id.txtSearchRecent)
        val rvLogs = sheetView.findViewById<RecyclerView>(R.id.rvRecentTransactions)
        val pbLoading = sheetView.findViewById<ProgressBar>(R.id.pbRecentLoading)

        rvLogs.layoutManager = LinearLayoutManager(this)

        val recordsList = ArrayList<RecentRecord>()
        val filteredList = ArrayList<RecentRecord>()

        class RecentAdapter(private val list: List<RecentRecord>) : RecyclerView.Adapter<RecentAdapter.VH>() {
            inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                val txtTitle: TextView = v.findViewById(android.R.id.text1)
                val txtSub: TextView = v.findViewById(android.R.id.text2)
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
                return VH(v)
            }
            override fun onBindViewHolder(holder: VH, position: Int) {
                val item = list[position]
                holder.txtTitle.text = "${item.id} - ${item.name} (${item.amount})"
                holder.txtTitle.setTextColor(Color.parseColor("#1F2937"))
                holder.txtSub.text = "Date: ${item.date} | Ref: ${item.entity}"

                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    loadVoucherForEditing(item.id, item.mode)
                }
            }
            override fun getItemCount() = list.size
        }

        val adapter = RecentAdapter(filteredList)
        rvLogs.adapter = adapter

        lifecycleScope.launch {
            try {
                when (currentMode) {
                    VoucherMode.RECEIPT -> {
                        val list = SupabaseClientManager.postgrest.from("receipts")
                            .select { filter { eq("branch_code", activeBranchCode) } }
                            .decodeList<ReceiptDto>()

                        for (doc in list) {
                            recordsList.add(RecentRecord(doc.date, doc.invoiceNo, doc.flatNo ?: "N/A", doc.partyName, "₹${doc.totalFinalAmount}", currentMode))
                        }
                    }
                    VoucherMode.PAYMENT -> {
                        val list = SupabaseClientManager.postgrest.from("payments")
                            .select { filter { eq("branch_code", activeBranchCode) } }
                            .decodeList<PaymentDto>()

                        for (doc in list) {
                            recordsList.add(RecentRecord(doc.date, doc.invoiceNo, doc.vendorCode ?: "N/A", doc.partyName, "₹${doc.totalFinalAmount}", currentMode))
                        }
                    }
                    VoucherMode.CONTRA -> {
                        val list = SupabaseClientManager.postgrest.from("contra_entries")
                            .select { filter { eq("branch_code", activeBranchCode) } }
                            .decodeList<ContraEntryDto>()

                        for (doc in list) {
                            recordsList.add(RecentRecord(doc.date, doc.voucherNo, doc.flatNo ?: "N/A", doc.partyName, "₹${doc.amount}", currentMode))
                        }
                    }
                }
                filteredList.addAll(recordsList)
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Toast.makeText(this@VoucherTransactionActivity, "Error loading recent records: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                pbLoading.visibility = View.GONE
            }
        }

        txtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().lowercase(Locale.getDefault())
                filteredList.clear()
                if (query.isEmpty()) {
                    filteredList.addAll(recordsList)
                } else {
                    for (item in recordsList) {
                        if (item.id.lowercase().contains(query) || item.name.lowercase().contains(query) || item.entity.lowercase().contains(query)) {
                            filteredList.add(item)
                        }
                    }
                }
                adapter.notifyDataSetChanged()
            }
        })

        dialog.show()
    }

    private fun generateProfessionalPdfArtifact() {
        val targetSerial = loadedVoucherNo ?: lblInvoiceNum.text.toString().replace("Invoice #", "").trim()
        val fileName = "Invoice_$targetSerial.pdf"

        try {
            val doc = Document(PageSize.A4, 36f, 36f, 45f, 45f)
            var fileUri: android.net.Uri? = null
            var legacyFile: File? = null

            val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                fileUri = contentResolver.insert(collection, contentValues)
                    ?: throw Exception("Failed to create MediaStore PDF entry")
                contentResolver.openOutputStream(fileUri) ?: throw Exception("Failed to open OutputStream")
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                legacyFile = File(downloadsDir, fileName)
                FileOutputStream(legacyFile)
            }

            PdfWriter.getInstance(doc, outputStream)
            doc.open()

            val fontCompany = Font(Font.FontFamily.HELVETICA, 16f, Font.BOLD, BaseColor(31, 41, 55))
            val fontMetaTitle = Font(Font.FontFamily.HELVETICA, 12f, Font.BOLD, BaseColor(25, 118, 210))
            val fontBody = Font(Font.FontFamily.HELVETICA, 10f, Font.BOLD)
            val fontTableHead = Font(Font.FontFamily.HELVETICA, 10f, Font.BOLD, BaseColor.WHITE)

            val headerTable = PdfPTable(2).apply { widthPercentage = 100f }

            val cellLeft = PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                addElement(Paragraph(branchName.uppercase(Locale.getDefault()), fontCompany))
                addElement(Paragraph(branchAddress, fontBody))
            }
            headerTable.addCell(cellLeft)

            val headerType = when (currentMode) {
                VoucherMode.RECEIPT -> "OFFICIAL RECEIPT"
                VoucherMode.PAYMENT -> "PAYMENT DISBURSEMENT"
                VoucherMode.CONTRA -> "INTERNAL CONTRA TRANSFERS"
            }
            val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            val cellRight = PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                horizontalAlignment = Element.ALIGN_RIGHT
                addElement(Paragraph(headerType, fontMetaTitle).apply { setAlignment(Element.ALIGN_RIGHT) })
                addElement(Paragraph("Invoice No: $targetSerial\nDate: $currentDate", fontBody).apply { setAlignment(Element.ALIGN_RIGHT) })
            }
            headerTable.addCell(cellRight)
            doc.add(headerTable)

            doc.add(Paragraph("\n"))
            doc.add(Paragraph("----------------------------------------------------------------------------------------------------------------------------------"))
            doc.add(Paragraph("\n"))

            val partyTable = PdfPTable(2).apply { widthPercentage = 100f }
            partyTable.addCell(createPdfLabelCell("Transaction Party Account Identity:", fontBody, true))
            partyTable.addCell(createPdfLabelCell(txtPartyName.text.toString().trim(), fontBody, false))

            val labelCode = if (currentMode == VoucherMode.PAYMENT) "Vendor Identification Code Reference:" else "Flat / Unit Location Identification:"
            partyTable.addCell(createPdfLabelCell(labelCode, fontBody, true))
            partyTable.addCell(createPdfLabelCell(txtEntityCode.text.toString().trim(), fontBody, false))

            if (currentMode == VoucherMode.CONTRA) {
                partyTable.addCell(createPdfLabelCell("Source Outflow Vault:", fontBody, true))
                partyTable.addCell(createPdfLabelCell(spnSourceAccount.selectedItem?.toString() ?: "", fontBody, false))
                partyTable.addCell(createPdfLabelCell("Destination Inflow Vault:", fontBody, true))
                partyTable.addCell(createPdfLabelCell(spnDestinationAccount.selectedItem?.toString() ?: "", fontBody, false))
            } else {
                val modeStr = spnPaymentMode.selectedItem?.toString() ?: "Cash"
                val bankStr = if (spnPaymentMode.selectedItemPosition == 1) " - " + (spnBank.selectedItem?.toString() ?: "") else ""
                partyTable.addCell(createPdfLabelCell("Payment Clearing Method Vector:", fontBody, true))
                partyTable.addCell(createPdfLabelCell(modeStr + bankStr, fontBody, false))
            }
            doc.add(partyTable)
            doc.add(Paragraph("\n\n"))

            if (currentMode == VoucherMode.CONTRA) {
                val itemsTable = PdfPTable(3).apply {
                    widthPercentage = 100f
                    setWidths(floatArrayOf(5f, 1f, 2f))
                }
                itemsTable.addCell(createPdfHeaderCell("Transaction Ledger Allocation Description", fontTableHead))
                itemsTable.addCell(createPdfHeaderCell("Qty", fontTableHead))
                itemsTable.addCell(createPdfHeaderCell("Total Shunted Value", fontTableHead))

                val desc = "Contra Asset Balancing Shunt from " + spnSourceAccount.selectedItem + " to " + spnDestinationAccount.selectedItem
                itemsTable.addCell(createPdfContentCell(desc, fontBody))
                itemsTable.addCell(createPdfContentCell("1", fontBody))
                itemsTable.addCell(createPdfContentCell(lblTotalVal.text.toString(), fontBody))
                doc.add(itemsTable)
            } else {
                val itemsTable = PdfPTable(4).apply {
                    widthPercentage = 100f
                    setWidths(floatArrayOf(4f, 1f, 1.5f, 1.5f))
                }
                itemsTable.addCell(createPdfHeaderCell("Account Ledger Allocation Description", fontTableHead))
                itemsTable.addCell(createPdfHeaderCell("Qty", fontTableHead))
                itemsTable.addCell(createPdfHeaderCell("Unit Rate", fontTableHead))
                itemsTable.addCell(createPdfHeaderCell("Total Valuation", fontTableHead))

                val category = spnCategory.selectedItem?.toString() ?: ""
                val narration = txtNarration.text.toString().trim()
                val itemDesc = category + if (narration.isNotEmpty()) " ($narration)" else ""

                itemsTable.addCell(createPdfContentCell(itemDesc, fontBody))
                itemsTable.addCell(createPdfContentCell("1", fontBody))
                itemsTable.addCell(createPdfContentCell("₹" + txtBaseAmount.text.toString(), fontBody))
                itemsTable.addCell(createPdfContentCell("₹" + txtBaseAmount.text.toString(), fontBody))
                doc.add(itemsTable)
            }

            doc.add(Paragraph("\n"))

            val totalsTable = PdfPTable(2).apply {
                widthPercentage = 45f
                horizontalAlignment = Element.ALIGN_RIGHT
            }
            if (currentMode != VoucherMode.CONTRA) {
                val fineVal = txtFine.text.toString().toDoubleOrNull() ?: 0.0
                totalsTable.addCell(createPdfLabelCell("Fine / Late Fee Assessment:", fontBody, true))
                totalsTable.addCell(createPdfLabelCell(String.format("+₹%,.2f", fineVal), fontBody, false))
            }
            totalsTable.addCell(createPdfLabelCell("Total Voucher Value:", fontBody, true))
            totalsTable.addCell(createPdfLabelCell(lblTotalVal.text.toString(), fontBody, false))

            doc.add(totalsTable)

            doc.close()
            outputStream.flush()
            outputStream.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileUri != null) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(fileUri, publishValues, null, null)

                val openIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/pdf")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(openIntent, "Open PDF Invoice"))
            } else if (legacyFile != null) {
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(legacyFile.absolutePath),
                    arrayOf("application/pdf"),
                    null
                )
                Toast.makeText(this, "PDF saved to Downloads/$fileName ✅", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "PDF Export Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createPdfHeaderCell(txt: String, f: Font): PdfPCell {
        return PdfPCell(Paragraph(txt, f)).apply {
            setBackgroundColor(BaseColor(25, 118, 210))
            setPadding(8f)
        }
    }

    private fun createPdfContentCell(txt: String, f: Font): PdfPCell {
        return PdfPCell(Paragraph(txt, f)).apply { setPadding(6f) }
    }

    private fun createPdfLabelCell(txt: String, f: Font, isHeader: Boolean): PdfPCell {
        return PdfPCell(Paragraph(txt, f)).apply {
            setBorder(Rectangle.NO_BORDER)
            if (isHeader) setBackgroundColor(BaseColor(245, 247, 250))
            setPadding(4f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
    }
}