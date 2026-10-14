package com.sierratechnologies.finmac

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class BankDetailsActivity : AppCompatActivity() {

    private lateinit var viewHeaderBackground: View
    private lateinit var btnBack: ImageView
    private lateinit var lblSocietyName: TextView
    private lateinit var lblSocietyAddress: TextView
    private lateinit var lblRegistrationNo: TextView

    private lateinit var txtBankName: EditText
    private lateinit var txtHolderName: EditText
    private lateinit var txtAccNum: EditText
    private lateinit var txtConfirmAccNum: EditText
    private lateinit var txtIfscCode: EditText
    private lateinit var btnSubmit: MaterialButton
    private lateinit var btnClear: MaterialButton

    private lateinit var rvBankCards: RecyclerView
    private lateinit var loadingOverlay: FrameLayout

    private var activeBranchCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550"))
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bank_details)

        initViews()
        setupWindowInsets()

        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        activeBranchCode = sharedPref.getString("SOCIETY_CODE", "") ?: ""

        btnBack.setOnClickListener { finish() }
        btnSubmit.setOnClickListener { commitNewBankAccount() }
        btnClear.setOnClickListener { resetForm() }

        loadSocietyAndBankPipeline()
    }

    private fun initViews() {
        viewHeaderBackground = findViewById(R.id.viewHeaderBackground)
        btnBack = findViewById(R.id.btnBack)
        lblSocietyName = findViewById(R.id.lblSocietyName)
        lblSocietyAddress = findViewById(R.id.lblSocietyAddress)
        lblRegistrationNo = findViewById(R.id.lblRegistrationNo)

        txtBankName = findViewById(R.id.txtBankName)
        txtHolderName = findViewById(R.id.txtHolderName)
        txtAccNum = findViewById(R.id.txtAccNum)
        txtConfirmAccNum = findViewById(R.id.txtConfirmAccNum)
        txtIfscCode = findViewById(R.id.txtIfscCode)

        btnSubmit = findViewById(R.id.btnSubmit)
        btnClear = findViewById(R.id.btnClear)

        rvBankCards = findViewById(R.id.rvBankCards)
        loadingOverlay = findViewById(R.id.loadingOverlay)
    }

    private fun setupWindowInsets() {
        val baseHeaderHeightPx = (220 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            val headerParams = viewHeaderBackground.layoutParams
            headerParams.height = baseHeaderHeightPx + statusBarHeight
            viewHeaderBackground.layoutParams = headerParams

            val backParams = btnBack.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            backParams.topMargin = (20 * resources.displayMetrics.density).toInt() + statusBarHeight
            btnBack.layoutParams = backParams

            insets
        }
    }

    private fun loadSocietyAndBankPipeline() {
        if (activeBranchCode.isEmpty()) return

        val cachedAccounts = AppCache.getBankAccounts(this, activeBranchCode)
        if (cachedAccounts != null) {
            val mapAccounts = cachedAccounts.map { acc ->
                mapOf<String, Any>(
                    "bankName" to acc.bankName,
                    "accountNumber" to acc.accountNumber,
                    "accountHolder" to acc.accountHolder,
                    "ifscCode" to acc.ifscCode,
                    "currentBalance" to acc.currentBalance
                )
            }
            setupRecyclerView(mapAccounts)
        } else {
            loadingOverlay.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val branches = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", activeBranchCode) } }
                    .decodeList<BranchDto>()

                if (branches.isNotEmpty()) {
                    val branch = branches.first()
                    lblSocietyName.text = branch.name.uppercase()
                    lblSocietyAddress.text = "Address: ${branch.address ?: ""}"
                    lblRegistrationNo.text = "Reg No: ${branch.registrationNo ?: "REG-MH-26/X4911"}"
                }

                val accounts = SupabaseClientManager.postgrest.from("bank_accounts")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<BankAccountDto>()

                AppCache.saveBankAccounts(this@BankDetailsActivity, activeBranchCode, accounts)

                val mapAccounts = accounts.map { acc ->
                    mapOf<String, Any>(
                        "bankName" to acc.bankName,
                        "accountNumber" to acc.accountNumber,
                        "accountHolder" to acc.accountHolder,
                        "ifscCode" to acc.ifscCode,
                        "currentBalance" to acc.currentBalance
                    )
                }

                setupRecyclerView(mapAccounts)
            } catch (e: Exception) {
                if (cachedAccounts == null) {
                    Toast.makeText(this@BankDetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun setupRecyclerView(accounts: List<Map<String, Any>>) {
        rvBankCards.layoutManager = GridLayoutManager(this, 2)
        rvBankCards.adapter = BankCardsAdapter(accounts) { selectedAccount ->
            launchBankPassbookOverlayCard(selectedAccount)
        }
    }

    private fun commitNewBankAccount() {
        val bank = txtBankName.text.toString().trim()
        val holder = txtHolderName.text.toString().trim()
        val accNum = txtAccNum.text.toString().trim()
        val confirmAccNum = txtConfirmAccNum.text.toString().trim()
        val ifsc = txtIfscCode.text.toString().trim().uppercase()

        if (bank.isEmpty() || holder.isEmpty() || accNum.isEmpty() || ifsc.isEmpty()) {
            Toast.makeText(this, "Please fill in all mandatory fields (*)", Toast.LENGTH_SHORT).show()
            return
        }

        if (accNum != confirmAccNum) {
            Toast.makeText(this, "Account Number mismatch!", Toast.LENGTH_SHORT).show()
            return
        }

        loadingOverlay.visibility = View.VISIBLE

        val bankAccountDto = BankAccountDto(
            branchCode = activeBranchCode,
            accountNumber = accNum,
            bankName = bank,
            accountHolder = holder,
            ifscCode = ifsc
        )

        lifecycleScope.launch {
            try {
                SupabaseClientManager.postgrest.from("bank_accounts").upsert(bankAccountDto)
                Toast.makeText(this@BankDetailsActivity, "Bank Account Registered Successfully! ✅", Toast.LENGTH_SHORT).show()
                resetForm()
                loadSocietyAndBankPipeline()
            } catch (e: Exception) {
                Toast.makeText(this@BankDetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun launchBankPassbookOverlayCard(account: Map<String, Any>) {
        try {
            val bankTitle = (account["bankName"] as? String ?: "BANK").uppercase()
            val accNum = account["accountNumber"] as? String ?: ""
            val holder = account["accountHolder"] as? String ?: ""
            val ifsc = account["ifscCode"] as? String ?: ""

            val dialog = BottomSheetDialog(this)
            val dialogView = layoutInflater.inflate(R.layout.dialog_bank_passbook, null)
            dialog.setContentView(dialogView)

            val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
            val txtHolder = dialogView.findViewById<TextView>(R.id.txtPassbookHolder)
            val txtAccNo = dialogView.findViewById<TextView>(R.id.txtPassbookAccNo)
            val txtIfsc = dialogView.findViewById<TextView>(R.id.txtPassbookIfsc)
            val txtBalance = dialogView.findViewById<TextView>(R.id.txtPassbookBalance)
            val btnClose = dialogView.findViewById<ImageView>(R.id.btnCloseDialog)
            val btnDownload = dialogView.findViewById<MaterialButton>(R.id.btnDownloadStatement)

            txtTitle?.text = "$bankTitle - PASSBOOK DETAILS"
            txtHolder?.text = holder
            txtAccNo?.text = accNum
            txtIfsc?.text = ifsc
            txtBalance?.text = "Calculating live balance..."

            btnClose?.setOnClickListener { dialog.dismiss() }

            dialog.show()

            calculateLiveBankBalance(bankTitle) { calculatedBalance ->
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    txtBalance?.text = String.format("₹ %,.2f", calculatedBalance)
                    if (calculatedBalance < 0) {
                        txtBalance?.setTextColor(Color.RED)
                    } else {
                        txtBalance?.setTextColor(Color.parseColor("#1976D2"))
                    }

                    btnDownload?.setOnClickListener {
                        Toast.makeText(this, "Generating Statement PDF for $bankTitle...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun calculateLiveBankBalance(bankTitle: String, onResult: (Double) -> Unit) {
        if (activeBranchCode.isEmpty()) {
            onResult(0.0)
            return
        }

        lifecycleScope.launch {
            try {
                var runningSum = 0.0

                val receipts = SupabaseClientManager.postgrest.from("receipts")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ReceiptDto>()

                for (doc in receipts) {
                    val selectBank = doc.selectedBank ?: ""
                    if (selectBank.uppercase().contains(bankTitle)) {
                        runningSum += doc.totalFinalAmount
                    }
                }

                val payments = SupabaseClientManager.postgrest.from("payments")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<PaymentDto>()

                for (doc in payments) {
                    val selectBank = doc.selectedBank ?: ""
                    if (selectBank.uppercase().contains(bankTitle)) {
                        runningSum -= doc.totalFinalAmount
                    }
                }

                val contras = SupabaseClientManager.postgrest.from("contra_entries")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<ContraEntryDto>()

                for (doc in contras) {
                    val fromBank = doc.sourceAccount
                    val toBank = doc.destinationAccount
                    val amt = doc.amount

                    if (fromBank.uppercase().contains(bankTitle)) runningSum -= amt
                    if (toBank.uppercase().contains(bankTitle)) runningSum += amt
                }

                onResult(runningSum)
            } catch (e: Exception) {
                onResult(0.0)
            }
        }
    }

    private fun resetForm() {
        txtBankName.setText("")
        txtHolderName.setText("")
        txtAccNum.setText("")
        txtConfirmAccNum.setText("")
        txtIfscCode.setText("")
    }
}