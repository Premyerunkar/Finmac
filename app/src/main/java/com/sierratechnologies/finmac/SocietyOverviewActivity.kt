package com.sierratechnologies.finmac

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale

class SocietyOverviewActivity : AppCompatActivity() {

    private val targetBranchCode by lazy {
        intent.getStringExtra("SOCIETY_CODE")
            ?.takeIf { it.isNotBlank() }
            ?: Util.Session.activeBranchCode
            ?.takeIf { it.isNotBlank() }
            ?: getSharedPreferences("UserSession", Context.MODE_PRIVATE).getString("SOCIETY_CODE", "")?.trim()
            ?: ""
    }

    private var base64CloudPdfPayload: String = ""
    private val committeeList = ArrayList<HashMap<String, String>>()
    private lateinit var adapter: CommitteeAdapter

    // Form input field pointers
    private lateinit var etSocietyName: EditText
    private lateinit var etRegNo: EditText
    private lateinit var etAddress: EditText
    private lateinit var etTotalFlats: EditText
    private lateinit var etCommitteePeriod: EditText
    private lateinit var etElectedDate: EditText
    private lateinit var txtPdfStatus: TextView
    private lateinit var progressOverlay: FrameLayout

    private val pdfPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> processSelectedPdfUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_society_overview)

        initViews()
        setupWindowInsets()
        setupSecurityAccessProfile()
        setupDatePicker()
        setupRecyclerViewGrid()

        // Auto-populate local session society name if available before cloud fetch
        if (Util.Session.activeBranchName.isNotBlank()) {
            etSocietyName.setText(Util.Session.activeBranchName)
        }

        fetchSocietyDataFromCloud()
    }

    private fun setupWindowInsets() {
        val appBar = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBarLayout)
        if (appBar != null) {
            ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.setPadding(0, statusBarHeight, 0, 0)
                insets
            }
        }
    }

    private fun initViews() {
        etSocietyName = findViewById(R.id.etSocietyName)
        etRegNo = findViewById(R.id.etRegNo)
        etAddress = findViewById(R.id.etAddress)
        etTotalFlats = findViewById(R.id.etTotalFlats)
        etCommitteePeriod = findViewById(R.id.etCommitteePeriod)
        etElectedDate = findViewById(R.id.etElectedDate)
        txtPdfStatus = findViewById(R.id.txtPdfStatus)
        progressOverlay = findViewById(R.id.progressOverlay)

        findViewById<ImageButton>(R.id.btnBack)?.setOnClickListener { finish() }

        findViewById<Button>(R.id.btnBrowsePdf).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/pdf"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            pdfPickerLauncher.launch(intent)
        }

        findViewById<Button>(R.id.btnViewPdf).setOnClickListener { launchDirectCloudPdfViewerEngine() }
        findViewById<Button>(R.id.btnSyncAll).setOnClickListener { commitUnifiedFormPayloadToCloud() }

        findViewById<Button>(R.id.btnAddRow).setOnClickListener {
            currentFocus?.clearFocus()
            committeeList.add(hashMapOf("role" to "", "name" to "", "contact" to ""))
            adapter.notifyItemInserted(committeeList.size - 1)
        }
    }

    private fun setupSecurityAccessProfile() {
        val isSuperAdmin = Util.Session.isSuperAdmin || targetBranchCode.equals("SUPERADMIN", ignoreCase = true)
        etSocietyName.isEnabled = isSuperAdmin
        etRegNo.isEnabled = isSuperAdmin
        etAddress.isEnabled = true
        etTotalFlats.isEnabled = true
        etCommitteePeriod.isEnabled = true
        etElectedDate.isEnabled = true
    }

    private fun setupDatePicker() {
        etElectedDate.isFocusable = false
        etElectedDate.isClickable = true

        etElectedDate.setOnClickListener {
            showDatePickerDialog()
        }
    }

    private fun showDatePickerDialog() {
        val cal = Calendar.getInstance()
        val existingText = etElectedDate.text.toString().trim()

        if (existingText.isNotBlank()) {
            try {
                val parts = existingText.split("-", "/", ".")
                if (parts.size == 3) {
                    if (parts[0].length == 4) { // YYYY-MM-DD
                        cal.set(Calendar.YEAR, parts[0].toInt())
                        cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                        cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                    } else if (parts[2].length == 4) { // DD-MM-YYYY
                        cal.set(Calendar.DAY_OF_MONTH, parts[0].toInt())
                        cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                        cal.set(Calendar.YEAR, parts[2].toInt())
                    }
                }
            } catch (_: Exception) {}
        }

        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(
            this,
            { _, selectedYear, selectedMonth, selectedDay ->
                val formattedDate = String.format(Locale.getDefault(), "%02d/%02d/%04d", selectedDay, selectedMonth + 1, selectedYear)
                etElectedDate.setText(formattedDate)
            },
            year,
            month,
            day
        ).show()
    }

    private fun setupRecyclerViewGrid() {
        val rvTable = findViewById<RecyclerView>(R.id.rvCommitteeTable)
        rvTable.layoutManager = LinearLayoutManager(this)
        adapter = CommitteeAdapter(committeeList)
        rvTable.adapter = adapter
    }

    private fun fetchSocietyDataFromCloud() {
        // 1. Instant 0ms Load from AppCache Disk/Memory
        val cachedBranches = AppCache.getBranches(this)
        val cachedBranch = cachedBranches?.find { it.code.equalsIgnoreCase(targetBranchCode) }

        if (cachedBranch != null) {
            etSocietyName.setText(cachedBranch.name.ifBlank { Util.Session.activeBranchName })
            etRegNo.setText(cachedBranch.registrationNo ?: "")
            etAddress.setText(cachedBranch.address ?: "")
            etTotalFlats.setText(cachedBranch.totalFlats ?: "")
            etCommitteePeriod.setText(cachedBranch.committeePeriod ?: "")
            etElectedDate.setText(cachedBranch.committeeElectedOn ?: "")

            base64CloudPdfPayload = cachedBranch.cloudPdfBase64 ?: ""
            if (base64CloudPdfPayload.isNotBlank()) {
                txtPdfStatus.text = "📄 Verified Audit Certificate Linked Successfully [Cloud Active]"
                txtPdfStatus.setTextColor(Color.parseColor("#16A34A"))
            } else {
                txtPdfStatus.text = "No Document File Synced"
                txtPdfStatus.setTextColor(Color.parseColor("#64748B"))
            }

            committeeList.clear()
            cachedBranch.committeeList?.forEach { member ->
                committeeList.add(
                    hashMapOf(
                        "role" to (member.role ?: ""),
                        "name" to (member.name ?: ""),
                        "contact" to (member.contact ?: "")
                    )
                )
            }

            if (committeeList.isEmpty()) {
                listOf("Chairperson", "Secretary", "Treasurer").forEach { role ->
                    committeeList.add(hashMapOf("role" to role, "name" to "", "contact" to ""))
                }
            }
            adapter.notifyDataSetChanged()
        } else {
            progressOverlay.visibility = View.VISIBLE
        }

        // 2. Silent Background Sync from Supabase
        lifecycleScope.launch {
            try {
                val list = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", targetBranchCode) } }
                    .decodeList<BranchDto>()

                if (list.isNotEmpty()) {
                    val branch = list.first()
                    etSocietyName.setText(branch.name.ifBlank { Util.Session.activeBranchName })
                    etRegNo.setText(branch.registrationNo ?: "")
                    etAddress.setText(branch.address ?: "")
                    etTotalFlats.setText(branch.totalFlats ?: "")
                    etCommitteePeriod.setText(branch.committeePeriod ?: "")
                    etElectedDate.setText(branch.committeeElectedOn ?: "")

                    base64CloudPdfPayload = branch.cloudPdfBase64 ?: ""
                    if (base64CloudPdfPayload.isNotBlank()) {
                        txtPdfStatus.text = "📄 Verified Audit Certificate Linked Successfully [Cloud Active]"
                        txtPdfStatus.setTextColor(Color.parseColor("#16A34A"))
                    } else {
                        txtPdfStatus.text = "No Document File Synced"
                        txtPdfStatus.setTextColor(Color.parseColor("#64748B"))
                    }

                    committeeList.clear()
                    branch.committeeList?.forEach { member ->
                        committeeList.add(
                            hashMapOf(
                                "role" to (member.role ?: ""),
                                "name" to (member.name ?: ""),
                                "contact" to (member.contact ?: "")
                            )
                        )
                    }

                    if (committeeList.isEmpty()) {
                        listOf("Chairperson", "Secretary", "Treasurer").forEach { role ->
                            committeeList.add(hashMapOf("role" to role, "name" to "", "contact" to ""))
                        }
                    }
                    adapter.notifyDataSetChanged()

                    val existingList = AppCache.getBranches(this@SocietyOverviewActivity)?.filter { !it.code.equalsIgnoreCase(targetBranchCode) }?.toMutableList() ?: mutableListOf()
                    existingList.add(branch)
                    AppCache.saveBranches(this@SocietyOverviewActivity, existingList)
                }
            } catch (e: Exception) {
                if (cachedBranch == null) {
                    Toast.makeText(this@SocietyOverviewActivity, "Operational Ledger Sync: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                progressOverlay.visibility = View.GONE
            }
        }
    }

    private fun processSelectedPdfUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                base64CloudPdfPayload = Base64.encodeToString(bytes, Base64.NO_WRAP)
                txtPdfStatus.text = "📄 Document Buffered for Cloud Save"
                txtPdfStatus.setTextColor(Color.parseColor("#052550"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Conversion Stream Read Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchDirectCloudPdfViewerEngine() {
        if (base64CloudPdfPayload.isBlank()) {
            Toast.makeText(this, "No audit certificate document has been linked to this society branch registry yet.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bytes = Base64.decode(base64CloudPdfPayload, Base64.DEFAULT)
            val cacheFile = File(cacheDir, "FINMAC_ATTACHMENT_${targetBranchCode}.pdf")
            FileOutputStream(cacheFile).use { it.write(bytes) }

            val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cacheFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/pdf")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NO_HISTORY
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to compile file from Cloud payload stream: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun String.equalsIgnoreCase(other: String?): Boolean {
        return this.equals(other, ignoreCase = true)
    }

    private fun commitUnifiedFormPayloadToCloud() {
        currentFocus?.clearFocus()
        progressOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val verifiedCommitteeList = committeeList.filter {
                    !(it["role"].isNullOrBlank() && it["name"].isNullOrBlank() && it["contact"].isNullOrBlank())
                }.map {
                    CommitteeMemberDto(
                        role = it["role"],
                        name = it["name"],
                        contact = it["contact"]
                    )
                }

                try {
                    val fullPayload = buildJsonObject {
                        put("name", etSocietyName.text.toString().trim())
                        put("registration_no", etRegNo.text.toString().trim())
                        put("address", etAddress.text.toString().trim())
                        put("total_flats", etTotalFlats.text.toString().trim())
                        put("committee_period", etCommitteePeriod.text.toString().trim())
                        put("committee_elected_on", etElectedDate.text.toString().trim())
                        put("cloud_pdf_base64", base64CloudPdfPayload)
                        put("committee_list", Json.encodeToJsonElement(verifiedCommitteeList))
                    }

                    SupabaseClientManager.postgrest.from("branches").update(fullPayload) {
                        filter { eq("code", targetBranchCode) }
                    }
                } catch (_: Exception) {
                    val corePayload = buildJsonObject {
                        put("name", etSocietyName.text.toString().trim())
                        put("registration_no", etRegNo.text.toString().trim())
                        put("address", etAddress.text.toString().trim())
                        put("total_flats", etTotalFlats.text.toString().trim())
                        put("committee_period", etCommitteePeriod.text.toString().trim())
                        put("committee_elected_on", etElectedDate.text.toString().trim())
                    }

                    SupabaseClientManager.postgrest.from("branches").update(corePayload) {
                        filter { eq("code", targetBranchCode) }
                    }
                }

                // Update AppCache instantly
                val updatedBranch = BranchDto(
                    code = targetBranchCode,
                    name = etSocietyName.text.toString().trim(),
                    registrationNo = etRegNo.text.toString().trim(),
                    address = etAddress.text.toString().trim(),
                    totalFlats = etTotalFlats.text.toString().trim(),
                    committeePeriod = etCommitteePeriod.text.toString().trim(),
                    committeeElectedOn = etElectedDate.text.toString().trim(),
                    cloudPdfBase64 = base64CloudPdfPayload,
                    committeeList = verifiedCommitteeList,
                    username = Util.Session.activeUsername,
                    password = ""
                )

                val existingList = AppCache.getBranches(this@SocietyOverviewActivity)?.filter { !it.code.equalsIgnoreCase(targetBranchCode) }?.toMutableList() ?: mutableListOf()
                existingList.add(updatedBranch)
                AppCache.saveBranches(this@SocietyOverviewActivity, existingList)

                Toast.makeText(this@SocietyOverviewActivity, "Society Profile Parameters Saved Successfully! ✅", Toast.LENGTH_LONG).show()
                fetchSocietyDataFromCloud()
            } catch (e: Exception) {
                Toast.makeText(this@SocietyOverviewActivity, "Save Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                progressOverlay.visibility = View.GONE
            }
        }
    }

    inner class CommitteeAdapter(private val list: ArrayList<HashMap<String, String>>) :
        RecyclerView.Adapter<CommitteeAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val etRole: EditText? = v.findViewById(R.id.etRole)
            val etName: EditText? = v.findViewById(R.id.etName)
            val etContact: EditText? = v.findViewById(R.id.etContact)
            val btnRemove: ImageButton? = v.findViewById(R.id.btnRemoveRow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_comittee_row, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]

            holder.etRole?.setText(item["role"] ?: "")
            holder.etName?.setText(item["name"] ?: "")
            holder.etContact?.setText(item["contact"] ?: "")

            holder.etRole?.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) item["role"] = holder.etRole.text.toString().trim()
            }
            holder.etName?.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) item["name"] = holder.etName.text.toString().trim()
            }
            holder.etContact?.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) item["contact"] = holder.etContact.text.toString().trim()
            }

            holder.btnRemove?.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    list.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }

        override fun getItemCount() = list.size
    }
}