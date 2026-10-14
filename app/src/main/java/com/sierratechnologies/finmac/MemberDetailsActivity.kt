package com.sierratechnologies.finmac

import android.app.AlertDialog
import android.content.Context
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
import java.util.Locale
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import io.github.jan.supabase.postgrest.from
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
            openMemberCrudBottomSheet(selectedMember)
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
            txtHeaderTitle.text = bName.uppercase(Locale.getDefault())
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
                    txtHeaderTitle.text = branch.name.uppercase(Locale.getDefault())
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
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_member_deck_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val flatId = item.flatNoId
            val nominees = item.nomineesList?.size ?: 0

            holder.txtFlat.text = "FLAT - ${flatId.uppercase()}"
            holder.txtMember.text = item.memberName
            holder.txtContact.text = "📞 ${item.contactNo ?: "N/A"}"
            holder.txtShares.text = "Shares: ${item.shares ?: "0"}"
            holder.txtNominees.text = "$nominees Nominee(s)"

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = list.size
    }
}