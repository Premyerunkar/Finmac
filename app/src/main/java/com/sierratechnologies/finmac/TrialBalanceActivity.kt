package com.sierratechnologies.finmac

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.itextpdf.text.BaseColor
import com.itextpdf.text.Document
import com.itextpdf.text.Element
import com.itextpdf.text.Font
import com.itextpdf.text.PageSize
import com.itextpdf.text.Paragraph
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
import kotlin.math.abs
import kotlin.math.max

class TrialBalanceActivity : AppCompatActivity() {

    private lateinit var viewHeaderBackground: View
    private lateinit var btnBack: ImageView
    private lateinit var btnExportPdf: ImageView
    private lateinit var lblSocietyTitle: TextView
    private lateinit var lblDateSubHeader: TextView

    private lateinit var lblDebitMetric: TextView
    private lateinit var lblCreditMetric: TextView
    private lateinit var lblVarianceMetric: TextView

    private lateinit var toggleGroupLedger: MaterialButtonToggleGroup
    private lateinit var btnTabDebit: MaterialButton
    private lateinit var btnTabCredit: MaterialButton
    private lateinit var lblListHeaderTitle: TextView

    private lateinit var lblFooterTitle: TextView
    private lateinit var lblFooterTotalValue: TextView

    private lateinit var rvTrialLedger: RecyclerView
    private lateinit var loadingOverlay: FrameLayout

    private var targetBranchCode = ""
    private var societyName = "LOADING SOCIETY..."

    enum class LedgerSide { DEBIT, CREDIT }
    private var currentSide = LedgerSide.DEBIT

    data class BalanceRow(val particulars: String, val amount: Double)

    private val debitEntries = ArrayList<BalanceRow>()
    private val creditEntries = ArrayList<BalanceRow>()
    private val activeDisplayedList = ArrayList<BalanceRow>()

    private lateinit var adapter: BalanceAdapter

