package com.sierratechnologies.finmac

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
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
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MemberDetailsActivity : AppCompatActivity() {

    private val targetBranchCode by lazy { Util.Session.activeBranchCode ?: "" }

    private lateinit var rvCardsGridDeck: RecyclerView
    private lateinit var etDeckSearch: EditText
    private lateinit var cmbSortOrder: Spinner
    private lateinit var progressOverlay: FrameLayout
    private lateinit var txtHeaderTitle: TextView
    private lateinit var txtHeaderSubtitle: TextView
    private lateinit var txtTotalMembersCount: TextView

    private val membersMasterCache = mutableListOf<MemberDto>()
    private val filteredDisplayList = mutableListOf<MemberDto>()
    private lateinit var adapter: MemberDeckAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_member_details)

        val rootView = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            findViewById<View>(R.id.headerPlacard)?.setPadding(0, statusBarHeight, 0, 0)
            view.setPadding(0, 0, 0, navBarHeight)
            insets
        }

        initViews()
        setupSortSpinner()
        setupRecyclerViewGrid()
        setupSearch()

        fetchMembersFromCloud()
    }

    var isSelectionMode = false
    val selectedFlatNos = mutableSetOf<String>()

    private lateinit var selectionBar: View
    private lateinit var chkSelectAll: android.widget.CheckBox
    private lateinit var lblSelectedCount: TextView
    private lateinit var btnDeleteSelectedMembers: MaterialButton
    private lateinit var btnCancelSelection: ImageButton

    private val csvPickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            importMembersFromCsvUri(uri)
        }
    }

    private fun initViews() {
        progressOverlay = findViewById(R.id.progressOverlay)
        txtHeaderTitle = findViewById(R.id.txtHeaderTitle)
        txtHeaderSubtitle = findViewById(R.id.txtHeaderSubtitle)
        txtTotalMembersCount = findViewById(R.id.txtTotalMembersCount)
        etDeckSearch = findViewById(R.id.etDeckSearch)
        cmbSortOrder = findViewById(R.id.cmbSortOrder)
        rvCardsGridDeck = findViewById(R.id.rvCardsGridDeck)

        findViewById<ImageButton>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.btnAddNewMember)?.setOnClickListener { openMemberCrudBottomSheet(null) }
        findViewById<MaterialButton>(R.id.btnImportCsv)?.setOnClickListener { showCsvImportGuidanceDialog() }
        findViewById<MaterialButton>(R.id.btnExportCsv)?.setOnClickListener { showExportFormatChoiceDialog() }

        initSelectionBar()
    }

    private fun initSelectionBar() {
        selectionBar = findViewById(R.id.selectionBar)
        chkSelectAll = findViewById(R.id.chkSelectAll)
        lblSelectedCount = findViewById(R.id.lblSelectedCount)
        btnDeleteSelectedMembers = findViewById(R.id.btnDeleteSelectedMembers)
        btnCancelSelection = findViewById(R.id.btnCancelSelection)

        chkSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectAllMembers()
            } else if (selectedFlatNos.size == filteredDisplayList.size) {
                deselectAllMembers()
            }
        }

        btnDeleteSelectedMembers.setOnClickListener {
            if (selectedFlatNos.isNotEmpty()) {
                confirmDeleteSelectedMembers()
            } else {
                Toast.makeText(this, "No members selected.", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancelSelection.setOnClickListener {
            exitSelectionMode()
        }
    }

    fun enterSelectionMode(initialFlatNo: String? = null) {
        isSelectionMode = true
        selectionBar.visibility = View.VISIBLE
        if (initialFlatNo != null) {
            selectedFlatNos.add(initialFlatNo)
        }
        updateSelectionBarUI()
        adapter.notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedFlatNos.clear()
        selectionBar.visibility = View.GONE
        chkSelectAll.isChecked = false
        adapter.notifyDataSetChanged()
    }

    fun toggleMemberSelection(flatNo: String) {
        if (selectedFlatNos.contains(flatNo)) {
            selectedFlatNos.remove(flatNo)
        } else {
            selectedFlatNos.add(flatNo)
        }
        if (selectedFlatNos.isEmpty()) {
            exitSelectionMode()
        } else {
            updateSelectionBarUI()
            adapter.notifyDataSetChanged()
        }
    }

    fun selectAllMembers() {
        selectedFlatNos.clear()
        for (m in filteredDisplayList) {
            selectedFlatNos.add(m.flatNoId)
        }
        updateSelectionBarUI()
        adapter.notifyDataSetChanged()
    }

    fun deselectAllMembers() {
        selectedFlatNos.clear()
        updateSelectionBarUI()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionBarUI() {
        lblSelectedCount.text = "${selectedFlatNos.size} Selected"
        chkSelectAll.isChecked = selectedFlatNos.isNotEmpty() && selectedFlatNos.size == filteredDisplayList.size
    }

    private fun confirmDeleteSelectedMembers() {
        val count = selectedFlatNos.size
        AlertDialog.Builder(this)
            .setTitle("Confirm Bulk Purge")
            .setMessage("Are you sure you want to permanently delete $count selected member(s)?")
            .setPositiveButton("Purge Selected") { _, _ ->
                deleteSelectedMembers()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelectedMembers() {
        val count = selectedFlatNos.size
        progressOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                for (flatId in selectedFlatNos) {
                    SupabaseClientManager.postgrest.from("members").delete {
                        filter {
                            eq("branch_code", targetBranchCode)
                            eq("flat_no_id", flatId)
                        }
                    }
                }
                Toast.makeText(this@MemberDetailsActivity, "Purged $count selected member(s) successfully! ✅", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                fetchMembersFromCloud()
            } catch (e: Exception) {
                progressOverlay.visibility = View.GONE
                Toast.makeText(this@MemberDetailsActivity, "Error deleting members: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSortSpinner() {
        val options = arrayOf("Sort: Flat (A-Z)", "Sort: Flat (Z-A)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        cmbSortOrder.adapter = adapter
        cmbSortOrder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                runLocalSearchAndSortPass()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerViewGrid() {
        rvCardsGridDeck.layoutManager = GridLayoutManager(this, 1)
        adapter = MemberDeckAdapter(filteredDisplayList) { selectedMember ->
            openMemberDetailViewSheet(selectedMember)
        }
        rvCardsGridDeck.adapter = adapter
    }

    private fun setupSearch() {
        etDeckSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { runLocalSearchAndSortPass() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun fetchMembersFromCloud() {
        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        val savedName = sharedPref.getString("SOCIETY_NAME", "") ?: ""
        val savedAddress = sharedPref.getString("SOCIETY_ADDRESS", "") ?: ""

        val cachedBranches = AppCache.getBranches(this)
        val cachedBranch = cachedBranches?.find { it.code.equals(targetBranchCode, ignoreCase = true) }

        val bName = cachedBranch?.name?.ifBlank { null } ?: savedName.ifBlank { Util.Session.activeBranchName }
        val bAddr = cachedBranch?.address?.ifBlank { null } ?: savedAddress.ifBlank { "N/A" }

        if (bName.isNotBlank()) {
            val titleStr = bName.uppercase(Locale.getDefault())
            txtHeaderTitle.text = if (Util.Session.isSuperAdmin || targetBranchCode.equals("SUPERADMIN", ignoreCase = true)) "$titleStr 👑 SUPERADMIN" else titleStr
            txtHeaderSubtitle.text = "Address: $bAddr"
        }

        val cachedMembers = AppCache.getMembers(this, targetBranchCode)
        if (cachedMembers != null) {
            progressOverlay.visibility = View.GONE
            membersMasterCache.clear()
            membersMasterCache.addAll(cachedMembers)
            txtTotalMembersCount.text = "Total Registered Members: ${membersMasterCache.size}"
            runLocalSearchAndSortPass()
        } else {
            progressOverlay.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val branches = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", targetBranchCode) } }
                    .decodeList<BranchDto>()

                if (branches.isNotEmpty()) {
                    val branch = branches.first()
                    val titleStr = branch.name.uppercase(Locale.getDefault())
                    txtHeaderTitle.text = if (Util.Session.isSuperAdmin || targetBranchCode.equals("SUPERADMIN", ignoreCase = true)) "$titleStr 👑 SUPERADMIN" else titleStr
                    txtHeaderSubtitle.text = "Address: ${branch.address ?: "N/A"}"
                }

                val members = SupabaseClientManager.postgrest.from("members")
                    .select { filter { eq("branch_code", targetBranchCode) } }
                    .decodeList<MemberDto>()

                AppCache.saveMembers(this@MemberDetailsActivity, targetBranchCode, members)
                membersMasterCache.clear()
                membersMasterCache.addAll(members)
                txtTotalMembersCount.text = "Total Registered Members: ${membersMasterCache.size}"
                runLocalSearchAndSortPass()
            } catch (e: Exception) {
                if (cachedMembers == null) {
                    Toast.makeText(this@MemberDetailsActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                progressOverlay.visibility = View.GONE
            }
        }
    }

    private fun runLocalSearchAndSortPass() {
        val filter = etDeckSearch.text.toString().trim().lowercase()
        val sortAsc = cmbSortOrder.selectedItemPosition == 0

        filteredDisplayList.clear()
        for (m in membersMasterCache) {
            val name = m.memberName.lowercase()
            val flat = m.flatNoId.lowercase()
            if (name.contains(filter) || flat.contains(filter)) {
                filteredDisplayList.add(m)
            }
        }

        filteredDisplayList.sortWith { m1, m2 ->
            val f1 = m1.flatNoId
            val f2 = m2.flatNoId
            if (sortAsc) f1.compareTo(f2, ignoreCase = true) else f2.compareTo(f1, ignoreCase = true)
        }
        adapter.notifyDataSetChanged()
        txtTotalMembersCount.text = "Total Registered Members: ${membersMasterCache.size}"
    }

    private fun openMemberCrudBottomSheet(memberData: MemberDto?) {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_member_crud_sheet, null)
        dialog.setContentView(sheetView)

        val etFlatNo = sheetView.findViewById<TextInputEditText>(R.id.etDialogFlatNo)
        val etMemberName = sheetView.findViewById<TextInputEditText>(R.id.etDialogMemberName)
        val etCoOwner = sheetView.findViewById<TextInputEditText>(R.id.etDialogCoOwner)
        val etContact = sheetView.findViewById<TextInputEditText>(R.id.etDialogContact)
        val etArea = sheetView.findViewById<TextInputEditText>(R.id.etDialogArea)
        val etEmail = sheetView.findViewById<TextInputEditText>(R.id.etDialogEmail)
        val etPermAddress = sheetView.findViewById<TextInputEditText>(R.id.etDialogPermAddress)
        val etSharesCount = sheetView.findViewById<TextInputEditText>(R.id.etDialogSharesCount)
        val etShareSerials = sheetView.findViewById<TextInputEditText>(R.id.etDialogShareSerials)
        val etEachValue = sheetView.findViewById<TextInputEditText>(R.id.etDialogEachValue)
        val etTotalValuation = sheetView.findViewById<TextInputEditText>(R.id.etDialogTotalValuation)
        val etContriAmt = sheetView.findViewById<TextInputEditText>(R.id.etDialogContriAmt)

        // Make Total Valuation Non-Editable & Auto-Calculated
        etTotalValuation.isFocusable = false
        etTotalValuation.isClickable = false
        etTotalValuation.isFocusableInTouchMode = false

        val valuationWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val sharesCount = etSharesCount.text.toString().toDoubleOrNull() ?: 0.0
                val shareValue = etEachValue.text.toString().toDoubleOrNull() ?: 0.0
                val totalValuation = sharesCount * shareValue
                etTotalValuation.setText(String.format(Locale.getDefault(), "%.2f", totalValuation))
            }
        }
        etSharesCount.addTextChangedListener(valuationWatcher)
        etEachValue.addTextChangedListener(valuationWatcher)

        // Nominee Controls
        val spnNomineeCount = sheetView.findViewById<Spinner>(R.id.spnNomineeCount)
        val cardNominee1 = sheetView.findViewById<MaterialCardView>(R.id.cardNominee1)
        val cardNominee2 = sheetView.findViewById<MaterialCardView>(R.id.cardNominee2)
        val cardNominee3 = sheetView.findViewById<MaterialCardView>(R.id.cardNominee3)

        val etNomineeName1 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeName1)
        val etNomineeShare1 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeShare1)
        val etNomineeContact1 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeContact1)
        val etNomineeAddress1 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeAddress1)

        val etNomineeName2 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeName2)
        val etNomineeShare2 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeShare2)
        val etNomineeContact2 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeContact2)
        val etNomineeAddress2 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeAddress2)

        val etNomineeName3 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeName3)
        val etNomineeShare3 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeShare3)
        val etNomineeContact3 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeContact3)
        val etNomineeAddress3 = sheetView.findViewById<TextInputEditText>(R.id.etNomineeAddress3)

        val nomineeOptions = arrayOf("0 Nominees", "1 Nominee", "2 Nominees", "3 Nominees")
        val nomineeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nomineeOptions)
        spnNomineeCount.adapter = nomineeAdapter

        spnNomineeCount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                cardNominee1.visibility = if (position >= 1) View.VISIBLE else View.GONE
                cardNominee2.visibility = if (position >= 2) View.VISIBLE else View.GONE
                cardNominee3.visibility = if (position >= 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val btnSave = sheetView.findViewById<MaterialButton>(R.id.btnSaveMember)
        val btnDelete = sheetView.findViewById<MaterialButton>(R.id.btnDeleteMember)
        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseDialog)

        btnClose.setOnClickListener { dialog.dismiss() }

        if (memberData != null) {
            etFlatNo.setText(memberData.flatNoId)
            etFlatNo.isEnabled = false
            etMemberName.setText(memberData.memberName)
            etCoOwner.setText(memberData.coOwnerName ?: "")
            etContact.setText(memberData.contactNo ?: "")
            etArea.setText(memberData.area ?: "")
            etEmail.setText(memberData.corrAddress ?: "")
            etPermAddress.setText(memberData.permAddress ?: "")
            etSharesCount.setText(memberData.shares ?: "")
            etShareSerials.setText(memberData.corrAmt ?: "")
            etEachValue.setText(memberData.sharesIi ?: "")

            val sCount = memberData.shares?.toDoubleOrNull() ?: 0.0
            val sVal = memberData.sharesIi?.toDoubleOrNull() ?: 0.0
            val initTotal = sCount * sVal
            etTotalValuation.setText(String.format(Locale.getDefault(), "%.2f", initTotal))

            etContriAmt.setText(memberData.contriAmt ?: "")
            btnDelete.visibility = View.VISIBLE

            // Populate Nominee fields if present
            val nominees = memberData.nomineesList ?: emptyList()
            val count = minOf(nominees.size, 3)
            spnNomineeCount.setSelection(count)

            if (count >= 1) {
                etNomineeName1.setText(nominees[0].name)
                etNomineeShare1.setText(nominees[0].share)
                etNomineeContact1.setText(nominees[0].contact)
                etNomineeAddress1.setText(nominees[0].address)
            }
            if (count >= 2) {
                etNomineeName2.setText(nominees[1].name)
                etNomineeShare2.setText(nominees[1].share)
                etNomineeContact2.setText(nominees[1].contact)
                etNomineeAddress2.setText(nominees[1].address)
            }
            if (count >= 3) {
                etNomineeName3.setText(nominees[2].name)
                etNomineeShare3.setText(nominees[2].share)
                etNomineeContact3.setText(nominees[2].contact)
                etNomineeAddress3.setText(nominees[2].address)
            }
        } else {
            etFlatNo.isEnabled = true
            btnDelete.visibility = View.GONE
            spnNomineeCount.setSelection(0)
        }

        btnSave.setOnClickListener {
            val flatNo = etFlatNo.text.toString().trim()
            val name = etMemberName.text.toString().trim()
            val contact = etContact.text.toString().trim()

            if (flatNo.isEmpty() || name.isEmpty() || contact.isEmpty()) {
                Toast.makeText(this, "Populate all required fields (*)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Construct Nominees list based on count selection
            val nomineeList = mutableListOf<NomineeDto>()
            val selectedCount = spnNomineeCount.selectedItemPosition

            if (selectedCount >= 1) {
                nomineeList.add(
                    NomineeDto(
                        name = etNomineeName1.text.toString().trim(),
                        share = etNomineeShare1.text.toString().trim(),
                        contact = etNomineeContact1.text.toString().trim(),
                        address = etNomineeAddress1.text.toString().trim()
                    )
                )
            }
            if (selectedCount >= 2) {
                nomineeList.add(
                    NomineeDto(
                        name = etNomineeName2.text.toString().trim(),
                        share = etNomineeShare2.text.toString().trim(),
                        contact = etNomineeContact2.text.toString().trim(),
                        address = etNomineeAddress2.text.toString().trim()
                    )
                )
            }
            if (selectedCount >= 3) {
                nomineeList.add(
                    NomineeDto(
                        name = etNomineeName3.text.toString().trim(),
                        share = etNomineeShare3.text.toString().trim(),
                        contact = etNomineeContact3.text.toString().trim(),
                        address = etNomineeAddress3.text.toString().trim()
                    )
                )
            }

            val memberDto = MemberDto(
                id = memberData?.id,
                branchCode = targetBranchCode,
                flatNoId = flatNo,
                memberName = name,
                coOwnerName = etCoOwner.text.toString().trim(),
                contactNo = contact,
                area = etArea.text.toString().trim(),
                corrAddress = etEmail.text.toString().trim(),
                permAddress = etPermAddress.text.toString().trim(),
                shares = etSharesCount.text.toString().trim(),
                corrAmt = etShareSerials.text.toString().trim(),
                sharesIi = etEachValue.text.toString().trim(),
                contriAmt = etContriAmt.text.toString().trim(),
                nomineesList = nomineeList
            )

            lifecycleScope.launch {
                try {
                    SupabaseClientManager.postgrest.from("members").upsert(memberDto)
                    Toast.makeText(this@MemberDetailsActivity, "Synced successfully! ✅", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    fetchMembersFromCloud()
                } catch (e: Exception) {
                    Toast.makeText(this@MemberDetailsActivity, "Error saving member: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnDelete.setOnClickListener {
            val flatId = memberData?.flatNoId ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Confirm Purge")
                .setMessage("Are you sure you want to delete member Flat $flatId?")
                .setPositiveButton("Purge") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            SupabaseClientManager.postgrest.from("members").delete {
                                filter {
                                    eq("branch_code", targetBranchCode)
                                    eq("flat_no_id", flatId)
                                }
                            }
                            Toast.makeText(this@MemberDetailsActivity, "Member purged.", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            fetchMembersFromCloud()
                        } catch (e: Exception) {
                            Toast.makeText(this@MemberDetailsActivity, "Error deleting member: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun openMemberDetailViewSheet(member: MemberDto) {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_member_detail_view_sheet, null)
        dialog.setContentView(sheetView)

        val lblFlat = sheetView.findViewById<TextView>(R.id.lblDetailFlat)
        val lblMemberName = sheetView.findViewById<TextView>(R.id.lblDetailMemberName)
        val lblCoOwner = sheetView.findViewById<TextView>(R.id.lblDetailCoOwner)
        val lblContact = sheetView.findViewById<TextView>(R.id.lblDetailContact)
        val lblArea = sheetView.findViewById<TextView>(R.id.lblDetailArea)
        val lblEmail = sheetView.findViewById<TextView>(R.id.lblDetailEmail)
        val lblAddress = sheetView.findViewById<TextView>(R.id.lblDetailAddress)

        val lblShares = sheetView.findViewById<TextView>(R.id.lblDetailShares)
        val lblSerials = sheetView.findViewById<TextView>(R.id.lblDetailSerials)
        val lblShareVal = sheetView.findViewById<TextView>(R.id.lblDetailShareVal)
        val lblTotalValuation = sheetView.findViewById<TextView>(R.id.lblDetailTotalValuation)
        val lblContriAmt = sheetView.findViewById<TextView>(R.id.lblDetailContriAmt)

        val lblNomineeSummary = sheetView.findViewById<TextView>(R.id.lblDetailNomineeSummary)

        val btnEdit = sheetView.findViewById<MaterialButton>(R.id.btnEditMemberFromSheet)
        val btnDelete = sheetView.findViewById<MaterialButton>(R.id.btnDeleteMemberFromSheet)
        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseDetailSheet)

        btnClose?.setOnClickListener { dialog.dismiss() }

        lblFlat?.text = "FLAT - ${member.flatNoId.uppercase(Locale.getDefault())}"
        lblMemberName?.text = member.memberName
        lblCoOwner?.text = "Co-Owner: ${member.coOwnerName?.ifBlank { "N/A" } ?: "N/A"}"
        lblContact?.text = "📞 Contact: ${member.contactNo?.ifBlank { "N/A" } ?: "N/A"}"
        lblArea?.text = "📐 Area: ${member.area?.ifBlank { "N/A" } ?: "N/A"} Sq ft"
        lblEmail?.text = "✉️ Email: ${member.corrAddress?.ifBlank { "N/A" } ?: "N/A"}"
        lblAddress?.text = "🏠 Permanent Address: ${member.permAddress?.ifBlank { "N/A" } ?: "N/A"}"

        val sCount = member.shares?.toDoubleOrNull() ?: 0.0
        val sVal = member.sharesIi?.toDoubleOrNull() ?: 0.0
        val totalVal = sCount * sVal

        lblShares?.text = "Allocated Shares: ${member.shares ?: "0"}"
        lblSerials?.text = "Serial Allocation: ${member.corrAmt?.ifBlank { "N/A" } ?: "N/A"}"
        lblShareVal?.text = String.format(Locale.getDefault(), "Per Share Value: ₹%,.2f", sVal)
        lblTotalValuation?.text = String.format(Locale.getDefault(), "Total Valuation: ₹%,.2f", totalVal)

        val contri = member.contriAmt?.toDoubleOrNull() ?: 0.0
        lblContriAmt?.text = String.format(Locale.getDefault(), "Monthly Contribution: ₹%,.2f", contri)

        val nominees = member.nomineesList ?: emptyList()
        if (nominees.isNotEmpty()) {
            val sb = StringBuilder()
            for ((idx, nom) in nominees.withIndex()) {
                sb.append("Nominee ${idx + 1}: ${nom.name} (${nom.share}%) | 📞 ${nom.contact}\nAddress: ${nom.address}\n\n")
            }
            lblNomineeSummary?.text = sb.toString().trim()
        } else {
            lblNomineeSummary?.text = "No nominees registered."
        }

        btnEdit?.setOnClickListener {
            dialog.dismiss()
            openMemberCrudBottomSheet(member)
        }

        btnDelete?.setOnClickListener {
            dialog.dismiss()
            val flatId = member.flatNoId
            AlertDialog.Builder(this)
                .setTitle("Confirm Purge")
                .setMessage("Are you sure you want to delete member Flat $flatId?")
                .setPositiveButton("Purge") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            SupabaseClientManager.postgrest.from("members").delete {
                                filter {
                                    eq("branch_code", targetBranchCode)
                                    eq("flat_no_id", flatId)
                                }
                            }
                            Toast.makeText(this@MemberDetailsActivity, "Member purged.", Toast.LENGTH_SHORT).show()
                            fetchMembersFromCloud()
                        } catch (e: Exception) {
                            Toast.makeText(this@MemberDetailsActivity, "Error deleting member: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun showExportSuccessWithOpenOption(title: String, fileUri: Uri, mimeType: String, fileName: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("File saved to Downloads/$fileName ✅\nWould you like to open it now?")
            .setPositiveButton("📂 Open File") { dialog, _ ->
                try {
                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(openIntent, "Open $fileName"))
                } catch (e: Exception) {
                    Toast.makeText(this, "No application available to open $mimeType files.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun showExportFormatChoiceDialog() {
        if (membersMasterCache.isEmpty()) {
            Toast.makeText(this, "No members registered to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("📊 CSV Spreadsheet (.csv)", "📄 Official PDF Document (.pdf)")
        AlertDialog.Builder(this)
            .setTitle("📤 Export Member Directory")
            .setItems(options) { dialog, which ->
                if (which == 0) {
                    exportMembersToCsv()
                } else {
                    exportMembersToPdf()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCsvImportGuidanceDialog() {
        val message = """
            Mandatory CSV Column Order (23 Columns):
            
            1. Flat No (e.g. A-101) *
            2. Primary Member Name (e.g. John Doe) *
            3. Co-Owner Name
            4. Contact No *
            5. Area (Sq ft)
            6. Email Address
            7. Permanent Address
            8. Allocated Shares
            9. Serial Allocation
            10. Per Share Value
            11. Monthly Contribution Amount (₹)
            12. Nominee 1 Name
            13. Nominee 1 % Share
            14. Nominee 1 Contact
            15. Nominee 1 Address
            16. Nominee 2 Name
            17. Nominee 2 % Share
            18. Nominee 2 Contact
            19. Nominee 2 Address
            20. Nominee 3 Name
            21. Nominee 3 % Share
            22. Nominee 3 Contact
            23. Nominee 3 Address
            
            Note: Rows with blank Flat No or Member Name will be automatically skipped.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("📥 Member CSV Import Guidance")
            .setMessage(message)
            .setPositiveButton("📂 Select CSV File") { dialog, _ ->
                dialog.dismiss()
                csvPickerLauncher.launch("text/*")
            }
            .setNeutralButton("📄 Download Sample CSV") { dialog, _ ->
                dialog.dismiss()
                downloadSampleCsvTemplate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadSampleCsvTemplate() {
        try {
            val fileName = "Sample_Member_Import_Template.csv"
            val sampleCsvContent = StringBuilder().apply {
                append("Flat No,Member Name,Co-Owner,Contact No,Area,Email,Permanent Address,Shares,Serials,Per Share Value,Monthly Contribution,Nominee 1 Name,Nominee 1 Share,Nominee 1 Contact,Nominee 1 Address,Nominee 2 Name,Nominee 2 Share,Nominee 2 Contact,Nominee 2 Address,Nominee 3 Name,Nominee 3 Share,Nominee 3 Contact,Nominee 3 Address\n")
                append("\"A-101\",\"John Doe\",\"Jane Doe\",\"9876543210\",\"650\",\"john@example.com\",\"123 Main Street, Mumbai\",\"10\",\"101-110\",\"50\",\"1500\",\"Nominee One\",\"100\",\"9876543211\",\"Same Address\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\n")
                append("\"B-202\",\"Robert Smith\",\"Mary Smith\",\"9123456789\",\"850\",\"robert@example.com\",\"456 Park Avenue, Pune\",\"20\",\"201-220\",\"50\",\"2000\",\"Nominee A\",\"50\",\"9123456780\",\"Address A\",\"Nominee B\",\"50\",\"9123456781\",\"Address B\",\"\",\"\",\"\",\"\"\n")
            }.toString()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(sampleCsvContent.toByteArray())
                    }
                    showExportSuccessWithOpenOption("📄 Sample CSV Template Ready", uri, "text/csv", fileName)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { os ->
                    os.write(sampleCsvContent.toByteArray())
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                showExportSuccessWithOpenOption("📄 Sample CSV Template Ready", uri, "text/csv", fileName)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Template Download Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportMembersToPdf() {
        if (membersMasterCache.isEmpty()) return

        val currentDateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Member_Directory_${targetBranchCode}_$currentDateStr.pdf"

        try {
            val doc = Document(PageSize.A4.rotate(), 20f, 20f, 30f, 30f)
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

            val fontCompany = Font(Font.FontFamily.HELVETICA, 14f, Font.BOLD, BaseColor(5, 37, 80))
            val fontSub = Font(Font.FontFamily.HELVETICA, 9f, Font.NORMAL, BaseColor.DARK_GRAY)
            val fontHead = Font(Font.FontFamily.HELVETICA, 9f, Font.BOLD, BaseColor.WHITE)
            val fontBody = Font(Font.FontFamily.HELVETICA, 8f, Font.NORMAL, BaseColor.BLACK)

            val headerTable = PdfPTable(2).apply { widthPercentage = 100f }
            headerTable.addCell(PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                addElement(Paragraph(txtHeaderTitle.text.toString(), fontCompany))
                addElement(Paragraph(txtHeaderSubtitle.text.toString(), fontSub))
            })
            headerTable.addCell(PdfPCell().apply {
                setBorder(Rectangle.NO_BORDER)
                horizontalAlignment = Element.ALIGN_RIGHT
                addElement(Paragraph("MEMBER DIRECTORY REGISTRY", Font(Font.FontFamily.HELVETICA, 11f, Font.BOLD, BaseColor(25, 118, 210))).apply { alignment = Element.ALIGN_RIGHT })
                addElement(Paragraph("Total Members: ${membersMasterCache.size}", fontSub).apply { alignment = Element.ALIGN_RIGHT })
            })
            doc.add(headerTable)
            doc.add(Paragraph("\n"))

            val table = PdfPTable(6).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(1.2f, 2.5f, 1.8f, 1.2f, 1.5f, 1.8f))
            }

            fun createHeaderCell(txt: String): PdfPCell {
                return PdfPCell(Paragraph(txt, fontHead)).apply {
                    backgroundColor = BaseColor(5, 37, 80)
                    setPadding(5f)
                }
            }

            table.addCell(createHeaderCell("Flat No"))
            table.addCell(createHeaderCell("Primary Member Name"))
            table.addCell(createHeaderCell("Contact No"))
            table.addCell(createHeaderCell("Shares"))
            table.addCell(createHeaderCell("Total Valuation"))
            table.addCell(createHeaderCell("Monthly Contribution"))

            for (m in membersMasterCache) {
                val sCount = m.shares?.toDoubleOrNull() ?: 0.0
                val sVal = m.sharesIi?.toDoubleOrNull() ?: 0.0
                val totalVal = sCount * sVal
                val contri = m.contriAmt?.toDoubleOrNull() ?: 0.0

                fun createBodyCell(txt: String): PdfPCell {
                    return PdfPCell(Paragraph(txt, fontBody)).apply { setPadding(4f) }
                }

                table.addCell(createBodyCell(m.flatNoId.uppercase(Locale.getDefault())))
                table.addCell(createBodyCell(m.memberName))
                table.addCell(createBodyCell(m.contactNo ?: "N/A"))
                table.addCell(createBodyCell(m.shares ?: "0"))
                table.addCell(createBodyCell(String.format(Locale.getDefault(), "₹%,.2f", totalVal)))
                table.addCell(createBodyCell(String.format(Locale.getDefault(), "₹%,.2f", contri)))
            }

            doc.add(table)
            doc.close()
            outputStream.flush()
            outputStream.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileUri != null) {
                val publishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(fileUri, publishValues, null, null)

                showExportSuccessWithOpenOption("📄 Member Directory PDF Ready", fileUri, "application/pdf", fileName)
            } else if (legacyFile != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", legacyFile)
                showExportSuccessWithOpenOption("📄 Member Directory PDF Ready", uri, "application/pdf", fileName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "PDF Export Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportMembersToCsv() {
        if (membersMasterCache.isEmpty()) {
            Toast.makeText(this, "No members available to export.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "Member_Registry_${targetBranchCode}_${System.currentTimeMillis()}.csv"
            val csvBuilder = StringBuilder()
            csvBuilder.append("Flat No,Member Name,Co-Owner,Contact No,Area,Email,Permanent Address,Shares,Serials,Per Share Value,Monthly Contribution,Nominee 1 Name,Nominee 1 Share,Nominee 1 Contact,Nominee 1 Address,Nominee 2 Name,Nominee 2 Share,Nominee 2 Contact,Nominee 2 Address,Nominee 3 Name,Nominee 3 Share,Nominee 3 Contact,Nominee 3 Address\n")

            for (m in membersMasterCache) {
                val sVal = m.sharesIi?.toDoubleOrNull() ?: 0.0

                csvBuilder.append("\"${m.flatNoId}\",")
                csvBuilder.append("\"${m.memberName}\",")
                csvBuilder.append("\"${m.coOwnerName ?: ""}\",")
                csvBuilder.append("\"${m.contactNo ?: ""}\",")
                csvBuilder.append("\"${m.area ?: ""}\",")
                csvBuilder.append("\"${m.corrAddress ?: ""}\",")
                csvBuilder.append("\"${m.permAddress ?: ""}\",")
                csvBuilder.append("\"${m.shares ?: ""}\",")
                csvBuilder.append("\"${m.corrAmt ?: ""}\",")
                csvBuilder.append("\"$sVal\",")
                csvBuilder.append("\"${m.contriAmt ?: ""}\",")

                val nominees = m.nomineesList ?: emptyList()
                val nom1 = if (nominees.isNotEmpty()) nominees[0] else NomineeDto()
                val nom2 = if (nominees.size > 1) nominees[1] else NomineeDto()
                val nom3 = if (nominees.size > 2) nominees[2] else NomineeDto()

                csvBuilder.append("\"${nom1.name}\",\"${nom1.share}\",\"${nom1.contact}\",\"${nom1.address}\",")
                csvBuilder.append("\"${nom2.name}\",\"${nom2.share}\",\"${nom2.contact}\",\"${nom2.address}\",")
                csvBuilder.append("\"${nom3.name}\",\"${nom3.share}\",\"${nom3.contact}\",\"${nom3.address}\"\n")
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
                    showExportSuccessWithOpenOption("📊 Member Registry CSV Ready", uri, "text/csv", fileName)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { os ->
                    os.write(csvBuilder.toString().toByteArray())
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                showExportSuccessWithOpenOption("📊 Member Registry CSV Ready", uri, "text/csv", fileName)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "CSV Export Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCsvValidationErrorDialog(title: String, errors: List<String>) {
        val message = errors.take(15).joinToString("\n") + if (errors.size > 15) "\n... and ${errors.size - 15} more errors." else ""
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("📄 Download Sample CSV") { dialog, _ ->
                dialog.dismiss()
                downloadSampleCsvTemplate()
            }
            .show()
    }

    private fun sanitizeNumberString(str: String): String {
        val num = str.filter { it.isDigit() || it == '.' }
        return if (num.isNotBlank()) num else "0"
    }

    private fun importMembersFromCsvUri(uri: Uri) {
        progressOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val importedMembers = mutableListOf<MemberDto>()
                val validationErrors = mutableListOf<String>()

                contentResolver.openInputStream(uri)?.use { stream ->
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
                    val headerLine = reader.readLine()?.trim()

                    if (headerLine.isNullOrBlank()) {
                        progressOverlay.visibility = View.GONE
                        showCsvValidationErrorDialog("❌ CSV Import Rejected", listOf("The CSV file is empty or missing a header row."))
                        return@launch
                    }

                    val headerTokens = headerLine.split(",").map { it.replace("\"", "").trim().lowercase(Locale.getDefault()) }
                    
                    // Dynamic Header Index Resolution
                    fun getColumnIndex(vararg keywords: String, defaultIdx: Int): Int {
                        val idx = headerTokens.indexOfFirst { header ->
                            keywords.any { kw -> header.contains(kw) }
                        }
                        return if (idx >= 0) idx else defaultIdx
                    }

                    val flatIdx = getColumnIndex("flat", defaultIdx = 0)
                    val nameIdx = getColumnIndex("member", "name", defaultIdx = 1)
                    val coOwnerIdx = getColumnIndex("co-owner", "coowner", "owner", defaultIdx = 2)
                    val contactIdx = getColumnIndex("contact", "phone", "mobile", defaultIdx = 3)
                    val areaIdx = getColumnIndex("area", "sq", defaultIdx = 4)
                    val emailIdx = getColumnIndex("email", "mail", defaultIdx = 5)
                    val permAddrIdx = getColumnIndex("address", "permanent", defaultIdx = 6)
                    val sharesIdx = getColumnIndex("shares", defaultIdx = 7)
                    val serialsIdx = getColumnIndex("serials", "serial", defaultIdx = 8)
                    val shareValIdx = getColumnIndex("per share", "share value", "rate", defaultIdx = 9)
                    val contriAmtIdx = getColumnIndex("contribution", "contri", defaultIdx = 10)

                    // Verify mandatory column headers present
                    val isHeaderValid = headerTokens.size >= 4 &&
                            headerTokens[flatIdx].contains("flat") &&
                            (headerTokens[nameIdx].contains("member") || headerTokens[nameIdx].contains("name")) &&
                            (headerTokens[contactIdx].contains("contact") || headerTokens[contactIdx].contains("phone") || headerTokens[contactIdx].contains("mobile"))

                    if (!isHeaderValid) {
                        progressOverlay.visibility = View.GONE
                        showCsvValidationErrorDialog(
                            "❌ Invalid Column Header Sequence",
                            listOf(
                                "The CSV column sequence does not match the official schema.",
                                "Mandatory Header Sequence:",
                                "1. Flat No | 2. Member Name | 3. Co-Owner | 4. Contact No | 5. Area | 6. Email | 7. Address | 8. Shares | 9. Serials | 10. Value | 11. Contribution",
                                "",
                                "Please download and use the official Sample CSV Template."
                            )
                        )
                        return@launch
                    }

                    var rowNumber = 1 // Header is row 1
                    var line: String? = null

                    while (reader.readLine().also { line = it } != null) {
                        rowNumber++
                        val currentLine = line?.trim() ?: continue
                        if (currentLine.isBlank()) continue

                        val tokens = currentLine.split(",").map { it.replace("\"", "").trim() }
                        
                        fun getTokenAt(idx: Int): String {
                            return if (idx < tokens.size) tokens[idx] else ""
                        }

                        val flat = getTokenAt(flatIdx)
                        val name = getTokenAt(nameIdx)
                        val contact = getTokenAt(contactIdx)

                        val rowErrors = mutableListOf<String>()
                        if (flat.isBlank()) rowErrors.add("Flat No")
                        if (name.isBlank()) rowErrors.add("Member Name")
                        if (contact.isBlank()) rowErrors.add("Contact No")

                        if (rowErrors.isNotEmpty()) {
                            validationErrors.add("Row #$rowNumber: Missing mandatory field(s): ${rowErrors.joinToString(", ")}")
                        } else {
                            val coOwner = getTokenAt(coOwnerIdx)
                            val area = getTokenAt(areaIdx)
                            val email = getTokenAt(emailIdx)
                            val permAddr = getTokenAt(permAddrIdx)
                            
                            val rawShares = getTokenAt(sharesIdx)
                            val serials = getTokenAt(serialsIdx)
                            val rawShareVal = getTokenAt(shareValIdx)
                            val rawContriAmt = getTokenAt(contriAmtIdx)

                            val shares = sanitizeNumberString(rawShares)
                            val shareVal = sanitizeNumberString(rawShareVal)
                            val contriAmt = sanitizeNumberString(rawContriAmt)

                            // Extract Nominees 1, 2, 3
                            val nomineeList = mutableListOf<NomineeDto>()

                            if (tokens.size >= 15 && tokens[11].isNotBlank()) {
                                nomineeList.add(NomineeDto(name = tokens[11], share = tokens[12], contact = tokens[13], address = tokens[14]))
                            }
                            if (tokens.size >= 19 && tokens[15].isNotBlank()) {
                                nomineeList.add(NomineeDto(name = tokens[15], share = tokens[16], contact = tokens[17], address = tokens[18]))
                            }
                            if (tokens.size >= 23 && tokens[19].isNotBlank()) {
                                nomineeList.add(NomineeDto(name = tokens[19], share = tokens[20], contact = tokens[21], address = tokens[22]))
                            }

                            importedMembers.add(
                                MemberDto(
                                    branchCode = targetBranchCode,
                                    flatNoId = flat,
                                    memberName = name,
                                    coOwnerName = coOwner,
                                    contactNo = contact,
                                    area = area,
                                    corrAddress = email,
                                    permAddress = permAddr,
                                    shares = shares,
                                    corrAmt = serials,
                                    sharesIi = shareVal,
                                    contriAmt = contriAmt,
                                    nomineesList = nomineeList
                                )
                            )
                        }
                    }
                }

                if (validationErrors.isNotEmpty()) {
                    progressOverlay.visibility = View.GONE
                    showCsvValidationErrorDialog("❌ CSV Data Accuracy Violations", validationErrors)
                    return@launch
                }

                if (importedMembers.isNotEmpty()) {
                    for (memberDto in importedMembers) {
                        SupabaseClientManager.postgrest.from("members").upsert(memberDto)
                    }
                    Toast.makeText(this@MemberDetailsActivity, "Imported ${importedMembers.size} validated member records! ✅", Toast.LENGTH_LONG).show()
                    fetchMembersFromCloud()
                } else {
                    progressOverlay.visibility = View.GONE
                    Toast.makeText(this@MemberDetailsActivity, "No valid data rows found in CSV file.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progressOverlay.visibility = View.GONE
                Toast.makeText(this@MemberDetailsActivity, "CSV Import Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    inner class MemberDeckAdapter(
        private val list: List<MemberDto>,
        private val onItemClick: (MemberDto) -> Unit
    ) : RecyclerView.Adapter<MemberDeckAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val txtFlat: TextView = v.findViewById(R.id.txtCardFlat)
            val txtMember: TextView = v.findViewById(R.id.txtCardMember)
            val txtContact: TextView = v.findViewById(R.id.txtCardContact)
            val txtShares: TextView = v.findViewById(R.id.txtCardShares)
            val txtNominees: TextView = v.findViewById(R.id.txtCardNominees)
            val chkSelect: android.widget.CheckBox = v.findViewById(R.id.chkSelectMember)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_member_deck_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val flatId = item.flatNoId
            val nominees = item.nomineesList?.size ?: 0

            holder.txtFlat.text = flatId.uppercase(Locale.getDefault())
            holder.txtMember.text = item.memberName
            holder.txtContact.text = "📞 ${item.contactNo ?: "N/A"}"
            holder.txtShares.text = "• Shares: ${item.shares ?: "0"}"
            holder.txtNominees.text = "$nominees Nominee(s)"

            if (isSelectionMode) {
                holder.chkSelect.visibility = View.VISIBLE
                holder.chkSelect.isChecked = selectedFlatNos.contains(flatId)
            } else {
                holder.chkSelect.visibility = View.GONE
                holder.chkSelect.isChecked = false
            }

            holder.itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleMemberSelection(flatId)
                } else {
                    onItemClick(item)
                }
            }

            holder.itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode(flatId)
                }
                true
            }
        }

        override fun getItemCount(): Int = list.size
    }
}