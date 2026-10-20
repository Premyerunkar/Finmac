package com.sierratechnologies.finmac

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.util.Locale

class SelectSocietyActivity : AppCompatActivity() {

    data class Branch(
        val name: String,
        val code: String,
        val division: String,
        val address: String,
        val city: String,
        val username: String,
        val password: String
    )

    private val branchList = ArrayList<Branch>()
    private val filteredList = ArrayList<Branch>()
    private lateinit var adapter: BranchAdapter

    private lateinit var searchField: EditText
    private lateinit var rvBranches: RecyclerView
    private lateinit var progressOverlay: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_society)

        initViews()
        setupWindowInsets()
        setupRecyclerView()

        findViewById<View>(R.id.btnAddBranch)?.setOnClickListener { showAddOrEditBranchDialog(null) }
        findViewById<View>(R.id.btnBin)?.setOnClickListener { openRecycleBinDialog() }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterBranches(s.toString().trim()) }
        })

        fetchBranchesFromSupabase()
    }

    private fun initViews() {
        searchField = findViewById(R.id.searchField)
        rvBranches = findViewById(R.id.rvBranches)
        progressOverlay = findViewById(R.id.progressOverlay)
    }

    private fun setupWindowInsets() {
        val appBar = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBarLayout)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun setupRecyclerView() {
        rvBranches.layoutManager = LinearLayoutManager(this)
        adapter = BranchAdapter(filteredList)
        rvBranches.adapter = adapter
    }

    private fun filterBranches(query: String) {
        filteredList.clear()
        if (query.isEmpty()) {
            filteredList.addAll(branchList)
        } else {
            val q = query.lowercase(Locale.getDefault())
            for (b in branchList) {
                if (b.name.lowercase().contains(q) ||
                    b.code.lowercase().contains(q) ||
                    b.division.lowercase().contains(q) ||
                    b.city.lowercase().contains(q)) {
                    filteredList.add(b)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun fetchBranchesFromSupabase() {
        val cachedBranches = AppCache.getBranches(this)
        if (cachedBranches != null) {
            branchList.clear()
            for (dto in cachedBranches) {
                branchList.add(
                    Branch(
                        name = dto.name,
                        code = dto.code,
                        division = dto.division ?: "N/A",
                        address = dto.address ?: "N/A",
                        city = dto.city ?: "N/A",
                        username = dto.username,
                        password = dto.password
                    )
                )
            }
            filterBranches(searchField.text.toString())
        } else {
            progressOverlay.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val list = SupabaseClientManager.postgrest.from("branches")
                    .select()
                    .decodeList<BranchDto>()

                AppCache.saveBranches(this@SelectSocietyActivity, list)
                branchList.clear()

                for (dto in list) {
                    branchList.add(
                        Branch(
                            name = dto.name,
                            code = dto.code,
                            division = dto.division ?: "N/A",
                            address = dto.address ?: "N/A",
                            city = dto.city ?: "N/A",
                            username = dto.username,
                            password = dto.password
                        )
                    )
                }
                filterBranches(searchField.text.toString())
            } catch (e: Exception) {
                if (cachedBranches == null) {
                    Toast.makeText(this@SelectSocietyActivity, "Error loading branches: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                progressOverlay.visibility = View.GONE
            }
        }
    }

    private fun handleRowSelectionEvent(code: String, name: String) {
        AppCache.clearAllInMemoryCache()
        Util.Session.activeBranchCode = code
        Util.Session.activeBranchName = name

        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        sharedPref.edit().apply {
            putString("SOCIETY_CODE", code)
            putString("SOCIETY_NAME", name)
            putString("USERNAME", "SUPERADMIN")
            apply()
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("SOCIETY_CODE", code)
        }
        startActivity(intent)
        finish()
    }

    private fun moveBranchToRecycleBin(branch: Branch) {
        AlertDialog.Builder(this)
            .setTitle("Confirm Delete Step")
            .setMessage("Are you sure you want to move '${branch.name}' to the Recycle Bin?\nIt can be restored at any time.")
            .setPositiveButton("Move to Bin") { _, _ ->
                progressOverlay.visibility = View.VISIBLE
                lifecycleScope.launch {
                    try {
                        val binDto = BinBranchDto(
                            code = branch.code,
                            name = branch.name,
                            division = branch.division,
                            address = branch.address,
                            city = branch.city,
                            username = branch.username,
                            password = branch.password,
                            deletedAt = System.currentTimeMillis().toString()
                        )

                        SupabaseClientManager.postgrest.from("bin_branches").upsert(binDto)
                        SupabaseClientManager.postgrest.from("branches").delete { filter { eq("code", branch.code) } }

                        Toast.makeText(this@SelectSocietyActivity, "Branch moved to Bin successfully! 🗑️", Toast.LENGTH_SHORT).show()
                        fetchBranchesFromSupabase()
                    } catch (e: Exception) {
                        progressOverlay.visibility = View.GONE
                        Toast.makeText(this@SelectSocietyActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddOrEditBranchDialog(existingBranch: Branch?) {
        val isEdit = existingBranch != null
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_branch_form_sheet, null)
        dialog.setContentView(view)

        val txtHeader = view.findViewById<TextView>(R.id.lblModalTitle)
        val etName = view.findViewById<EditText>(R.id.etBranchName)
        val etCode = view.findViewById<EditText>(R.id.etBranchCode)
        val spinnerDiv = view.findViewById<Spinner>(R.id.spinnerDivision)
        val etAddress = view.findViewById<EditText>(R.id.etAddress)
        val etCity = view.findViewById<EditText>(R.id.etCity)
        val etUser = view.findViewById<EditText>(R.id.etUsername)
        val etPass = view.findViewById<EditText>(R.id.etPassword)
        val btnSubmit = view.findViewById<Button>(R.id.btnSubmitBranch)

        val divisions = arrayOf("-- Select Division --", "Raigad", "Mumbai East", "Thane", "Pune")
        spinnerDiv.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, divisions)

        if (isEdit) {
            txtHeader.text = "Modify Branch Profile"
            etName.setText(existingBranch.name)
            etCode.setText(existingBranch.code)
            etCode.isEnabled = false
            etAddress.setText(existingBranch.address)
            etCity.setText(existingBranch.city)
            etUser.setText(existingBranch.username)
            etPass.setText(existingBranch.password)
            val divIndex = divisions.indexOf(existingBranch.division)
            if (divIndex >= 0) spinnerDiv.setSelection(divIndex)
            btnSubmit.text = "Update Branch Record"
        } else {
            txtHeader.text = "Create New Branch Record"
        }

        btnSubmit.setOnClickListener {
            val name = etName.text.toString().trim()
            val code = etCode.text.toString().trim().uppercase(Locale.getDefault())
            val division = spinnerDiv.selectedItem.toString()
            val address = etAddress.text.toString().trim()
            val city = etCity.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()

            if (name.isEmpty() || code.isEmpty() || division == divisions[0] || user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Please populate all required fields (*).", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            progressOverlay.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    val branchDto = BranchDto(
                        code = code,
                        name = name,
                        division = division,
                        address = if (address.isEmpty()) "Unknown Address" else address,
                        city = if (city.isEmpty()) "Unknown" else city,
                        username = user,
                        password = pass
                    )

                    SupabaseClientManager.postgrest.from("branches").upsert(branchDto)
                    Toast.makeText(this@SelectSocietyActivity, if (isEdit) "Branch Profile Updated! ✅" else "Branch Created Successfully! ✅", Toast.LENGTH_SHORT).show()
                    fetchBranchesFromSupabase()
                } catch (e: Exception) {
                    progressOverlay.visibility = View.GONE
                    Toast.makeText(this@SelectSocietyActivity, "Error saving branch: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    private fun openRecycleBinDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_recycle_bin_sheet, null)
        dialog.setContentView(view)

        val rvBin = view.findViewById<RecyclerView>(R.id.rvBinBranches)
        rvBin.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            try {
                val binList = ArrayList<Branch>()
                val list = SupabaseClientManager.postgrest.from("bin_branches")
                    .select()
                    .decodeList<BinBranchDto>()

                for (dto in list) {
                    binList.add(
                        Branch(
                            name = dto.name,
                            code = dto.code,
                            division = dto.division ?: "N/A",
                            address = dto.address ?: "",
                            city = dto.city ?: "",
                            username = dto.username ?: "",
                            password = dto.password ?: ""
                        )
                    )
                }
                rvBin.adapter = BinAdapter(
                    list = binList,
                    onRestore = { branch ->
                        dialog.dismiss()
                        restoreBranch(branch)
                    },
                    onDelete = { branch ->
                        dialog.dismiss()
                        permanentDelete(branch)
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(this@SelectSocietyActivity, "Error loading bin: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun restoreBranch(branch: Branch) {
        progressOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val branchDto = BranchDto(
                    code = branch.code,
                    name = branch.name,
                    division = branch.division,
                    address = branch.address,
                    city = branch.city,
                    username = branch.username,
                    password = branch.password
                )

                SupabaseClientManager.postgrest.from("branches").upsert(branchDto)
                SupabaseClientManager.postgrest.from("bin_branches").delete { filter { eq("code", branch.code) } }

                Toast.makeText(this@SelectSocietyActivity, "Branch restored successfully! ♻️", Toast.LENGTH_SHORT).show()
                fetchBranchesFromSupabase()
            } catch (e: Exception) {
                progressOverlay.visibility = View.GONE
                Toast.makeText(this@SelectSocietyActivity, "Error restoring: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun permanentDelete(branch: Branch) {
        AlertDialog.Builder(this)
            .setTitle("Permanent Delete")
            .setMessage("⚠️ This will permanently purge '${branch.name}' from cloud servers. This action CANNOT be undone.")
            .setPositiveButton("Erase") { _, _ ->
                progressOverlay.visibility = View.VISIBLE
                lifecycleScope.launch {
                    try {
                        SupabaseClientManager.postgrest.from("bin_branches").delete { filter { eq("code", branch.code) } }
                        Toast.makeText(this@SelectSocietyActivity, "Branch permanently erased.", Toast.LENGTH_SHORT).show()
                        openRecycleBinDialog()
                    } catch (e: Exception) {
                        progressOverlay.visibility = View.GONE
                        Toast.makeText(this@SelectSocietyActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class BranchAdapter(private val list: List<Branch>) :
        RecyclerView.Adapter<BranchAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.lblBranchName)
            val info: TextView = v.findViewById(R.id.lblDivisionCity)
            val code: TextView = v.findViewById(R.id.lblBranchCode)
            val btnEdit: MaterialButton = v.findViewById(R.id.btnEdit)
            val btnDelete: MaterialButton = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_branch_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.name.text = "🏢 ${item.name}"
            holder.info.text = "${item.division} • ${item.city}, ${item.address}"
            holder.code.text = item.code

            holder.itemView.setOnClickListener { handleRowSelectionEvent(item.code, item.name) }
            holder.btnEdit.setOnClickListener { showAddOrEditBranchDialog(item) }
            holder.btnDelete.setOnClickListener { moveBranchToRecycleBin(item) }
        }

        override fun getItemCount() = list.size
    }

    inner class BinAdapter(
        private val list: List<Branch>,
        private val onRestore: (Branch) -> Unit,
        private val onDelete: (Branch) -> Unit
    ) : RecyclerView.Adapter<BinAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txtBinBranchName)
            val info: TextView = v.findViewById(R.id.txtBinBranchInfo)
            val code: TextView = v.findViewById(R.id.txtBinBranchCode)
            val btnRestore: MaterialButton = v.findViewById(R.id.btnRestoreBinBranch)
            val btnDelete: MaterialButton = v.findViewById(R.id.btnPermanentDeleteBinBranch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bin_branch_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.name.text = "🏢 ${item.name}"
            holder.info.text = "${item.division} • ${item.city}, ${item.address}"
            holder.code.text = item.code

            holder.btnRestore.setOnClickListener { onRestore(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount() = list.size
    }
}