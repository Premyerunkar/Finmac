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
import com.google.android.material.button.MaterialButtonToggleGroup
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
    private lateinit var toggleSideGroup: MaterialButtonToggleGroup

    private lateinit var lblOpeningBal: TextView
    private lateinit var lblTotalReceipts: TextView
    private lateinit var lblTotalPayments: TextView
    private lateinit var lblClosingBal: TextView

    private lateinit var txtSearchLedger: EditText
    private lateinit var spnPeriod: Spinner
    private lateinit var rvCashbookLedger: RecyclerView
    private lateinit var loadingOverlay: FrameLayout

    private var activeBranchCode = ""
    private var branchName = "Khetan Group / Society"
    private var branchAddress = "Head Office Administration"
    private var branchRegNo = "REG-MH-26/X4911"

    enum class ViewSide { RECEIPTS, PAYMENTS }
    private var currentSide = ViewSide.RECEIPTS

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
    private val displayedList = ArrayList<LedgerEntry>()
    private lateinit var adapter: CashbookAdapter

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
        setupRecyclerView()

        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { loadCompleteCashbookData() }
        btnExportPdf.setOnClickListener { generateSplitTwoSidedPdfReport() }

        val navyColor = Color.parseColor("#052550")
        val btnReceipts = findViewById<MaterialButton>(R.id.btnSideReceipts)
        val btnPayments = findViewById<MaterialButton>(R.id.btnSidePayments)

        toggleSideGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnSideReceipts) {
                    currentSide = ViewSide.RECEIPTS
                    btnReceipts.setBackgroundColor(navyColor)
                    btnReceipts.setTextColor(Color.WHITE)

                    btnPayments.setBackgroundColor(Color.WHITE)
                    btnPayments.setTextColor(navyColor)
                } else {
                    currentSide = ViewSide.PAYMENTS
                    btnPayments.setBackgroundColor(navyColor)
                    btnPayments.setTextColor(Color.WHITE)

                    btnReceipts.setBackgroundColor(Color.WHITE)
                    btnReceipts.setTextColor(navyColor)
                }
                filterAndRenderDisplayList(txtSearchLedger.text.toString().trim())
            }
        }

        btnReceipts.setBackgroundColor(navyColor)
        btnReceipts.setTextColor(Color.WHITE)
        btnPayments.setBackgroundColor(Color.WHITE)
        btnPayments.setTextColor(navyColor)

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
        toggleSideGroup = findViewById(R.id.toggleSideGroup)

        lblOpeningBal = findViewById(R.id.lblOpeningBal)
        lblTotalReceipts = findViewById(R.id.lblTotalReceipts)
        lblTotalPayments = findViewById(R.id.lblTotalPayments)
        lblClosingBal = findViewById(R.id.lblClosingBal)

        txtSearchLedger = findViewById(R.id.txtSearchLedger)
        spnPeriod = findViewById(R.id.spnPeriod)
        rvCashbookLedger = findViewById(R.id.rvCashbookLedger)
        loadingOverlay = findViewById(R.id.loadingOverlay)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            viewHeaderBackground.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun setupRecyclerView() {
        rvCashbookLedger.layoutManager = LinearLayoutManager(this)
        adapter = CashbookAdapter(displayedList)
        rvCashbookLedger.adapter = adapter
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

        dynamicOpeningBalance = baseCashOpeningInput

        if (periodThresholdDateStr != null) {
            for (r in tempReceiptsList) {
                if (r.date < periodThresholdDateStr) {
                    dynamicOpeningBalance += r.cashAmount
                }
            }
            for (p in tempPaymentsList) {
                if (p.date < periodThresholdDateStr) {
                    dynamicOpeningBalance -= p.cashAmount
                }
            }
            for (c in cList) {
                if (c.date < periodThresholdDateStr) {
                    if (c.sourceAccount.contains("Cash", ignoreCase = true)) {
                        dynamicOpeningBalance -= c.amount
                    }
                    if (c.destinationAccount.contains("Cash", ignoreCase = true)) {
                        dynamicOpeningBalance += c.amount
                    }
                }
            }
        }

        for (r in tempReceiptsList) {
            if (periodThresholdDateStr == null || r.date >= periodThresholdDateStr) {
                allReceipts.add(r)
                totalReceiptsSum += r.totalAmount
            }
        }

        for (p in tempPaymentsList) {
            if (periodThresholdDateStr == null || p.date >= periodThresholdDateStr) {
                allPayments.add(p)
                totalPaymentsSum += p.totalAmount
            }
        }

        allReceipts.sortBy { it.date }
        allPayments.sortBy { it.date }

        if (allReceipts.isNotEmpty()) {
            dynamicOpeningBalance = allReceipts.first().totalAmount
        } else {
            dynamicOpeningBalance = baseCashOpeningInput
        }

        val closingBal = totalReceiptsSum - totalPaymentsSum
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

        // Always display shimmer overlay and fetch fresh live data from database
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
        displayedList.clear()
        val targetSource = if (currentSide == ViewSide.RECEIPTS) allReceipts else allPayments

        if (query.isEmpty()) {
            displayedList.addAll(targetSource)
        } else {
            val q = query.lowercase(Locale.getDefault())
            for (item in targetSource) {
                if (item.voucherNo.lowercase().contains(q) ||
                    item.partyName.lowercase().contains(q) ||
                    item.categoryHead.lowercase().contains(q) ||
                    item.entityCode.lowercase().contains(q)
                ) {
                    displayedList.add(item)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    inner class CashbookAdapter(private val list: List<LedgerEntry>) : RecyclerView.Adapter<CashbookAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val lblDate: TextView = v.findViewById(R.id.lblDate)
            val lblVoucherNo: TextView = v.findViewById(R.id.lblVoucherNo)
            val lblAmount: TextView = v.findViewById(R.id.lblAmount)
            val lblPartyName: TextView = v.findViewById(R.id.lblPartyName)
            val lblRunningBal: TextView = v.findViewById(R.id.lblRunningBal)
            val lblNarration: TextView = v.findViewById(R.id.lblNarration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cashbook_entry, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.lblDate.text = item.date
            holder.lblVoucherNo.text = "#${item.voucherNo}"
            holder.lblPartyName.text = "${item.partyName} (${item.entityCode})"
            holder.lblNarration.text = "${item.categoryHead} • ${item.mode}"
            holder.lblRunningBal.visibility = View.GONE

            if (item.isReceipt) {
                holder.lblAmount.text = String.format("+₹%,.2f", item.totalAmount)
                holder.lblAmount.setTextColor(Color.parseColor("#16A34A"))
            } else {
                holder.lblAmount.text = String.format("-₹%,.2f", item.totalAmount)
                holder.lblAmount.setTextColor(Color.parseColor("#DC2626"))
            }

            holder.itemView.setOnClickListener {
                openDetailedVoucherModalSheet(item)
            }
        }

        override fun getItemCount() = list.size
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