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
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class VendorDetailsActivity : AppCompatActivity() {

    private lateinit var viewHeaderBackground: View
    private lateinit var btnBack: ImageView
    private lateinit var btnAddVendor: MaterialButton
    private lateinit var lblSocietyName: TextView
    private lateinit var lblSocietyAddress: TextView
    private lateinit var lblRegistrationNo: TextView

    private lateinit var searchViewVendors: SearchView
    private lateinit var rvVendorCards: RecyclerView
    private lateinit var txtEmptyState: TextView
    private lateinit var loadingOverlay: FrameLayout

    private var activeBranchCode: String = ""
    private var allVendorsList = ArrayList<VendorDto>()
    private var adapter: VendorCardsAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550"))
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vendor_details)

        initViews()
        setupWindowInsets()

        val sharedPref = getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        activeBranchCode = sharedPref.getString("SOCIETY_CODE", "") ?: ""

        btnBack.setOnClickListener { finish() }
        btnAddVendor.setOnClickListener { showAddVendorBottomSheet() }

        setupSearchView()
        loadSocietyAndVendorPipeline()
    }

    private fun initViews() {
        viewHeaderBackground = findViewById(R.id.viewHeaderBackground)
        btnBack = findViewById(R.id.btnBack)
        btnAddVendor = findViewById(R.id.btnAddVendor)
        lblSocietyName = findViewById(R.id.lblSocietyName)
        lblSocietyAddress = findViewById(R.id.lblSocietyAddress)
        lblRegistrationNo = findViewById(R.id.lblRegistrationNo)

        searchViewVendors = findViewById(R.id.searchViewVendors)
        rvVendorCards = findViewById(R.id.rvVendorCards)
        txtEmptyState = findViewById(R.id.txtEmptyState)
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

            val btnAddParams = btnAddVendor.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            btnAddParams.topMargin = (20 * resources.displayMetrics.density).toInt() + statusBarHeight
            btnAddVendor.layoutParams = btnAddParams

            insets
        }
    }

    private fun loadSocietyAndVendorPipeline() {
        if (activeBranchCode.isEmpty()) return

        val cachedVendors = AppCache.getVendors(this, activeBranchCode)
        if (cachedVendors != null) {
            allVendorsList.clear()
            allVendorsList.addAll(cachedVendors)
            updateAdapterList(allVendorsList)
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

                val vendors = SupabaseClientManager.postgrest.from("vendors")
                    .select { filter { eq("branch_code", activeBranchCode) } }
                    .decodeList<VendorDto>()

                AppCache.saveVendors(this@VendorDetailsActivity, activeBranchCode, vendors)
                allVendorsList.clear()
                allVendorsList.addAll(vendors)
                updateAdapterList(allVendorsList)
            } catch (e: Exception) {
                if (cachedVendors == null) {
                    Toast.makeText(this@VendorDetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun updateAdapterList(list: List<VendorDto>) {
        if (list.isEmpty()) {
            txtEmptyState.visibility = View.VISIBLE
            rvVendorCards.visibility = View.GONE
        } else {
            txtEmptyState.visibility = View.GONE
            rvVendorCards.visibility = View.VISIBLE

            val legacyMapList = list.map { vendor ->
                mapOf(
                    "partyName" to vendor.partyName,
                    "vendorCode" to vendor.vendorCode,
                    "categoryHead" to vendor.categoryHead,
                    "contactNo" to (vendor.contactNo ?: ""),
                    "panNo" to (vendor.panNo ?: "N/A")
                )
            }

            rvVendorCards.layoutManager = GridLayoutManager(this, 2)
            adapter = VendorCardsAdapter(legacyMapList) { selectedVendor ->
                launchVendorDetailsOverlayCard(selectedVendor)
            }
            rvVendorCards.adapter = adapter
        }
    }

    private fun setupSearchView() {
        searchViewVendors.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val searchText = newText?.trim()?.lowercase() ?: ""
                if (searchText.isEmpty()) {
                    updateAdapterList(allVendorsList)
                } else {
                    val filteredList = allVendorsList.filter { vendor ->
                        val name = vendor.partyName.lowercase()
                        val code = vendor.vendorCode.lowercase()
                        val head = vendor.categoryHead.lowercase()
                        name.contains(searchText) || code.contains(searchText) || head.contains(searchText)
                    }
                    updateAdapterList(filteredList)
                }
                return true
            }
        })
    }

    private fun showAddVendorBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_vendor, null)
        dialog.setContentView(dialogView)

        val txtCode = dialogView.findViewById<EditText>(R.id.txtVendorCode)
        val txtName = dialogView.findViewById<EditText>(R.id.txtVendorName)
        val txtHead = dialogView.findViewById<EditText>(R.id.txtAccountHead)
        val txtContact = dialogView.findViewById<EditText>(R.id.txtContactNo)
        val txtPan = dialogView.findViewById<EditText>(R.id.txtPanNo)
        val txtAadhaar = dialogView.findViewById<EditText>(R.id.txtAadhaarNo)
        val btnSubmit = dialogView.findViewById<MaterialButton>(R.id.btnSubmitVendor)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseModal)

        btnClose?.setOnClickListener { dialog.dismiss() }

        btnSubmit?.setOnClickListener {
            val code = txtCode?.text.toString().trim()
            val name = txtName?.text.toString().trim()
            val head = txtHead?.text.toString().trim()
            val contact = txtContact?.text.toString().trim()
            val pan = txtPan?.text.toString().trim().uppercase()
            val rawAadhaar = txtAadhaar?.text.toString().trim()

            if (code.isEmpty() || name.isEmpty() || head.isEmpty() || contact.isEmpty()) {
                Toast.makeText(this, "Please fill in all mandatory fields (*)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loadingOverlay.visibility = View.VISIBLE
            dialog.dismiss()

            val vendorDto = VendorDto(
                branchCode = activeBranchCode,
                vendorCode = code,
                partyName = name,
                categoryHead = head,
                contactNo = contact,
                panNo = if (pan.isEmpty()) "N/A" else pan,
                aadhaarIdentity = if (rawAadhaar.isNotEmpty()) "[Aadhaar Redacted]" else "N/A"
            )

            lifecycleScope.launch {
                try {
                    SupabaseClientManager.postgrest.from("vendors").upsert(vendorDto)
                    Toast.makeText(this@VendorDetailsActivity, "Vendor profile created successfully! ✅", Toast.LENGTH_SHORT).show()
                    loadSocietyAndVendorPipeline()
                } catch (e: Exception) {
                    Toast.makeText(this@VendorDetailsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    loadingOverlay.visibility = View.GONE
                }
            }
        }

        dialog.show()
    }

    private fun launchVendorDetailsOverlayCard(vendor: Map<String, Any>) {
        try {
            val vendorTitle = (vendor["partyName"] as? String ?: "VENDOR").uppercase()
            val code = vendor["vendorCode"] as? String ?: "N/A"
            val head = vendor["categoryHead"] as? String ?: "N/A"
            val contact = vendor["contactNo"] as? String ?: "N/A"
            val pan = vendor["panNo"] as? String ?: "N/A"

            val dialog = BottomSheetDialog(this)
            val dialogView = layoutInflater.inflate(R.layout.dialog_vendor_details, null)
            dialog.setContentView(dialogView)

            dialogView.findViewById<TextView>(R.id.txtDialogTitle)?.text = "$vendorTitle - VENDOR PROFILE"
            dialogView.findViewById<TextView>(R.id.txtVendorCode)?.text = code
            dialogView.findViewById<TextView>(R.id.txtAccountHead)?.text = head
            dialogView.findViewById<TextView>(R.id.txtContactNo)?.text = contact
            dialogView.findViewById<TextView>(R.id.txtPanNo)?.text = pan

            dialogView.findViewById<ImageView>(R.id.btnCloseDialog)?.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error opening details: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}