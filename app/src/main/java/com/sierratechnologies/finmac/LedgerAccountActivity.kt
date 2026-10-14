package com.sierratechnologies.finmac

import android.content.Context
import android.graphics.Color
import android.os.Bundle
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
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TreeSet

class LedgerAccountActivity : AppCompatActivity() {

    private lateinit var viewHeaderBackground: View
    private lateinit var btnBack: ImageView
    private lateinit var cmbTxTypeScope: Spinner
    private lateinit var cmbAccountHeads: Spinner
    private lateinit var cmbSortOptions: Spinner
    private lateinit var txtGlobalSearch: EditText

    private lateinit var rvLedgerTable: RecyclerView
    private lateinit var loadingOverlay: FrameLayout

    private lateinit var lblTotalCredits: TextView
    private lateinit var lblTotalDebits: TextView
    private lateinit var lblNetClosingBalance: TextView

    private var activeBranchCode = ""

    private val globalReceiptHeads = TreeSet<String>()
    private val globalPaymentHeads = TreeSet<String>()

    data class LedgerRow(
        val date: String,
        val invoiceNo: String,
        val identity: String,
        val name: String,
        val categoryHead: String,
        val mode: String,
        val amount: Double,
        val isCredit: Boolean
    )

    private val masterRowList = ArrayList<LedgerRow>()
    private val filteredRowList = ArrayList<LedgerRow>()
    private lateinit var adapter: LedgerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ledger_account)

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

        setupSpinnerAdapters()
        setupTextWatchers()

        populateDynamicAccountHeadFiltersPipeline()
    }

    private fun initViews() {
        viewHeaderBackground = findViewById(R.id.viewHeaderBackground)
        btnBack = findViewById(R.id.btnBack)
        cmbTxTypeScope = findViewById(R.id.cmbTxTypeScope)
        cmbAccountHeads = findViewById(R.id.cmbAccountHeads)
        cmbSortOptions = findViewById(R.id.cmbSortOptions)
        txtGlobalSearch = findViewById(R.id.txtGlobalSearch)

        rvLedgerTable = findViewById(R.id.rvLedgerTable)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        lblTotalCredits = findViewById(R.id.lblTotalCredits)
        lblTotalDebits = findViewById(R.id.lblTotalDebits)
        lblNetClosingBalance = findViewById(R.id.lblNetClosingBalance)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            viewHeaderBackground.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun setupRecyclerView() {
        rvLedgerTable.layoutManager = LinearLayoutManager(this)
        adapter = LedgerAdapter(filteredRowList)
        rvLedgerTable.adapter = adapter
    }

    private fun setupSpinnerAdapters() {
        val scopeOptions = arrayOf("All", "Receipts Only", "Payments Only")
        cmbTxTypeScope.adapter = ArrayAdapter(this, R.layout.spinner_item, scopeOptions).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        cmbTxTypeScope.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                cascadeAccountHeadDropdownListOptions()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val sortOptions = arrayOf(
            "Sort: Date",
            "Sort: Name (A-Z)",
            "Sort: Amount (Low-High)",
            "Sort: Amount (High-Low)"
        )
        cmbSortOptions.adapter = ArrayAdapter(this, R.layout.spinner_item, sortOptions).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        cmbSortOptions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                executeLedgerTableSortingSequences()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        cmbAccountHeads.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                pullFilteredAccountHeadLedgerEntries()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTextWatchers() {
        txtGlobalSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyGlobalSearchFilter(s.toString().trim())
            }
        })
    }

    private fun populateDynamicAccountHeadFiltersPipeline() {
        loadingOverlay.visibility = View.VISIBLE
        globalReceiptHeads.clear()
        globalPaymentHeads.clear()

        // Seed default Entry Book Receipt categories
        globalReceiptHeads.addAll(
            listOf(
                "Bank Intrest", "Deposits Recieved", "Entry Fee", "Fixed Deposit Maturity",
                "Maintainance Collection", "Monthly Contributions", "Other Charges",
                "Parking Charges", "Reserve Funds", "Share Capital", "Sinking Fund"
            )
        )

        // Seed default Entry Book Payment categories
        globalPaymentHeads.addAll(
            listOf(
                "Audit Fees", "Bank Charges", "Clearing Expenses", "Deposit Refund",
                "Diwali Bonus", "Electrical Repairs and Materials", "Maintainance Expenses",
                "Miscellaneous Expenses", "Monthly Contributions", "P & L Accounts",
                "Reserve Fund Deposit", "Salary Expenses", "Temporary Labour Charges"
            )
        )

        lifecycleScope.launch {
            try {
                // Fetch custom categories created in Entry Book
                try {
                    val customCats = SupabaseClientManager.postgrest.from("account_categories")
                        .select { filter { eq("branch_code", activeBranchCode) } }
                        .decodeList<AccountCategoryDto>()

                    for (cat in customCats) {
                        val head = cat.categoryHead.trim()
                        if (head.isNotBlank()) {
                            if (cat.voucherType.equals("ALL", ignoreCase = true) || cat.voucherType.equals("RECEIPT", ignoreCase = true)) {
                                globalReceiptHeads.add(head)
                            }
                            if (cat.voucherType.equals("ALL", ignoreCase = true) || cat.voucherType.equals("PAYMENT", ignoreCase = true)) {
                                globalPaymentHeads.add(head)
                            }
                        }
                    }
                } catch (_: Exception) {}

                val receipts = SupabaseClientManager.postgrest.from("receipts")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ReceiptDto>()

                for (doc in receipts) {
                    val head = doc.categoryHead
                    if (head.isNotBlank()) globalReceiptHeads.add(head.trim())
                }

                val payments = SupabaseClientManager.postgrest.from("payments")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<PaymentDto>()

                for (doc in payments) {
                    val head = doc.categoryHead
                    if (head.isNotBlank()) globalPaymentHeads.add(head.trim())
                }

                cascadeAccountHeadDropdownListOptions()
            } catch (e: Exception) {
                Toast.makeText(this@LedgerAccountActivity, "Error loading account heads: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun cascadeAccountHeadDropdownListOptions() {
        val selectionIndex = cmbTxTypeScope.selectedItemPosition
        val headOptions = ArrayList<String>()

        when (selectionIndex) {
            0 -> {
                val masterCombinedHeads = TreeSet<String>()
                masterCombinedHeads.addAll(globalReceiptHeads)
                masterCombinedHeads.addAll(globalPaymentHeads)
                headOptions.add("Show All Combined Rows")
                headOptions.addAll(masterCombinedHeads)
            }
            1 -> {
                headOptions.add("Show All Receipts Rows")
                headOptions.addAll(globalReceiptHeads)
            }
            2 -> {
                headOptions.add("Show All Payments Rows")
                headOptions.addAll(globalPaymentHeads)
            }
        }

        val headAdapter = ArrayAdapter(this, R.layout.spinner_item, headOptions)
        headAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        cmbAccountHeads.adapter = headAdapter
        cmbAccountHeads.setSelection(0)

        pullFilteredAccountHeadLedgerEntries()
    }

    private fun pullFilteredAccountHeadLedgerEntries() {
        if (cmbAccountHeads.selectedItem == null) return

        val selectedHead = cmbAccountHeads.selectedItem.toString()
        val scopeIndex = cmbTxTypeScope.selectedItemPosition

        loadingOverlay.visibility = View.VISIBLE
        masterRowList.clear()

        val fetchReceipts = (scopeIndex == 0 || scopeIndex == 1)
        val fetchPayments = (scopeIndex == 0 || scopeIndex == 2)
        val matchSpecificHead = !selectedHead.startsWith("Show All")

        lifecycleScope.launch {
            try {
                if (fetchReceipts) {
                    val receipts = SupabaseClientManager.postgrest.from("receipts")
                        .select {
                            filter {
                                eq("branch_code", activeBranchCode)
                                if (matchSpecificHead) eq("category_head", selectedHead)
                            }
                        }
                        .decodeList<ReceiptDto>()

                    for (doc in receipts) {
                        masterRowList.add(
                            LedgerRow(
                                date = doc.date,
                                invoiceNo = doc.invoiceNo,
                                identity = doc.flatNo ?: "N/A",
                                name = doc.partyName,
                                categoryHead = doc.categoryHead,
                                mode = doc.paymentMode ?: "Cash",
                                amount = doc.totalFinalAmount,
                                isCredit = true
                            )
                        )
                    }
                }

                if (fetchPayments) {
                    val payments = SupabaseClientManager.postgrest.from("payments")
                        .select {
                            filter {
                                eq("branch_code", activeBranchCode)
                                if (matchSpecificHead) eq("category_head", selectedHead)
                            }
                        }
                        .decodeList<PaymentDto>()

                    for (doc in payments) {
                        masterRowList.add(
                            LedgerRow(
                                date = doc.date,
                                invoiceNo = doc.invoiceNo,
                                identity = doc.vendorCode ?: "N/A",
                                name = doc.partyName,
                                categoryHead = doc.categoryHead,
                                mode = doc.paymentMode ?: "Cash",
                                amount = doc.totalFinalAmount,
                                isCredit = false
                            )
                        )
                    }
                }

                executeLedgerTableSortingSequences()
            } catch (e: Exception) {
                Toast.makeText(this@LedgerAccountActivity, "Error loading ledger entries: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun executeLedgerTableSortingSequences() {
        when (cmbSortOptions.selectedItemPosition) {
            0 -> masterRowList.sortBy { it.date }
            1 -> masterRowList.sortBy { it.name.lowercase(Locale.getDefault()) }
            2 -> masterRowList.sortBy { it.amount }
            3 -> masterRowList.sortByDescending { it.amount }
        }
        applyGlobalSearchFilter(txtGlobalSearch.text.toString().trim())
    }

    private fun applyGlobalSearchFilter(query: String) {
        filteredRowList.clear()
        var sumCredits = 0.0
        var sumDebits = 0.0

        val q = query.lowercase(Locale.getDefault())

        for (row in masterRowList) {
            val matches = query.isEmpty() ||
                    row.date.lowercase().contains(q) ||
                    row.invoiceNo.lowercase().contains(q) ||
                    row.identity.lowercase().contains(q) ||
                    row.name.lowercase().contains(q) ||
                    row.categoryHead.lowercase().contains(q)

            if (matches) {
                filteredRowList.add(row)
                if (row.isCredit) sumCredits += row.amount else sumDebits += row.amount
            }
        }

        adapter.notifyDataSetChanged()

        val netPosition = sumCredits - sumDebits
        lblTotalCredits.text = String.format("₹%,.2f", sumCredits)
        lblTotalDebits.text = String.format("₹%,.2f", sumDebits)
        lblNetClosingBalance.text = String.format("₹%,.2f", netPosition)

        lblNetClosingBalance.setTextColor(
            if (netPosition >= 0) Color.parseColor("#1976D2") else Color.parseColor("#D32F2F")
        )
    }

    inner class LedgerAdapter(private val list: List<LedgerRow>) : RecyclerView.Adapter<LedgerAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val lblDate: TextView = v.findViewById(R.id.lblDate)
            val lblInvoiceNo: TextView = v.findViewById(R.id.lblInvoiceNo)
            val lblPartyName: TextView = v.findViewById(R.id.lblPartyName)
            val lblIdentity: TextView = v.findViewById(R.id.lblIdentity)
            val lblHeadCategory: TextView = v.findViewById(R.id.lblHeadCategory)
            val lblMode: TextView = v.findViewById(R.id.lblMode)
            val lblAmount: TextView = v.findViewById(R.id.lblAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ledger_entry, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.lblDate.text = item.date
            holder.lblInvoiceNo.text = "#${item.invoiceNo}"
            holder.lblPartyName.text = item.name
            holder.lblIdentity.text = "Ref: ${item.identity}"
            holder.lblHeadCategory.text = item.categoryHead

            val modeType = if (item.mode.lowercase(Locale.getDefault()).contains("cash")) "Cash" else "Bank"

            if (item.isCredit) {
                holder.lblMode.text = "$modeType Inflow (Cr)"
                holder.lblAmount.text = String.format("+₹%,.2f", item.amount)
                holder.lblAmount.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                holder.lblMode.text = "$modeType Outflow (Dr)"
                holder.lblAmount.text = String.format("-₹%,.2f", item.amount)
                holder.lblAmount.setTextColor(Color.parseColor("#D32F2F"))
            }
        }

        override fun getItemCount() = list.size
    }
}