    private var grandTotalDebit = 0.0
    private var grandTotalCredit = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trial_balance)

        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        targetBranchCode = intent.getStringExtra("SOCIETY_CODE") ?: sharedPref.getString("SOCIETY_CODE", "")?.trim() ?: ""

        if (targetBranchCode.isBlank()) {
            Toast.makeText(this, "Session invalid. Please re-login.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initViews()
        setupWindowInsets()
        setupRecyclerView()

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        lblDateSubHeader.text = String.format("Financial Period: 01/04/%d to 31/03/%d", currentYear - 1, currentYear)

        btnBack.setOnClickListener { finish() }
        btnExportPdf.setOnClickListener { generateTrialBalancePdfArtifact() }

        toggleGroupLedger.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnTabDebit) {
                    switchSide(LedgerSide.DEBIT)
                } else {
                    switchSide(LedgerSide.CREDIT)
                }
            }
        }

        toggleGroupLedger.check(R.id.btnTabDebit)
        switchSide(LedgerSide.DEBIT)

        fetchTrialBalanceDataFromCloud()
    }

    private fun initViews() {
        viewHeaderBackground = findViewById(R.id.viewHeaderBackground)
        btnBack = findViewById(R.id.btnBack)
        btnExportPdf = findViewById(R.id.btnExportPdf)
        lblSocietyTitle = findViewById(R.id.lblSocietyTitle)
        lblDateSubHeader = findViewById(R.id.lblDateSubHeader)

        lblDebitMetric = findViewById(R.id.lblDebitMetric)
        lblCreditMetric = findViewById(R.id.lblCreditMetric)
        lblVarianceMetric = findViewById(R.id.lblVarianceMetric)

        toggleGroupLedger = findViewById(R.id.toggleGroupLedger)
        btnTabDebit = findViewById(R.id.btnTabDebit)
        btnTabCredit = findViewById(R.id.btnTabCredit)
        lblListHeaderTitle = findViewById(R.id.lblListHeaderTitle)

        lblFooterTitle = findViewById(R.id.lblFooterTitle)
        lblFooterTotalValue = findViewById(R.id.lblFooterTotalValue)

        rvTrialLedger = findViewById(R.id.rvTrialLedger)
        loadingOverlay = findViewById(R.id.loadingOverlay)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(viewHeaderBackground) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun setupRecyclerView() {
        rvTrialLedger.layoutManager = LinearLayoutManager(this)
        adapter = BalanceAdapter(activeDisplayedList)
        rvTrialLedger.adapter = adapter
    }

    private fun switchSide(side: LedgerSide) {
        currentSide = side
        val navyColor = Color.parseColor("#052550")

        if (side == LedgerSide.DEBIT) {
            btnTabDebit.setBackgroundColor(navyColor)
            btnTabDebit.setTextColor(Color.WHITE)

            btnTabCredit.setBackgroundColor(Color.WHITE)
            btnTabCredit.setTextColor(navyColor)

            lblListHeaderTitle.text = "DEBIT PARTICULARS LEDGER (OUTFLOWS / ASSETS)"
            lblListHeaderTitle.setBackgroundColor(Color.parseColor("#EFF6FF"))
            lblListHeaderTitle.setTextColor(Color.parseColor("#1976D2"))

            lblFooterTitle.text = "TOTAL DEBIT ACCUMULATION"
            lblFooterTotalValue.setTextColor(Color.parseColor("#1976D2"))
            lblFooterTotalValue.text = String.format("₹%,.2f", grandTotalDebit)

            activeDisplayedList.clear()
            activeDisplayedList.addAll(debitEntries)
        } else {
            btnTabCredit.setBackgroundColor(navyColor)
            btnTabCredit.setTextColor(Color.WHITE)

            btnTabDebit.setBackgroundColor(Color.WHITE)
            btnTabDebit.setTextColor(navyColor)

            lblListHeaderTitle.text = "CREDIT PARTICULARS LEDGER (INFLOWS)"
            lblListHeaderTitle.setBackgroundColor(Color.parseColor("#F8FAFC"))
            lblListHeaderTitle.setTextColor(navyColor)

            lblFooterTitle.text = "TOTAL CREDIT ACCUMULATION"
            lblFooterTotalValue.setTextColor(navyColor)
            lblFooterTotalValue.text = String.format("₹%,.2f", grandTotalCredit)

            activeDisplayedList.clear()
            activeDisplayedList.addAll(creditEntries)
        }
        adapter.notifyDataSetChanged()
    }

    private fun fetchTrialBalanceDataFromCloud() {
        loadingOverlay.visibility = View.VISIBLE
        debitEntries.clear()
        creditEntries.clear()
        grandTotalDebit = 0.0
        grandTotalCredit = 0.0

        lifecycleScope.launch {
            try {
                val branches = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", targetBranchCode) } }
                    .decodeList<BranchDto>()

                if (branches.isNotEmpty()) {
                    societyName = branches.first().name
                    lblSocietyTitle.text = societyName.uppercase(Locale.getDefault())
                }

                val receipts = SupabaseClientManager.postgrest.from("receipts")
                    .select { filter { eq("branch_code", targetBranchCode) } }
                    .decodeList<ReceiptDto>()

                val receiptCategories = HashMap<String, Double>()
                for (doc in receipts) {
                    val category = doc.categoryHead
                    val total = doc.totalFinalAmount
                    if (category.isNotBlank()) {
                        receiptCategories[category] = receiptCategories.getOrDefault(category, 0.0) + total
                    }
                }
                for ((cat, sum) in receiptCategories) {
                    creditEntries.add(BalanceRow(cat, sum))
                    grandTotalCredit += sum
                }

                val payments = SupabaseClientManager.postgrest.from("payments")
                    .select { filter { eq("branch_code", targetBranchCode) } }
                    .decodeList<PaymentDto>()

                val paymentCategories = HashMap<String, Double>()
                for (doc in payments) {
                    val category = doc.categoryHead
                    val total = doc.totalFinalAmount
                    if (category.isNotBlank()) {
                        paymentCategories[category] = paymentCategories.getOrDefault(category, 0.0) + total
                    }
                }
                for ((cat, sum) in paymentCategories) {
                    debitEntries.add(BalanceRow(cat, sum))
                    grandTotalDebit += sum
                }

                val bankAccounts = SupabaseClientManager.postgrest.from("bank_accounts")
                    .select { filter { eq("branch_code", targetBranchCode) } }
                    .decodeList<BankAccountDto>()

                for (doc in bankAccounts) {
                    val bankName = doc.bankName
                    val accNo = doc.accountNumber
                    val bal = doc.currentBalance

                    if (bankName.isNotBlank() && bal > 0) {
                        val accLabel = if (accNo.isNotBlank()) "$bankName A/c No. $accNo" else bankName
                        debitEntries.add(BalanceRow(accLabel, bal))
                        grandTotalDebit += bal
                    }
                }

                lblDebitMetric.text = String.format("₹%,.2f", grandTotalDebit)
                lblCreditMetric.text = String.format("₹%,.2f", grandTotalCredit)

                val variance = abs(grandTotalDebit - grandTotalCredit)
                if (variance < 0.01) {
                    lblVarianceMetric.text = "Balanced ✅"
                    lblVarianceMetric.setTextColor(Color.parseColor("#10B981"))
                } else {
                    lblVarianceMetric.text = String.format("Diff: ₹%,.2f ⚠️", variance)
                    lblVarianceMetric.setTextColor(Color.parseColor("#EF4444"))
                }

                switchSide(currentSide)
            } catch (e: Exception) {
                Toast.makeText(this@TrialBalanceActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun generateTrialBalancePdfArtifact() {
        val currentDateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Trial_Balance_${targetBranchCode}_$currentDateStr.pdf"

        try {
            val doc = Document(PageSize.A4, 30f, 30f, 36f, 36f)
            var fileUri: Uri? = null
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

            val fontSociety = Font(Font.FontFamily.HELVETICA, 14f, Font.BOLD, BaseColor.BLACK)
            val fontSub = Font(Font.FontFamily.HELVETICA, 10f, Font.BOLD, BaseColor.DARK_GRAY)
            val fontTitle = Font(Font.FontFamily.HELVETICA, 12f, Font.BOLD, BaseColor(25, 118, 210))
            val fontTableHead = Font(Font.FontFamily.HELVETICA, 9f, Font.BOLD, BaseColor.BLACK)
            val fontBody = Font(Font.FontFamily.HELVETICA, 9f, Font.NORMAL, BaseColor.BLACK)
            val fontBoldBody = Font(Font.FontFamily.HELVETICA, 9f, Font.BOLD, BaseColor.BLACK)

            val pSociety = Paragraph(societyName.uppercase(Locale.getDefault()), fontSociety).apply { alignment = Element.ALIGN_CENTER }
            doc.add(pSociety)

            val pDate = Paragraph(lblDateSubHeader.text.toString(), fontSub).apply { alignment = Element.ALIGN_CENTER }
            doc.add(pDate)

            val pTitle = Paragraph("STATUTORY TRIAL BALANCE STATEMENT\n\n", fontTitle).apply { alignment = Element.ALIGN_CENTER }
            doc.add(pTitle)

            val table = PdfPTable(4).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(3.5f, 1.5f, 3.5f, 1.5f))
            }

            table.addCell(createPdfCell("DEBIT PARTICULARS", fontTableHead, Element.ALIGN_CENTER, true))
            table.addCell(createPdfCell("AMOUNT (₹)", fontTableHead, Element.ALIGN_CENTER, true))
            table.addCell(createPdfCell("CREDIT PARTICULARS", fontTableHead, Element.ALIGN_CENTER, true))
            table.addCell(createPdfCell("AMOUNT (₹)", fontTableHead, Element.ALIGN_CENTER, true))

            val maxRows = max(debitEntries.size, creditEntries.size)
            for (i in 0 until maxRows) {
                val deb = if (i < debitEntries.size) debitEntries[i] else null
                val cred = if (i < creditEntries.size) creditEntries[i] else null

                table.addCell(createPdfCell(deb?.particulars ?: "", fontBody, Element.ALIGN_LEFT, false))
                table.addCell(createPdfCell(deb?.let { String.format("%,.2f", it.amount) } ?: "", fontBody, Element.ALIGN_RIGHT, false))
                table.addCell(createPdfCell(cred?.particulars ?: "", fontBody, Element.ALIGN_LEFT, false))
                table.addCell(createPdfCell(cred?.let { String.format("%,.2f", it.amount) } ?: "", fontBody, Element.ALIGN_RIGHT, false))
            }

            table.addCell(createPdfCell("TOTAL DEBIT", fontBoldBody, Element.ALIGN_LEFT, true))
            table.addCell(createPdfCell(String.format("%,.2f", grandTotalDebit), fontBoldBody, Element.ALIGN_RIGHT, true))
            table.addCell(createPdfCell("TOTAL CREDIT", fontBoldBody, Element.ALIGN_LEFT, true))
            table.addCell(createPdfCell(String.format("%,.2f", grandTotalCredit), fontBoldBody, Element.ALIGN_RIGHT, true))

            doc.add(table)
            doc.close()
            outputStream.flush()
            outputStream.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileUri != null) {
                val publishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(fileUri, publishValues, null, null)

                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(openIntent, "Open Trial Balance PDF"))
            } else if (legacyFile != null) {
                Toast.makeText(this, "Trial Balance saved to Downloads/$fileName ✅", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "PDF Export Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createPdfCell(text: String, font: Font, align: Int, isHeader: Boolean): PdfPCell {
        return PdfPCell(Paragraph(text, font)).apply {
            horizontalAlignment = align
            verticalAlignment = Element.ALIGN_MIDDLE
            setPadding(5f)
            if (isHeader) {
                backgroundColor = BaseColor(240, 243, 246)
            }
        }
    }

    inner class BalanceAdapter(private val list: List<BalanceRow>) :
        RecyclerView.Adapter<BalanceAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val lblParticulars: TextView = v.findViewById(R.id.lblParticulars)
            val lblAmount: TextView = v.findViewById(R.id.lblAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trial_balance_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.lblParticulars.text = item.particulars
            holder.lblAmount.text = String.format("₹%,.2f", item.amount)
        }

        override fun getItemCount() = list.size
    }
}