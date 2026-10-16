package com.sierratechnologies.finmac

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
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
import kotlin.math.max

class CashbookActivity : AppCompatActivity() {

    private lateinit var viewHeaderBackground: View
    private lateinit var btnBack: ImageView
    private lateinit var btnExportPdf: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var lblBranchSubtitle: TextView

    private lateinit var lblOpeningBal: TextView
    private lateinit var lblTotalReceipts: TextView
    private lateinit var lblTotalPayments: TextView
    private lateinit var lblClosingBal: TextView

    private lateinit var txtSearchLedger: EditText
    private lateinit var spnPeriod: Spinner
    private lateinit var loadingOverlay: FrameLayout

    // Receipts Card UI pointers
    private lateinit var lblReceiptsCount: TextView
    private lateinit var lblReceiptsPillTotal: TextView
    private lateinit var chipGroupReceipts: ChipGroup
    private lateinit var rvReceiptsTable: RecyclerView
    private lateinit var btnViewAllReceipts: MaterialButton

    // Payments Card UI pointers
    private lateinit var lblPaymentsCount: TextView
    private lateinit var lblPaymentsPillTotal: TextView
    private lateinit var chipGroupPayments: ChipGroup
    private lateinit var rvPaymentsTable: RecyclerView
    private lateinit var btnViewAllPayments: MaterialButton

    private var activeBranchCode = ""
    private var branchName = "Ciera Tech Services / Society"
    private var branchAddress = "Head Office Administration"
    private var branchRegNo = "REG-MH-26/X4911"

    data class LedgerEntry(
        val date: String,
        val voucherNo: String,
        val entityCode: String,
        val partyName: String,
        val categoryHead: String,
        val cashAmount: Double,
        val bankAmount: Double,
        val totalAmount: Double,
        val mode: String,
        val operatorIdentity: String,
        val customNarration: String,
        val isReceipt: Boolean
    )

    private val allReceipts = ArrayList<LedgerEntry>()
    private val allPayments = ArrayList<LedgerEntry>()

    private val displayedReceiptsList = ArrayList<LedgerEntry>()
    private val displayedPaymentsList = ArrayList<LedgerEntry>()

    private lateinit var receiptsAdapter: ReceiptsAdapter
    private lateinit var paymentsAdapter: PaymentsAdapter

