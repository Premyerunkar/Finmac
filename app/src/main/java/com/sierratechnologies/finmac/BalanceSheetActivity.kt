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

class BalanceSheetActivity : AppCompatActivity() {

    private lateinit var viewHeaderBackground: View
    private lateinit var btnBack: ImageView
    private lateinit var btnExportPdf: ImageView
    private lateinit var lblSocietyTitle: TextView
    private lateinit var lblDateSubHeader: TextView

    private lateinit var lblLiabilitiesMetric: TextView
    private lateinit var lblAssetsMetric: TextView
    private lateinit var lblEquilibriumMetric: TextView

    private lateinit var toggleGroupSheet: MaterialButtonToggleGroup
    private lateinit var btnTabLiabilities: MaterialButton
    private lateinit var btnTabAssets: MaterialButton
    private lateinit var lblListHeaderTitle: TextView

    private lateinit var lblFooterTitle: TextView
    private lateinit var lblFooterTotalValue: TextView

    private lateinit var rvBalanceSheetLedger: RecyclerView
    private lateinit var loadingOverlay: FrameLayout

    private var targetBranchCode = ""
    private var societyName = "LOADING SOCIETY..."

    enum class SheetSide { LIABILITIES, ASSETS }
    private var currentSide = SheetSide.LIABILITIES

    data class BalanceRow(val particulars: String, val amount: Double)

    private val liabilitiesEntries = ArrayList<BalanceRow>()
    private val assetsEntries = ArrayList<BalanceRow>()
    private val activeDisplayedList = ArrayList<BalanceRow>()

    private lateinit var adapter: BalanceAdapter

