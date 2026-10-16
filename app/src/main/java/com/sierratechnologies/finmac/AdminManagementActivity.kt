package com.sierratechnologies.finmac

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.util.Locale

class AdminManagementActivity : AppCompatActivity() {

    private lateinit var viewHeaderBackground: View
    private lateinit var btnBack: ImageView
    private lateinit var btnAddAdmin: MaterialButton
    private lateinit var btnViewMasterAuditLogs: MaterialButton
    private lateinit var lblSocietyName: TextView
    private lateinit var lblSocietyAddress: TextView

    private lateinit var txtSearchDeck: EditText
    private lateinit var rvAdminCards: RecyclerView
    private lateinit var loadingOverlay: FrameLayout

    private var branchCode = ""
    private var activeUsername = "Admin"

    data class StaffUser(
        val username: String,
        val fullName: String,
        val contactNo: String,
        val email: String,
        val remarks: String,
        val password: String
    )

    private val masterCache = ArrayList<StaffUser>()
    private val displayedList = ArrayList<StaffUser>()
    private lateinit var adapter: StaffAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")))
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_management)

        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        branchCode = intent.getStringExtra("SOCIETY_CODE") ?: sharedPref.getString("SOCIETY_CODE", "")?.trim() ?: ""
        activeUsername = sharedPref.getString("USERNAME", "Admin") ?: "Admin"

        if (branchCode.isBlank()) {
            Toast.makeText(this, "Session invalid. Please re-login.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initViews()
        setupWindowInsets()
        setupRecyclerView()

        btnBack.setOnClickListener { finish() }
        btnAddAdmin.setOnClickListener { openAdminFormModalSheet(null) }
        btnViewMasterAuditLogs.setOnClickListener { showMasterChangeLogsSheet() }

        txtSearchDeck.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterStaffList(s.toString().trim()) }
        })

        loadStaffDataPipeline()
    }

    private fun initViews() {
        viewHeaderBackground = findViewById(R.id.viewHeaderBackground)
        btnBack = findViewById(R.id.btnBack)
        btnAddAdmin = findViewById(R.id.btnAddAdmin)
        btnViewMasterAuditLogs = findViewById(R.id.btnViewMasterAuditLogs)
        lblSocietyName = findViewById(R.id.lblSocietyName)
        lblSocietyAddress = findViewById(R.id.lblSocietyAddress)

        txtSearchDeck = findViewById(R.id.txtSearchDeck)
        rvAdminCards = findViewById(R.id.rvAdminCards)
        loadingOverlay = findViewById(R.id.loadingOverlay)
    }

    private fun showMasterChangeLogsSheet() {
        val sheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_cashbook_full_view_sheet, null)
        sheetDialog.setContentView(sheetView)

        val lblTitle = sheetView.findViewById<TextView>(R.id.lblFullViewSheetTitle)
        val lblSubtitle = sheetView.findViewById<TextView>(R.id.lblFullViewSheetSubtitle)
        val btnClose = sheetView.findViewById<ImageView>(R.id.btnCloseFullViewSheet)
        val txtSearch = sheetView.findViewById<EditText>(R.id.txtFullViewSearch)
        val btnPdf = sheetView.findViewById<MaterialButton>(R.id.btnFullExportPdf)
        val btnCsv = sheetView.findViewById<MaterialButton>(R.id.btnFullExportCsv)
        val rvTable = sheetView.findViewById<RecyclerView>(R.id.rvFullStatementTable)

        btnClose?.setOnClickListener { sheetDialog.dismiss() }
        btnPdf?.visibility = View.GONE
        btnCsv?.visibility = View.GONE

        lblTitle?.text = "📋 Master Revision Audit Logs"
        lblSubtitle?.text = "Fetching modification notes across branches..."

        rvTable?.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            try {
                val auditLogs = SupabaseClientManager.postgrest.from("voucher_audit_logs")
                    .select()
                    .decodeList<VoucherAuditLogDto>()

                lblSubtitle?.text = "Total Audit Logs Recorded: ${auditLogs.size}"

                class AuditLogsAdapter(private val logs: List<VoucherAuditLogDto>) :
                    RecyclerView.Adapter<AuditLogsAdapter.VH>() {

                    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                        val txtInvoice: TextView = v.findViewById(R.id.txtAuditInvoice)
                        val txtTimestamp: TextView = v.findViewById(R.id.txtAuditTimestamp)
                        val txtUser: TextView = v.findViewById(R.id.txtAuditUser)
                        val txtReasonNote: TextView = v.findViewById(R.id.txtAuditReasonNote)
                    }

                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_audit_log_row, parent, false)
                        return VH(v)
                    }

                    override fun onBindViewHolder(holder: VH, position: Int) {
                        val item = logs[position]
                        holder.txtInvoice.text = "#${item.invoiceNo} (${item.branchCode})"
                        holder.txtTimestamp.text = item.timestamp
                        holder.txtUser.text = "👤 Modified By: ${item.modifiedBy}"
                        holder.txtReasonNote.text = "✏️ Reason: ${item.reasonNote ?: "N/A"}"

                        holder.itemView.setOnClickListener {
                            showDetailedAuditLogModalSheet(item)
                        }
                    }

                    override fun getItemCount() = logs.size
                }

                val adapter = AuditLogsAdapter(auditLogs)
                rvTable?.adapter = adapter

                txtSearch?.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val q = s.toString().trim().lowercase(Locale.getDefault())
                        val filtered = auditLogs.filter {
                            it.invoiceNo.lowercase().contains(q) ||
                                    it.branchCode.lowercase().contains(q) ||
                                    it.modifiedBy.lowercase().contains(q) ||
                                    (it.reasonNote?.lowercase()?.contains(q) == true)
                        }
                        rvTable?.adapter = AuditLogsAdapter(filtered)
                    }
                })
            } catch (e: Exception) {
                lblSubtitle?.text = "Failed to load audit logs: ${e.message}"
            }
        }

        sheetDialog.show()
    }

    private fun showDetailedAuditLogModalSheet(log: VoucherAuditLogDto) {
        val sheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.layout_recent_transactions_sheet, null)
        sheetDialog.setContentView(sheetView)

        val txtSearch = sheetView.findViewById<EditText>(R.id.txtSearchRecent)
        txtSearch?.visibility = View.GONE

        val rvLogs = sheetView.findViewById<RecyclerView>(R.id.rvRecentTransactions)
        rvLogs?.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            try {
                var sName = "Branch ${log.branchCode}"
                var sReg = "REG-MH-26/X4911"
                var sAddr = "Head Office"

                try {
                    val branchList = SupabaseClientManager.postgrest.from("branches")
                        .select { filter { eq("code", log.branchCode) } }
                        .decodeList<BranchDto>()
                    if (branchList.isNotEmpty()) {
                        val b = branchList.first()
                        sName = b.name
                        sReg = b.registrationNo ?: sReg
                        sAddr = b.address ?: sAddr
                    }
                } catch (_: Exception) {}

                var partyStr = "N/A"
                var categoryStr = "N/A"
                var modeStr = "N/A"
                var amountStr = "N/A"

                try {
                    val receipts = SupabaseClientManager.postgrest.from("receipts")
                        .select { filter { eq("branch_code", log.branchCode); eq("invoice_no", log.invoiceNo) } }
                        .decodeList<ReceiptDto>()
                    if (receipts.isNotEmpty()) {
                        val r = receipts.first()
                        partyStr = "${r.partyName} (${r.flatNo ?: "N/A"})"
                        categoryStr = r.categoryHead
                        modeStr = r.paymentMode ?: "Cash"
                        amountStr = String.format("₹%,.2f", r.totalFinalAmount)
                    } else {
                        val payments = SupabaseClientManager.postgrest.from("payments")
                            .select { filter { eq("branch_code", log.branchCode); eq("invoice_no", log.invoiceNo) } }
                            .decodeList<PaymentDto>()
                        if (payments.isNotEmpty()) {
                            val p = payments.first()
                            partyStr = "${p.partyName} (${p.vendorCode ?: "N/A"})"
                            categoryStr = p.categoryHead
                            modeStr = p.paymentMode ?: "Cash"
                            amountStr = String.format("₹%,.2f", p.totalFinalAmount)
                        } else {
                            val contra = SupabaseClientManager.postgrest.from("contra_entries")
                                .select { filter { eq("branch_code", log.branchCode); eq("voucher_no", log.invoiceNo) } }
                                .decodeList<ContraEntryDto>()
                            if (contra.isNotEmpty()) {
                                val c = contra.first()
                                partyStr = c.partyName
                                categoryStr = "Contra Transfer"
                                modeStr = "${c.sourceAccount} -> ${c.destinationAccount}"
                                amountStr = String.format("₹%,.2f", c.amount)
                            }
                        }
                    }
                } catch (_: Exception) {}

                val details = listOf(
                    "Society Branch Name" to "$sName (${log.branchCode})",
                    "Society Registration No" to sReg,
                    "Voucher Invoice Number" to "#${log.invoiceNo}",
                    "Modified By User" to log.modifiedBy,
                    "Modification Timestamp" to log.timestamp,
                    "Modification Reason Note" to (log.reasonNote ?: "N/A"),
                    "Transaction Party Name" to partyStr,
                    "Account Head Category" to categoryStr,
                    "Payment Clearing Mode" to modeStr,
                    "Total Valuation Amount" to amountStr
                )

                class AuditDetailAdapter : RecyclerView.Adapter<AuditDetailAdapter.VH>() {
                    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                        val txtLabel: TextView = v.findViewById(android.R.id.text1)
                        val txtValue: TextView = v.findViewById(android.R.id.text2)
                    }
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
                        return VH(v)
                    }
                    override fun onBindViewHolder(holder: VH, position: Int) {
                        val (lbl, valStr) = details[position]
                        holder.txtLabel.text = lbl
                        holder.txtLabel.setTextColor(Color.parseColor("#64748B"))
                        holder.txtLabel.textSize = 12f

                        holder.txtValue.text = valStr
                        holder.txtValue.setTextColor(if (lbl.contains("Reason")) Color.parseColor("#92400E") else Color.parseColor("#0F172A"))
                        holder.txtValue.textSize = 14f
                    }
                    override fun getItemCount() = details.size
                }

                rvLogs?.adapter = AuditDetailAdapter()
            } catch (e: Exception) {
                Toast.makeText(this@AdminManagementActivity, "Error opening audit log detail: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        sheetDialog.show()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(viewHeaderBackground) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun setupRecyclerView() {
        rvAdminCards.layoutManager = LinearLayoutManager(this)
        adapter = StaffAdapter(displayedList)
        rvAdminCards.adapter = adapter
    }

    private fun loadStaffDataPipeline() {
        loadingOverlay.visibility = View.VISIBLE
        masterCache.clear()

        lifecycleScope.launch {
            try {
                val branches = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", branchCode) } }
                    .decodeList<BranchDto>()

                if (branches.isNotEmpty()) {
                    val branch = branches.first()
                    lblSocietyName.text = branch.name.uppercase(Locale.getDefault())
                    lblSocietyAddress.text = "Address: ${branch.address ?: "Head Office"}"
                }

                val staffList = SupabaseClientManager.postgrest.from("staff_users")
                    .select { filter { eq("branch_code", branchCode) } }
                    .decodeList<StaffUserDto>()

                for (staff in staffList) {
                    masterCache.add(
                        StaffUser(
                            username = staff.username,
                            fullName = staff.fullName,
                            contactNo = staff.contactNo ?: "N/A",
                            email = staff.email ?: "N/A",
                            remarks = staff.remarks ?: "N/A",
                            password = staff.password
                        )
                    )
                }

                filterStaffList(txtSearchDeck.text.toString().trim())
            } catch (e: Exception) {
                Toast.makeText(this@AdminManagementActivity, "Error loading staff: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun filterStaffList(query: String) {
        displayedList.clear()
        if (query.isEmpty()) {
            displayedList.addAll(masterCache)
        } else {
            val q = query.lowercase(Locale.getDefault())
            for (user in masterCache) {
                if (user.fullName.lowercase().contains(q) || user.username.lowercase().contains(q)) {
                    displayedList.add(user)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun openAdminFormModalSheet(existingUser: StaffUser?) {
        val sheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.layout_admin_form_sheet, null)
        sheetDialog.setContentView(sheetView)

        val lblFormHeader = sheetView.findViewById<TextView>(R.id.lblFormHeader)
        val txtUserCode = sheetView.findViewById<TextInputEditText>(R.id.txtUserCode)
        val txtFullName = sheetView.findViewById<TextInputEditText>(R.id.txtFullName)
        val txtPassword = sheetView.findViewById<TextInputEditText>(R.id.txtPassword)
        val txtConfirmPassword = sheetView.findViewById<TextInputEditText>(R.id.txtConfirmPassword)
        val txtContact = sheetView.findViewById<TextInputEditText>(R.id.txtContact)
        val txtEmail = sheetView.findViewById<TextInputEditText>(R.id.txtEmail)
        val txtRemarks = sheetView.findViewById<TextInputEditText>(R.id.txtRemarks)
        val btnSubmit = sheetView.findViewById<MaterialButton>(R.id.btnSubmit)

        if (existingUser != null) {
            lblFormHeader.text = "Update Credentials: ${existingUser.username}"
            txtUserCode.setText(existingUser.username)
            txtUserCode.isEnabled = false
            txtFullName.setText(existingUser.fullName)
            txtPassword.setText(existingUser.password)
            txtConfirmPassword.setText(existingUser.password)
            txtContact.setText(existingUser.contactNo)
            txtEmail.setText(existingUser.email)
            txtRemarks.setText(existingUser.remarks)
            btnSubmit.text = "Save Credentials Revision"
        }

        btnSubmit.setOnClickListener {
            val uName = txtUserCode.text.toString().trim().lowercase(Locale.getDefault())
            val fName = txtFullName.text.toString().trim()
            val pass = txtPassword.text.toString().trim()
            val confirm = txtConfirmPassword.text.toString().trim()
            val phone = txtContact.text.toString().trim()
            val mail = txtEmail.text.toString().trim()
            val rem = txtRemarks.text.toString().trim()

            if (uName.isEmpty() || fName.isEmpty() || pass.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Please fill required fields (*)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (pass != confirm) {
                Toast.makeText(this, "Password confirmation mismatch!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sheetDialog.dismiss()
            loadingOverlay.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    val staffDto = StaffUserDto(
                        branchCode = branchCode,
                        username = uName,
                        password = pass,
                        fullName = fName,
                        contactNo = if (phone.isEmpty()) "N/A" else phone,
                        email = if (mail.isEmpty()) "N/A" else mail,
                        remarks = if (rem.isEmpty()) "N/A" else rem
                    )

                    SupabaseClientManager.postgrest.from("staff_users").upsert(staffDto)
                    Toast.makeText(this@AdminManagementActivity, "Staff profile registered cleanly! ✅", Toast.LENGTH_SHORT).show()
                    loadStaffDataPipeline()
                } catch (e: Exception) {
                    loadingOverlay.visibility = View.GONE
                    Toast.makeText(this@AdminManagementActivity, "Cloud Sync Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        sheetDialog.show()
    }

    private fun executeAdminAccountRevocation(targetUser: String) {
        if (targetUser.equals(activeUsername, ignoreCase = true)) {
            AlertDialog.Builder(this)
                .setTitle("Self Termination Blocked")
                .setMessage("Security Restriction: You cannot revoke or purge your active operating profile.")
                .setPositiveButton("Understood") { d, _ -> d.dismiss() }
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Purge Verification Prompt")
            .setMessage("Are you certain you want to purge administrator credentials for [$targetUser] permanently?")
            .setPositiveButton("Purge Access") { d, _ ->
                d.dismiss()
                loadingOverlay.visibility = View.VISIBLE

                lifecycleScope.launch {
                    try {
                        SupabaseClientManager.postgrest.from("staff_users").delete {
                            filter {
                                eq("branch_code", branchCode)
                                eq("username", targetUser)
                            }
                        }
                        Toast.makeText(this@AdminManagementActivity, "Staff credentials revoked successfully.", Toast.LENGTH_SHORT).show()
                        loadStaffDataPipeline()
                    } catch (e: Exception) {
                        loadingOverlay.visibility = View.GONE
                        Toast.makeText(this@AdminManagementActivity, "Error revoking profile: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    inner class StaffAdapter(private val list: List<StaffUser>) :
        RecyclerView.Adapter<StaffAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val lblFullName: TextView = v.findViewById(R.id.lblFullName)
            val lblUsername: TextView = v.findViewById(R.id.lblUsername)
            val lblContact: TextView = v.findViewById(R.id.lblContact)
            val lblEmail: TextView = v.findViewById(R.id.lblEmail)
            val lblRemarks: TextView = v.findViewById(R.id.lblRemarks)
            val btnEdit: MaterialButton = v.findViewById(R.id.btnEdit)
            val btnRevoke: MaterialButton = v.findViewById(R.id.btnRevoke)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.lblFullName.text = item.fullName.uppercase(Locale.getDefault())
            holder.lblUsername.text = "ID: ${item.username}"
            holder.lblContact.text = "Contact: ${item.contactNo}"
            holder.lblEmail.text = "Email: ${item.email}"
            holder.lblRemarks.text = "Note: ${item.remarks}"

            holder.btnEdit.setOnClickListener { openAdminFormModalSheet(item) }
            holder.btnRevoke.setOnClickListener { executeAdminAccountRevocation(item.username) }
        }

        override fun getItemCount() = list.size
    }
}