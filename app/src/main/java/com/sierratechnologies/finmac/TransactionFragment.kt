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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.util.Locale

class TransactionFragment : Fragment() {

    enum class TxType { RECEIPT, PAYMENT, CONTRA }

    data class TransactionItem(
        val date: String,
        val voucherNo: String,
        val partyName: String,
        val narration: String,
        val category: String,
        val amount: Double,
        val type: TxType
    )

    private lateinit var txtSearchTx: EditText
    private lateinit var spnTxScope: Spinner
    private lateinit var spnSortOptions: Spinner
    private lateinit var rvTransactionStream: RecyclerView
    private lateinit var loadingOverlay: FrameLayout

    private lateinit var lblTotalReceipts: TextView
    private lateinit var lblTotalPayments: TextView
    private lateinit var lblNetFlow: TextView

    private var activeBranchCode = ""

    private val allTransactions = ArrayList<TransactionItem>()
    private val filteredTransactions = ArrayList<TransactionItem>()
    private lateinit var adapter: TransactionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_transaction, container, false)

        val ctx = context ?: container?.context ?: inflater.context
        val sharedPref = ctx.getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        activeBranchCode = sharedPref.getString("SOCIETY_CODE", "")?.trim() ?: ""

        initViews(root)
        setupRecyclerView()
        setupSpinners()
        setupSearchWatcher()

        if (activeBranchCode.isNotEmpty()) {
            loadAllTransactionsPipeline()
        } else {
            Toast.makeText(ctx, "Branch session uninitialized", Toast.LENGTH_SHORT).show()
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val viewHeaderBackground = view.findViewById<View>(R.id.viewHeaderBackground)
        val rvTransactionStream = view.findViewById<RecyclerView>(R.id.rvTransactionStream)

        if (viewHeaderBackground != null && rvTransactionStream != null) {
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

                viewHeaderBackground.setPadding(
                    viewHeaderBackground.paddingLeft,
                    statusBarHeight,
                    viewHeaderBackground.paddingRight,
                    viewHeaderBackground.paddingBottom
                )

                val extraPaddingPx = (56 * resources.displayMetrics.density).toInt()
                rvTransactionStream.setPadding(
                    rvTransactionStream.paddingLeft,
                    rvTransactionStream.paddingTop,
                    rvTransactionStream.paddingRight,
                    navBarHeight + extraPaddingPx
                )

                insets
            }
        }
    }

    private fun initViews(v: View) {
        txtSearchTx = v.findViewById(R.id.txtSearchTx)
        spnTxScope = v.findViewById(R.id.spnTxScope)
        spnSortOptions = v.findViewById(R.id.spnSortOptions)
        rvTransactionStream = v.findViewById(R.id.rvTransactionStream)
        loadingOverlay = v.findViewById(R.id.loadingOverlay)

        lblTotalReceipts = v.findViewById(R.id.lblTotalReceipts)
        lblTotalPayments = v.findViewById(R.id.lblTotalPayments)
        lblNetFlow = v.findViewById(R.id.lblNetFlow)
    }

    private fun setupRecyclerView() {
        rvTransactionStream.layoutManager = LinearLayoutManager(context)
        adapter = TransactionAdapter(filteredTransactions)
        rvTransactionStream.adapter = adapter
    }

    private fun setupSpinners() {
        val ctx = context ?: return
        val scopeOptions = arrayOf("All Entries", "Receipts Only", "Payments Only", "Contra Shunts")
        spnTxScope.adapter = ArrayAdapter(ctx, R.layout.spinner_item, scopeOptions).apply {
            setDropDownViewResource(R.layout.spinner_item)
        }

        val sortOptions = arrayOf("Sort: Date (Newest)", "Sort: Date (Oldest)", "Sort: Amount (High-Low)", "Sort: Amount (Low-High)")
        spnSortOptions.adapter = ArrayAdapter(ctx, R.layout.spinner_item, sortOptions).apply {
            setDropDownViewResource(R.layout.spinner_item)
        }

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                applyFiltersAndSorting()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        spnTxScope.onItemSelectedListener = listener
        spnSortOptions.onItemSelectedListener = listener
    }

    private fun setupSearchWatcher() {
        txtSearchTx.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFiltersAndSorting() }
        })
    }

    private fun loadAllTransactionsPipeline() {
        val ctx = context ?: return
        allTransactions.clear()

        val cachedReceipts = AppCache.getReceipts(ctx, activeBranchCode)
        val cachedPayments = AppCache.getPayments(ctx, activeBranchCode)
        val cachedContra = AppCache.getContra(ctx, activeBranchCode)

        var hasCache = false
        if (cachedReceipts != null && cachedPayments != null && cachedContra != null) {
            hasCache = true
            for (doc in cachedReceipts) {
                allTransactions.add(
                    TransactionItem(
                        date = doc.date,
                        voucherNo = doc.invoiceNo,
                        partyName = doc.partyName,
                        narration = "Payment via ${doc.paymentMode ?: "Cash"}",
                        category = doc.categoryHead,
                        amount = doc.totalFinalAmount,
                        type = TxType.RECEIPT
                    )
                )
            }
            for (doc in cachedPayments) {
                allTransactions.add(
                    TransactionItem(
                        date = doc.date,
                        voucherNo = doc.invoiceNo,
                        partyName = doc.partyName,
                        narration = "Disbursement via ${doc.paymentMode ?: "Cash"}",
                        category = doc.categoryHead,
                        amount = doc.totalFinalAmount,
                        type = TxType.PAYMENT
                    )
                )
            }
            for (doc in cachedContra) {
                allTransactions.add(
                    TransactionItem(
                        date = doc.date,
                        voucherNo = doc.voucherNo,
                        partyName = doc.partyName,
                        narration = "Transfer: ${doc.sourceAccount} ➔ ${doc.destinationAccount}",
                        category = "Contra",
                        amount = doc.amount,
                        type = TxType.CONTRA
                    )
                )
            }
            applyFiltersAndSorting()
        } else {
            loadingOverlay.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val receipts = SupabaseClientManager.postgrest.from("receipts")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ReceiptDto>()

                AppCache.saveReceipts(ctx, activeBranchCode, receipts)

                val payments = SupabaseClientManager.postgrest.from("payments")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<PaymentDto>()

                AppCache.savePayments(ctx, activeBranchCode, payments)

                val contras = SupabaseClientManager.postgrest.from("contra_entries")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ContraEntryDto>()

                AppCache.saveContra(ctx, activeBranchCode, contras)

                allTransactions.clear()
                for (doc in receipts) {
                    allTransactions.add(
                        TransactionItem(
                            date = doc.date,
                            voucherNo = doc.invoiceNo,
                            partyName = doc.partyName,
                            narration = "Payment via ${doc.paymentMode ?: "Cash"}",
                            category = doc.categoryHead,
                            amount = doc.totalFinalAmount,
                            type = TxType.RECEIPT
                        )
                    )
                }

                for (doc in payments) {
                    allTransactions.add(
                        TransactionItem(
                            date = doc.date,
                            voucherNo = doc.invoiceNo,
                            partyName = doc.partyName,
                            narration = "Disbursement via ${doc.paymentMode ?: "Cash"}",
                            category = doc.categoryHead,
                            amount = doc.totalFinalAmount,
                            type = TxType.PAYMENT
                        )
                    )
                }

                for (doc in contras) {
                    allTransactions.add(
                        TransactionItem(
                            date = doc.date,
                            voucherNo = doc.voucherNo,
                            partyName = doc.partyName,
                            narration = "Transfer: ${doc.sourceAccount} ➔ ${doc.destinationAccount}",
                            category = "Contra",
                            amount = doc.amount,
                            type = TxType.CONTRA
                        )
                    )
                }

                applyFiltersAndSorting()
            } catch (e: Exception) {
                if (!hasCache) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun applyFiltersAndSorting() {
        filteredTransactions.clear()
        val query = txtSearchTx.text.toString().trim().lowercase(Locale.getDefault())
        val scopeIndex = spnTxScope.selectedItemPosition

        var sumReceipts = 0.0
        var sumPayments = 0.0

        for (item in allTransactions) {
            val matchesScope = when (scopeIndex) {
                1 -> item.type == TxType.RECEIPT
                2 -> item.type == TxType.PAYMENT
                3 -> item.type == TxType.CONTRA
                else -> true
            }

            val matchesQuery = query.isEmpty() ||
                    item.voucherNo.lowercase().contains(query) ||
                    item.partyName.lowercase().contains(query) ||
                    item.narration.lowercase().contains(query) ||
                    item.category.lowercase().contains(query)

            if (matchesScope && matchesQuery) {
                filteredTransactions.add(item)
                if (item.type == TxType.RECEIPT) sumReceipts += item.amount
                else if (item.type == TxType.PAYMENT) sumPayments += item.amount
            }
        }

        when (spnSortOptions.selectedItemPosition) {
            0 -> filteredTransactions.sortByDescending { it.date }
            1 -> filteredTransactions.sortBy { it.date }
            2 -> filteredTransactions.sortByDescending { it.amount }
            3 -> filteredTransactions.sortBy { it.amount }
        }

        adapter.notifyDataSetChanged()

        lblTotalReceipts.text = String.format("₹%,.2f", sumReceipts)
        lblTotalPayments.text = String.format("₹%,.2f", sumPayments)
        lblNetFlow.text = String.format("₹%,.2f", sumReceipts - sumPayments)
    }

    inner class TransactionAdapter(private val list: List<TransactionItem>) :
        RecyclerView.Adapter<TransactionAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val lblTxTypeBadge: TextView = v.findViewById(R.id.lblTxTypeBadge)
            val lblDate: TextView = v.findViewById(R.id.lblDate)
            val lblVoucherNo: TextView = v.findViewById(R.id.lblVoucherNo)
            val lblPartyName: TextView = v.findViewById(R.id.lblPartyName)
            val lblNarration: TextView = v.findViewById(R.id.lblNarration)
            val lblCategory: TextView = v.findViewById(R.id.lblCategory)
            val lblAmount: TextView = v.findViewById(R.id.lblAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.lblDate.text = item.date
            holder.lblVoucherNo.text = "#${item.voucherNo}"
            holder.lblPartyName.text = item.partyName
            holder.lblNarration.text = item.narration
            holder.lblCategory.text = "Category: ${item.category}"

            when (item.type) {
                TxType.RECEIPT -> {
                    holder.lblTxTypeBadge.text = "RECEIPT"
                    holder.lblTxTypeBadge.setBackgroundColor(Color.parseColor("#DCFCE7"))
                    holder.lblTxTypeBadge.setTextColor(Color.parseColor("#166534"))

                    holder.lblAmount.text = String.format("+₹%,.2f", item.amount)
                    holder.lblAmount.setTextColor(Color.parseColor("#16A34A"))
                }
                TxType.PAYMENT -> {
                    holder.lblTxTypeBadge.text = "PAYMENT"
                    holder.lblTxTypeBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
                    holder.lblTxTypeBadge.setTextColor(Color.parseColor("#991B1B"))

                    holder.lblAmount.text = String.format("-₹%,.2f", item.amount)
                    holder.lblAmount.setTextColor(Color.parseColor("#DC2626"))
                }
                TxType.CONTRA -> {
                    holder.lblTxTypeBadge.text = "CONTRA"
                    holder.lblTxTypeBadge.setBackgroundColor(Color.parseColor("#E0F2FE"))
                    holder.lblTxTypeBadge.setTextColor(Color.parseColor("#075985"))

                    holder.lblAmount.text = String.format("₹%,.2f", item.amount)
                    holder.lblAmount.setTextColor(Color.parseColor("#0284C7"))
                }
            }
        }

        override fun getItemCount() = list.size
    }
}