    private var grandTotalLiabilities = 0.0
    private var grandTotalAssets = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_balance_sheet)

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
        lblDateSubHeader.text = String.format("BALANCE SHEET AS ON 31/03/%d", currentYear)

        btnBack.setOnClickListener { finish() }
        btnExportPdf.setOnClickListener { generateBalanceSheetPdfArtifact() }

        toggleGroupSheet.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnTabLiabilities) {
                    switchSide(SheetSide.LIABILITIES)
                } else {
                    switchSide(SheetSide.ASSETS)
                }
            }
        }

        toggleGroupSheet.check(R.id.btnTabLiabilities)
        switchSide(SheetSide.LIABILITIES)

        fetchBalanceSheetDataFromCloud()
    }

    private fun initViews() {
        viewHeaderBackground = findViewById(R.id.viewHeaderBackground)
        btnBack = findViewById(R.id.btnBack)
        btnExportPdf = findViewById(R.id.btnExportPdf)
        lblSocietyTitle = findViewById(R.id.lblSocietyTitle)
        lblDateSubHeader = findViewById(R.id.lblDateSubHeader)

        lblLiabilitiesMetric = findViewById(R.id.lblLiabilitiesMetric)
        lblAssetsMetric = findViewById(R.id.lblAssetsMetric)
        lblEquilibriumMetric = findViewById(R.id.lblEquilibriumMetric)

        toggleGroupSheet = findViewById(R.id.toggleGroupSheet)
        btnTabLiabilities = findViewById(R.id.btnTabLiabilities)
        btnTabAssets = findViewById(R.id.btnTabAssets)
        lblListHeaderTitle = findViewById(R.id.lblListHeaderTitle)

        lblFooterTitle = findViewById(R.id.lblFooterTitle)
        lblFooterTotalValue = findViewById(R.id.lblFooterTotalValue)

        rvBalanceSheetLedger = findViewById(R.id.rvBalanceSheetLedger)
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
        rvBalanceSheetLedger.layoutManager = LinearLayoutManager(this)
        adapter = BalanceAdapter(activeDisplayedList)
        rvBalanceSheetLedger.adapter = adapter
    }

    private fun switchSide(side: SheetSide) {
        currentSide = side
        val navyColor = Color.parseColor("#052550")

        if (side == SheetSide.LIABILITIES) {
            btnTabLiabilities.setBackgroundColor(navyColor)
            btnTabLiabilities.setTextColor(Color.WHITE)

            btnTabAssets.setBackgroundColor(Color.WHITE)
            btnTabAssets.setTextColor(navyColor)

            lblListHeaderTitle.text = "SOCIETY LIABILITIES LEDGER"
            lblListHeaderTitle.setBackgroundColor(Color.parseColor("#EFF6FF"))
            lblListHeaderTitle.setTextColor(Color.parseColor("#1976D2"))

            lblFooterTitle.text = "TOTAL LIABILITIES ACCUMULATION"
            lblFooterTotalValue.setTextColor(Color.parseColor("#1976D2"))
            lblFooterTotalValue.text = String.format("₹%,.2f", grandTotalLiabilities)

            activeDisplayedList.clear()
            activeDisplayedList.addAll(liabilitiesEntries)
        } else {
            btnTabAssets.setBackgroundColor(navyColor)
            btnTabAssets.setTextColor(Color.WHITE)

            btnTabLiabilities.setBackgroundColor(Color.WHITE)
            btnTabLiabilities.setTextColor(navyColor)

            lblListHeaderTitle.text = "SOCIETY ASSETS LEDGER"
            lblListHeaderTitle.setBackgroundColor(Color.parseColor("#F8FAFC"))
            lblListHeaderTitle.setTextColor(navyColor)

            lblFooterTitle.text = "TOTAL ASSETS ACCUMULATION"
            lblFooterTotalValue.setTextColor(navyColor)
            lblFooterTotalValue.text = String.format("₹%,.2f", grandTotalAssets)

            activeDisplayedList.clear()
            activeDisplayedList.addAll(assetsEntries)
        }
        adapter.notifyDataSetChanged()
    }

    private fun fetchBalanceSheetDataFromCloud() {
        loadingOverlay.visibility = View.VISIBLE
        liabilitiesEntries.clear()
        assetsEntries.clear()
        grandTotalLiabilities = 0.0
        grandTotalAssets = 0.0

        lifecycleScope.launch {
            try {
                val branches = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", targetBranchCode) } }
                    .decodeList<BranchDto>()

                if (branches.isNotEmpty()) {
                    societyName = branches.first().name
                    lblSocietyTitle.text = societyName.uppercase(Locale.getDefault())
                }

                val liabilitiesMap = HashMap<String, Double>()
                val assetsMap = HashMap<String, Double>()
                var totalCashInHand = 0.0

                val receipts = SupabaseClientManager.postgrest.from("receipts")
                    .select { filter { eq("branch_code", targetBranchCode) } }
                    .decodeList<ReceiptDto>()

                for (doc in receipts) {
                    val group = doc.accountGroup
                    val head = doc.categoryHead
                    val pMode = doc.paymentMode
                    val amt = doc.totalFinalAmount

                    if ("LIABILITY".equals(group, ignoreCase = true) && head.isNotBlank()) {
                        liabilitiesMap[head] = liabilitiesMap.getOrDefault(head, 0.0) + amt
                    } else if ("ASSET".equals(group, ignoreCase = true) && head.isNotBlank()) {
                        assetsMap[head] = assetsMap.getOrDefault(head, 0.0) + amt
                    }

                    if ("Cash".equals(pMode, ignoreCase = true)) {
                        totalCashInHand += amt
                    }
                }

                val payments = SupabaseClientManager.postgrest.from("payments")
                    .select { filter { eq("branch_code", targetBranchCode) } }
                    .decodeList<PaymentDto>()

                for (doc in payments) {
                    val group = doc.accountGroup
                    val head = doc.categoryHead
                    val pMode = doc.paymentMode
                    val amt = doc.totalFinalAmount

                    if ("LIABILITY".equals(group, ignoreCase = true) && head.isNotBlank()) {
                        liabilitiesMap[head] = liabilitiesMap.getOrDefault(head, 0.0) + amt
                    } else if ("ASSET".equals(group, ignoreCase = true) && head.isNotBlank()) {
                        assetsMap[head] = assetsMap.getOrDefault(head, 0.0) + amt
                    }

                    if ("Cash".equals(pMode, ignoreCase = true)) {
                        totalCashInHand -= amt
                    }
                }

                val contras = SupabaseClientManager.postgrest.from("contra_entries")
                    .select { filter { eq("branch_code", targetBranchCode) } }
                    .decodeList<ContraEntryDto>()

                for (doc in contras) {
                    val srcAcc = doc.sourceAccount
                    val destAcc = doc.destinationAccount
                    val amt = doc.amount

                    if (srcAcc.equals("Cash in Hand Account", ignoreCase = true) || srcAcc.uppercase(Locale.getDefault()).contains("CASH")) {
                        totalCashInHand -= amt
                    }
                    if (destAcc.equals("Cash in Hand Account", ignoreCase = true) || destAcc.uppercase(Locale.getDefault()).contains("CASH")) {
                        totalCashInHand += amt
                    }
                }

                if (totalCashInHand != 0.0) {
                    assetsMap["Cash in Hand Account"] = totalCashInHand
                }

                val bankAccounts = SupabaseClientManager.postgrest.from("bank_accounts")
                    .select { filter { eq("branch_code", targetBranchCode) } }
                    .decodeList<BankAccountDto>()

                for (doc in bankAccounts) {
                    val bankName = doc.bankName
                    val accNo = doc.accountNumber
                    val currentBal = doc.currentBalance

                    if (bankName.isNotBlank() && currentBal > 0) {
                        val label = if (accNo.isNotBlank()) "$bankName A/c $accNo" else bankName
                        assetsMap[label] = assetsMap.getOrDefault(label, 0.0) + currentBal
                    }
                }

                for ((cat, sum) in liabilitiesMap) {
                    liabilitiesEntries.add(BalanceRow(cat, sum))
                    grandTotalLiabilities += sum
                }

                for ((cat, sum) in assetsMap) {
                    assetsEntries.add(BalanceRow(cat, sum))
                    grandTotalAssets += sum
                }

                lblLiabilitiesMetric.text = String.format("₹%,.2f", grandTotalLiabilities)
                lblAssetsMetric.text = String.format("₹%,.2f", grandTotalAssets)

                val variance = abs(grandTotalLiabilities - grandTotalAssets)
                if (variance < 0.01) {
                    lblEquilibriumMetric.text = "Balanced ✅"
                    lblEquilibriumMetric.setTextColor(Color.parseColor("#10B981"))
                } else {
                    lblEquilibriumMetric.text = String.format("Diff: ₹%,.2f ⚠️", variance)
                    lblEquilibriumMetric.setTextColor(Color.parseColor("#EF4444"))
                }

                switchSide(currentSide)
            } catch (e: Exception) {
                Toast.makeText(this@BalanceSheetActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun generateBalanceSheetPdfArtifact() {
        val currentDateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Balance_Sheet_${targetBranchCode}_$currentDateStr.pdf"

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

            val fontSociety = Font(Font.FontFamily.HELVETICA, 13f, Font.BOLD, BaseColor.BLACK)
            val fontSub = Font(Font.FontFamily.HELVETICA, 10f, Font.BOLD, BaseColor.DARK_GRAY)
            val fontTableHead = Font(Font.FontFamily.HELVETICA, 9f, Font.BOLD, BaseColor.BLACK)
            val fontBody = Font(Font.FontFamily.HELVETICA, 8f, Font.NORMAL, BaseColor.BLACK)
            val fontBoldBody = Font(Font.FontFamily.HELVETICA, 9f, Font.BOLD, BaseColor.BLACK)

            val pSociety = Paragraph(societyName.uppercase(Locale.getDefault()), fontSociety).apply { alignment = Element.ALIGN_CENTER }
            doc.add(pSociety)

            val pDate = Paragraph(lblDateSubHeader.text.toString() + "\n\n", fontSub).apply { alignment = Element.ALIGN_CENTER }
            doc.add(pDate)

            val table = PdfPTable(4).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(3.5f, 1.5f, 3.5f, 1.5f))
            }

            table.addCell(createPdfCell("LIABILITIES", fontTableHead, Element.ALIGN_CENTER, true))
            table.addCell(createPdfCell("AMOUNT (₹)", fontTableHead, Element.ALIGN_CENTER, true))
            table.addCell(createPdfCell("ASSETS", fontTableHead, Element.ALIGN_CENTER, true))
            table.addCell(createPdfCell("AMOUNT (₹)", fontTableHead, Element.ALIGN_CENTER, true))

            val maxRows = max(liabilitiesEntries.size, assetsEntries.size)
            for (i in 0 until maxRows) {
                val liab = if (i < liabilitiesEntries.size) liabilitiesEntries[i] else null
                val ass = if (i < assetsEntries.size) assetsEntries[i] else null

                table.addCell(createPdfCell(liab?.particulars ?: "", fontBody, Element.ALIGN_LEFT, false))
                table.addCell(createPdfCell(liab?.let { String.format("%,.2f", it.amount) } ?: "", fontBody, Element.ALIGN_RIGHT, false))
                table.addCell(createPdfCell(ass?.particulars ?: "", fontBody, Element.ALIGN_LEFT, false))
                table.addCell(createPdfCell(ass?.let { String.format("%,.2f", it.amount) } ?: "", fontBody, Element.ALIGN_RIGHT, false))
            }

            table.addCell(createPdfCell("TOTAL", fontBoldBody, Element.ALIGN_LEFT, true))
            table.addCell(createPdfCell(String.format("%,.2f", grandTotalLiabilities), fontBoldBody, Element.ALIGN_RIGHT, true))
            table.addCell(createPdfCell("TOTAL", fontBoldBody, Element.ALIGN_LEFT, true))
            table.addCell(createPdfCell(String.format("%,.2f", grandTotalAssets), fontBoldBody, Element.ALIGN_RIGHT, true))

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
                startActivity(Intent.createChooser(openIntent, "Open Balance Sheet PDF"))
            } else if (legacyFile != null) {
                Toast.makeText(this, "Balance Sheet saved to Downloads/$fileName ✅", Toast.LENGTH_LONG).show()
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
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_balance_sheet_row, parent, false)
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