    private var dynamicOpeningBalance = 0.0
    private var totalReceiptsSum = 0.0
    private var totalPaymentsSum = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cashbook)

        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        activeBranchCode = intent.getStringExtra("SOCIETY_CODE") ?: sharedPref.getString("SOCIETY_CODE", "")?.trim() ?: ""

        if (activeBranchCode.isBlank()) {
            Toast.makeText(this, "Session invalid. Please re-login.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initViews()
        setupWindowInsets()
        setupRecyclerViews()
        setupChipGroupListeners()

        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { loadCompleteCashbookData() }
        btnExportPdf.setOnClickListener { generateSplitTwoSidedPdfReport() }

        val periodOptions = arrayOf("All-Time Opening", "Today's Opening", "Current Month Opening", "Current FY Opening")
        spnPeriod.adapter = ArrayAdapter(this, R.layout.spinner_item, periodOptions).apply {
            setDropDownViewResource(R.layout.spinner_item)
        }
        spnPeriod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadCompleteCashbookData()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        txtSearchLedger.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterAndRenderDisplayList(s.toString().trim()) }
        })

        loadCompleteCashbookData()
    }

    private fun initViews() {
        viewHeaderBackground = findViewById(R.id.viewHeaderBackground)
        btnBack = findViewById(R.id.btnBack)
        btnExportPdf = findViewById(R.id.btnExportPdf)
        btnRefresh = findViewById(R.id.btnRefresh)
        lblBranchSubtitle = findViewById(R.id.lblBranchSubtitle)

        lblOpeningBal = findViewById(R.id.lblOpeningBal)
        lblTotalReceipts = findViewById(R.id.lblTotalReceipts)
        lblTotalPayments = findViewById(R.id.lblTotalPayments)
        lblClosingBal = findViewById(R.id.lblClosingBal)

        txtSearchLedger = findViewById(R.id.txtSearchLedger)
        spnPeriod = findViewById(R.id.spnPeriod)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        // Receipts Section
        lblReceiptsCount = findViewById(R.id.lblReceiptsCount)
        lblReceiptsPillTotal = findViewById(R.id.lblReceiptsPillTotal)
        chipGroupReceipts = findViewById(R.id.chipGroupReceipts)
        rvReceiptsTable = findViewById(R.id.rvReceiptsTable)
        btnViewAllReceipts = findViewById(R.id.btnViewAllReceipts)

        // Payments Section
        lblPaymentsCount = findViewById(R.id.lblPaymentsCount)
        lblPaymentsPillTotal = findViewById(R.id.lblPaymentsPillTotal)
        chipGroupPayments = findViewById(R.id.chipGroupPayments)
        rvPaymentsTable = findViewById(R.id.rvPaymentsTable)
        btnViewAllPayments = findViewById(R.id.btnViewAllPayments)

        btnViewAllReceipts.setOnClickListener {
            openFullViewStatementSheet(isReceipts = true)
        }

        btnViewAllPayments.setOnClickListener {
            openFullViewStatementSheet(isReceipts = false)
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            viewHeaderBackground.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun setupRecyclerViews() {
        rvReceiptsTable.layoutManager = LinearLayoutManager(this)
        receiptsAdapter = ReceiptsAdapter(displayedReceiptsList) { item ->
            openDetailedVoucherModalSheet(item)
        }
        rvReceiptsTable.adapter = receiptsAdapter

        rvPaymentsTable.layoutManager = LinearLayoutManager(this)
        paymentsAdapter = PaymentsAdapter(displayedPaymentsList) { item ->
            openDetailedVoucherModalSheet(item)
        }
        rvPaymentsTable.adapter = paymentsAdapter
    }

    private fun setupChipGroupListeners() {
        chipGroupReceipts.setOnCheckedStateChangeListener { _, _ ->
            filterAndRenderDisplayList(txtSearchLedger.text.toString().trim())
        }
        chipGroupPayments.setOnCheckedStateChangeListener { _, _ ->
            filterAndRenderDisplayList(txtSearchLedger.text.toString().trim())
        }
    }

    private fun processAndRenderCashbook(
        baseCashOpeningInput: Double,
        rList: List<ReceiptDto>,
        pList: List<PaymentDto>,
        cList: List<ContraEntryDto>,
        periodThresholdDateStr: String?
    ) {
        allReceipts.clear()
        allPayments.clear()
        totalReceiptsSum = 0.0
        totalPaymentsSum = 0.0

        val tempReceiptsList = ArrayList<LedgerEntry>()
        for (doc in rList) {
            val d = doc.date
            val inv = doc.invoiceNo
            val flatNo = doc.flatNo ?: "N/A"
            val party = doc.partyName
            val cat = doc.categoryHead
            val mode = doc.paymentMode ?: "Cash"
            val opUser = doc.operatorAccountIdentity ?: "Admin"
            val narration = doc.customNarration ?: ""
            val totalAmt = doc.totalFinalAmount

            val isCash = mode.lowercase(Locale.getDefault()).contains("cash")
            val cashAmt = if (isCash) totalAmt else 0.0
            val bankAmt = if (!isCash) totalAmt else 0.0

            tempReceiptsList.add(LedgerEntry(d, inv, flatNo, party, cat, cashAmt, bankAmt, totalAmt, mode, opUser, narration, true))
        }

        val tempPaymentsList = ArrayList<LedgerEntry>()
        for (doc in pList) {
            val d = doc.date
            val inv = doc.invoiceNo
            val vendorCode = doc.vendorCode ?: "N/A"
            val party = doc.partyName
            val cat = doc.categoryHead
            val mode = doc.paymentMode ?: "Cash"
            val opUser = doc.operatorAccountIdentity ?: "Admin"
            val narration = doc.customNarration ?: ""
            val totalAmt = doc.totalFinalAmount

            val isCash = mode.lowercase(Locale.getDefault()).contains("cash")
            val cashAmt = if (isCash) totalAmt else 0.0
            val bankAmt = if (!isCash) totalAmt else 0.0

            tempPaymentsList.add(LedgerEntry(d, inv, vendorCode, party, cat, cashAmt, bankAmt, totalAmt, mode, opUser, narration, false))
        }

        allReceipts.clear()
        allReceipts.addAll(tempReceiptsList)

        allPayments.clear()
        allPayments.addAll(tempPaymentsList)

        totalReceiptsSum = tempReceiptsList.sumOf { it.totalAmount }
        totalPaymentsSum = tempPaymentsList.sumOf { it.totalAmount }

        val masterTimeline = (tempReceiptsList + tempPaymentsList).sortedWith(compareBy({ it.date }, { it.voucherNo }))

        if (baseCashOpeningInput > 0) {
            dynamicOpeningBalance = baseCashOpeningInput
        } else if (allReceipts.isNotEmpty()) {
            val firstReceipt = allReceipts.minByOrNull { it.date }
            dynamicOpeningBalance = firstReceipt?.totalAmount ?: 0.0
        } else {
            dynamicOpeningBalance = 0.0
        }

        allReceipts.sortByDescending { it.date }
        allPayments.sortByDescending { it.date }

        val closingBal = if (baseCashOpeningInput > 0) {
            dynamicOpeningBalance + totalReceiptsSum - totalPaymentsSum
        } else {
            totalReceiptsSum - totalPaymentsSum
        }

        lblOpeningBal.text = String.format("₹%,.2f", dynamicOpeningBalance)
        lblTotalReceipts.text = String.format("₹%,.2f", totalReceiptsSum)
        lblTotalPayments.text = String.format("₹%,.2f", totalPaymentsSum)
        lblClosingBal.text = String.format("₹%,.2f", closingBal)

        filterAndRenderDisplayList(txtSearchLedger.text.toString().trim())
    }

    private fun loadCompleteCashbookData() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = sdf.format(Date())

        val cal = Calendar.getInstance()
        val currentMonth = cal.get(Calendar.MONTH) + 1
        val currentYear = cal.get(Calendar.YEAR)
        val startYear = if (currentMonth >= 4) currentYear else currentYear - 1
        val activeFYStr = String.format("FY %d-%s", startYear, (startYear + 1).toString().takeLast(2))

        val selectedPeriodIndex = spnPeriod.selectedItemPosition
        val periodThresholdDateStr: String? = when (selectedPeriodIndex) {
            1 -> todayStr
            2 -> String.format("%04d-%02d-01", currentYear, currentMonth)
            3 -> String.format("%04d-04-01", startYear)
            else -> null
        }

        loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val branches = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", activeBranchCode) } }
                    .decodeList<BranchDto>()

                if (branches.isNotEmpty()) {
                    val branch = branches.first()
                    branchName = branch.name
                    branchAddress = branch.address ?: branchAddress
                    branchRegNo = branch.registrationNo ?: branchRegNo
                    lblBranchSubtitle.text = branchName
                }

                val openingList = SupabaseClientManager.postgrest.from("opening_balances")
                    .select {
                        filter {
                            eq("branch_code", activeBranchCode)
                            eq("financial_year", activeFYStr)
                        }
                    }
                    .decodeList<OpeningBalanceDto>()

                val baseCashOpening = if (openingList.isNotEmpty()) openingList.first().openingCashBalance else 0.0

                val rList = SupabaseClientManager.postgrest.from("receipts")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ReceiptDto>()

                AppCache.saveReceipts(this@CashbookActivity, activeBranchCode, rList)

                val pList = SupabaseClientManager.postgrest.from("payments")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<PaymentDto>()

                AppCache.savePayments(this@CashbookActivity, activeBranchCode, pList)

                val cList = SupabaseClientManager.postgrest.from("contra_entries")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ContraEntryDto>()

                AppCache.saveContra(this@CashbookActivity, activeBranchCode, cList)

                processAndRenderCashbook(baseCashOpening, rList, pList, cList, periodThresholdDateStr)
            } catch (e: Exception) {
                Toast.makeText(this@CashbookActivity, "Error loading live cashbook: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun filterAndRenderDisplayList(query: String) {
        val q = query.lowercase(Locale.getDefault())

        // 1. Filter Receipts Table
        displayedReceiptsList.clear()
        val checkedReceiptChip = chipGroupReceipts.checkedChipId

        var displayedReceiptsTotal = 0.0
        for (item in allReceipts) {
            val matchesQuery = q.isEmpty() ||
                    item.voucherNo.lowercase().contains(q) ||
                    item.partyName.lowercase().contains(q) ||
                    item.categoryHead.lowercase().contains(q) ||
                    item.entityCode.lowercase().contains(q)

            val matchesChip = when (checkedReceiptChip) {
                R.id.chipReceiptsCash -> item.cashAmount > 0
                R.id.chipReceiptsBank -> item.bankAmount > 0
                else -> true
            }

            if (matchesQuery && matchesChip) {
                displayedReceiptsList.add(item)
                displayedReceiptsTotal += item.totalAmount
            }
        }
        receiptsAdapter.notifyDataSetChanged()

        lblReceiptsCount.text = "${displayedReceiptsList.size} transactions"
        lblReceiptsPillTotal.text = String.format(Locale.getDefault(), "₹ %,.2f", if (q.isEmpty() && checkedReceiptChip == R.id.chipReceiptsAll) totalReceiptsSum else displayedReceiptsTotal)
        btnViewAllReceipts.text = "View All Receipts (${displayedReceiptsList.size}) >"

        // 2. Filter Payments Table
        displayedPaymentsList.clear()
        val checkedPaymentChip = chipGroupPayments.checkedChipId

        var displayedPaymentsTotal = 0.0
        for (item in allPayments) {
            val matchesQuery = q.isEmpty() ||
                    item.voucherNo.lowercase().contains(q) ||
                    item.partyName.lowercase().contains(q) ||
                    item.categoryHead.lowercase().contains(q) ||
                    item.entityCode.lowercase().contains(q)

            val matchesChip = when (checkedPaymentChip) {
                R.id.chipPaymentsCash -> item.cashAmount > 0
                R.id.chipPaymentsBank -> item.bankAmount > 0
                else -> true
            }

            if (matchesQuery && matchesChip) {
                displayedPaymentsList.add(item)
                displayedPaymentsTotal += item.totalAmount
            }
        }
        paymentsAdapter.notifyDataSetChanged()

        lblPaymentsCount.text = "${displayedPaymentsList.size} transactions"
        lblPaymentsPillTotal.text = String.format(Locale.getDefault(), "₹ %,.2f", if (q.isEmpty() && checkedPaymentChip == R.id.chipPaymentsAll) totalPaymentsSum else displayedPaymentsTotal)
        btnViewAllPayments.text = "View All Payments (${displayedPaymentsList.size}) >"
    }

    inner class ReceiptsAdapter(
        private val list: List<LedgerEntry>,
        private val onItemClick: (LedgerEntry) -> Unit
    ) : RecyclerView.Adapter<ReceiptsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtDate: TextView = v.findViewById(R.id.txtRowDate)
            val txtInvoice: TextView = v.findViewById(R.id.txtRowInvoice)
            val txtFlat: TextView = v.findViewById(R.id.txtRowFlat)
            val txtMember: TextView = v.findViewById(R.id.txtRowMember)
            val txtCategory: TextView = v.findViewById(R.id.txtRowCategory)
            val txtCashCr: TextView = v.findViewById(R.id.txtRowCashCr)
            val txtBankCr: TextView = v.findViewById(R.id.txtRowBankCr)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cashbook_receipt_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.txtDate.text = item.date
            holder.txtInvoice.text = item.voucherNo
            holder.txtFlat.text = item.entityCode
            holder.txtMember.text = item.partyName
            holder.txtCategory.text = item.categoryHead
            holder.txtCashCr.text = String.format("₹%,.2f", item.cashAmount)
            holder.txtBankCr.text = String.format("₹%,.2f", item.bankAmount)

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = list.size
    }

    inner class PaymentsAdapter(
        private val list: List<LedgerEntry>,
        private val onItemClick: (LedgerEntry) -> Unit
    ) : RecyclerView.Adapter<PaymentsAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtDate: TextView = v.findViewById(R.id.txtRowDate)
            val txtInvoice: TextView = v.findViewById(R.id.txtRowInvoice)
            val txtVendorCode: TextView = v.findViewById(R.id.txtRowVendorCode)
            val txtVendorName: TextView = v.findViewById(R.id.txtRowVendorName)
            val txtCategory: TextView = v.findViewById(R.id.txtRowCategory)
            val txtCashDb: TextView = v.findViewById(R.id.txtRowCashDb)
            val txtBankDb: TextView = v.findViewById(R.id.txtRowBankDb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cashbook_payment_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.txtDate.text = item.date
            holder.txtInvoice.text = item.voucherNo
            holder.txtVendorCode.text = item.entityCode
            holder.txtVendorName.text = item.partyName
            holder.txtCategory.text = item.categoryHead
            holder.txtCashDb.text = String.format("₹%,.2f", item.cashAmount)
            holder.txtBankDb.text = String.format("₹%,.2f", item.bankAmount)

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = list.size
    }

    private fun showExportSuccessWithOpenOption(title: String, fileUri: android.net.Uri, mimeType: String, fileName: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("File saved to Downloads/$fileName ✅\nWould you like to open it now?")
            .setPositiveButton("📂 Open File") { dialog, _ ->
                try {
                    val openIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, mimeType)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(openIntent, "Open $fileName"))
                } catch (_: Exception) {
                    Toast.makeText(this, "No app available to open $mimeType files.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun openFullViewStatementSheet(isReceipts: Boolean) {
        val sheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_cashbook_full_view_sheet, null)
        sheetDialog.setContentView(sheetView)

        val lblTitle = sheetView.findViewById<TextView>(R.id.lblFullViewSheetTitle)
        val lblSubtitle = sheetView.findViewById<TextView>(R.id.lblFullViewSheetSubtitle)
        val btnClose = sheetView.findViewById<android.widget.ImageButton>(R.id.btnCloseFullViewSheet)
        val txtSearch = sheetView.findViewById<EditText>(R.id.txtFullViewSearch)
        val btnPdf = sheetView.findViewById<MaterialButton>(R.id.btnFullExportPdf)
        val btnCsv = sheetView.findViewById<MaterialButton>(R.id.btnFullExportCsv)
        val rvTable = sheetView.findViewById<RecyclerView>(R.id.rvFullStatementTable)

        btnClose?.setOnClickListener { sheetDialog.dismiss() }

        val activeList = if (isReceipts) displayedReceiptsList else displayedPaymentsList
        val filteredSheetList = ArrayList<LedgerEntry>(activeList)

        val typeTitle = if (isReceipts) "Receipts Statement Registry" else "Payments Disbursement Registry"
        val totalSum = if (isReceipts) totalReceiptsSum else totalPaymentsSum

        lblTitle?.text = typeTitle
        lblSubtitle?.text = String.format(Locale.getDefault(), "Total: %d Transactions | ₹ %,.2f", activeList.size, totalSum)

        rvTable?.layoutManager = LinearLayoutManager(this)

        if (isReceipts) {
            val sheetAdapter = ReceiptsAdapter(filteredSheetList) { item -> openDetailedVoucherModalSheet(item) }
            rvTable?.adapter = sheetAdapter

            txtSearch?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase(Locale.getDefault())
                    filteredSheetList.clear()
                    if (q.isEmpty()) {
                        filteredSheetList.addAll(displayedReceiptsList)
                    } else {
                        for (item in displayedReceiptsList) {
                            if (item.voucherNo.lowercase().contains(q) ||
                                item.partyName.lowercase().contains(q) ||
                                item.categoryHead.lowercase().contains(q) ||
                                item.entityCode.lowercase().contains(q)) {
                                filteredSheetList.add(item)
                            }
                        }
                    }
                    sheetAdapter.notifyDataSetChanged()
                    lblSubtitle?.text = String.format(Locale.getDefault(), "Filtered: %d / %d | ₹ %,.2f", filteredSheetList.size, displayedReceiptsList.size, totalSum)
                }
            })
        } else {
            val sheetAdapter = PaymentsAdapter(filteredSheetList) { item -> openDetailedVoucherModalSheet(item) }
            rvTable?.adapter = sheetAdapter

            txtSearch?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase(Locale.getDefault())
                    filteredSheetList.clear()
                    if (q.isEmpty()) {
                        filteredSheetList.addAll(displayedPaymentsList)
                    } else {
                        for (item in displayedPaymentsList) {
                            if (item.voucherNo.lowercase().contains(q) ||
                                item.partyName.lowercase().contains(q) ||
                                item.categoryHead.lowercase().contains(q) ||
                                item.entityCode.lowercase().contains(q)) {
                                filteredSheetList.add(item)
                            }
                        }
                    }
                    sheetAdapter.notifyDataSetChanged()
                    lblSubtitle?.text = String.format(Locale.getDefault(), "Filtered: %d / %d | ₹ %,.2f", filteredSheetList.size, displayedPaymentsList.size, totalSum)
                }
            })
        }

        btnPdf?.setOnClickListener {
            sheetDialog.dismiss()
            exportFullViewToPdf(isReceipts, filteredSheetList)
        }

        btnCsv?.setOnClickListener {
            sheetDialog.dismiss()
            exportFullViewToCsv(isReceipts, filteredSheetList)
        }

        sheetDialog.show()
    }

    private fun exportFullViewToPdf(isReceipts: Boolean, list: List<LedgerEntry>) {
        if (list.isEmpty()) {
            Toast.makeText(this, "No records available to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentDateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val docType = if (isReceipts) "Receipts_Registry" else "Payments_Registry"
        val fileName = "${docType}_${activeBranchCode}_$currentDateStr.pdf"

        try {
            val doc = Document(PageSize.A4.rotate(), 20f, 20f, 30f, 30f)
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
                fileUri = contentResolver.insert(collection, contentValues) ?: throw Exception("Failed MediaStore entry")
                contentResolver.openOutputStream(fileUri) ?: throw Exception("Failed OutputStream")
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                legacyFile = File(downloadsDir, fileName)
                FileOutputStream(legacyFile)
            }

            PdfWriter.getInstance(doc, outputStream)
            doc.open()

            val fontCompany = Font(Font.FontFamily.HELVETICA, 14f, Font.BOLD, BaseColor(5, 37, 80))
            val fontSub = Font(Font.FontFamily.HELVETICA, 9f, Font.NORMAL, BaseColor.DARK_GRAY)
            val fontTitle = Font(Font.FontFamily.HELVETICA, 11f, Font.BOLD, if (isReceipts) BaseColor(6, 95, 70) else BaseColor(153, 27, 27))
            val fontHead = Font(Font.FontFamily.HELVETICA, 9f, Font.BOLD, BaseColor.WHITE)
            val fontBody = Font(Font.FontFamily.HELVETICA, 8f, Font.NORMAL, BaseColor.BLACK)
            val fontBoldBody = Font(Font.FontFamily.HELVETICA, 9f, Font.BOLD, BaseColor.BLACK)

            // Header Section
            val headerTable = PdfPTable(2).apply { widthPercentage = 100f }
            headerTable.addCell(PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                addElement(Paragraph(branchName.uppercase(Locale.getDefault()), fontCompany))
                addElement(Paragraph(branchAddress, fontSub))
            })
            headerTable.addCell(PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                horizontalAlignment = Element.ALIGN_RIGHT
                val sheetName = if (isReceipts) "RECEIPTS STATEMENT REGISTRY" else "PAYMENTS DISBURSEMENT REGISTRY"
                addElement(Paragraph(sheetName, fontTitle).apply { alignment = Element.ALIGN_RIGHT })
                addElement(Paragraph("Date Generated: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}", fontSub).apply { alignment = Element.ALIGN_RIGHT })
            })
            doc.add(headerTable)
            doc.add(Paragraph("\n"))

            // MANDATORY FINANCIAL SUMMARY BLOCK (Opening, Receipts, Payments, Closing Balances)
            val summaryTable = PdfPTable(4).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(1f, 1f, 1f, 1f))
            }

            fun createSummaryCell(label: String, valueStr: String, bg: BaseColor): PdfPCell {
                val p = Paragraph().apply {
                    add(Paragraph(label, Font(Font.FontFamily.HELVETICA, 8f, Font.BOLD, BaseColor.DARK_GRAY)))
                    add(Paragraph(valueStr, Font(Font.FontFamily.HELVETICA, 11f, Font.BOLD, BaseColor.BLACK)))
                }
                return PdfPCell(p).apply {
                    backgroundColor = bg
                    setPadding(6f)
                }
            }

            val closingBal = dynamicOpeningBalance + totalReceiptsSum - totalPaymentsSum

            summaryTable.addCell(createSummaryCell("OPENING BALANCE B/F", String.format("₹%,.2f", dynamicOpeningBalance), BaseColor(241, 245, 249)))
            summaryTable.addCell(createSummaryCell("TOTAL RECEIPTS", String.format("₹%,.2f", totalReceiptsSum), BaseColor(209, 250, 229)))
            summaryTable.addCell(createSummaryCell("TOTAL PAYMENTS", String.format("₹%,.2f", totalPaymentsSum), BaseColor(254, 226, 226)))
            summaryTable.addCell(createSummaryCell("CLOSING BALANCE C/F", String.format("₹%,.2f", closingBal), BaseColor(224, 242, 254)))

            doc.add(summaryTable)
            doc.add(Paragraph("\n\n"))

            // Statements Table
            val table = PdfPTable(7).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(1.2f, 1.2f, 1.2f, 2.5f, 1.8f, 1.3f, 1.3f))
            }

            fun createHeaderCell(txt: String): PdfPCell {
                return PdfPCell(Paragraph(txt, fontHead)).apply {
                    backgroundColor = if (isReceipts) BaseColor(5, 150, 105) else BaseColor(220, 38, 38)
                    setPadding(5f)
                }
            }

            table.addCell(createHeaderCell("Date"))
            table.addCell(createHeaderCell(if (isReceipts) "Receipt #" else "Payment #"))
            table.addCell(createHeaderCell(if (isReceipts) "Flat No" else "Vendor Code"))
            table.addCell(createHeaderCell(if (isReceipts) "Member Name" else "Vendor Name"))
            table.addCell(createHeaderCell("Account Head"))
            table.addCell(createHeaderCell(if (isReceipts) "Cash Cr" else "Cash Db"))
            table.addCell(createHeaderCell(if (isReceipts) "Bank Cr" else "Bank Db"))

            var sumCash = 0.0
            var sumBank = 0.0

            for (item in list) {
                sumCash += item.cashAmount
                sumBank += item.bankAmount

                fun createBodyCell(txt: String, isRight: Boolean = false): PdfPCell {
                    return PdfPCell(Paragraph(txt, fontBody)).apply {
                        setPadding(4f)
                        if (isRight) horizontalAlignment = Element.ALIGN_RIGHT
                    }
                }

                table.addCell(createBodyCell(item.date))
                table.addCell(createBodyCell(item.voucherNo))
                table.addCell(createBodyCell(item.entityCode))
                table.addCell(createBodyCell(item.partyName))
                table.addCell(createBodyCell(item.categoryHead))
                table.addCell(createBodyCell(String.format("₹%,.2f", item.cashAmount), true))
                table.addCell(createBodyCell(String.format("₹%,.2f", item.bankAmount), true))
            }

            // Totals Row
            fun createTotalCell(txt: String, isRight: Boolean = false): PdfPCell {
                return PdfPCell(Paragraph(txt, fontBoldBody)).apply {
                    setPadding(5f)
                    backgroundColor = BaseColor(241, 245, 249)
                    if (isRight) horizontalAlignment = Element.ALIGN_RIGHT
                }
            }

            table.addCell(createTotalCell("-"))
            table.addCell(createTotalCell("-"))
            table.addCell(createTotalCell("-"))
            table.addCell(createTotalCell("PERIOD TOTALS"))
            table.addCell(createTotalCell("-"))
            table.addCell(createTotalCell(String.format("₹%,.2f", sumCash), true))
            table.addCell(createTotalCell(String.format("₹%,.2f", sumBank), true))

            doc.add(table)
            doc.close()
            outputStream.flush()
            outputStream.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileUri != null) {
                val publishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(fileUri, publishValues, null, null)

                showExportSuccessWithOpenOption("📄 PDF Statement Document Ready", fileUri, "application/pdf", fileName)
            } else if (legacyFile != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", legacyFile)
                showExportSuccessWithOpenOption("📄 PDF Statement Document Ready", uri, "application/pdf", fileName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "PDF Export Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportFullViewToCsv(isReceipts: Boolean, list: List<LedgerEntry>) {
        if (list.isEmpty()) {
            Toast.makeText(this, "No records available to export.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val docType = if (isReceipts) "Receipts_Registry" else "Payments_Registry"
            val fileName = "${docType}_${activeBranchCode}_${System.currentTimeMillis()}.csv"

            val closingBal = dynamicOpeningBalance + totalReceiptsSum - totalPaymentsSum

            val csvBuilder = StringBuilder().apply {
                append("FINANCIAL STATEMENT SUMMARY\n")
                append("\"Society Name\",\"${branchName}\"\n")
                append("\"Opening Balance B/F\",\"$dynamicOpeningBalance\"\n")
                append("\"Total Receipts\",\"$totalReceiptsSum\"\n")
                append("\"Total Payments\",\"$totalPaymentsSum\"\n")
                append("\"Closing Balance C/F\",\"$closingBal\"\n\n")

                if (isReceipts) {
                    append("Date,Receipt No,Flat No,Member Name,Account Head,Clearing Mode,Cash Cr,Bank Cr,Total Amount\n")
                } else {
                    append("Date,Payment No,Vendor Code,Name of Vendor,Account Head,Clearing Mode,Cash Db,Bank Db,Total Amount\n")
                }

                for (item in list) {
                    append("\"${item.date}\",")
                    append("\"${item.voucherNo}\",")
                    append("\"${item.entityCode}\",")
                    append("\"${item.partyName}\",")
                    append("\"${item.categoryHead}\",")
                    append("\"${item.mode}\",")
                    append("\"${item.cashAmount}\",")
                    append("\"${item.bankAmount}\",")
                    append("\"${item.totalAmount}\"\n")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(csvBuilder.toString().toByteArray())
                    }
                    showExportSuccessWithOpenOption("📊 Excel / CSV Statement Ready", uri, "text/csv", fileName)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { os ->
                    os.write(csvBuilder.toString().toByteArray())
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                showExportSuccessWithOpenOption("📊 Excel / CSV Statement Ready", uri, "text/csv", fileName)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "CSV Export Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openDetailedVoucherModalSheet(item: LedgerEntry) {
        val sheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.layout_recent_transactions_sheet, null)
        sheetDialog.setContentView(sheetView)

        val txtSearch = sheetView.findViewById<EditText>(R.id.txtSearchRecent)
        txtSearch?.visibility = View.GONE

        val rvLogs = sheetView.findViewById<RecyclerView>(R.id.rvRecentTransactions)
        rvLogs?.layoutManager = LinearLayoutManager(this)

        class AuditDetailAdapter : RecyclerView.Adapter<AuditDetailAdapter.VH>() {
            val details: List<Pair<String, String>> = listOf(
                "Voucher Number" to "#${item.voucherNo}",
                "Transaction Date" to item.date,
                (if (item.isReceipt) "Received From (Member)" else "Disbursed To (Vendor)") to "${item.partyName} (${item.entityCode})",
                "Category Head" to item.categoryHead,
                "Clearing Mode" to item.mode,
                "Authorized Operator" to item.operatorIdentity,
                "Custom Narration" to if (item.customNarration.isNotBlank()) item.customNarration else "N/A",
                "Total Valuation" to String.format("₹%,.2f", item.totalAmount)
            )

            inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                val txtLabel: TextView = v.findViewById(android.R.id.text1)
                val txtValue: TextView = v.findViewById(android.R.id.text2)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
                return VH(v)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val (label, value) = details[position]

                holder.txtLabel.text = label
                holder.txtLabel.setTextColor(Color.parseColor("#6B7280"))
                holder.txtLabel.textSize = 12f

                holder.txtValue.text = value
                holder.txtValue.setTextColor(Color.parseColor("#1F2937"))
                holder.txtValue.textSize = 14f
            }

            override fun getItemCount() = details.size
        }

        rvLogs?.adapter = AuditDetailAdapter()
        sheetDialog.show()
    }

    private fun generateSplitTwoSidedPdfReport() {
        val currentDateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Two_Sided_Cashbook_$currentDateStr.pdf"

        try {
            val doc = Document(PageSize.A4.rotate(), 15f, 15f, 25f, 25f)
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
                fileUri = contentResolver.insert(collection, contentValues) ?: throw Exception("Failed MediaStore entry")
                contentResolver.openOutputStream(fileUri) ?: throw Exception("Failed OutputStream")
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                legacyFile = File(downloadsDir, fileName)
                FileOutputStream(legacyFile)
            }

            PdfWriter.getInstance(doc, outputStream)
            doc.open()

            val fontCompany = Font(Font.FontFamily.HELVETICA, 14f, Font.BOLD, BaseColor(5, 37, 80))
            val fontBody = Font(Font.FontFamily.HELVETICA, 8f, Font.NORMAL)
            val fontBold = Font(Font.FontFamily.HELVETICA, 8f, Font.BOLD)
            val fontHead = Font(Font.FontFamily.HELVETICA, 8f, Font.BOLD, BaseColor.WHITE)

            val headerTable = PdfPTable(2).apply { widthPercentage = 100f }
            headerTable.addCell(PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                addElement(Paragraph(branchName.uppercase(Locale.getDefault()), fontCompany))
                addElement(Paragraph(branchAddress, fontBody))
            })
            headerTable.addCell(PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                horizontalAlignment = Element.ALIGN_RIGHT
                addElement(Paragraph("UNIFIED CASH BOOK STATEMENT", Font(Font.FontFamily.HELVETICA, 11f, Font.BOLD, BaseColor(25, 118, 210))).apply { alignment = Element.ALIGN_RIGHT })
                addElement(Paragraph("Date Generated: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}", fontBody).apply { alignment = Element.ALIGN_RIGHT })
            })
            doc.add(headerTable)

            doc.add(Paragraph("\n"))

            val masterTable = PdfPTable(2).apply { widthPercentage = 100f }

            val receiptsTable = PdfPTable(6).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(1.2f, 1.2f, 2.5f, 1.5f, 1.2f, 1.2f))
            }
            receiptsTable.addCell(createPdfHeaderCell("Date", fontHead, BaseColor(22, 101, 52)))
            receiptsTable.addCell(createPdfHeaderCell("Receipt #", fontHead, BaseColor(22, 101, 52)))
            receiptsTable.addCell(createPdfHeaderCell("Member / Particulars", fontHead, BaseColor(22, 101, 52)))
            receiptsTable.addCell(createPdfHeaderCell("Account Head", fontHead, BaseColor(22, 101, 52)))
            receiptsTable.addCell(createPdfHeaderCell("Cash Cr", fontHead, BaseColor(22, 101, 52)))
            receiptsTable.addCell(createPdfHeaderCell("Bank Cr", fontHead, BaseColor(22, 101, 52)))

            // Opening Balance Row
            receiptsTable.addCell(createPdfContentCell("-", fontBody))
            receiptsTable.addCell(createPdfContentCell("-", fontBody))
            receiptsTable.addCell(createPdfContentCell("To Opening Balance B/F", fontBold))
            receiptsTable.addCell(createPdfContentCell("-", fontBody))
            receiptsTable.addCell(createPdfContentCell(String.format("₹%,.2f", dynamicOpeningBalance), fontBold))
            receiptsTable.addCell(createPdfContentCell("₹0.00", fontBody))

            for (r in allReceipts) {
                receiptsTable.addCell(createPdfContentCell(r.date, fontBody))
                receiptsTable.addCell(createPdfContentCell(r.voucherNo, fontBody))
                receiptsTable.addCell(createPdfContentCell("${r.partyName} (${r.entityCode})", fontBody))
                receiptsTable.addCell(createPdfContentCell(r.categoryHead, fontBody))
                receiptsTable.addCell(createPdfContentCell(if (r.cashAmount > 0) String.format("₹%,.2f", r.cashAmount) else "₹0.00", fontBody))
                receiptsTable.addCell(createPdfContentCell(if (r.bankAmount > 0) String.format("₹%,.2f", r.bankAmount) else "₹0.00", fontBody))
            }

            val paymentsTable = PdfPTable(6).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(1.2f, 1.2f, 2.5f, 1.5f, 1.2f, 1.2f))
            }
            paymentsTable.addCell(createPdfHeaderCell("Date", fontHead, BaseColor(185, 28, 28)))
            paymentsTable.addCell(createPdfHeaderCell("Payment #", fontHead, BaseColor(185, 28, 28)))
            paymentsTable.addCell(createPdfHeaderCell("Vendor / Particulars", fontHead, BaseColor(185, 28, 28)))
            paymentsTable.addCell(createPdfHeaderCell("Account Head", fontHead, BaseColor(185, 28, 28)))
            paymentsTable.addCell(createPdfHeaderCell("Cash Db", fontHead, BaseColor(185, 28, 28)))
            paymentsTable.addCell(createPdfHeaderCell("Bank Db", fontHead, BaseColor(185, 28, 28)))

            for (p in allPayments) {
                paymentsTable.addCell(createPdfContentCell(p.date, fontBody))
                paymentsTable.addCell(createPdfContentCell(p.voucherNo, fontBody))
                paymentsTable.addCell(createPdfContentCell("${p.partyName} (${p.entityCode})", fontBody))
                paymentsTable.addCell(createPdfContentCell(p.categoryHead, fontBody))
                paymentsTable.addCell(createPdfContentCell(if (p.cashAmount > 0) String.format("₹%,.2f", p.cashAmount) else "₹0.00", fontBody))
                paymentsTable.addCell(createPdfContentCell(if (p.bankAmount > 0) String.format("₹%,.2f", p.bankAmount) else "₹0.00", fontBody))
            }

            val receiptRows = allReceipts.size + 1
            val paymentRows = allPayments.size
            val maxRows = max(receiptRows, paymentRows)

            for (i in receiptRows until maxRows) {
                for (c in 0..5) receiptsTable.addCell(createPdfContentCell("", fontBody))
            }

            for (i in paymentRows until maxRows) {
                for (c in 0..5) paymentsTable.addCell(createPdfContentCell("", fontBody))
            }

            val closingBal = dynamicOpeningBalance + totalReceiptsSum - totalPaymentsSum
            paymentsTable.addCell(createPdfContentCell("-", fontBody))
            paymentsTable.addCell(createPdfContentCell("-", fontBody))
            paymentsTable.addCell(createPdfContentCell("By Closing Balance C/F", fontBold))
            paymentsTable.addCell(createPdfContentCell("-", fontBody))
            paymentsTable.addCell(createPdfContentCell(String.format("₹%,.2f", closingBal), fontBold))
            paymentsTable.addCell(createPdfContentCell("₹0.00", fontBody))

            receiptsTable.addCell(createPdfContentCell("-", fontBody))
            receiptsTable.addCell(createPdfContentCell("-", fontBody))
            receiptsTable.addCell(createPdfContentCell("Total Balance", fontBold))
            receiptsTable.addCell(createPdfContentCell("-", fontBody))
            receiptsTable.addCell(createPdfContentCell(String.format("₹%,.2f", dynamicOpeningBalance + totalReceiptsSum), fontBold))
            receiptsTable.addCell(createPdfContentCell("₹0.00", fontBody))

            masterTable.addCell(PdfPCell(receiptsTable).apply { setPadding(2f); setBorder(Rectangle.NO_BORDER) })
            masterTable.addCell(PdfPCell(paymentsTable).apply { setPadding(2f); setBorder(Rectangle.NO_BORDER) })

            doc.add(masterTable)
            doc.close()
            outputStream.flush()
            outputStream.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileUri != null) {
                val publishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(fileUri, publishValues, null, null)

                val openIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/pdf")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(openIntent, "Open Two-Sided Cashbook PDF"))
            } else if (legacyFile != null) {
                Toast.makeText(this, "Two-Sided Cashbook saved to Downloads/$fileName ✅", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "PDF Export Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createPdfHeaderCell(txt: String, f: Font, bgColor: BaseColor): PdfPCell {
        return PdfPCell(Paragraph(txt, f)).apply {
            setBackgroundColor(bgColor)
            setPadding(5f)
        }
    }

    private fun createPdfContentCell(txt: String, f: Font): PdfPCell {
        return PdfPCell(Paragraph(txt, f)).apply { setPadding(4f) }
    }
}